package dev.unifiphotosync.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMBApiException
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.net.ConnectException
import java.net.NoRouteToHostException
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
import dev.unifiphotosync.app.data.NasConfig
import dev.unifiphotosync.app.data.NasPath
import dev.unifiphotosync.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class NasNotConfiguredException : IllegalStateException("Set up the NAS connection in Settings first")

data class NasShare(val name: String, val remark: String)

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

    private val client = SMBClient(
        SmbConfig.builder()
            // Android's built-in crypto lacks MD4, which NTLM authentication needs.
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
    )

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
        val connection = client.connect(config.host)
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
        val connection = client.connect(c.host)
        try {
            val session = connection.authenticate(auth(c))
            val share = session.connectShare(c.share) as? DiskShare
                ?: error("\"${c.share}\" is not a file share")
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
    suspend fun <T> withShare(block: suspend (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(acquire())
        } catch (e: NasNotConfiguredException) {
            throw e
        } catch (e: Exception) {
            invalidate()
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
                "Connected to ${config.share} ($count items$baseNote)"
            } finally {
                o.close()
            }
        }
    }

    suspend fun list(path: String): List<NasEntry> = withShare { share -> listOn(share, path) }

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
     * Deletes files and folders (folders with everything in them). Returns how many were
     * deleted; stops at the first failure and throws, so nothing is silently skipped.
     */
    suspend fun delete(entries: List<NasEntry>): Int = withShare { share ->
        var deleted = 0
        for (e in entries) {
            val path = NasPath.clean(e.path)
            when {
                e.isDirectory -> if (share.folderExists(path)) share.rmdir(path, true)
                share.fileExists(path) -> share.rm(path)
            }
            deleted++
        }
        deleted
    }

    /** Streams a file from the share into [out]. */
    suspend fun download(path: String, out: OutputStream) = withShare { share ->
        share.openFile(
            NasPath.clean(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { f -> f.inputStream.use { it.copyTo(out, BUFFER) } }
    }

    companion object {
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
                if (!share.folderExists(current)) share.mkdir(current)
            }
        }

        /** A file in a NAS folder listing. */
        data class RemoteFile(val name: String, val size: Long, val modifiedAt: Long)

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
                    it.fileName.lowercase() to RemoteFile(it.fileName, it.endOfFile, it.lastWriteTime.toEpochMillis())
                }
        }

        /** Lower-cased file name -> size for the files in [dir]; empty if the folder doesn't exist. */
        fun fileSizes(share: DiskShare, dir: String): Map<String, Long> =
            listFiles(share, dir).orEmpty().mapValues { it.value.size }

        /**
         * SHA-256 of the file at [path] on the share, read in full. [onBytes] gets the bytes
         * read so far, for progress and transfer rate.
         */
        suspend fun sha256(share: DiskShare, path: String, onBytes: suspend (Long) -> Unit = {}): ByteArray {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            share.openFile(
                NasPath.clean(path), EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f ->
                f.inputStream.use { input ->
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
                }
            }
            return md.digest()
        }

        /** Size of the file at [path], or null if there is none. */
        fun sizeOf(share: DiskShare, path: String): Long? =
            if (share.fileExists(path)) share.getFileInformation(path).standardInformation.endOfFile else null

        /** Sets the file's "last modified" time on the NAS, leaving its other times alone. */
        fun setModified(share: DiskShare, path: String, modifiedAt: Long) {
            share.openFile(
                NasPath.clean(path), EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { it.setFileInformation(modifiedInfo(modifiedAt)) }
        }

        private fun modifiedInfo(modifiedAt: Long) = FileBasicInformation(
            FileBasicInformation.DONT_SET, FileBasicInformation.DONT_SET,
            FileTime.ofEpochMillis(modifiedAt), FileBasicInformation.DONT_SET,
            0L, // 0 = leave attributes unchanged
        )

        /**
         * Uploads to "<path>.partial" and renames it into place only once complete, so
         * an interrupted backup never leaves a truncated file that looks finished. The copy
         * gets the phone file's [modifiedAt] time, so later runs can recognize it from a
         * folder listing alone. [replace] overwrites an existing file at [path].
         */
        suspend fun upload(
            share: DiskShare,
            input: InputStream,
            path: String,
            modifiedAt: Long,
            replace: Boolean = false,
            onBytes: suspend (Long) -> Unit,
        ) {
            val tmp = path + PARTIAL
            share.openFile(
                tmp, EnumSet.of(AccessMask.GENERIC_WRITE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
            ).use { f ->
                f.outputStream.use { out ->
                    val buf = ByteArray(BUFFER)
                    var total = 0L
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
                if (modifiedAt > 0) runCatching { f.setFileInformation(modifiedInfo(modifiedAt)) }
                f.rename(path, replace)
            }
        }
    }
}
