package dev.uphotosync.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMBApiException
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dev.uphotosync.app.data.NasConfig
import dev.uphotosync.app.data.NasPath
import dev.uphotosync.app.data.SettingsStore
import dev.uphotosync.app.data.StorageSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeoutException
import com.hierynomus.protocol.transport.TransportException
import kotlinx.coroutines.CancellationException

class NasNotConfiguredException : IllegalStateException("Set up the NAS connection in Settings first")

data class NasShare(val name: String, val remark: String)

/**
 * An open NAS file for reading any part of it: lets videos stream (and seek) and video
 * thumbnails be taken from a few MB instead of downloading the whole file. Not thread-safe;
 * use one per reader.
 */
class RemoteReader internal constructor(private val file: com.hierynomus.smbj.share.File, val size: Long) : java.io.Closeable {
    /** Reads up to [length] bytes at [position]; returns the count, or -1 at the end of the file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val n = file.read(buffer, position, offset, minOf(length.toLong(), size - position).toInt())
        return if (n <= 0) -1 else n
    }

    override fun close() {
        runCatching { file.close() }
    }
}

data class NasEntry(
    val name: String,
    /** Path inside the share. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
) {
    val extension get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = !isDirectory && extension in IMAGE_EXT
    val isVideo get() = !isDirectory && extension in VIDEO_EXT

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng")
        val VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm")
    }
}

/**
 * SMB access to the UniFi NAS share. Keeps one authenticated session open and
 * reuses it across calls; it is dropped and rebuilt if the settings change or an
 * operation fails with a transport error.
 */
class NasClient(private val settings: SettingsStore) {

    /** One SMB client per encryption setting (the setting is fixed when a client is built). */
    private val clients = HashMap<Boolean, SMBClient>()

