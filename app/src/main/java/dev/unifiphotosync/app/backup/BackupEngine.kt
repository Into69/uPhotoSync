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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.InputStream
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
        /** Every file being transferred right now (several with parallel uploads). */
        val files: List<FileProgress> = emptyList(),
        /** Bytes of the whole job processed so far and in total (0 if unknown), for the job bar and time left. */
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
    )

    data class FileProgress(val name: String, val sent: Long, val size: Long)

    data class Summary(
        val uploaded: Int,
        val alreadyThere: Int,
        val failed: Int,
        /** True if the run stopped early because the NAS kept failing. */
        val aborted: Boolean,
        val lastError: String?,
        /** Bytes sent to the NAS in this run. */
        val bytesUploaded: Long = 0,
        /** "file: reason" for each failure, for the history (capped). */
        val errors: List<String> = emptyList(),
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
        /** Not compared, e.g. deleted from the phone during the check (full check only). */
        val skipped: Int,
        /**
         * On the phone but not on the NAS, and not marked as backed up: deleted on the NAS
         * (which unmarks them), never backed up yet, or the backup record was reset.
         */
        val notBackedUp: Int = 0,
        /** Re-uploaded to fix the above, if repair was on. */
        val repaired: Int,
        val repairFailed: Int,
        val aborted: Boolean,
        val lastError: String?,
        val errors: List<String> = emptyList(),
    )

    private val lock = Mutex()

    /**
     * Per-run memory of NAS folders, so each one is listed (and created) at most once.
     * Shared by parallel uploads, hence the locking and concurrent maps.
     */
    private class FolderCache {
        private val listings = HashMap<String, MutableMap<String, RemoteFile>>()
        private val ensured = HashSet<String>()

        @Synchronized
        fun files(share: DiskShare, dir: String): MutableMap<String, RemoteFile> =
            listings.getOrPut(dir.lowercase()) {
                NasClient.listFiles(share, dir)?.let { ConcurrentHashMap(it) }?.also { ensured += dir.lowercase() }
                    ?: ConcurrentHashMap()
            }

        @Synchronized
        fun ensureDir(share: DiskShare, dir: String) {
            if (ensured.add(dir.lowercase())) NasClient.mkdirs(share, dir)
        }

        @Synchronized
        fun put(dir: String, file: RemoteFile) {
            listings.getOrPut(dir.lowercase()) { ConcurrentHashMap() }[file.name.lowercase()] = file
        }
    }

    // --- Backup -------------------------------------------------------------------------

    suspend fun run(items: List<MediaItem>, onProgress: suspend (Progress) -> Unit): Summary = lock.withLock {
        val config = settings.config.first() ?: throw NasNotConfiguredException()
        onProgress(Progress(0, 0, 0, null))
        // Housekeeping: deleted NAS files older than 30 days leave the recycle folder.
        runCatching { nas.emptyOldRecycle() }
        val cache = FolderCache()
        forgetMissing(config, items, cache)
        uploadAll(config, items.filterNot(index::isBackedUp), cache, overwrite = index.damaged(), onProgress = onProgress)
    }

    /**
     * Counters and progress for one upload run, shared by the parallel upload workers.
     * Progress shows every file in flight combined, so the "current file" bar still means
     * "how far along the files being sent right now are".
     */
    private class UploadRun(
        private val total: Int,
        /** Sum of all pending files' sizes, so the job bar can move by bytes, not by file count. */
        private val totalBytes: Long,
        private val onProgress: suspend (Progress) -> Unit,
    ) {
        /** Bytes of files already finished, skipped or failed (whole file sizes). */
        private var processedBytes = 0L
        private val mutex = Mutex()
        private val inFlight = LinkedHashMap<Long, Triple<String, Long, Long>>() // id -> name, sent, size
        private val meter = RateMeter()
        private var lastReport = 0L
        var runBytes = 0L
            private set
        var done = 0
        var uploaded = 0
        var alreadyThere = 0
        var failed = 0
        private var consecutiveFailures = 0
        var lastError: String? = null
        val errors = ArrayList<String>()

        @Volatile
        var aborted = false

        suspend fun start(item: MediaItem) = mutex.withLock {
            inFlight[item.id] = Triple(item.name, 0L, item.size)
            report(force = true)
        }

        /** [sent] bytes of [item] so far; [previous] is what was last reported for it. */
        suspend fun bytes(item: MediaItem, sent: Long, previous: Long) = mutex.withLock {
            runBytes += sent - previous
            inFlight[item.id] = Triple(item.name, sent, item.size)
            report(force = false)
        }

        /** A retry restarts the file, so don't count the failed attempt's bytes twice. */
        suspend fun restart(item: MediaItem, previous: Long) = mutex.withLock {
            runBytes -= previous
            inFlight[item.id] = Triple(item.name, 0L, item.size)
        }

        suspend fun finished(item: MediaItem, sent: Boolean) = mutex.withLock {
            inFlight.remove(item.id)
            processedBytes += item.size
            done++
            if (sent) uploaded++ else alreadyThere++
            consecutiveFailures = 0
            report(force = true)
        }

        suspend fun skipped(item: MediaItem) = mutex.withLock {
            inFlight.remove(item.id)
            processedBytes += item.size
            done++
            report(force = true)
        }

        suspend fun failed(item: MediaItem, error: String) = mutex.withLock {
            inFlight.remove(item.id)
            processedBytes += item.size
            done++
            failed++
            consecutiveFailures++
            lastError = error
            if (errors.size < MAX_ERRORS_KEPT) errors += error
            // Several in a row means the NAS itself is in trouble: stop and retry later.
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) aborted = true
            report(force = true)
        }

        private suspend fun report(force: Boolean) {
            val now = System.currentTimeMillis()
            // Progress goes through WorkManager's database, so report a few times a second at most.
            if (!force && now - lastReport < PROGRESS_INTERVAL_MS) return
            lastReport = now
            val files = inFlight.values.toList()
            val current = when (files.size) {
                0 -> null
                1 -> files[0].first
                else -> "${files[0].first} + ${files.size - 1} more"
            }
            val inFlightSent = files.sumOf { it.second }
            onProgress(
                Progress(
                    done, total, failed, current,
                    fileBytes = inFlightSent,
                    fileSize = files.sumOf { it.third },
                    bytesPerSecond = meter.update(runBytes),
                    files = files.map { FileProgress(it.first, it.second, it.third) },
                    bytesDone = processedBytes + inFlightSent,
                    bytesTotal = totalBytes,
                )
            )
        }
    }

    /**
     * Uploads [pending], several at a time (Settings > Simultaneous uploads). [overwrite]
     * maps media ids to a NAS path to replace (a damaged copy found by a full check) instead
     * of choosing a name.
     */
    private suspend fun uploadAll(
        config: NasConfig,
        pending: List<MediaItem>,
        cache: FolderCache,
        overwrite: Map<Long, String>,
        onProgress: suspend (Progress) -> Unit,
    ): Summary = coroutineScope {
        val parallel = settings.prefs.first().parallelUploads.coerceIn(1, 4)
        val run = UploadRun(pending.size, pending.sumOf { it.size }, onProgress)
        val queue = Channel<MediaItem>(Channel.UNLIMITED)
        pending.forEach { queue.trySend(it) }
        queue.close()

        val workers = List(minOf(parallel, pending.size.coerceAtLeast(1))) {
            launch {
                for (item in queue) {
                    if (run.aborted) break
                    run.start(item)
                    var fileSent = 0L
                    try {
                        // The block re-opens the input itself, so NasClient's reconnect-and-retry is safe.
                        val sent = nas.withShare { share ->
                            run.restart(item, fileSent)
                            fileSent = 0
                            backupOne(share, config, item, cache, overwrite[item.id]) { s ->
                                val previous = fileSent
                                fileSent = s
                                run.bytes(item, s, previous)
                            }
                        }
                        run.finished(item, sent)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: NasNotConfiguredException) {
                        throw e
                    } catch (e: FileNotFoundException) {
                        // Deleted from the phone since the list was loaded; nothing to back up.
                        run.skipped(item)
                    } catch (e: Exception) {
                        run.failed(item, "${item.name}: ${NasClient.describe(e)}")
                    }
                }
            }
        }
        workers.joinAll()
        onProgress(Progress(pending.size, pending.size, run.failed, null))
        Summary(
            uploaded = run.uploaded,
            alreadyThere = run.alreadyThere,
            failed = run.failed,
            aborted = run.aborted,
            lastError = run.lastError,
            bytesUploaded = run.runBytes,
            errors = run.errors.toList(),
        )
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
            index.clearDamaged(item.id)
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
        val same = openOriginal(item).use { NasClient.sha256(it) }.contentEquals(NasClient.sha256(share, path))
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
     * "Check backups": compares the phone with the NAS. Every photo and video in [items] should
     * have a copy there; ones marked as backed up are checked, and unmarked ones are looked for
     * (and re-recorded if found), so files deleted on the NAS are reported too.
     * Quick checks name and size from folder listings; [full] also reads every copy back and
     * compares it byte for byte. Damaged copies are remembered so the next backup replaces
     * them; with [repair], that happens now, and anything missing is uploaded again.
     * Never deletes anything.
     */
    suspend fun check(
        items: List<MediaItem>,
        full: Boolean,
        repair: Boolean,
        onProgress: suspend (Progress) -> Unit,
    ): CheckReport = lock.withLock {
        val config = settings.config.first() ?: throw NasNotConfiguredException()
        val marked = items.filter { index.isBackedUp(it) }
        onProgress(Progress(0, items.size, 0, null, phase = Phase.Check))
        val cache = FolderCache()

        // 0. Unmarked photos: find their copy where a backup would have put it. Found with
        // matching size and time means it's there (e.g. after Reset history); re-record it.
        // Anything not found isn't on the NAS: deleted there, or never backed up.
        val notBackedUp = ArrayList<MediaItem>()
        val refound = ArrayList<MediaItem>()
        nas.withShare { share ->
            notBackedUp.clear()
            refound.clear()
            for (item in items) {
                currentCoroutineContext().ensureActive()
                if (index.isBackedUp(item)) continue
                val dir = remoteDir(config, item)
                val name = NasPath.safeName(item.name)
                val remote = cache.files(share, dir)[name.lowercase()]
                val same = remote != null && remote.size == item.size &&
                    item.modifiedAt > 0 && abs(remote.modifiedAt - item.modifiedAt) <= TIME_TOLERANCE_MS
                if (same) refound += item else notBackedUp += item
            }
        }
        refound.forEach { index.mark(it.id, NasPath.join(remoteDir(config, it), NasPath.safeName(it.name)), it.size) }

        // 1. Presence and size, from one listing per folder.
        val missingIds = forgetMissing(config, marked, cache).toHashSet()
        val present = marked.filter { it.id !in missingIds } + refound

        // 2. Content, file by file (full only).
        val damaged = HashMap<Long, String>() // id -> path of the bad copy
        var matched = 0
        var unreadable = 0
        if (full) {
            val recorded = index.entries()
            val transfer = Transfer()
            for ((i, item) in present.withIndex()) {
                currentCoroutineContext().ensureActive()
                val path = recorded[item.id]?.first ?: continue
                try {
                    if (readBackMatches(item, path, Phase.Check, i, present.size, transfer, onProgress)) matched++
                    else damaged[item.id] = path
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    // Gone from the phone meanwhile; nothing to compare (counted as skipped).
                } catch (e: Exception) {
                    unreadable++
                }
            }
            // Unmark them, and remember them: their name, size and time still match, so a
            // normal backup would otherwise take the damaged copy as good.
            index.remove(damaged.keys)
            index.markDamaged(damaged)
        } else {
            matched = present.size
            onProgress(Progress(marked.size, marked.size, 0, null, phase = Phase.Check))
        }

        // 3. Repair: upload what's missing, replace what's damaged.
        var repaired = 0
        var repairFailed = 0
        var aborted = false
        var lastError: String? = null
        var repairErrors: List<String> = emptyList()
        if (repair && (missingIds.isNotEmpty() || damaged.isNotEmpty() || notBackedUp.isNotEmpty())) {
            val toFix = marked.filter { it.id in missingIds || it.id in damaged } + notBackedUp
            val s = uploadAll(config, toFix, cache, overwrite = index.damaged(), onProgress = onProgress)
            repaired = s.uploaded + s.alreadyThere
            repairFailed = s.failed
            aborted = s.aborted
            lastError = s.lastError
            repairErrors = s.errors
        }

        CheckReport(
            // Every phone file in scope was compared with the NAS one way or another.
            checked = items.size,
            ok = matched,
            missing = missingIds.size,
            different = damaged.size,
            unreadable = unreadable,
            skipped = present.size - matched - damaged.size - unreadable,
            notBackedUp = notBackedUp.size,
            repaired = repaired,
            repairFailed = repairFailed,
            aborted = aborted,
            lastError = lastError,
            errors = repairErrors,
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
            val transfer = Transfer()
            for ((i, item) in items.withIndex()) {
                currentCoroutineContext().ensureActive()
                val (path, size) = recorded[item.id] ?: continue
                if (size != item.size) continue
                try {
                    if (readBackMatches(item, path, Phase.Verify, i, items.size, transfer, onProgress)) verified += item.id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Can't prove the copy is good, so keep the phone's file.
                }
            }
            onProgress(Progress(items.size, items.size, 0, null, phase = Phase.Verify))
            verified
        }

    /** Bytes moved across a whole run, for its transfer rate. */
    private class Transfer {
        val meter = RateMeter()
        var runBytes = 0L
    }

    /**
     * Reads the NAS copy at [path] back and compares it with the phone's file by SHA-256,
     * reporting progress and transfer rate as item [i] of [total] in [phase].
     */
    private suspend fun readBackMatches(
        item: MediaItem,
        path: String,
        phase: Phase,
        i: Int,
        total: Int,
        transfer: Transfer,
        onProgress: suspend (Progress) -> Unit,
    ): Boolean {
        onProgress(Progress(i, total, 0, item.name, 0, item.size, phase, transfer.meter.bytesPerSecond))
        val local = openOriginal(item).use { NasClient.sha256(it) }
        var lastReport = 0L
        var fileRead = 0L
        val remote = nas.withShare { share ->
            // A retry starts the file again; a fresh digest is made per attempt.
            transfer.runBytes -= fileRead
            fileRead = 0
            NasClient.sha256(share, path) { read ->
                transfer.runBytes += read - fileRead
                fileRead = read
                val rate = transfer.meter.update(transfer.runBytes)
                val now = System.currentTimeMillis()
                if (now - lastReport >= PROGRESS_INTERVAL_MS || read >= item.size) {
                    lastReport = now
                    onProgress(
                        Progress(
                            i, total, 0, item.name, read, item.size, phase, rate,
                            files = listOf(FileProgress(item.name, read, item.size)),
                        )
                    )
                }
            }
        }
        return local.contentEquals(remote)
    }

    // --- Helpers -------------------------------------------------------------------------

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
        const val MAX_ERRORS_KEPT = 50
        const val PROGRESS_INTERVAL_MS = 300L
        /** SMB servers store times at 100 ns to 2 s precision (FAT-style), so allow a little slack. */
        const val TIME_TOLERANCE_MS = 2_000L
    }
}
