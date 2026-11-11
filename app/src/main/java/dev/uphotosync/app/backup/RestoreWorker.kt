package dev.uphotosync.app.backup

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
import dev.uphotosync.app.data.NasPath
import dev.uphotosync.app.smb.NasClient
import dev.uphotosync.app.smb.NasEntry
import dev.uphotosync.app.ui.MainActivity
import dev.uphotosync.app.ui.Tab
import dev.uphotosync.app.ui.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * "Save to phone" for many files at once (e.g. whole NAS folders): saves each photo and video
 * into its original phone folder, one at a time, as a background job with a progress
 * notification. The list comes in a file, as it can be thousands of entries.
 */
class RestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph get() = (applicationContext as PhotoSyncApp).graph
    private val notifications get() = NotificationManagerCompat.from(applicationContext)

    override suspend fun doWork(): Result {
        val listFile = inputData.getString(KEY_LIST_FILE)?.let(::File) ?: return Result.failure()
        val entries = runCatching {
            listFile.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                val path = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NasEntry(
                    name = NasPath.name(path), path = path, isDirectory = false,
                    size = parts.getOrNull(1)?.toLongOrNull() ?: 0, modifiedAt = parts.getOrNull(2)?.toLongOrNull() ?: 0,
                )
            }
        }.getOrDefault(emptyList())
            // A job that was paused (e.g. Wi-Fi dropped) runs again from the top: skip what an
            // earlier attempt already saved, so nothing is saved twice.
            .let { all ->
                val onPhone = runCatching {
                    val ids = graph.media.loadAll().mapTo(HashSet()) { it.id }
                    graph.index.entries().filterKeys { it in ids }.values.mapTo(HashSet()) { it.first.lowercase() }
                }.getOrDefault(emptySet())
                all.filter { it.path.lowercase() !in onPhone }
            }
        val startedAt = System.currentTimeMillis()
        runCatching { setForeground(foregroundInfo(0, entries.size, null, 0)) }

        var saved = 0
        var failed = 0
        val errors = ArrayList<String>()
        var bytesSaved = 0L
        var finished = false
        try {
            for ((i, entry) in entries.withIndex()) {
                val written = AtomicLong(0)
                try {
                    coroutineScope {
                        // Progress twice a second rather than on every write.
                        val reporter = launch {
                            val meter = RateMeter()
                            while (isActive) {
                                val bytes = written.get()
                                val rate = meter.update(bytes)
                                setProgress(
                                    workDataOf(
                                        DownloadWorker.KEY_BYTES to bytes, DownloadWorker.KEY_SIZE to entry.size,
                                        DownloadWorker.KEY_RATE to rate, KEY_DONE to i, KEY_TOTAL to entries.size,
                                        KEY_CURRENT to entry.name,
                                    )
                                )
                                notify(foregroundInfo(i, entries.size, entry.name, rate))
                                delay(500)
                            }
                        }
                        graph.restorer.restore(entry) { written.set(it) }
                        reporter.cancel()
                    }
                    saved++
                    bytesSaved += entry.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    if (errors.size < 50) errors += "${entry.name}: ${NasClient.describe(e)}"
                }
            }
            finished = true
        } finally {
            notifications.cancel(NOTIFICATION_ID)
            // Keep the list if the job was stopped partway: WorkManager runs it again later.
            if (finished) listFile.delete()
        }

        val summary = buildList {
            add("$saved saved to the phone")
            if (failed > 0) add("$failed failed")
        }.joinToString(", ")
        runCatching {
            graph.history.add(
                HistoryEntry(
                    id = 0, kind = "Save to phone", startedAt = startedAt, finishedAt = System.currentTimeMillis(),
                    outcome = if (failed == 0) HistoryEntry.Outcome.Success else HistoryEntry.Outcome.Partial,
                    summary = summary, bytes = bytesSaved, errors = errors,
                )
            )
        }
        val name = "$saved ${if (saved == 1) "file" else "files"}"
        // Nothing saved at all is a failure, so the NAS tab says so instead of "Saved 0 files".
        if (saved == 0 && failed > 0) {
            return Result.failure(
                workDataOf(
                    DownloadWorker.KEY_NAME to "$failed ${if (failed == 1) "file" else "files"}",
                    DownloadWorker.KEY_ERROR to (errors.firstOrNull() ?: "every file failed"),
                )
            )
        }
        return Result.success(
            workDataOf(
                DownloadWorker.KEY_NAME to name,
                DownloadWorker.KEY_ERROR to if (failed > 0) "$failed failed" else null,
            )
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null, 0)

    private fun notify(info: ForegroundInfo) {
        if (notifications.areNotificationsEnabled()) runCatching { notifications.notify(NOTIFICATION_ID, info.notification) }
    }

    private fun foregroundInfo(done: Int, total: Int, current: String?, rate: Long): ForegroundInfo {
        val text = listOfNotNull(current, rate.takeIf { it > 0 }?.let { formatSize(it) + "/s" }).joinToString(" · ")
        val notification = NotificationCompat.Builder(applicationContext, PhotoSyncApp.CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_backup)
            .setContentTitle(if (total > 0) "Saving to phone: ${minOf(done + 1, total)} of $total" else "Saving to phone")
            .setContentText(text.ifEmpty { null })
            .setProgress(total, done, total == 0)
            .setContentIntent(MainActivity.openIntent(applicationContext, Tab.Nas))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_LIST_FILE = "list_file"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"
        private const val NOTIFICATION_ID = 1004
    }
}
