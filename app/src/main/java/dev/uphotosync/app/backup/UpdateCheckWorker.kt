package dev.uphotosync.app.backup

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.R
import dev.uphotosync.app.data.AppMeta
import dev.uphotosync.app.data.Updater
import dev.uphotosync.app.ui.MainActivity
import dev.uphotosync.app.ui.Tab
import java.util.concurrent.TimeUnit

/**
 * Once a week, on Wi-Fi: asks GitHub for the newest release and, if it's newer than this app,
 * posts one quiet notification for it (each version is announced once). Tapping it opens
 * Settings, where App updates downloads and installs it.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!AppMeta.autoUpdateCheck(context)) return Result.success()
        val updater = Updater(context)
        // Offline, rate-limited, GitHub down: try again next week rather than retrying now.
        val latest = runCatching { updater.latest() }.getOrNull() ?: return Result.success()
        if (!updater.isNewer(latest) || AppMeta.notifiedUpdate(context) == latest.version) return Result.success()

        val notifications = NotificationManagerCompat.from(context)
        if (notifications.areNotificationsEnabled()) {
            val notification = NotificationCompat.Builder(context, PhotoSyncApp.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_backup)
                .setContentTitle("uPhotoSync ${latest.version} is available")
                .setContentText("Tap to download and install it from Settings")
                .setContentIntent(MainActivity.openIntent(context, Tab.Settings))
                .setAutoCancel(true)
                .build()
            runCatching { notifications.notify(NOTIFICATION_ID, notification) }
        }
        AppMeta.setNotifiedUpdate(context, latest.version)
        return Result.success()
    }

    companion object {
        private const val WORK = "update-check"
        private const val NOTIFICATION_ID = 1010

        /** Starts (or stops) the weekly check to match the setting. Safe to call on every app start. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!AppMeta.autoUpdateCheck(context)) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                // Not on the very first launch; a day in is soon enough.
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