    private fun client(encrypt: Boolean): SMBClient = synchronized(clients) {
        clients.getOrPut(encrypt) {
            SMBClient(
                SmbConfig.builder()
                    // Android's built-in crypto lacks MD4 (NTLM) and AES-CCM/GCM (SMB3 encryption).
                    .withSecurityProvider(BCSecurityProvider())
                    .withMultiProtocolNegotiate(true)
                    .withEncryptData(encrypt)
                    .withTimeout(30, TimeUnit.SECONDS)
                    .withSoTimeout(60, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    private class Open(val config: NasConfig, val connection: Connection, val session: Session, val share: DiskShare) {
        val alive get() = connection.isConnected && share.isConnected
        fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    private val lock = Mutex()
    private var open: Open? = null

    private fun auth(c: NasConfig) =
        AuthenticationContext(c.username, c.password.toCharArray(), c.domain.ifBlank { null })

    /**
     * The file shares the NAS offers this user, via the SRVSVC RPC call (what Windows'
     * "net view" uses). Hidden/admin shares like IPC$ are left out.
     */
    suspend fun listShares(config: NasConfig): List<NasShare> = withContext(Dispatchers.IO) {
        val connection = client(config.encrypt).connect(config.host)
        try {
            val session = connection.authenticate(auth(config))
            val transport = SMBTransportFactories.SRVSVC.getTransport(session)
            ServerService(transport).shares1
                .filter { s ->
                    val type = s.type
                    (type and STYPE_MASK) == STYPE_DISKTREE && (type and STYPE_SPECIAL) == 0 && !s.netName.endsWith("$")
                }
                .map { NasShare(it.netName, it.remark.orEmpty()) }
                .sortedBy { it.name.lowercase() }
        } catch (e: LinkageError) {
            // dcerpc reports malformed replies with java.rmi exceptions, which Android lacks.
            throw IOException("The NAS sent a share list the app couldn't read. Type the share name instead.", e)
        } finally {
            runCatching { connection.close() }
        }
    }

    private fun connect(c: NasConfig): Open {
        val connection = client(c.encrypt).connect(c.host)
        try {
            if (c.encrypt && !connection.connectionContext.supportsEncryption()) {
                throw IOException(ENCRYPTION_UNSUPPORTED)
            }
            val session = connection.authenticate(auth(c))
            val share = session.connectShare(c.share) as? DiskShare
                ?: error("\"${c.share}\" is not a file share")
            // Never fall back to plain text when encryption was asked for.
            if (c.encrypt && !session.shouldEncryptData()) throw IOException(ENCRYPTION_UNSUPPORTED)
            return Open(c, connection, session, share)
        } catch (e: Exception) {
            runCatching { connection.close() }
            throw e
        }
    }

    private suspend fun acquire(): DiskShare = lock.withLock {
        val config = settings.config.first() ?: throw NasNotConfiguredException()
        open?.let { if (it.config == config && it.alive) return it.share else it.close() }
        connect(config).also { open = it }.share
    }

    private suspend fun invalidate() = lock.withLock {
        open?.close()
        open = null
    }

    /** Runs [block] against the share on the IO dispatcher, reconnecting once on a stale session. */
    suspend fun <T> withShare(block: suspend (DiskShare) -> T): T = withShare({ true }, block)

    /**
     * Runs [block] against the share on the IO dispatcher. If the connection turns out to
     * be dead (dropped Wi-Fi, NAS restart, expired session), reconnects and runs it once
     * more, but only while [canRetry] says a second run is safe (e.g. nothing has been
     * written to an output stream yet). Other errors, like a missing file or access
     * denied, are thrown straight away and keep the connection.
     */
    suspend fun <T> withShare(canRetry: () -> Boolean, block: suspend (DiskShare) -> T): T =
        withContext(Dispatchers.IO) {
            val share = acquire()
            try {
                block(share)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NasNotConfiguredException) {
                throw e
            } catch (e: Exception) {
                val dead = !share.isConnected || isConnectionLost(e)
                if (dead) invalidate()
                if (!dead || !canRetry()) throw e
                currentCoroutineContext().ensureActive()
                block(acquire())
            }
        }

    /** Connects with [config] (not the saved settings) and returns a short status line. */
    suspend fun test(config: NasConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val o = connect(config)
            try {
                val base = NasPath.clean(config.destination)
                val count = o.share.list("").count { it.fileName != "." && it.fileName != ".." }
                val baseNote = when {
                    base.isEmpty() -> ""
                    o.share.folderExists(base) -> ", destination found"
                    else -> ", destination will be created"
                }
                val ctx = o.connection.connectionContext
                val dialect = ctx.negotiatedProtocol?.dialect?.name?.removePrefix("SMB_")?.replace('_', '.')
                val security = if (config.encrypt) "encrypted" else when (probeEncryption(config.host)) {
                    true -> "encryption available"
                    false -> "this NAS doesn't offer encryption"
                    null -> "couldn't check encryption support"
                }
                "Connected to ${config.share} ($count items$baseNote)\n" +
                    listOfNotNull(dialect?.let { "SMB $it" }, security).joinToString(" · ")
            } finally {
                o.close()
            }
        }
    }

    /**
     * Whether [host] can do SMB3 encryption. smbj only reports encryption support when the
     * client offered it too during negotiation, so a connection made with encryption off
     * always says "no". This negotiates separately with an encryption-capable client
     * (no login needed). Null if the probe itself failed.
     */
    private fun probeEncryption(host: String): Boolean? = runCatching {
        val probe = client(true).connect(host)
        try {
            probe.connectionContext.supportsEncryption()
        } finally {
            runCatching { probe.close() }
        }
    }.getOrNull()

    suspend fun list(path: String): List<NasEntry> = withShare { share -> listOn(share, path) }

    /**
     * Every photo and video in [entries]: files as they are, folders searched all the way down.
     * The .recycle folder is skipped unless it was picked itself (or something inside it).
     */
    suspend fun listMediaRecursive(entries: List<NasEntry>): List<NasEntry> = withShare { share ->
        val out = ArrayList<NasEntry>()
        val folders = ArrayDeque<String>()
        for (e in entries) {
            when {
                e.isDirectory -> folders += e.path
                e.isImage || e.isVideo -> out += e
            }
        }
        val seen = HashSet<String>()
        while (folders.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = folders.removeFirst()
            if (!seen.add(dir.lowercase())) continue
            for (child in listOn(share, dir)) {
                when {
                    child.isDirectory -> {
                        if (!isInRecycle(child.path) || isInRecycle(dir)) folders += child.path
                    }
                    child.isImage || child.isVideo -> out += child
                }
            }
        }
        out
    }

    /**
     * Folders under [path] using [config] directly, so the destination can be picked
     * in Settings before the connection is saved.
     */
    suspend fun listFolders(config: NasConfig, path: String): List<NasEntry> = withTemp(config) { share ->
        listOn(share, path).filter { it.isDirectory }
    }

    /** Creates [path] (and any missing parents) using [config]. */
    suspend fun createFolder(config: NasConfig, path: String) = withTemp(config) { share -> mkdirs(share, path) }

    private suspend fun <T> withTemp(config: NasConfig, block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        val o = connect(config)
        try {
            block(o.share)
        } finally {
            o.close()
        }
    }

    private fun listOn(share: DiskShare, path: String): List<NasEntry> {
        val dir = NasPath.clean(path)
        return share.list(dir)
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.endsWith(PARTIAL) }
            .map {
                NasEntry(
                    name = it.fileName,
                    path = NasPath.join(dir, it.fileName),
                    isDirectory = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = it.endOfFile,
                    modifiedAt = it.lastWriteTime.toEpochMillis(),
                )
            }
            .sortedWith(compareBy<NasEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Deletes files and folders (folders with everything in them). With [recycle], they're
     * moved into the share's .recycle folder (emptied after [RECYCLE_DAYS] days) instead,
     * keeping their path so they're easy to find. Anything already in .recycle is deleted for
     * good. Returns how many were deleted; stops at the first failure and throws.
     */
    suspend fun delete(entries: List<NasEntry>, recycle: Boolean): Int = withShare { share ->
        val batch = NasPath.join(RECYCLE_DIR, LocalDateTime.now().format(RECYCLE_STAMP))
        var deleted = 0
        for (e in entries) {
            val path = NasPath.clean(e.path)
            val exists = if (e.isDirectory) share.folderExists(path) else share.fileExists(path)
            when {
                !exists -> Unit
                recycle && !isInRecycle(path) -> {
                    val target = NasPath.join(batch, path)
                    mkdirs(share, NasPath.parent(target))
                    val access = EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ)
                    if (e.isDirectory) {
                        share.openDirectory(path, access, null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                            .use { it.rename(target, false) }
                    } else {
                        share.openFile(path, access, null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                            .use { it.rename(target, false) }
                    }
                }
                e.isDirectory -> share.rmdir(path, true)
                else -> share.rm(path)
            }
            deleted++
        }
        deleted
    }

    /**
     * Permanently deletes .recycle batches older than [RECYCLE_DAYS] days. Cheap (one listing),
     * so it's run at the start of each backup. Returns how many batches were removed.
     */
    suspend fun emptyOldRecycle(): Int = withShare { share ->
        if (!share.folderExists(RECYCLE_DIR)) return@withShare 0
        val cutoff = LocalDateTime.now().minusDays(RECYCLE_DAYS)
        var removed = 0
        for (batch in share.list(RECYCLE_DIR)) {
            val name = batch.fileName
            if (name == "." || name == "..") continue
            val at = runCatching { LocalDateTime.parse(name, RECYCLE_STAMP) }.getOrNull() ?: continue
            if (at.isBefore(cutoff)) {
                runCatching { share.rmdir(NasPath.join(RECYCLE_DIR, name), true) }.onSuccess { removed++ }
            }
        }
        removed
    }

    /**
     * Size and free space of the share. Free space is what this NAS user can actually use,
     * so it respects any quota on the account.
     */
    suspend fun space(): StorageSpace = withShare { share ->
        val info = share.shareInformation
        StorageSpace(total = info.totalSpace, free = info.callerFreeSpace)
    }

    /** Opens [path] for random-access reading; close it when done. */
    suspend fun openReader(path: String): RemoteReader = withShare { share ->
        val f = share.openFile(
            NasPath.clean(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        try {
            RemoteReader(f, f.fileInformation.standardInformation.endOfFile)
        } catch (e: Exception) {
            runCatching { f.close() }
            throw e
        }
    }

    /** Streams a file from the share into [out]. */
    suspend fun download(path: String, out: OutputStream) {
        var written = 0L
        val counting = object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                written += len
            }
            override fun write(b: Int) {
                out.write(b)
                written++
            }
        }
        // Retrying after bytes reached [out] would append a second copy after the partial
        // first one and corrupt the file, so only retry if nothing was written yet.
        withShare({ written == 0L }) { share ->
            share.openFile(
                NasPath.clean(path), EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f ->
                f.inputStream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        counting.write(buf, 0, n)
                    }
                }
            }
        }
    }

    companion object {
        /** Deleted NAS files wait here (at the share root) before being removed for good. */
        const val RECYCLE_DIR = ".recycle"
        const val RECYCLE_DAYS = 30L
        private val RECYCLE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

        fun isInRecycle(path: String) =
            NasPath.clean(path).let { it.equals(RECYCLE_DIR, true) || it.startsWith("$RECYCLE_DIR\\", true) }

        private const val ENCRYPTION_UNSUPPORTED =
            "This NAS doesn't support encrypted (SMB3) connections. Turn off \"Encrypt connection\" in Settings."

        private const val STYPE_DISKTREE = 0
        private const val STYPE_MASK = 0xFF
        private const val STYPE_SPECIAL = Int.MIN_VALUE // 0x80000000: admin/hidden shares

        /** Turns SMB status codes and network errors into something a person can act on. */
        fun describe(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.take(6).toList()
            val status = chain.firstNotNullOfOrNull { (it as? SMBApiException)?.status }
            return when (status) {
                NtStatus.STATUS_BAD_NETWORK_NAME ->
                    "The NAS has no share with that name. Tap the list button next to Share name to pick one."
                NtStatus.STATUS_LOGON_FAILURE -> "Wrong username or password."
                NtStatus.STATUS_ACCOUNT_DISABLED -> "That NAS user account is disabled."
                NtStatus.STATUS_PASSWORD_EXPIRED -> "That NAS user's password has expired."
                NtStatus.STATUS_ACCESS_DENIED -> "This user doesn't have permission for that share or folder."
                NtStatus.STATUS_DISK_FULL -> "The NAS is full (or this user's space quota is used up)."
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                    "That folder doesn't exist on the NAS."
                else -> when {
                    chain.any { it is UnknownHostException } -> "Couldn't find a NAS at that address."
                    chain.any { it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } ->
                        "Couldn't reach the NAS. Check the address and that the phone is on the same network."
                    else -> e.message ?: e.javaClass.simpleName
                }
            }
        }

        const val PARTIAL = ".partial"
        const val BUFFER = 256 * 1024

        fun mkdirs(share: DiskShare, path: String) {
            var current = ""
            for (part in NasPath.clean(path).split('\\').filter { it.isNotEmpty() }) {
                current = NasPath.join(current, part)
                if (!share.folderExists(current)) {
                    try {
                        share.mkdir(current)
                    } catch (e: SMBApiException) {
                        // Created meanwhile by a parallel upload: fine.
                        if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
                    }
                }
            }
        }

        /** A file in a NAS folder listing. */
        data class RemoteFile(val name: String, val size: Long, val modifiedAt: Long, val createdAt: Long = 0)

        /**
         * The files in [dir], keyed by lower-cased name (SMB names are case-insensitive);
         * null if the folder doesn't exist. One request for a whole folder, so callers can
         * check many files without a round-trip each.
         */
        fun listFiles(share: DiskShare, dir: String): Map<String, RemoteFile>? {
            val clean = NasPath.clean(dir)
            if (clean.isNotEmpty() && !share.folderExists(clean)) return null
            return share.list(clean)
                .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value == 0L }
                .associate {
                    it.fileName.lowercase() to RemoteFile(
                        it.fileName, it.endOfFile, it.lastWriteTime.toEpochMillis(), it.creationTime.toEpochMillis(),
                    )
                }
        }

        /**
         * SHA-256 of the file at [path] on the share, read in full. [onBytes] gets the bytes
         * read so far, for progress and transfer rate.
         */
        suspend fun sha256(share: DiskShare, path: String, onBytes: suspend (Long) -> Unit = {}): ByteArray =
            share.openFile(
                NasPath.clean(path), EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f -> f.inputStream.use { sha256(it, onBytes) } }

        /** SHA-256 of a stream (phone file or NAS file), reporting bytes read so far. */
        suspend fun sha256(input: InputStream, onBytes: suspend (Long) -> Unit = {}): ByteArray {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
                onBytes(total)
            }
            return md.digest()
        }

        /** Size of the file at [path], or null if there is none. */
        fun sizeOf(share: DiskShare, path: String): Long? =
            if (share.fileExists(path)) share.getFileInformation(path).standardInformation.endOfFile else null

        /**
         * Gives the NAS file the phone file's dates: created = when the photo was taken,
         * modified = the phone file's modified time. Other times are left alone.
         */
        fun setTimes(share: DiskShare, path: String, createdAt: Long, modifiedAt: Long) {
            share.openFile(
                NasPath.clean(path), EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { it.setFileInformation(timesInfo(createdAt, modifiedAt)) }
        }

        private fun timesInfo(createdAt: Long, modifiedAt: Long) = FileBasicInformation(
            if (createdAt > 0) FileTime.ofEpochMillis(createdAt) else FileBasicInformation.DONT_SET,
            FileBasicInformation.DONT_SET,
            if (modifiedAt > 0) FileTime.ofEpochMillis(modifiedAt) else FileBasicInformation.DONT_SET,
            FileBasicInformation.DONT_SET,
            0L, // 0 = leave attributes unchanged
        )

        /**
         * Uploads to "<path>.partial" and renames it into place only once complete, so
         * an interrupted backup never leaves a truncated file that looks finished. The copy
         * gets the phone file's dates ([createdAt] = taken, [modifiedAt]), so it shows the
         * right date on the NAS and later runs can recognize it from a folder listing alone.
         * [replace] overwrites an existing file at [path].
         */
        suspend fun upload(
            share: DiskShare,
            input: InputStream,
            path: String,
            modifiedAt: Long,
            createdAt: Long,
            replace: Boolean = false,
            onBytes: suspend (Long) -> Unit,
        ) {
            val tmp = path + PARTIAL
            var total = 0L
            share.openFile(
                tmp, EnumSet.of(AccessMask.GENERIC_WRITE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
            ).use { f ->
                f.outputStream.use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
            // Stamp the time on a fresh handle after the writes are closed (closing a written
            // handle would update it again), then move the finished file into place.
            share.openFile(
                tmp, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ, AccessMask.FILE_WRITE_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f ->
                if (modifiedAt > 0 || createdAt > 0) runCatching { f.setFileInformation(timesInfo(createdAt, modifiedAt)) }
                try {
                    f.rename(path, replace)
                } catch (e: SMBApiException) {
                    // A retry after a dropped connection: the first attempt's rename reached the
                    // NAS but its reply didn't. If the finished file is there at the right size,
                    // the upload is done; just remove this duplicate.
                    if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION || sizeOf(share, path) != total) throw e
                    f.deleteOnClose()
                }
            }
        }

        /**
         * True if [e] means the SMB connection or session is gone, so reconnecting could help.
         * Errors the server answered with (file not found, access denied, ...) aren't.
         */
        fun isConnectionLost(e: Throwable): Boolean {
            val chain = generateSequence(e) { it.cause }.take(8).toList()
            val status = chain.firstNotNullOfOrNull { (it as? SMBApiException)?.status }
            if (status != null) return status in SESSION_GONE
            return chain.any {
                it is TransportException || it is SocketException || it is SocketTimeoutException ||
                    it is EOFException || it is TimeoutException
            }
        }

        private val SESSION_GONE = setOf(
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_NAME_DELETED,
            NtStatus.STATUS_CONNECTION_DISCONNECTED,
            NtStatus.STATUS_CONNECTION_RESET,
        )
    }
}
