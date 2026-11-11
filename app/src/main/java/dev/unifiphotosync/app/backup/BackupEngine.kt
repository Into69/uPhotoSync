package dev.unifiphotosync.app.backup

import android.content.Context
import android.provider.MediaStore
import com.hierynomus.smbj.share.DiskShare
import dev.unifiphotosync.app.data.BackupIndex
import dev.unifiphotosync.app.data.MediaItem
import dev.unifiphotosync.app.data.NasConfig
import dev.unifiphotosync.app.data.NasPath
import dev.unifiphotosync.app.data.SettingsStore
import dev.unifiphotosync.app.smb.NasClient
import dev.unifiphotosync.app.smb.NasNotConfiguredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Copies media to the NAS as <destination>/<phone folder>/<name>, e.g. Backups/DCIM/Camera/IMG_1.jpg. Runs are serialized,
 * so a manual backup and the scheduled one never upload the same file twice at once.
 */
class BackupEngine(
    private val context: Context,
    private val index: BackupIndex,
    private val nas: NasClient,
    private val settings: SettingsStore,
) {
    data class Progress(
        val done: Int,
        val total: Int,
        val failed: Int,
        val current: String?,
        /** Bytes of [current] sent so far, and its full size. */
        val fileBytes: Long = 0,
        val fileSize: Long = 0,
    )

    data class Summary(
        val uploaded: Int,
        val alreadyThere: Int,
        val failed: Int,
        /** True if the run stopped early because the NAS kept failing. */
        val aborted: Boolean,
        val lastError: String?,
    )

    private val lock = Mutex()

    suspend fun run(items: List<MediaItem>, onProgress: suspend (Progress) -> Unit): Summary = lock.withLock {
        val config = settings.config.first() ?: throw NasNotConfiguredException()
        onProgress(Progress(0, 0, 0, null))
        verifyAgainstNas(config, items)
        val pending = items.filterNot(index::isBackedUp)
        var uploaded = 0
        var alreadyThere = 0
        var failed = 0
        var consecutiveFailures = 0
        var lastError: String? = null
        var aborted = false

        for ((i, item) in pending.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(Progress(i, pending.size, failed, item.name, 0, item.size))
            var lastReport = 0L
            val onBytes: suspend (Long) -> Unit = { sent ->
                // Progress goes through WorkManager's database, so report a few times a second at most.
                val now = System.currentTimeMillis()
                if (now - lastReport >= PROGRESS_INTERVAL_MS || sent >= item.size) {
                    lastReport = now
                    onProgress(Progress(i, pending.size, failed, item.name, sent, item.size))
                }
            }
            try {
                // The block re-opens the input itself, so NasClient's reconnect-and-retry is safe.
                if (nas.withShare { share -> backupOne(share, config, item, onBytes) }) uploaded++ else alreadyThere++
                consecutiveFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: NasNotConfiguredException) {
                throw e
            } catch (e: FileNotFoundException) {
                // Deleted from the phone since the list was loaded; nothing to back up.
                continue
            } catch (e: Exception) {
                failed++
                consecutiveFailures++
                lastError = "${item.name}: ${NasClient.describe(e)}"
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    aborted = true
                    break
                }
            }
        }
        onProgress(Progress(pending.size, pending.size, failed, null))
        Summary(uploaded, alreadyThere, failed, aborted, lastError)
    }

    /**
     * The local record only says what was uploaded, not what is still there. Before a run,
     * check each recorded copy against the NAS and forget any that were deleted there
     * (or that live outside the current destination), so they are uploaded again.
     * Deleting on the phone never touches the NAS; this only ever adds back.
     */
    private suspend fun verifyAgainstNas(config: NasConfig, items: List<MediaItem>) {
        val recorded = index.entries()
        val toCheck = items.filter { index.isBackedUp(it) }
        if (toCheck.isEmpty()) return
        val destination = NasPath.clean(config.destination).lowercase()
        // One directory listing per year/month folder instead of one request per file.
        val listings = HashMap<String, Map<String, Long>>()
        val gone = ArrayList<Long>()
        nas.withShare { share ->
            for (item in toCheck) {
                currentCoroutineContext().ensureActive()
                val path = recorded[item.id]?.first ?: continue
                val inDestination = destination.isEmpty() || path.lowercase().startsWith("$destination\\")
                val dir = NasPath.parent(path)
                val files = listings.getOrPut(dir.lowercase()) { NasClient.fileSizes(share, dir) }
                if (!inDestination || files[NasPath.name(path).lowercase()] != item.size) gone += item.id
            }
        }
        index.remove(gone)
    }

    /** Returns true if the file was uploaded, false if an identical copy was already there. */
    private suspend fun backupOne(
        share: DiskShare,
        config: NasConfig,
        item: MediaItem,
        onBytes: suspend (Long) -> Unit,
    ): Boolean {
        val dir = remoteDir(config, item)
        NasClient.mkdirs(share, dir)

        val safe = NasPath.safeName(item.name)
        var target = NasPath.join(dir, safe)
        val existing = NasClient.sizeOf(share, target)
        if (existing != null) {
            if (existing == item.size) {
                index.mark(item.id, target, item.size)
                return false
            }
            // A different file with the same name (common with IMG_0001.jpg across
            // phones/resets): keep both by tagging this one with its media id.
            val stem = safe.substringBeforeLast('.')
            val ext = safe.substringAfterLast('.', "")
            target = NasPath.join(dir, if (ext.isEmpty()) "${stem}_${item.id}" else "${stem}_${item.id}.$ext")
            if (NasClient.sizeOf(share, target) == item.size) {
                index.mark(item.id, target, item.size)
                return false
            }
        }

        openOriginal(item).use { input -> NasClient.upload(share, input, target, onBytes) }
        index.mark(item.id, target, item.size)
        return true
    }

    /**
     * Opens the unredacted file so photo GPS data survives the backup. Falls back to the
     * redacted stream if ACCESS_MEDIA_LOCATION wasn't granted.
     */
    private fun openOriginal(item: MediaItem): InputStream {
        val resolver = context.contentResolver
        val original = try {
            resolver.openInputStream(MediaStore.setRequireOriginal(item.uri))
        } catch (e: SecurityException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        return original ?: resolver.openInputStream(item.uri)
            ?: throw FileNotFoundException(item.uri.toString())
    }

    /** Mirrors the phone's folder: DCIM/Camera on the phone -> <destination>\DCIM\Camera on the NAS. */
    private fun remoteDir(config: NasConfig, item: MediaItem): String {
        val folders = item.relativePath.split('/', '\\').filter { it.isNotBlank() }.map(NasPath::safeName)
        return NasPath.join(config.destination, *folders.toTypedArray())
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val PROGRESS_INTERVAL_MS = 300L
    }
}
