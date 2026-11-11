package dev.unifiphotosync.app.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import dev.unifiphotosync.app.data.ViewMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.unifiphotosync.app.PhotoSyncApp
import dev.unifiphotosync.app.data.NasPath
import androidx.work.WorkInfo
import dev.unifiphotosync.app.backup.DownloadWorker
import dev.unifiphotosync.app.smb.NasClient
import java.util.UUID
import dev.unifiphotosync.app.smb.NasEntry
import dev.unifiphotosync.app.smb.NasFile
import dev.unifiphotosync.app.smb.NasNotConfiguredException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NasUiState(
    val configured: Boolean = true,
    val path: String = "",
    val entries: List<NasEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** What's being saved to the phone (a file name or "3 files"), if anything. */
    val downloading: String? = null,
    /** Progress of the running download, when its size is known. */
    val downloadProgress: Float? = null,
)

class NasViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as PhotoSyncApp).graph
    private val _state = MutableStateFlow(NasUiState())
    val state = _state.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var loadJob: Job? = null
    private var started = false

    val viewMode = graph.settings.viewMode("nas")
        .stateIn(viewModelScope, SharingStarted.Eagerly, ViewMode.Thumbnails)

    fun setViewMode(mode: ViewMode) = viewModelScope.launch { graph.settings.setViewMode("nas", mode) }

    /** Opens the backup folder the first time the tab is shown. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val config = graph.settings.config.first()
            if (config == null) {
                started = false
                _state.update { it.copy(configured = false) }
            } else {
                open(config.destination)
            }
        }
    }

    fun open(path: String) {
        loadJob?.cancel()
        _state.update { it.copy(path = NasPath.clean(path), loading = true, error = null, configured = true) }
        loadJob = viewModelScope.launch {
            try {
                val entries = graph.nas.list(path)
                _state.update { it.copy(entries = entries, loading = false) }
            } catch (e: NasNotConfiguredException) {
                started = false
                _state.update { it.copy(configured = false, loading = false) }
            } catch (e: Exception) {
                // The device folder won't exist before the first backup; fall back to its parent.
                val parent = NasPath.parent(path)
                if (path.isNotEmpty() && _state.value.entries.isEmpty() && parent != path) {
                    open(parent)
                } else {
                    _state.update { it.copy(loading = false, error = NasClient.describe(e)) }
                }
            }
        }
    }

    fun up(): Boolean {
        val p = _state.value.path
        if (p.isEmpty()) return false
        open(NasPath.parent(p))
        return true
    }

    fun refresh() = open(_state.value.path)

    /**
     * Saves a NAS photo or video to the phone's gallery as a background job, so it
     * finishes even if the app is closed. It goes back to its original folder when possible.
     */
    fun download(entry: NasEntry) {
        graph.scheduler.download(entry)
        messages.tryEmit("Saving ${entry.name} to the phone…")
    }

    init {
        // Show running downloads, and say when each one finishes.
        viewModelScope.launch {
            var reported: Set<UUID>? = null
            graph.scheduler.downloads.collect { infos ->
                val active = infos.filter { !it.state.isFinished }
                val running = active.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val size = running?.progress?.getLong(DownloadWorker.KEY_SIZE, 0) ?: 0
                val bytes = running?.progress?.getLong(DownloadWorker.KEY_BYTES, 0) ?: 0
                _state.update {
                    it.copy(
                        downloading = when (active.size) {
                            0 -> null
                            1 -> fileName(active[0])
                            else -> "${active.size} files"
                        },
                        downloadProgress = if (size > 0) (bytes.toFloat() / size).coerceIn(0f, 1f) else null,
                    )
                }
                val finished = infos.filter { it.state.isFinished }
                // The first emission includes old results; only report ones that finish from now on.
                reported?.let { seen ->
                    finished.filter { it.id !in seen }.forEach { info ->
                        val name = fileName(info)
                        messages.tryEmit(
                            when (info.state) {
                                WorkInfo.State.SUCCEEDED -> "Saved $name to the gallery"
                                WorkInfo.State.CANCELLED -> "Cancelled saving $name"
                                else -> "Couldn't save $name: " +
                                    (info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "unknown error")
                            }
                        )
                    }
                }
                reported = finished.mapTo(HashSet()) { it.id }
            }
        }
    }

    private fun fileName(info: WorkInfo) =
        info.tags.firstOrNull { it.startsWith(DownloadWorker.TAG_FILE_PREFIX) }
            ?.removePrefix(DownloadWorker.TAG_FILE_PREFIX) ?: "file"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasScreen(onOpenSettings: () -> Unit, vm: NasViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var preview by remember { mutableStateOf<NasEntry?>(null) }
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = state.path.isNotEmpty()) { vm.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (state.path.isNotEmpty()) {
                        IconButton(onClick = { vm.up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                    }
                },
                title = {
                    Column {
                        Text(NasPath.name(state.path).ifEmpty { "NAS" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "\\" + state.path, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    ViewModeToggle(viewMode, vm::setViewMode)
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            val progress = state.downloadProgress
            when {
                state.downloading != null && progress != null ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                state.loading || state.downloading != null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.downloading?.let {
                Text(
                    "Saving $it to the phone" + (progress?.let { p -> " · ${(p * 100).toInt()}%" } ?: "…"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp, 4.dp),
                )
            }
            when {
                !state.configured -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Connect to your UniFi NAS to browse your backups.", textAlign = TextAlign.Center)
                    Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text("Set up NAS") }
                }
                state.error != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't reach the NAS:\n${state.error}", textAlign = TextAlign.Center)
                    Button(onClick = vm::refresh, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
                }
                !state.loading && state.entries.isEmpty() -> CenterText("This folder is empty")
                else -> {
                    val onOpen: (NasEntry) -> Unit = { e ->
                        when {
                            e.isDirectory -> vm.open(e.path)
                            e.isImage || e.isVideo -> preview = e
                        }
                    }
                    when (viewMode) {
                        ViewMode.Thumbnails -> NasGrid(state.entries, onOpen)
                        ViewMode.Details -> NasList(state.entries, onOpen)
                    }
                }
            }
        }
    }

    preview?.let { e ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (e.isImage) {
                        AsyncImage(
                            model = NasFile(e.path, e.size, e.modifiedAt),
                            contentDescription = e.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        )
                    }
                    Text(formatSize(e.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.download(e); preview = null }) { Text("Save to phone") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun NasGrid(entries: List<NasEntry>, onOpen: (NasEntry) -> Unit) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.path }) { e ->
            Column(Modifier.clickable { onOpen(e) }) {
                Box(
                    Modifier.aspectRatio(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        e.isDirectory -> Icon(Icons.Filled.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        // Thumbnails read the whole file over SMB; skip very large ones.
                        e.isImage && e.size < MAX_THUMB_BYTES -> {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(NasFile(e.path, e.size, e.modifiedAt))
                                        .size(320).build(),
                                    contentDescription = e.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        e.isVideo -> Icon(Icons.Filled.Movie, null, Modifier.size(40.dp))
                        else -> Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(40.dp))
                    }
                }
                Text(
                    e.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun NasList(entries: List<NasEntry>, onOpen: (NasEntry) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.path }) { e ->
            ListItem(
                modifier = Modifier.clickable { onOpen(e) },
                leadingContent = {
                    Box(
                        Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            e.isDirectory -> Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            e.isImage && e.size < MAX_THUMB_BYTES -> AsyncImage(
                                model = ImageRequest.Builder(context).data(NasFile(e.path, e.size, e.modifiedAt))
                                    .size(160).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            e.isVideo -> Icon(Icons.Filled.Movie, null)
                            else -> Icon(Icons.Filled.InsertDriveFile, null)
                        }
                    }
                },
                headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        if (e.isDirectory) "Folder · ${formatDate(e.modifiedAt)}"
                        else "${formatDate(e.modifiedAt)} · ${formatSize(e.size)}",
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

private const val MAX_THUMB_BYTES = 40L * 1024 * 1024

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
