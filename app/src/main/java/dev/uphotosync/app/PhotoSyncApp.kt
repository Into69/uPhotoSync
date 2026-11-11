package dev.uphotosync.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dev.uphotosync.app.backup.BackupEngine
import dev.uphotosync.app.backup.BackupScheduler
import dev.uphotosync.app.data.BackupHistory
import dev.uphotosync.app.data.CrashReport
import dev.uphotosync.app.data.BackupIndex
import dev.uphotosync.app.data.MediaRepository
import dev.uphotosync.app.data.PendingMoves
import dev.uphotosync.app.data.Restorer
import dev.uphotosync.app.data.SettingsStore
import dev.uphotosync.app.smb.NasClient
import dev.uphotosync.app.smb.NasFileFetcher
import dev.uphotosync.app.smb.NasFileKeyer
import dev.uphotosync.app.smb.NasThumbFetcher
import dev.uphotosync.app.smb.NasThumbKeyer
import dev.uphotosync.app.smb.NasThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Process-wide singletons; small enough that a DI framework isn't worth it. */
class AppGraph(app: Application) {
    val settings = SettingsStore(app)
    val media = MediaRepository(app)
    val index = BackupIndex(app)
    val nas = NasClient(settings)
    val engine = BackupEngine(app, index, nas, settings)
    val scheduler = BackupScheduler(app)
    val restorer = Restorer(app, nas, index, settings)
    val moves = PendingMoves(app)
    val history = BackupHistory(app)
    val thumbnails = NasThumbnails(app, nas)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class PhotoSyncApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        graph = AppGraph(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_BACKUP, "Backup progress", NotificationManager.IMPORTANCE_LOW),
                // Low importance: shown in the shade without sound, since scheduled runs are often at night.
                NotificationChannel(CHANNEL_REPORTS, "Backup reports", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Summary after each scheduled backup"
                },
            )
        )
        // Re-apply the auto-backup schedule in case it was lost (app update, cleared data).
        graph.appScope.launch {
            graph.scheduler.apply(graph.settings.prefs.first(), replaceTrigger = false)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(VideoFrameDecoder.Factory())
            add(NasFileFetcher.Factory(graph.nas))
            add(NasFileKeyer())
            add(NasThumbFetcher.Factory(graph.thumbnails))
            add(NasThumbKeyer())
        }
        .crossfade(true)
        .build()

    companion object {
        const val CHANNEL_BACKUP = "backup"
        const val CHANNEL_REPORTS = "reports"
    }
}
