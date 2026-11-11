package dev.unifiphotosync.app.backup

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.unifiphotosync.app.data.BackupPrefs
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Duration
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

    fun apply(prefs: BackupPrefs, replaceTrigger: Boolean = true) {
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

    fun backupNow(scope: BackupScope) {
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
            .setInputData(input)
            .addTag(TAG)
            .addTag(TAG_MANUAL)
            .build()
        wm.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelAll() = wm.cancelAllWorkByTag(TAG_MANUAL)

    val workInfos: Flow<List<WorkInfo>> = wm.getWorkInfosByTagFlow(TAG)

    private fun autoConstraints(prefs: BackupPrefs) = Constraints.Builder()
        .setRequiredNetworkType(if (prefs.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(prefs.chargingOnly)
        .setRequiresBatteryNotLow(true)

    companion object {
        const val TAG = "backup"
        const val TAG_MANUAL = "backup-manual-run"
        private const val PERIODIC = "backup-periodic"
        private const val TRIGGER = "backup-trigger"
        private const val MANUAL = "backup-manual"

        fun selectionDir(context: Context) = File(context.noBackupFilesDir, "selections").apply { mkdirs() }
    }
}
