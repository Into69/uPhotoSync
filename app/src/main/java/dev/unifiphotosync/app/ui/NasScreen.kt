package dev.unifiphotosync.app.ui

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
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
import dev.unifiphotosync.app.smb.NasClient
import dev.unifiphotosync.app.smb.NasEntry
import dev.unifiphotosync.app.smb.NasFile
import dev.unifiphotosync.app.smb.NasNotConfiguredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NasUiState(
    val configured: Boolean = true,
    val path: String = "",
    val entries: List<NasEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val downloading: String? = null,
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

    /** Copies a NAS photo or video into the phone's gallery (Pictures/ or Movies/uPhotoSync). */
    fun download(entry: NasEntry) {
        if (_state.value.downloading != null) return
        _state.update { it.copy(downloading = entry.name) }
        viewModelScope.launch {
            val result = runCatching { saveToGallery(entry) }
            _state.update { it.copy(downloading = null) }
            messages.tryEmit(
                result.fold({ "Saved ${entry.name} to the gallery" }, { "Download failed: ${it.message}" })
            )
        }
    }

    private suspend fun saveToGallery(entry: NasEntry) = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension)
            ?: if (entry.isVideo) "video/mp4" else "image/jpeg"
        val collection = if (entry.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dir = if (entry.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/uPhotoSync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Couldn't create the file")
        try {
            resolver.openOutputStream(uri)?.use { graph.nas.download(entry.path, it) } ?: error("Couldn't write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // It came from the NAS, so it's already backed up — don't upload it again.
            graph.index.mark(ContentUris.parseId(uri), entry.path, entry.size)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
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
            if (state.loading || state.downloading != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.downloading?.let {
                Text("Downloading $it…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 4.dp))
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
