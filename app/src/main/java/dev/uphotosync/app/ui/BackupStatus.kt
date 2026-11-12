package dev.uphotosync.app.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.backup.BackupScheduler
import dev.uphotosync.app.backup.BackupWorker
import dev.uphotosync.app.smb.NasEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A file being transferred right now. */
data class FileTransfer(val name: String, val sent: Long, val size: Long) {
    val fraction get() = if (size > 0) (sent.toFloat() / size).coerceIn(0f, 1f) else 0f
}

data class BackupStatus(
    val running: Boolean = false,
    /** Manual runs can be stopped; scheduled ones just run again later. */
    val stoppable: Boolean = false,
    val waiting: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    /** Upload, Verify or Check (see BackupEngine.Phase). */
    val phase: String = "Upload",
    /** Current transfer rate in bytes per second. */
    val bytesPerSecond: Long = 0,
    /** One entry per file in flight. */
    val files: List<FileTransfer> = emptyList(),
    /** Bytes of the whole job processed so far and in total (0 when not known). */
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** Outcome of the most recent finished run, if it's worth showing. */
    val lastResult: String? = null,
) {
    /**
     * How far the whole job is. By bytes when known, so one big video doesn't stall the bar
     * while small photos race ahead; otherwise by files, counting the share of those in flight.
     */
    val jobFraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            total > 0 -> ((done + files.sumOf { it.fraction.toDouble() }.toFloat()) / total).coerceIn(0f, 1f)
            else -> 0f
        }

    /** Seconds left at the current rate, or null if it can't be estimated yet. */
    val secondsLeft: Long?
        get() = if (bytesTotal > 0 && bytesPerSecond > 0) (bytesTotal - bytesDone).coerceAtLeast(0) / bytesPerSecond else null
}

class BackupStatusViewModel(app: Application) : AndroidViewModel(app) {
    private val scheduler = (app as PhotoSyncApp).graph.scheduler

    val status = scheduler.workInfos.map(::summarize)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupStatus())

    fun cancel() = scheduler.cancelAll()

    private fun summarize(infos: List<WorkInfo>): BackupStatus {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            val p = running.progress
            val names = p.getStringArray(BackupWorker.KEY_FILE_NAMES).orEmpty()
            val sents = p.getLongArray(BackupWorker.KEY_FILE_SENTS) ?: LongArray(0)
            val sizes = p.getLongArray(BackupWorker.KEY_FILE_SIZES) ?: LongArray(0)
            return BackupStatus(
                running = true,
                stoppable = BackupScheduler.TAG_MANUAL in running.tags,
                done = p.getInt(BackupWorker.KEY_DONE, 0),
                total = p.getInt(BackupWorker.KEY_TOTAL, 0),
                failed = p.getInt(BackupWorker.KEY_FAILED, 0),
                phase = p.getString(BackupWorker.KEY_PHASE) ?: "Upload",
                bytesPerSecond = p.getLong(BackupWorker.KEY_RATE, 0),
                files = names.indices.map { i ->
                    FileTransfer(names[i] ?: "", sents.getOrElse(i) { 0 }, sizes.getOrElse(i) { 0 })
                },
                bytesDone = p.getLong(BackupWorker.KEY_BYTES_DONE, 0),
                bytesTotal = p.getLong(BackupWorker.KEY_BYTES_TOTAL, 0),
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
            out.getInt(BackupWorker.KEY_NO_SPACE, 0) > 0 ->
                "Last backup: $uploaded uploaded, ${out.getInt(BackupWorker.KEY_NO_SPACE, 0)} skipped. The NAS is nearly full."
            info.state == WorkInfo.State.FAILED && error != null -> "Last backup failed: $error"
            failed > 0 -> "Last backup: $uploaded uploaded, $failed failed" + (error?.let { " ($it)" } ?: "")
            uploaded > 0 -> "Last backup: $uploaded uploaded"
            else -> null
        }
    }
}

