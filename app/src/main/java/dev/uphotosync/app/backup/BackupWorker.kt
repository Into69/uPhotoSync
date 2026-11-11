package dev.uphotosync.app.backup

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.R
import dev.uphotosync.app.data.HistoryEntry
import dev.uphotosync.app.data.HistoryEntry.Outcome
import dev.uphotosync.app.data.MediaItem
import dev.uphotosync.app.data.WifiNetwork
import dev.uphotosync.app.smb.NasClient
import dev.uphotosync.app.smb.NasNotConfiguredException
import dev.uphotosync.app.ui.MainActivity
import dev.uphotosync.app.ui.Tab
import dev.uphotosync.app.ui.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph get() = (applicationContext as PhotoSyncApp).graph
    private val scheduled get() = inputData.getBoolean(KEY_SCHEDULED, false)
    private val move get() = inputData.getBoolean(KEY_MOVE, false)
    private val runLabel get() = if (move) "Move to NAS" else "Scheduled backup"
    private val notifications get() = NotificationManagerCompat.from(applicationContext)

    override suspend fun doWork(): Result {
        val fromTrigger = inputData.getBoolean(KEY_FROM_TRIGGER, false)
        val idsFile = inputData.getString(KEY_IDS_FILE)?.let(::File)
        val startedAt = System.currentTimeMillis()
        var retrying = false
        try {
            if (graph.settings.config.first() == null) {
                if (scheduled) postReport("Scheduled backup failed", "The NAS isn't set up. Open Settings to connect it.")
                record(Outcome.Failed, "The NAS isn't set up", startedAt)
                return Result.failure(stamped(KEY_ERROR to "NAS not set up"))
            }
            if (scheduled) {
                // Daily job: only back up on the chosen days, near the chosen time. Off days are silent.
                val prefs = graph.settings.prefs.first()
                if (!BackupSchedule.isDue(LocalDateTime.now(), prefs.scheduleMinutes, prefs.scheduleDays)) {
                    return Result.success()
                }
            }
            // Automatic runs (not manual "Back up") can be limited to named Wi-Fi networks.
            val automatic = inputData.getString(KEY_SCOPE) == null
            val prefs = graph.settings.prefs.first()
            if (automatic && prefs.ssidRestricted) {
                val ssid = WifiNetwork.currentSsid(applicationContext)
                if (!WifiNetwork.matches(ssid, prefs.wifiSsids)) {
                    val why = if (ssid == null) {
                        "couldn't read the Wi-Fi name (check location access in Settings > Background access)"
                    } else {
                        "on \"$ssid\", not an allowed Wi-Fi network"
                    }
                    if (scheduled) postReport("Scheduled backup skipped", why.replaceFirstChar { it.uppercase() })
                    record(Outcome.Skipped, "Skipped: $why", startedAt)
                    return Result.success(stamped(KEY_SKIPPED to "Automatic backup skipped: $why"))
                }
            }

            // Show that it's running straight away. Long backups need a foreground service;
            // if the OS refuses (background-start limits), carry on as a normal job and post
            // the same notification ourselves.
            val starting = foregroundInfo(0, 0, null)
            if (runCatching { setForeground(starting) }.isFailure && scheduled) postProgress(starting)

            val onProgress: suspend (BackupEngine.Progress) -> Unit = { p ->
                setProgress(
                    workDataOf(
                        KEY_DONE to p.done, KEY_TOTAL to p.total, KEY_FAILED to p.failed, KEY_CURRENT to p.current,
                        KEY_FILE_BYTES to p.fileBytes, KEY_FILE_SIZE to p.fileSize,
                        KEY_PHASE to p.phase.name, KEY_RATE to p.bytesPerSecond,
                        // One entry per file in flight (at most 4, well within WorkManager's 10 KB limit).
                        KEY_FILE_NAMES to p.files.map { it.name.take(120) }.toTypedArray(),
                        KEY_FILE_SENTS to p.files.map { it.sent }.toLongArray(),
                        KEY_FILE_SIZES to p.files.map { it.size }.toLongArray(),
                        KEY_BYTES_DONE to p.bytesDone, KEY_BYTES_TOTAL to p.bytesTotal,
                    )
                )
                if (p.total > 0) {
                    val detail = buildList {
                        if (p.fileSize > 0 && p.fileBytes > 0) add("${(100 * p.fileBytes / p.fileSize).toInt()}%")
                        if (p.bytesPerSecond > 0) add(formatRate(p.bytesPerSecond))
                    }.joinToString(" · ")
                    val text = listOfNotNull(p.current, detail.takeIf { it.isNotEmpty() }).joinToString(" · ")
                    postProgress(foregroundInfo(p.done, p.total, text.ifEmpty { null }, p.phase))
                }
            }

            // "Check backups": make sure the NAS really has everything marked as backed up.
            inputData.getString(KEY_CHECK)?.let { mode ->
                val albums = graph.settings.prefs.first().albums
                val report = graph.engine.check(
                    graph.media.loadAll().filter { albums.isEmpty() || it.bucketId in albums },
                    full = mode == CHECK_FULL,
                    repair = inputData.getBoolean(KEY_REPAIR, true),
                    onProgress = onProgress,
                )
                val (title, text) = describeCheck(report, mode == CHECK_FULL)
                // Check reports open Settings, where Check backups lives.
                postReport(title, text, Tab.Settings)
                record(
                    outcome = when {
                        report.aborted || report.repairFailed > 0 -> Outcome.Partial
                        // Problems found and not (all) fixed.
                        report.missing + report.notBackedUp + report.different + report.unreadable > report.repaired ->
                            Outcome.Partial
                        else -> Outcome.Success
                    },
                    summary = text,
                    startedAt = startedAt,
                    uploaded = report.repaired,
                    failed = report.repairFailed,
                    errors = report.errors,
                )
                return Result.success(stamped(KEY_CHECK_RESULT to "$title: $text"))
            }

            val items = selectItems(graph.media.loadAll(), idsFile)
            val summary = graph.engine.run(items, onProgress)

            // "Move to NAS": prove each copy is identical, then hand the verified ones to the
            // app to delete from the phone (Android only allows that with the user's OK).
            var moveReady = 0
            var moveKept = 0
            if (move && !summary.aborted) {
                val verified = graph.engine.verifyCopies(items, onProgress)
                graph.moves.add(items.filter { it.id in verified }.associate { it.id to it.modifiedAt })
                moveReady = verified.size
                moveKept = items.size - verified.size
                reportMove(moveReady, moveKept)
            }

            val out = stamped(
                KEY_UPLOADED to summary.uploaded,
                KEY_FAILED to summary.failed,
                KEY_ERROR to summary.lastError,
                KEY_MOVE_READY to moveReady,
                KEY_MOVE_KEPT to moveKept,
                KEY_NO_SPACE to summary.noSpace,
            )
            val willRetry = summary.aborted && runAttemptCount < 3
            if (scheduled) reportSummary(summary, System.currentTimeMillis() - startedAt, willRetry)
            if (move && summary.aborted) {
                postReport(
                    if (willRetry) "Move to NAS will retry soon" else "Move to NAS failed",
                    "${summary.lastError ?: "The NAS stopped responding"}. Nothing was deleted from the phone.",
                )
            }
            val quietAutomatic = automatic && !scheduled && summary.uploaded == 0 && summary.failed == 0
            if (!quietAutomatic) {
                record(
                    outcome = when {
                        summary.aborted -> Outcome.Failed
                        summary.failed > 0 || moveKept > 0 || summary.noSpace > 0 -> Outcome.Partial
                        else -> Outcome.Success
                    },
                    summary = buildList {
                        add("${summary.uploaded} uploaded")
                        if (summary.alreadyThere > 0) add("${summary.alreadyThere} already on the NAS")
                        if (summary.failed > 0) add("${summary.failed} failed")
                        if (summary.noSpace > 0) add(noSpaceNote(summary))
                        if (move) add("$moveReady verified for deleting from the phone")
                        if (moveKept > 0) add("$moveKept kept on the phone")
                        if (summary.aborted) add(if (willRetry) "stopped, will retry" else "stopped")
                    }.joinToString(", "),
                    startedAt = startedAt,
                    summary.uploaded, summary.alreadyThere, summary.failed, summary.bytesUploaded, summary.errors,
                )
            }
            return when {
                willRetry -> Result.retry().also { retrying = true }
                summary.aborted -> Result.failure(out)
                else -> Result.success(out)
            }
        } catch (e: NasNotConfiguredException) {
            if (scheduled || move) postReport("$runLabel failed", e.message.orEmpty())
            record(Outcome.Failed, e.message.orEmpty(), startedAt)
            return Result.failure(stamped(KEY_ERROR to e.message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // NAS unreachable before any upload started (e.g. while checking what's on it).
            val reason = NasClient.describe(e)
            if (runAttemptCount < 3) {
                retrying = true
                if (scheduled || move) postReport("$runLabel will retry soon", reason)
                record(Outcome.Failed, "$reason (will retry)", startedAt)
                return Result.retry()
            }
            if (scheduled || move) postReport("$runLabel failed", reason + if (move) ". Nothing was deleted from the phone." else "")
            record(Outcome.Failed, reason, startedAt)
            return Result.failure(stamped(KEY_ERROR to reason))
        } finally {
            withContext(NonCancellable) {
                // A progress notification we posted ourselves (no foreground service) isn't
                // removed by WorkManager, so clear it; the report has its own ID.
                notifications.cancel(PROGRESS_ID)
                // Content-URI triggers fire once; queue the next one behind this run.
                if (fromTrigger) graph.scheduler.queueNextTrigger(graph.settings.prefs.first())
                // Pin the next daily run to the chosen time (a retry keeps its own backoff timing).
                if (scheduled && !retrying) graph.scheduler.applySchedule(graph.settings.prefs.first())
                // The selection list is only needed if this run will be retried. Finished,
                // failed or cancelled, it's done with.
                if (!retrying) idsFile?.delete()
            }
        }
    }

    private suspend fun selectItems(all: List<MediaItem>, idsFile: File?): List<MediaItem> =
        when (inputData.getString(KEY_SCOPE) ?: SCOPE_AUTO) {
            SCOPE_ALL -> all
            SCOPE_ALBUM -> inputData.getString(KEY_ALBUM).let { album -> all.filter { it.bucketId == album } }
            SCOPE_ITEMS -> {
                val ids = idsFile?.takeIf { it.exists() }?.readLines()?.mapNotNull { it.toLongOrNull() }?.toHashSet()
                    ?: emptySet()
                all.filter { it.id in ids }
            }
            else -> {
                val albums = graph.settings.prefs.first().albums
                if (albums.isEmpty()) all else all.filter { it.bucketId in albums }
            }
        }

    /** What kind of run this is, for the history. */
    private val kindLabel: String
        get() = when {
            inputData.getString(KEY_CHECK) == CHECK_FULL -> "Full check"
            inputData.getString(KEY_CHECK) == CHECK_QUICK -> "Quick check"
            move -> "Move to NAS"
            scheduled -> "Scheduled backup"
            inputData.getString(KEY_SCOPE) == null -> "Automatic backup"
            else -> "Backup"
        }

    private fun record(
        outcome: Outcome,
        summary: String,
        startedAt: Long,
        uploaded: Int = 0,
        alreadyThere: Int = 0,
        failed: Int = 0,
        bytes: Long = 0,
        errors: List<String> = emptyList(),
    ) {
        runCatching {
            graph.history.add(
                HistoryEntry(
                    id = 0, kind = kindLabel, startedAt = startedAt, finishedAt = System.currentTimeMillis(),
                    outcome = outcome, summary = summary, uploaded = uploaded, alreadyThere = alreadyThere,
                    failed = failed, bytes = bytes, errors = errors,
                )
            )
        }
    }

    /** Output data with the finish time, so the UI can show the most recent result. */
    private fun stamped(vararg pairs: Pair<String, Any?>) =
        workDataOf(*pairs, KEY_FINISHED_AT to System.currentTimeMillis())

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    private fun postProgress(info: ForegroundInfo) {
        // SecurityException if POST_NOTIFICATIONS was revoked mid-run.
        if (notifications.areNotificationsEnabled()) runCatching { notifications.notify(PROGRESS_ID, info.notification) }
    }

    /** Tapping a notification opens the app on [tab]. */
    private fun openAppIntent(tab: Tab = Tab.Device): PendingIntent = MainActivity.openIntent(applicationContext, tab)

    private fun foregroundInfo(
        done: Int,
        total: Int,
        current: String?,
        phase: BackupEngine.Phase = BackupEngine.Phase.Upload,
    ): ForegroundInfo {
        val prefix = when {
            phase == BackupEngine.Phase.Check -> "Checking backups"
            phase == BackupEngine.Phase.Verify -> "Verifying on NAS"
            inputData.getString(KEY_CHECK) != null -> "Checking backups"
            move -> "Moving to NAS"
            scheduled -> "Scheduled backup"
            else -> "Backup"
        }
        val notification = NotificationCompat.Builder(applicationContext, PhotoSyncApp.CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_backup)
            .setContentTitle(
                if (total > 0) "$prefix: ${minOf(done + 1, total)} of $total" else "$prefix running: checking for new photos"
            )
            .setContentText(current)
            .setProgress(total, done, total == 0)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun reportSummary(s: BackupEngine.Summary, tookMillis: Long, willRetry: Boolean) {
        val took = formatDuration(tookMillis)
        val (title, text) = when {
            s.aborted && willRetry -> "Scheduled backup will retry soon" to
                "Stopped after ${s.uploaded} uploaded: ${s.lastError ?: "the NAS stopped responding"}"
            s.aborted -> "Scheduled backup failed" to
                "${s.uploaded} uploaded before it stopped: ${s.lastError ?: "the NAS stopped responding"}"
            s.noSpace > 0 -> "Scheduled backup: the NAS is nearly full" to
                "${s.uploaded} uploaded; " + noSpaceNote(s) + ". Free up space on the NAS."
            s.failed > 0 -> "Scheduled backup finished with errors" to
                "${s.uploaded} uploaded, ${s.failed} failed in $took. Last error: ${s.lastError}"
            s.uploaded > 0 -> "Scheduled backup complete" to
                "${s.uploaded} ${if (s.uploaded == 1) "file" else "files"} uploaded in $took"
            else -> "Scheduled backup complete" to "Everything was already backed up"
        }
        postReport(title, text)
    }

    private fun describeCheck(r: BackupEngine.CheckReport, full: Boolean): Pair<String, String> {
        val kind = if (full) "Full check" else "Quick check"
        // Missing (was backed up, now gone) and never/no-longer backed up look the same to the
        // user: on the phone, not on the NAS.
        val notOnNas = r.missing + r.notBackedUp
        val problems = notOnNas + r.different + r.unreadable
        if (r.checked == 0) return "$kind finished" to "No photos or videos to check."
        if (problems == 0) {
            // Count only what was actually confirmed; files skipped mid-check weren't compared.
            val all = if (r.ok == r.checked) "All " else ""
            val skippedNote = if (r.skipped > 0) " ${r.skipped} skipped (no longer on the phone)." else ""
            return "$kind: all good" to
                "$all${r.ok} ${if (r.ok == 1) "photo or video" else "photos and videos"} on this phone " +
                (if (r.ok == 1) "is" else "are") + " on the NAS" +
                (if (full) " and identical." else ".") + skippedNote
        }
        val found = buildList {
            if (notOnNas > 0) add("$notOnNas not on the NAS")
            if (r.different > 0) add("${r.different} different")
            if (r.unreadable > 0) add("${r.unreadable} couldn't be read")
            if (r.skipped > 0) add("${r.skipped} skipped (no longer on the phone)")
        }.joinToString(", ")
        val fix = when {
            r.repaired + r.repairFailed == 0 -> " Run a backup to upload them again."
            r.repairFailed > 0 -> " Re-uploaded ${r.repaired}; ${r.repairFailed} failed" +
                (r.lastError?.let { " ($it)" } ?: "") + "."
            else -> " Re-uploaded ${r.repaired}."
        }
        return "$kind: $problems ${if (problems == 1) "problem" else "problems"} found" to
            "Checked ${r.checked}: ${r.ok} OK, $found.$fix"
    }

    private fun noSpaceNote(s: BackupEngine.Summary) =
        "${s.noSpace} (${formatSize(s.noSpaceBytes)}) not backed up: not enough space on the NAS"

    private fun reportMove(ready: Int, kept: Int) {
        val keptNote = if (kept > 0) " $kept couldn't be verified and stay on the phone." else ""
        if (ready > 0) {
            postReport(
                "$ready ${if (ready == 1) "file" else "files"} verified on the NAS",
                "Tap to finish moving: confirm deleting ${if (ready == 1) "it" else "them"} from this phone.$keptNote",
            )
        } else {
            postReport("Move didn't finish", "Nothing could be verified on the NAS, so everything stays on the phone.")
        }
    }

    /** The end-of-run report; replaces the progress notification and stays until dismissed. */
    private fun postReport(title: String, text: String, tab: Tab = Tab.Device) {
        if (!notifications.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(applicationContext, PhotoSyncApp.CHANNEL_REPORTS)
            .setSmallIcon(R.drawable.ic_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(tab))
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        // Clear the progress notification first so the report takes its place.
        notifications.cancel(PROGRESS_ID)
        runCatching { notifications.notify(REPORT_ID, notification) }
    }

    private fun formatRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1L shl 20 -> "%.1f MB/s".format(bytesPerSecond / (1L shl 20).toDouble())
        bytesPerSecond >= 1L shl 10 -> "%.0f KB/s".format(bytesPerSecond / (1L shl 10).toDouble())
        else -> "$bytesPerSecond B/s"
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        }
    }

    companion object {
        const val KEY_SCOPE = "scope"
        const val KEY_ALBUM = "album"
        const val KEY_IDS_FILE = "ids_file"
        const val KEY_FROM_TRIGGER = "from_trigger"
        const val KEY_SCHEDULED = "scheduled"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_CURRENT = "current"
        const val KEY_FILE_BYTES = "file_bytes"
        const val KEY_FILE_SIZE = "file_size"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_ERROR = "error"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FINISHED_AT = "finished_at"
        const val KEY_MOVE = "move"
        const val KEY_MOVE_READY = "move_ready"
        const val KEY_MOVE_KEPT = "move_kept"
        const val KEY_NO_SPACE = "no_space"
        const val KEY_PHASE = "phase"
        const val KEY_RATE = "rate"
        const val KEY_FILE_NAMES = "file_names"
        const val KEY_FILE_SENTS = "file_sents"
        const val KEY_FILE_SIZES = "file_sizes"
        const val KEY_BYTES_DONE = "bytes_done"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_CHECK = "check"
        const val KEY_REPAIR = "repair"
        const val KEY_CHECK_RESULT = "check_result"
        const val CHECK_QUICK = "quick"
        const val CHECK_FULL = "full"

        const val SCOPE_AUTO = "auto"
        const val SCOPE_ALL = "all"
        const val SCOPE_ALBUM = "album"
        const val SCOPE_ITEMS = "items"

        private const val PROGRESS_ID = 1001
        private const val REPORT_ID = 1003
    }
}
