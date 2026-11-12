package dev.uphotosync.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.uphotosync.app.backup.RateMeter
import dev.uphotosync.app.data.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(
        val release: Updater.Release,
        val done: Long,
        val total: Long,
        val bytesPerSecond: Long = 0,
    ) : UpdateState {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        val secondsLeft get() = if (total > 0 && bytesPerSecond > 0) (total - done).coerceAtLeast(0) / bytesPerSecond else null
    }
    /** [autoInstall]: open the installer once, straight after the download finishes. */
    data class Ready(val release: Updater.Release, val apk: File, val autoInstall: Boolean = true) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    val updater = Updater(app)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun check() {
        job?.cancel()
        _state.value = UpdateState.Checking
        job = viewModelScope.launch {
            _state.value = try {
                val latest = updater.latest()
                if (latest != null && updater.isNewer(latest)) UpdateState.Available(latest)
                else UpdateState.UpToDate(updater.currentVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "Couldn't reach GitHub")
            }
        }
    }

    fun download(release: Updater.Release) {
        job?.cancel()
        _state.value = UpdateState.Downloading(release, 0, release.apkSize)
        job = viewModelScope.launch {
            _state.value = try {
                val meter = RateMeter()
                var lastAt = 0L
                val apk = updater.download(release) { done, total ->
                    val rate = meter.update(done)
                    // A few updates a second, like the backup banner; the bar animates in between.
                    val now = System.nanoTime()
                    if (now - lastAt >= 200_000_000L || done == total) {
                        lastAt = now
                        _state.value = UpdateState.Downloading(release, done, total, rate)
                    }
                }
                UpdateState.Ready(release, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun autoInstallStarted() {
        (_state.value as? UpdateState.Ready)?.let { _state.value = it.copy(autoInstall = false) }
    }

    fun cancel() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }
}

/** "App version" row with Check for updates, and the download / install flow under it. */
@Composable
fun AppUpdatesPanel(vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notesFor by remember { mutableStateOf<Updater.Release?>(null) }
    // Sent to Android's "Install unknown apps" page; install as soon as they come back allowed.
    var installAfterAllow by remember { mutableStateOf<File?>(null) }
    fun install(apk: File) {
        if (vm.updater.canInstall()) {
            context.startActivity(vm.updater.installIntent(apk))
        } else {
            installAfterAllow = apk
            context.startActivity(vm.updater.allowInstallsIntent())
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installAfterAllow?.let { apk ->
            installAfterAllow = null
            if (vm.updater.canInstall() && apk.exists()) context.startActivity(vm.updater.installIntent(apk))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("uPhotoSync ${vm.updater.currentVersion}")
                Text(
                    when (val s = state) {
                        UpdateState.Idle -> "Updates come from the app's GitHub releases"
                        UpdateState.Checking -> "Checking…"
                        is UpdateState.UpToDate -> "You have the latest version"
                        is UpdateState.Available -> "Version ${s.release.version} is available (${formatSize(s.release.apkSize)})"
                        is UpdateState.Downloading -> "Downloading version ${s.release.version}"
                        is UpdateState.Ready -> "Version ${s.release.version} is downloaded"
                        is UpdateState.Failed -> s.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state !is UpdateState.Available && state !is UpdateState.Downloading && state !is UpdateState.Ready) {
                OutlinedButton(onClick = vm::check, enabled = state != UpdateState.Checking) {
                    Text("Check for updates")
                }
            }
        }
        when (val s = state) {
            is UpdateState.Available -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.download(s.release) }) { Text("Download and install") }
                if (s.release.notes.isNotEmpty()) TextButton(onClick = { notesFor = s.release }) { Text("What's new") }
            }
            is UpdateState.Downloading -> UpdateDownloadCard(s, onStop = vm::cancel)
            is UpdateState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { install(s.apk) }) { Text("Install") }
                if (s.release.notes.isNotEmpty()) TextButton(onClick = { notesFor = s.release }) { Text("What's new") }
            }
            else -> Unit
        }
        if (state is UpdateState.Ready && !vm.updater.canInstall()) {
            Text(
                "Android will ask you to allow uPhotoSync to install apps. Turn it on, then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // "Download and install": open the installer as soon as the download is done.
    val ready = state as? UpdateState.Ready
    LaunchedEffect(ready) {
        if (ready != null && ready.autoInstall) {
            vm.autoInstallStarted()
            install(ready.apk)
        }
    }

    notesFor?.let { r ->
        AlertDialog(
            onDismissRequest = { notesFor = null },
            title = { Text("What's new in ${r.version}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(r.notes, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { notesFor = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl)))
                    notesFor = null
                }) { Text("Open on GitHub") }
            },
        )
    }
}

/** Download progress in the same card style as the backup banner. */
@Composable
private fun UpdateDownloadCard(s: UpdateState.Downloading, onStop: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp).animateContentSize()) {
            Header(
                icon = Icons.Filled.SystemUpdate,
                title = "Downloading update ${s.release.version}",
                subtitle = listOfNotNull(
                    s.bytesPerSecond.takeIf { it > 0 }?.let { formatSize(it) + "/s" },
                    s.secondsLeft?.let { timeLeft(it) },
                ).joinToString(" · ").ifEmpty { "Starting…" },
                busy = true,
                onStop = onStop,
            )
            val fraction = s.fraction
            if (fraction != null) {
                val shown by animateFloatAsState(fraction, tween(600, easing = FastOutSlowInEasing), label = "update")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    SmoothBar(shown, height = 10.dp, modifier = Modifier.weight(1f))
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(
                    "${formatSize(s.done)} of ${formatSize(s.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                SmoothBar(null, height = 6.dp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}