/** Banner showing a running backup, shared by the Device and Settings screens. */
@Composable
fun BackupStatusBanner(vm: BackupStatusViewModel = viewModel()) {
    val s by vm.status.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = s.running || s.waiting || s.lastResult != null) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.padding(14.dp).animateContentSize()) {
                when {
                    s.running -> RunningContent(s, onStop = vm::cancel)
                    s.waiting -> Header(
                        icon = Icons.Filled.CloudQueue, title = "Backup queued", subtitle = "Waiting for a network",
                        busy = false, onStop = vm::cancel,
                    )
                    else -> Header(icon = Icons.Filled.Info, title = s.lastResult.orEmpty(), subtitle = null, busy = false, onStop = null)
                }
            }
        }
    }
}

@Composable
private fun RunningContent(s: BackupStatus, onStop: () -> Unit) {
    val (icon, verb) = when (s.phase) {
        "Verify" -> Icons.Filled.Verified to "Verifying on NAS"
        "Check" -> Icons.Filled.FactCheck to "Checking backups"
        else -> Icons.Filled.CloudUpload to "Backing up"
    }
    val title = if (s.total > 0) "$verb ${minOf(s.done + 1, s.total)} of ${s.total}" else "Checking for new photos…"
    val subtitle = listOfNotNull(
        s.bytesPerSecond.takeIf { it > 0 }?.let { formatSize(it) + "/s" },
        s.secondsLeft?.let { timeLeft(it) },
        s.failed.takeIf { it > 0 }?.let { "$it failed" },
    ).joinToString(" · ").ifEmpty { null }
    Header(icon = icon, title = title, subtitle = subtitle, busy = true, onStop = if (s.stoppable) onStop else null)

    if (s.total > 0) {
        // The whole job: a thick, rounded bar that glides between updates.
        val job by animateFloatAsState(s.jobFraction, tween(600, easing = FastOutSlowInEasing), label = "job")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            SmoothBar(job, height = 10.dp, modifier = Modifier.weight(1f))
            Text(
                "${(s.jobFraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            buildString {
                append("${minOf(s.done, s.total)} of ${s.total} files")
                if (s.bytesTotal > 0) append(" · ${formatSize(s.bytesDone)} of ${formatSize(s.bytesTotal)}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // One slim bar per file in flight.
        s.files.forEach { FileRow(it) }
    } else {
        SmoothBar(null, height = 6.dp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** Icon, title and subtitle row used by transfer banners (backups, app updates). */
@Composable
internal fun Header(icon: ImageVector, title: String, subtitle: String?, busy: Boolean, onStop: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Icon in a tinted circle; it gently pulses while work is running.
        val pulse = if (busy) {
            rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.55f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
            ).value
        } else 1f
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp).alpha(pulse),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onStop != null) {
            IconButton(onClick = onStop) { Icon(Icons.Filled.Close, "Stop") }
        }
    }
}

@Composable
private fun FileRow(f: FileTransfer) {
    val progress by animateFloatAsState(f.fraction, tween(400), label = "file")
    val isVideo = f.name.substringAfterLast('.', "").lowercase() in NasEntry.VIDEO_EXT
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Icon(
                if (isVideo) Icons.Filled.Movie else Icons.Filled.Image, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        "${(f.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                SmoothBar(progress, height = 4.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                if (f.size > 0) {
                    Text(
                        "${formatSize(f.sent)} / ${formatSize(f.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Rounded progress bar with no end marker; [progress] null means "working, amount unknown". */
@Composable
internal fun SmoothBar(progress: Float?, height: Dp, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier.height(height),
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
        )
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier.height(height),
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

internal fun timeLeft(seconds: Long): String = when {
    seconds < 60 -> "less than a minute left"
    seconds < 3600 -> "~${(seconds + 30) / 60} min left"
    else -> "~${seconds / 3600} h ${seconds % 3600 / 60} min left"
}
