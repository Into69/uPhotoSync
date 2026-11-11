package dev.unifiphotosync.app.backup

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.unifiphotosync.app.PhotoSyncApp
import dev.unifiphotosync.app.R
import dev.unifiphotosync.app.data.NasPath
import dev.unifiphotosync.app.smb.NasClient
import dev.unifiphotosync.app.smb.NasEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * "Save to phone" as a background job, so a large download finishes even if the app is
 * closed. Runs as a foreground service with its own progress notification.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph get() = (applicationContext as PhotoSyncApp).graph
    private val path get() = inputData.getString(KEY_PATH).orEmpty()
    private val size get() = inputData.getLong(KEY_SIZE, 0)
    private val name get() = NasPath.name(path)
    private val notificationId get() = NOTIFICATION_BASE + (id.hashCode() and 0xFFFF)

    override suspend fun doWork(): Result {
        val entry = NasEntry(name, path, isDirectory = false, size = size, modifiedAt = inputData.getLong(KEY_MODIFIED, 0))
        runCatching { setForeground(foregroundInfo(0)) }
        val written = AtomicLong(0)
        return try {
            coroutineScope {
                // Report progress twice a second rather than on every buffer write.
                val reporter = launch {
                    while (isActive) {
                        val bytes = written.get()
                        setProgress(workDataOf(KEY_BYTES to bytes, KEY_SIZE to size))
                        notify(foregroundInfo(bytes))
                        delay(500)
                    }
                }
                graph.restorer.restore(entry) { written.set(it) }
                reporter.cancel()
            }
            Result.success(workDataOf(KEY_NAME to name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf(KEY_NAME to name, KEY_ERROR to NasClient.describe(e)))
        } finally {
            // If the foreground service couldn't start, the progress notification is ours to clear.
            NotificationManagerCompat.from(applicationContext).cancel(notificationId)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    private fun notify(info: ForegroundInfo) {
        val nm = NotificationManagerCompat.from(applicationContext)
        if (nm.areNotificationsEnabled()) runCatching { nm.notify(notificationId, info.notification) }
    }

    private fun foregroundInfo(bytes: Long): ForegroundInfo {
        val max = if (size > 0) 1000 else 0
        val progress = if (size > 0) (bytes * 1000 / size).toInt().coerceIn(0, 1000) else 0
        val notification = NotificationCompat.Builder(applicationContext, PhotoSyncApp.CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_backup)
            .setContentTitle("Saving to phone")
            .setContentText(name)
            .setProgress(max, progress, size <= 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "download"
        /** Tag prefix carrying the file name, so the UI can show what's downloading. */
        const val TAG_FILE_PREFIX = "file:"
        const val KEY_PATH = "path"
        const val KEY_SIZE = "size"
        const val KEY_MODIFIED = "modified"
        const val KEY_BYTES = "bytes"
        const val KEY_NAME = "name"
        const val KEY_ERROR = "error"
        private const val NOTIFICATION_BASE = 2000
    }
}
