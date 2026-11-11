package dev.unifiphotosync.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dev.unifiphotosync.app.backup.BackupEngine
import dev.unifiphotosync.app.backup.BackupScheduler
import dev.unifiphotosync.app.data.BackupHistory
import dev.unifiphotosync.app.data.BackupIndex
import dev.unifiphotosync.app.data.MediaRepository
import dev.unifiphotosync.app.data.PendingMoves
import dev.unifiphotosync.app.data.Restorer
import dev.unifiphotosync.app.data.SettingsStore
import dev.unifiphotosync.app.smb.NasClient
import dev.unifiphotosync.app.smb.NasFileFetcher
import dev.unifiphotosync.app.smb.NasFileKeyer
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
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class PhotoSyncApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
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
        }
        .crossfade(true)
        .build()

    companion object {
        const val CHANNEL_BACKUP = "backup"
        const val CHANNEL_REPORTS = "reports"
    }
}
