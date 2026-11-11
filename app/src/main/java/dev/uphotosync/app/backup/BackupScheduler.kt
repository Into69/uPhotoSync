package dev.uphotosync.app.backup

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.uphotosync.app.data.BackupPrefs
import dev.uphotosync.app.smb.NasEntry
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What a manual "back up now" covers. */
sealed interface BackupScope {
    /** Every photo and video, ignoring the auto-backup album choice. */
    data object All : BackupScope
    data class Album(val bucketId: String) : BackupScope
    data class Items(val ids: Collection<Long>) : BackupScope
}

/**
 * Auto backup = a periodic sweep (catches anything missed) plus a MediaStore
 * content trigger so new photos go up shortly after they're taken.
 */
class BackupScheduler(private val context: Context) {
    private val wm = WorkManager.getInstance(context)

    /**
     * [replaceTrigger] is true when settings change. It's false at app start, which only
     * restores missing work, so a scheduled run that's due but waiting isn't pushed to tomorrow.
     */
    fun apply(prefs: BackupPrefs, replaceTrigger: Boolean = true) {
        if (replaceTrigger || !prefs.scheduled || !hasPendingSchedule()) applySchedule(prefs)
        if (!prefs.autoBackup) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(TRIGGER)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(autoConstraints(prefs).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        enqueueTrigger(prefs, if (replaceTrigger) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /**
     * The daily backup at the user's chosen time. It's a 24-hour periodic job whose next
     * run is pinned to the next chosen time and day. A plain periodic job would drift later
     * each day, so each scheduled run calls this again to re-pin. Updating doesn't
     * interrupt a run in progress.
     */
    fun applySchedule(prefs: BackupPrefs) {
        val next = BackupSchedule.next(LocalDateTime.now(), prefs.scheduleMinutes, prefs.scheduleDays)
        if (!prefs.scheduled || next == null) {
            wm.cancelUniqueWork(SCHEDULED)
            return
        }
        val nextMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(autoConstraints(prefs).build())
            .setNextScheduleTimeOverride(nextMillis)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .setInputData(workDataOf(BackupWorker.KEY_SCHEDULED to true))
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(SCHEDULED, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun hasPendingSchedule(): Boolean =
        runCatching { wm.getWorkInfosForUniqueWork(SCHEDULED).get().any { !it.state.isFinished } }.getOrDefault(false)

    /** Called by a trigger run; appends so it doesn't cancel the run that's calling it. */
    fun queueNextTrigger(prefs: BackupPrefs) {
        if (prefs.autoBackup) enqueueTrigger(prefs, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueTrigger(prefs: BackupPrefs, policy: ExistingWorkPolicy) {
        val constraints = autoConstraints(prefs)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            // Let a burst of photos settle before uploading.
            .setTriggerContentUpdateDelay(Duration.ofSeconds(30))
            .setTriggerContentMaxDelay(Duration.ofMinutes(5))
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_FROM_TRIGGER to true))
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(TRIGGER, policy, request)
    }

    /**
     * Back up now. With [move] ("Move to NAS", manual only, never scheduled), the job also
     * verifies each copy and queues the verified files for deletion from the phone.
     */
    fun backupNow(scope: BackupScope, move: Boolean = false) {
        val input = when (scope) {
            BackupScope.All -> workDataOf(BackupWorker.KEY_SCOPE to BackupWorker.SCOPE_ALL)
            is BackupScope.Album -> workDataOf(
                BackupWorker.KEY_SCOPE to BackupWorker.SCOPE_ALBUM,
                BackupWorker.KEY_ALBUM to scope.bucketId,
            )
            // WorkManager input is capped at 10 KB, so big selections go through a file.
            is BackupScope.Items -> {
                val file = File(selectionDir(context), UUID.randomUUID().toString())
                file.writeText(scope.ids.joinToString("\n"))
                workDataOf(
                    BackupWorker.KEY_SCOPE to BackupWorker.SCOPE_ITEMS,
                    BackupWorker.KEY_IDS_FILE to file.absolutePath,
                )
            }
        }
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(if (move) Data.Builder().putAll(input).putBoolean(BackupWorker.KEY_MOVE, true).build() else input)
            .addTag(TAG)
            .addTag(TAG_MANUAL)
            .build()
        wm.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * "Check backups": confirms everything marked as backed up is really on the NAS. [full]
     * also compares every file byte for byte; [repair] re-uploads anything missing or different.
     */
    fun checkNow(full: Boolean, repair: Boolean) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    // A scope marks it as manual, so Wi-Fi-name limits for automatic runs don't apply.
                    BackupWorker.KEY_SCOPE to BackupWorker.SCOPE_ALL,
                    BackupWorker.KEY_CHECK to if (full) BackupWorker.CHECK_FULL else BackupWorker.CHECK_QUICK,
                    BackupWorker.KEY_REPAIR to repair,
                )
            )
            .addTag(TAG)
            .addTag(TAG_MANUAL)
            .build()
        wm.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelAll() = wm.cancelAllWorkByTag(TAG_MANUAL)

    /** "Save to phone" in the background, so it finishes even if the app is closed. */
    fun download(entry: NasEntry) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_PATH to entry.path,
                    DownloadWorker.KEY_SIZE to entry.size,
                    DownloadWorker.KEY_MODIFIED to entry.modifiedAt,
                )
            )
            .addTag(DownloadWorker.TAG)
            .addTag(DownloadWorker.TAG_FILE_PREFIX + entry.name)
            .build()
        // Independent jobs (not a chain), so one failed download doesn't cancel the rest.
        wm.enqueue(request)
    }

    /**
     * Saves many NAS files to the phone as one background job (e.g. whole folders). It shows up
     * alongside single downloads on the NAS tab.
     */
    fun restoreBatch(entries: List<NasEntry>) {
        if (entries.isEmpty()) return
        // The list can be thousands of files; WorkManager input is capped at 10 KB, so use a file.
        val file = File(selectionDir(context), "restore-" + UUID.randomUUID())
        file.writeText(entries.joinToString("\n") { "${it.path}\t${it.size}\t${it.modifiedAt}" })
        val n = entries.size
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(RestoreWorker.KEY_LIST_FILE to file.absolutePath))
            .addTag(DownloadWorker.TAG)
            .addTag(DownloadWorker.TAG_FILE_PREFIX + "$n ${if (n == 1) "file" else "files"}")
            .build()
        wm.enqueue(request)
    }

    val downloads: Flow<List<WorkInfo>> = wm.getWorkInfosByTagFlow(DownloadWorker.TAG)

    val workInfos: Flow<List<WorkInfo>> = wm.getWorkInfosByTagFlow(TAG)

    private fun autoConstraints(prefs: BackupPrefs) = Constraints.Builder()
        .setRequiredNetworkType(if (prefs.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(prefs.chargingOnly)
        .setRequiresBatteryNotLow(true)

    companion object {
        const val TAG = "backup"
        const val TAG_MANUAL = "backup-manual-run"
        private const val PERIODIC = "backup-periodic"
        private const val SCHEDULED = "backup-scheduled"
        private const val TRIGGER = "backup-trigger"
        private const val MANUAL = "backup-manual"

        fun selectionDir(context: Context) = File(context.noBackupFilesDir, "selections").apply { mkdirs() }
    }
}
