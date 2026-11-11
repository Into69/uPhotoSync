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
import dev.unifiphotosync.app.smb.NasClient.Companion.RemoteFile
import dev.unifiphotosync.app.smb.NasNotConfiguredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Copies media to the NAS as <destination>/<phone folder>/<name>, e.g. Backups/DCIM/Camera/IMG_1.jpg.
 * Runs are serialized, so a manual backup and the scheduled one never upload the same file
 * twice at once.
 *
 * Efficiency: each NAS folder is listed once per run and looked up in memory, so checking a
 * file costs no extra round-trip. Uploaded copies carry the phone file's modified time, so a
 * file with the same name, size and time is recognized as identical from the listing alone;
 * only same-size files with a different time are compared by content (SHA-256).
 */
class BackupEngine(
    private val context: Context,
    private val index: BackupIndex,
    private val nas: NasClient,
    private val settings: SettingsStore,
) {
    enum class Phase { Upload, Verify, Check }

    data class Progress(
        val done: Int,
        val total: Int,
        val failed: Int,
        val current: String?,
        /** Bytes of [current] transferred so far, and its full size. */
        val fileBytes: Long = 0,
        val fileSize: Long = 0,
        val phase: Phase = Phase.Upload,
        /** Current transfer rate, bytes per second (0 when nothing is moving). */
        val bytesPerSecond: Long = 0,
    ) {
        val verifying get() = phase == Phase.Verify
    }

    data class Summary(
        val uploaded: Int,
        val alreadyThere: Int,
        val failed: Int,
        /** True if the run stopped early because the NAS kept failing. */
        val aborted: Boolean,
        val lastError: String?,
    )

    /** Result of "Check backups". */
    data class CheckReport(
        val checked: Int,
        val ok: Int,
        /** Marked as backed up but not on the NAS (or the wrong size). */
        val missing: Int,
        /** On the NAS but not identical to the phone's file (full check only). */
        val different: Int,
        /** Couldn't be read to compare (full check only). */
        val unreadable: Int,
        /** Re-uploaded to fix the above, if repair was on. */
        val repaired: Int,
        val repairFailed: Int,
        val aborted: Boolean,
        val lastError: String?,
    )

    private val lock = Mutex()

    /** Per-run memory of NAS folders, so each one is listed (and created) at most once. */
    private class FolderCache {
        private val listings = HashMap<String, MutableMap<String, RemoteFile>>()
        private val ensured = HashSet<String>()

        fun files(share: DiskShare, dir: String): MutableMap<String, RemoteFile> =
            listings.getOrPut(dir.lowercase()) {
                NasClient.listFiles(share, dir)?.toMutableMap()?.also { ensured += dir.lowercase() } ?: HashMap()
            }

        fun ensureDir(share: DiskShare, dir: String) {
            if (ensured.add(dir.lowercase())) NasClient.mkdirs(share, dir)
        }

        fun put(dir: String, file: RemoteFile) {
            listings.getOrPut(dir.lowercase()) { HashMap() }[file.name.lowercase()] = file
        }
    }

    // --- Backup -------------------------------------------------------------------------

    suspend fun run(items: List<MediaItem>, onProgress: suspend (Progress) -> Unit): Summary = lock.withLock {
        val config = settings.config.first() ?: throw NasNotConfiguredException()
        onProgress(Progress(0, 0, 0, null))
        val cache = FolderCache()
        forgetMissing(config, items, cache)
        uploadAll(config, items.filterNot(index::isBackedUp), cache, overwrite = emptyMap(), onProgress = onProgress)
    }

    /**
     * Uploads [pending]. [overwrite] maps media ids to a NAS path to replace (a damaged copy
     * found by a full check) instead of choosing a name.
     */
    private suspend fun uploadAll(
        config: NasConfig,
        pending: List<MediaItem>,
        cache: FolderCache,
        overwrite: Map<Long, String>,
        onProgress: suspend (Progress) -> Unit,
    ): Summary {
        var uploaded = 0
        var alreadyThere = 0
        var failed = 0
        var consecutiveFailures = 0
        var lastError: String? = null
        var aborted = false
        val meter = RateMeter()
        var runBytes = 0L // bytes sent across the whole run, for the rate

        for ((i, item) in pending.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(Progress(i, pending.size, failed, item.name, 0, item.size, bytesPerSecond = meter.bytesPerSecond))
            var lastReport = 0L
            var fileSent = 0L
            val onBytes: suspend (Long) -> Unit = { sent ->
                runBytes += sent - fileSent
                fileSent = sent
                val rate = meter.update(runBytes)
                // Progress goes through WorkManager's database, so report a few times a second at most.
                val now = System.currentTimeMillis()
                if (now - lastReport >= PROGRESS_INTERVAL_MS || sent >= item.size) {
                    lastReport = now
                    onProgress(Progress(i, pending.size, failed, item.name, sent, item.size, bytesPerSecond = rate))
                }
            }
            try {
                // The block re-opens the input itself, so NasClient's reconnect-and-retry is safe.
                val sent = nas.withShare { share ->
                    // A retry restarts the file, so don't count the failed attempt's bytes twice.
                    runBytes -= fileSent
                    fileSent = 0
                    backupOne(share, config, item, cache, overwrite[item.id], onBytes)
                }
                if (sent) uploaded++ else alreadyThere++
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
        return Summary(uploaded, alreadyThere, failed, aborted, lastError)
    }

    /**
     * Returns true if the file was uploaded, false if an identical copy was already there.
     */
    private suspend fun backupOne(
        share: DiskShare,
        config: NasConfig,
        item: MediaItem,
        cache: FolderCache,
        overwritePath: String?,
        onBytes: suspend (Long) -> Unit,
    ): Boolean {
        if (overwritePath != null) {
            // Full check found this copy damaged: replace it in place.
            val dir = NasPath.parent(overwritePath)
            cache.ensureDir(share, dir)
            openOriginal(item).use { NasClient.upload(share, it, overwritePath, item.modifiedAt, replace = true, onBytes) }
            cache.put(dir, RemoteFile(NasPath.name(overwritePath), item.size, item.modifiedAt))
            index.mark(item.id, overwritePath, item.size)
            return true
        }

        val dir = remoteDir(config, item)
        val files = cache.files(share, dir)
        val safe = NasPath.safeName(item.name)
        val stem = safe.substringBeforeLast('.')
        val ext = safe.substringAfterLast('.', "")
        // The plain name first; if a different file already has it (IMG_0001.jpg from another
        // phone, or an older edit), this one is kept alongside, tagged with its media id.
        val candidates = listOf(safe, if (ext.isEmpty()) "${stem}_${item.id}" else "${stem}_${item.id}.$ext")

        for ((n, name) in candidates.withIndex()) {
            val path = NasPath.join(dir, name)
            val remote = files[name.lowercase()]
            when {
                remote == null -> return upload(share, item, dir, path, replace = false, cache, onBytes)
                isSameFile(share, item, path, remote) -> {
                    index.mark(item.id, path, item.size)
                    return false
                }
                // The id-tagged name is this media's own slot: replace the outdated copy.
                n == candidates.lastIndex -> return upload(share, item, dir, path, replace = true, cache, onBytes)
            }
        }
        error("unreachable")
    }

    private suspend fun upload(
        share: DiskShare, item: MediaItem, dir: String, path: String, replace: Boolean,
        cache: FolderCache, onBytes: suspend (Long) -> Unit,
    ): Boolean {
        cache.ensureDir(share, dir)
        openOriginal(item).use { NasClient.upload(share, it, path, item.modifiedAt, replace, onBytes) }
        cache.put(dir, RemoteFile(NasPath.name(path), item.size, item.modifiedAt))
        index.mark(item.id, path, item.size)
        return true
    }

    /**
     * Whether [remote] is the same file as [item]. Size plus modified time (what this app
     * stamps on uploads) settles it from the listing alone; same size with a different time
     * is compared by content, and then stamped so next time is instant.
     */
    private suspend fun isSameFile(share: DiskShare, item: MediaItem, path: String, remote: RemoteFile): Boolean {
        if (remote.size != item.size) return false
        if (item.modifiedAt > 0 && abs(remote.modifiedAt - item.modifiedAt) <= TIME_TOLERANCE_MS) return true
        val same = openOriginal(item).use { localSha256(it) }.contentEquals(NasClient.sha256(share, path))
        if (same && item.modifiedAt > 0) runCatching { NasClient.setModified(share, path, item.modifiedAt) }
        return same
    }

    /**
     * The local record only says what was uploaded, not what is still there. Before a run,
     * check each recorded copy against the NAS (from folder listings) and forget any that
     * are gone, the wrong size, or outside the current destination, so they're uploaded again.
     * Deleting on the phone never touches the NAS; this only ever adds back. Returns their ids.
     */
    private suspend fun forgetMissing(config: NasConfig, items: List<MediaItem>, cache: FolderCache): List<Long> {
        val recorded = index.entries()
        val toCheck = items.filter { index.isBackedUp(it) }
        if (toCheck.isEmpty()) return emptyList()
        val destination = NasPath.clean(config.destination).lowercase()
        val gone = ArrayList<Long>()
        nas.withShare { share ->
            gone.clear()
            for (item in toCheck) {
                currentCoroutineContext().ensureActive()
                val path = recorded[item.id]?.first ?: continue
                val inDestination = destination.isEmpty() || path.lowercase().startsWith("$destination\\")
                val remote = cache.files(share, NasPath.parent(path))[NasPath.name(path).lowercase()]
                if (!inDestination || remote?.size != item.size) gone += item.id
            }
        }
        index.remove(gone)
        return gone
    }

    // --- Check backups ------------------------------------------------------------------

    /**
     * "Check backups": makes sure everything marked as backed up really is on the NAS.
     * Quick checks name and size from folder listings; [full] also reads every copy back and
     * compares it byte for byte. With [repair], anything missing or different is uploaded again
     * (a damaged copy is replaced in place). Never deletes anything.
     */
    suspend fun check(
        items: List<MediaItem>,
        full: Boolean,
        repair: Boolean,
        onProgress: suspend (Progress) -> Unit,
    ): CheckReport = lock.withLock {
        val config = settings.config.first() ?: throw NasNotConfiguredException()
        val marked = items.filter { index.isBackedUp(it) }
        onProgress(Progress(0, marked.size, 0, null, phase = Phase.Check))
        val cache = FolderCache()

        // 1. Presence and size, from one listing per folder.
        val missingIds = forgetMissing(config, marked, cache).toHashSet()

        // 2. Content, file by file (full only).
        val damaged = HashMap<Long, String>() // id -> path of the bad copy
        var unreadable = 0
        if (full) {
            val recorded = index.entries()
            val meter = RateMeter()
            var runBytes = 0L
            val present = marked.filter { it.id !in missingIds }
            for ((i, item) in present.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(Progress(i, present.size, 0, item.name, 0, item.size, Phase.Check, meter.bytesPerSecond))
                val path = recorded[item.id]?.first ?: continue
                try {
                    val local = openOriginal(item).use { localSha256(it) }
                    var lastReport = 0L
                    var fileRead = 0L
                    val remote = nas.withShare { share ->
                        runBytes -= fileRead
                        fileRead = 0
                        NasClient.sha256(share, path) { read ->
                            runBytes += read - fileRead
                            fileRead = read
                            val rate = meter.update(runBytes)
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                onProgress(Progress(i, present.size, 0, item.name, read, item.size, Phase.Check, rate))
                            }
                        }
                    }
                    if (!local.contentEquals(remote)) damaged[item.id] = path
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    // Gone from the phone meanwhile; nothing to compare.
                } catch (e: Exception) {
                    unreadable++
                }
            }
            index.remove(damaged.keys)
        } else {
            onProgress(Progress(marked.size, marked.size, 0, null, phase = Phase.Check))
        }

        // 3. Repair: upload what's missing, replace what's damaged.
        var repaired = 0
        var repairFailed = 0
        var aborted = false
        var lastError: String? = null
        if (repair && (missingIds.isNotEmpty() || damaged.isNotEmpty())) {
            val toFix = marked.filter { it.id in missingIds || it.id in damaged }
            val s = uploadAll(config, toFix, cache, overwrite = damaged, onProgress = onProgress)
            repaired = s.uploaded + s.alreadyThere
            repairFailed = s.failed
            aborted = s.aborted
            lastError = s.lastError
        }

        CheckReport(
            checked = marked.size,
            ok = marked.size - missingIds.size - damaged.size - unreadable,
            missing = missingIds.size,
            different = damaged.size,
            unreadable = unreadable,
            repaired = repaired,
            repairFailed = repairFailed,
            aborted = aborted,
            lastError = lastError,
        )
    }

    // --- Move to NAS --------------------------------------------------------------------

    /**
     * For "Move to NAS": reads each item's NAS copy back and compares it byte for byte with
     * the phone's file (SHA-256 of both). Returns the ids whose copy matches exactly; only
     * those may be deleted from the phone. Anything unreadable or different is left out.
     */
    suspend fun verifyCopies(items: List<MediaItem>, onProgress: suspend (Progress) -> Unit): Set<Long> =
        lock.withLock {
            val recorded = index.entries()
            val verified = HashSet<Long>()
            val meter = RateMeter()
            var runBytes = 0L
            for ((i, item) in items.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(Progress(i, items.size, 0, item.name, 0, item.size, Phase.Verify, meter.bytesPerSecond))
                val (path, size) = recorded[item.id] ?: continue
                if (size != item.size) continue
                try {
                    val local = openOriginal(item).use { localSha256(it) }
                    var lastReport = 0L
                    var fileRead = 0L
                    // A fresh digest per attempt: NasClient may retry the read after a reconnect.
                    val remote = nas.withShare { share ->
                        runBytes -= fileRead
                        fileRead = 0
                        NasClient.sha256(share, path) { read ->
                            runBytes += read - fileRead
                            fileRead = read
                            val rate = meter.update(runBytes)
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                onProgress(Progress(i, items.size, 0, item.name, read, item.size, Phase.Verify, rate))
                            }
                        }
                    }
                    if (local.contentEquals(remote)) verified += item.id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Can't prove the copy is good, so keep the phone's file.
                }
            }
            onProgress(Progress(items.size, items.size, 0, null, phase = Phase.Verify))
            verified
        }

    // --- Helpers -------------------------------------------------------------------------

    private fun localSha256(input: InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(NasClient.BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest()
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
        /** SMB servers store times at 100 ns to 2 s precision (FAT-style), so allow a little slack. */
        const val TIME_TOLERANCE_MS = 2_000L
    }
}
