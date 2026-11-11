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
import dev.unifiphotosync.app.data.MediaItem
import dev.unifiphotosync.app.smb.NasClient
import dev.unifiphotosync.app.smb.NasNotConfiguredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph get() = (applicationContext as PhotoSyncApp).graph

    override suspend fun doWork(): Result {
        val fromTrigger = inputData.getBoolean(KEY_FROM_TRIGGER, false)
        val idsFile = inputData.getString(KEY_IDS_FILE)?.let(::File)
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        var retrying = false
        try {
            if (graph.settings.config.first() == null) return Result.failure(workDataOf(KEY_ERROR to "NAS not set up"))
            if (scheduled) {
                // Daily job: only back up on the chosen days, near the chosen time.
                val prefs = graph.settings.prefs.first()
                if (!BackupSchedule.isDue(LocalDateTime.now(), prefs.scheduleMinutes, prefs.scheduleDays)) {
                    return Result.success()
                }
            }
            // Long backups need a foreground service; if the OS refuses (background-start
            // limits on content-trigger runs), carry on as a normal job.
            runCatching { setForeground(foregroundInfo(0, 0, null)) }

            val items = selectItems(graph.media.loadAll(), idsFile)
            val summary = graph.engine.run(items) { p ->
                setProgress(
                    workDataOf(
                        KEY_DONE to p.done, KEY_TOTAL to p.total, KEY_FAILED to p.failed, KEY_CURRENT to p.current,
                        KEY_FILE_BYTES to p.fileBytes, KEY_FILE_SIZE to p.fileSize,
                    )
                )
                if (p.total > 0) {
                    val pct = if (p.fileSize > 0) " · ${(100 * p.fileBytes / p.fileSize).toInt()}%" else ""
                    notifyProgress(foregroundInfo(p.done, p.total, p.current?.let { it + pct }))
                }
            }
            val out = workDataOf(
                KEY_UPLOADED to summary.uploaded,
                KEY_FAILED to summary.failed,
                KEY_ERROR to summary.lastError,
            )
            return when {
                summary.aborted && runAttemptCount < 3 -> Result.retry().also { retrying = true }
                summary.aborted -> Result.failure(out).also { idsFile?.delete() }
                else -> Result.success(out).also { idsFile?.delete() }
            }
        } catch (e: NasNotConfiguredException) {
            idsFile?.delete()
            return Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // NAS unreachable before any upload started (e.g. while checking what's on it).
            val error = workDataOf(KEY_ERROR to NasClient.describe(e))
            if (runAttemptCount < 3) {
                retrying = true
                return Result.retry()
            }
            return Result.failure(error).also { idsFile?.delete() }
        } finally {
            withContext(NonCancellable) {
                // Content-URI triggers fire once; queue the next one behind this run.
                if (fromTrigger) graph.scheduler.queueNextTrigger(graph.settings.prefs.first())
                // Pin the next daily run to the chosen time (a retry keeps its own backoff timing).
                if (scheduled && !retrying) graph.scheduler.applySchedule(graph.settings.prefs.first())
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

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    private fun notifyProgress(info: ForegroundInfo) {
        val nm = NotificationManagerCompat.from(applicationContext)
        if (nm.areNotificationsEnabled()) {
            // SecurityException if POST_NOTIFICATIONS was revoked mid-run.
            runCatching { nm.notify(NOTIFICATION_ID, info.notification) }
        }
    }

    private fun foregroundInfo(done: Int, total: Int, current: String?): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, PhotoSyncApp.CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_backup)
            .setContentTitle(if (total > 0) "Backing up ${minOf(done + 1, total)} of $total" else "Checking for new photos")
            .setContentText(current)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

        const val SCOPE_AUTO = "auto"
        const val SCOPE_ALL = "all"
        const val SCOPE_ALBUM = "album"
        const val SCOPE_ITEMS = "items"

        private const val NOTIFICATION_ID = 1001
    }
}
