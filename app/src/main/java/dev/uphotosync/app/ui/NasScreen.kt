package dev.uphotosync.app.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import dev.uphotosync.app.data.SortOrder
import dev.uphotosync.app.data.StorageSpace
import dev.uphotosync.app.data.ViewMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.ui.graphics.Color
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
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.data.NasPath
import androidx.work.WorkInfo
import dev.uphotosync.app.backup.DownloadWorker
import dev.uphotosync.app.smb.NasClient
import java.util.UUID
import dev.uphotosync.app.smb.NasEntry
import dev.uphotosync.app.smb.NasFile
import dev.uphotosync.app.smb.NasThumb
import dev.uphotosync.app.smb.NasNotConfiguredException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /** Transfer rate of the running download, bytes per second. */
    val downloadRate: Long = 0,
    /** Paths selected with a long-press, for deleting. */
    val selected: Set<String> = emptySet(),
    val deleting: Boolean = false,
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

    val sort = graph.settings.sortOrder("nas", SortOrder.NameAZ)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortOrder.NameAZ)

    fun setSort(order: SortOrder) = viewModelScope.launch { graph.settings.setSortOrder("nas", order) }

    init {
        // Re-sort what's on screen when the order changes.
        viewModelScope.launch { sort.collect { order -> _state.update { it.copy(entries = sortEntries(it.entries, order)) } } }
    }

    /** Folders first, then files in [order]; folders have no size, so they're by name for size orders. */
    private fun sortEntries(entries: List<NasEntry>, order: SortOrder): List<NasEntry> {
        val byName = compareBy<NasEntry> { it.name.lowercase() }
        val within: Comparator<NasEntry> = when (order) {
            SortOrder.DateNewest -> compareByDescending<NasEntry> { it.modifiedAt }.then(byName)
            SortOrder.DateOldest -> compareBy<NasEntry> { it.modifiedAt }.then(byName)
            SortOrder.NameAZ -> byName
            SortOrder.NameZA -> byName.reversed()
            SortOrder.SizeLargest -> compareByDescending<NasEntry> { if (it.isDirectory) 0L else it.size }.then(byName)
            SortOrder.SizeSmallest -> compareBy<NasEntry> { if (it.isDirectory) 0L else it.size }.then(byName)
        }
        return entries.sortedWith(compareBy<NasEntry> { !it.isDirectory }.then(within))
    }

    /** NAS deletes go to the .recycle folder for 30 days (Settings). */
    val recycleOn = graph.settings.prefs.map { it.nasRecycle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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
        // Stop filling in the previous folder's thumbnails (one already downloading still finishes).
        prefetchJob?.cancel()
        _state.update {
            it.copy(path = NasPath.clean(path), loading = true, error = null, configured = true, selected = emptySet())
        }
        loadJob = viewModelScope.launch {
            try {
                val entries = sortEntries(graph.nas.list(path), sort.value)
                _state.update { it.copy(entries = entries, loading = false) }
                startPrefetch(entries)
                refreshSpace()
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

    private var prefetchJob: Job? = null

    private val _space = MutableStateFlow<StorageSpace?>(null)

    /** The share's size and free space, refreshed when a folder loads (and after deletes). */
    val space = _space.asStateFlow()

    private fun refreshSpace() = viewModelScope.launch {
        // Not every server reports it; leave the line hidden if it doesn't.
        runCatching { graph.nas.space() }.onSuccess { _space.value = it }
    }

    /**
     * Once what's on screen has its thumbnails, slowly fill in the rest of this folder so they're
     * ready when scrolled to. Replaced when another folder opens; stops with the screen.
     */
    private fun startPrefetch(entries: List<NasEntry>) {
        prefetchJob?.cancel()
        val thumbs = entries
            .filter { it.isVideo || (it.isImage && it.size < MAX_THUMB_BYTES) }
            .map { NasThumb(it.path, it.size, it.modifiedAt) }
        if (thumbs.isNotEmpty()) prefetchJob = graph.thumbnails.prefetch(thumbs, viewModelScope)
    }

    fun up(): Boolean {
        val p = _state.value.path
        if (p.isEmpty()) return false
        open(NasPath.parent(p))
        return true
    }

    fun refresh() = open(_state.value.path)

    fun toggle(path: String) = _state.update {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun selectAll() = _state.update { s -> s.copy(selected = s.entries.mapTo(HashSet()) { it.path }) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /**
     * Deletes files and folders (with their contents) from the NAS. Their photos are no
     * longer counted as backed up, so any still on the phone are uploaded again next backup.
     */
    fun delete(entries: List<NasEntry>) {
        if (entries.isEmpty() || _state.value.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val recycle = graph.settings.prefs.first().nasRecycle
            val result = runCatching { graph.nas.delete(entries, recycle) }
            // Forget every attempted path: if one survived, the next backup just finds it and re-records it.
            withContext(Dispatchers.IO) {
                val forgotten = entries.flatMap { graph.index.forgetRemote(it.path) }
                // Their NAS copy is gone, so a pending Move must not delete them from the phone.
                graph.moves.remove(forgotten)
            }
            _state.update { it.copy(deleting = false, selected = emptySet()) }
            messages.tryEmit(
                result.fold(
                    { n ->
                        val items = if (n == 1) "item" else "items"
                        if (recycle && entries.none { NasClient.isInRecycle(it.path) }) "Moved $n $items to the NAS recycle folder"
                        else "Deleted $n $items from the NAS"
                    },
                    { e -> "Delete failed: ${NasClient.describe(e)}" },
                )
            )
            refresh()
        }
    }

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
                val rate = running?.progress?.getLong(DownloadWorker.KEY_RATE, 0) ?: 0
                _state.update {
                    it.copy(
                        downloading = when (active.size) {
                            0 -> null
                            1 -> fileName(active[0])
                            else -> "${active.size} files"
                        },
                        downloadProgress = if (size > 0) (bytes.toFloat() / size).coerceIn(0f, 1f) else null,
                        downloadRate = rate,
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
    var confirmDelete by remember { mutableStateOf<List<NasEntry>?>(null) }
    val selecting = state.selected.isNotEmpty()
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = state.path.isNotEmpty() && !selecting) { vm.up() }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Clear selection") }
                    },
                    title = { Text("${state.selected.size} selected") },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton(
                            onClick = { confirmDelete = state.entries.filter { it.path in state.selected } },
                            enabled = !state.deleting,
                        ) { Icon(Icons.Filled.Delete, "Delete from NAS") }
                    },
                )
            } else TopAppBar(
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
                    val sort by vm.sort.collectAsStateWithLifecycle()
                    SortMenu(sort) { vm.setSort(it) }
                    ViewModeToggle(viewMode, vm::setViewMode)
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            val space by vm.space.collectAsStateWithLifecycle()
            if (state.configured) StorageLine("NAS", space, isNas = true)
            val progress = state.downloadProgress
            when {
                state.deleting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.downloading != null && progress != null ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                state.loading || state.downloading != null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.downloading?.let {
                Text(
                    "Saving $it to the phone" + (progress?.let { p -> " · ${(p * 100).toInt()}%" } ?: "…") +
                        (if (state.downloadRate > 0) " · " + formatSize(state.downloadRate) + "/s" else ""),
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
                            selecting -> vm.toggle(e.path)
                            e.isDirectory -> vm.open(e.path)
                            e.isImage || e.isVideo -> preview = e
                        }
                    }
                    val onSelect: (NasEntry) -> Unit = { vm.toggle(it.path) }
                    when (viewMode) {
                        ViewMode.Thumbnails -> NasGrid(state.entries, state.selected, onOpen, onSelect)
                        ViewMode.Details -> NasList(state.entries, state.selected, onOpen, onSelect)
                    }
                }
            }
        }
    }

    preview?.let { e ->
        // Swipe through the photos and videos in this folder.
        val media = remember(state.entries) { state.entries.filter { it.isImage || it.isVideo } }
        val viewerItems = remember(media) {
            media.map {
                ViewerItem(
                    key = it.path,
                    // Photos load in full; videos show their thumbnail frame and stream when played.
                    model = if (it.isVideo) NasThumb(it.path, it.size, it.modifiedAt) else NasFile(it.path, it.size, it.modifiedAt),
                    title = it.name,
                    subtitle = "${formatDate(it.modifiedAt)} · ${formatSize(it.size)}",
                    isVideo = it.isVideo,
                    video = if (it.isVideo) NasFile(it.path, it.size, it.modifiedAt) else null,
                )
            }
        }
        MediaViewer(
            items = viewerItems,
            startIndex = media.indexOfFirst { it.path == e.path }.coerceAtLeast(0),
            onDismiss = { preview = null },
        ) { current ->
            val entry = media.firstOrNull { it.path == current.key }
            if (entry != null) {
                TextButton(onClick = { vm.download(entry) }) { Text("Save to phone", color = Color.White) }
                TextButton(onClick = { confirmDelete = listOf(entry); preview = null }) {
                    Text("Delete", color = Color.White)
                }
            }
        }
    }

    confirmDelete?.let { targets ->
        val recycleOn by vm.recycleOn.collectAsStateWithLifecycle()
        // Deleting from inside .recycle itself is always for good.
        val toRecycle = recycleOn && targets.none { NasClient.isInRecycle(it.path) }
        val folders = targets.count { it.isDirectory }
        val files = targets.size - folders
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = {
                Text(
                    if (targets.size == 1) "Delete \"${targets[0].name}\" from the NAS?"
                    else "Delete ${targets.size} items from the NAS?"
                )
            },
            text = {
                Text(
                    buildString {
                        if (folders > 0) append("Folders are deleted with everything in them. ")
                        if (toRecycle) {
                            append("They'll be moved to the ${NasClient.RECYCLE_DIR} folder at the top of the share ")
                            append("and removed for good after ${NasClient.RECYCLE_DAYS} days. ")
                        } else {
                            append("This can't be undone. ")
                        }
                        append("Photos and videos still on this phone will be uploaded again on the next backup.")
                        if (targets.size > 1) append("\n\n$files ${if (files == 1) "file" else "files"}, $folders ${if (folders == 1) "folder" else "folders"}")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(targets); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NasGrid(
    entries: List<NasEntry>,
    selected: Set<String>,
    onOpen: (NasEntry) -> Unit,
    onSelect: (NasEntry) -> Unit,
) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.path }) { e ->
            val isSelected = e.path in selected
            Column(Modifier.combinedClickable(onClick = { onOpen(e) }, onLongClick = { onSelect(e) })) {
                Box(
                    Modifier.aspectRatio(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(if (isSelected) 8.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        e.isDirectory -> Icon(Icons.Filled.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        // Thumbnails read the whole file over SMB; skip very large ones.
                        e.isImage && e.size < MAX_THUMB_BYTES -> {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(NasThumb(e.path, e.size, e.modifiedAt))
                                        .size(320).build(),
                                    contentDescription = e.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        // A frame from the video, read from just a few MB of it.
                        e.isVideo -> Box(contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(NasThumb(e.path, e.size, e.modifiedAt))
                                    .size(320).build(),
                                contentDescription = e.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Icon(
                                Icons.Filled.PlayCircle, "Video", tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp),
                            )
                        }
                        else -> Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(40.dp))
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopStart)
                                .background(MaterialTheme.colorScheme.surface, CircleShape),
                        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NasList(
    entries: List<NasEntry>,
    selected: Set<String>,
    onOpen: (NasEntry) -> Unit,
    onSelect: (NasEntry) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.path }) { e ->
            val isSelected = e.path in selected
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { onOpen(e) }, onLongClick = { onSelect(e) }),
                colors = if (isSelected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else ListItemDefaults.colors(),
                trailingContent = if (selected.isNotEmpty()) {
                    { Checkbox(checked = isSelected, onCheckedChange = { onSelect(e) }) }
                } else null,
                leadingContent = {
                    Box(
                        Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            e.isDirectory -> Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            e.isImage && e.size < MAX_THUMB_BYTES -> AsyncImage(
                                model = ImageRequest.Builder(context).data(NasThumb(e.path, e.size, e.modifiedAt))
                                    .size(160).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            e.isVideo -> AsyncImage(
                                model = ImageRequest.Builder(context).data(NasThumb(e.path, e.size, e.modifiedAt))
                                    .size(160).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
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
