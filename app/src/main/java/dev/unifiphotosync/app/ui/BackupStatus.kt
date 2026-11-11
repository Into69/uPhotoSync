package dev.unifiphotosync.app.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import dev.unifiphotosync.app.PhotoSyncApp
import dev.unifiphotosync.app.backup.BackupScheduler
import dev.unifiphotosync.app.backup.BackupWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class BackupStatus(
    val running: Boolean = false,
    /** Manual runs can be stopped; scheduled ones just run again later. */
    val stoppable: Boolean = false,
    val waiting: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val fileBytes: Long = 0,
    val fileSize: Long = 0,
    /** Reading copies back from the NAS to check them (Move to NAS). */
    val verifying: Boolean = false,
    /** Upload, Verify or Check (see BackupEngine.Phase). */
    val phase: String = "Upload",
    /** Current transfer rate in bytes per second. */
    val bytesPerSecond: Long = 0,
    /** Outcome of the most recent finished run, if it's worth showing. */
    val lastResult: String? = null,
)

class BackupStatusViewModel(app: Application) : AndroidViewModel(app) {
    private val scheduler = (app as PhotoSyncApp).graph.scheduler

    val status = scheduler.workInfos.map(::summarize)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupStatus())

    fun cancel() = scheduler.cancelAll()

    private fun summarize(infos: List<WorkInfo>): BackupStatus {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            val p = running.progress
            return BackupStatus(
                running = true,
                stoppable = BackupScheduler.TAG_MANUAL in running.tags,
                done = p.getInt(BackupWorker.KEY_DONE, 0),
                total = p.getInt(BackupWorker.KEY_TOTAL, 0),
                failed = p.getInt(BackupWorker.KEY_FAILED, 0),
                current = p.getString(BackupWorker.KEY_CURRENT),
                fileBytes = p.getLong(BackupWorker.KEY_FILE_BYTES, 0),
                fileSize = p.getLong(BackupWorker.KEY_FILE_SIZE, 0),
                verifying = p.getBoolean(BackupWorker.KEY_VERIFYING, false),
                phase = p.getString(BackupWorker.KEY_PHASE) ?: "Upload",
                bytesPerSecond = p.getLong(BackupWorker.KEY_RATE, 0),
            )
        }
        // A manual run that's queued (e.g. waiting for a network) but not started yet.
        val waiting = infos.any {
            it.state == WorkInfo.State.ENQUEUED && BackupScheduler.TAG_MANUAL in it.tags
        }
        val last = infos.filter { it.state.isFinished && it.state != WorkInfo.State.CANCELLED }
            .maxByOrNull { it.outputData.getLong(BackupWorker.KEY_FINISHED_AT, 0) }
            ?.let { describe(it) }
        return BackupStatus(waiting = waiting, lastResult = last)
    }

    private fun describe(info: WorkInfo): String? {
        val out = info.outputData
        val uploaded = out.getInt(BackupWorker.KEY_UPLOADED, 0)
        val failed = out.getInt(BackupWorker.KEY_FAILED, 0)
        val error = out.getString(BackupWorker.KEY_ERROR)
        val skipped = out.getString(BackupWorker.KEY_SKIPPED)
        return when {
            skipped != null -> skipped
            out.getString(BackupWorker.KEY_CHECK_RESULT) != null -> out.getString(BackupWorker.KEY_CHECK_RESULT)
            out.getInt(BackupWorker.KEY_MOVE_READY, 0) > 0 || out.getInt(BackupWorker.KEY_MOVE_KEPT, 0) > 0 -> {
                val ready = out.getInt(BackupWorker.KEY_MOVE_READY, 0)
                val kept = out.getInt(BackupWorker.KEY_MOVE_KEPT, 0)
                "Move: $ready verified on the NAS" + if (kept > 0) ", $kept kept on the phone (couldn't verify)" else ""
            }
            info.state == WorkInfo.State.FAILED && error != null -> "Last backup failed: $error"
            failed > 0 -> "Last backup: $uploaded uploaded, $failed failed" + (error?.let { " ($it)" } ?: "")
            uploaded > 0 -> "Last backup: $uploaded uploaded"
            else -> null
        }
    }
}

@Composable
private fun ProgressLine(label: String, detail: String, progress: Float) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

/** Banner showing a running backup, shared by the Device and Settings screens. */
@Composable
fun BackupStatusBanner(vm: BackupStatusViewModel = viewModel()) {
    val s by vm.status.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = s.running || s.waiting || s.lastResult != null) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            s.running && s.total > 0 -> when (s.phase) {
                                "Verify" -> "Verifying on NAS "
                                "Check" -> "Checking backups "
                                else -> "Backing up "
                            } + "${minOf(s.done + 1, s.total)} of ${s.total}"
                            s.running -> "Checking for new photos…"
                            s.waiting -> "Backup queued, waiting for network"
                            else -> s.lastResult.orEmpty()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.stoppable || s.waiting) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = vm::cancel) { Text("Stop") }
                    }
                }
                if (s.running && s.total > 0) {
                    // Current file: bytes sent of this file.
                    val fileFraction = if (s.fileSize > 0) (s.fileBytes.toFloat() / s.fileSize).coerceIn(0f, 1f) else 0f
                    ProgressLine(
                        label = s.current ?: "Preparing…",
                        // Bytes of this file so far, plus the transfer rate.
                        detail = listOfNotNull(
                            if (s.fileSize > 0 && s.fileBytes > 0) "${formatSize(s.fileBytes)} / ${formatSize(s.fileSize)}" else null,
                            if (s.bytesPerSecond > 0) formatSize(s.bytesPerSecond) + "/s" else null,
                        ).joinToString(" · "),
                        progress = fileFraction,
                    )
                    // Whole job: finished files plus the share of the current one.
                    val jobFraction = ((s.done + fileFraction) / s.total).coerceIn(0f, 1f)
                    ProgressLine(
                        label = "Job",
                        detail = "${minOf(s.done, s.total)} of ${s.total} files" +
                            (if (s.failed > 0) " · ${s.failed} failed" else "") +
                            " · ${(jobFraction * 100).toInt()}%",
                        progress = jobFraction,
                    )
                } else if (s.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}
