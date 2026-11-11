package dev.unifiphotosync.app.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
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
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class NasNotConfiguredException : IllegalStateException("Set up the NAS connection in Settings first")

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

    private fun connect(c: NasConfig): Open {
        val connection = client.connect(c.host)
        try {
            val session = connection.authenticate(
                AuthenticationContext(c.username, c.password.toCharArray(), c.domain.ifBlank { null })
            )
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

    /** Streams a file from the share into [out]. */
    suspend fun download(path: String, out: OutputStream) = withShare { share ->
        share.openFile(
            NasPath.clean(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { f -> f.inputStream.use { it.copyTo(out, BUFFER) } }
    }

    companion object {
        const val PARTIAL = ".partial"
        const val BUFFER = 256 * 1024

        fun mkdirs(share: DiskShare, path: String) {
            var current = ""
            for (part in NasPath.clean(path).split('\\').filter { it.isNotEmpty() }) {
                current = NasPath.join(current, part)
                if (!share.folderExists(current)) share.mkdir(current)
            }
        }

        /** Lower-cased file name -> size for the files in [dir]; empty if the folder doesn't exist. */
        fun fileSizes(share: DiskShare, dir: String): Map<String, Long> {
            if (!share.folderExists(dir)) return emptyMap()
            return share.list(dir)
                .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value == 0L }
                .associate { it.fileName.lowercase() to it.endOfFile }
        }

        /** Size of the file at [path], or null if there is none. */
        fun sizeOf(share: DiskShare, path: String): Long? =
            if (share.fileExists(path)) share.getFileInformation(path).standardInformation.endOfFile else null

        /**
         * Uploads to "<path>.partial" and renames it into place only once complete, so
         * an interrupted backup never leaves a truncated file that looks finished.
         */
        suspend fun upload(share: DiskShare, input: InputStream, path: String, onBytes: suspend (Long) -> Unit) {
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
            share.openFile(
                tmp, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { it.rename(path, false) }
        }
    }
}
