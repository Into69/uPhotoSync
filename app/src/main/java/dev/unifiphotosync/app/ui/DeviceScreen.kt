package dev.unifiphotosync.app.ui

import android.app.Application
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.text.style.TextOverflow
import dev.unifiphotosync.app.data.ViewMode
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.unifiphotosync.app.PhotoSyncApp
import dev.unifiphotosync.app.backup.BackupScope
import dev.unifiphotosync.app.data.Album
import dev.unifiphotosync.app.data.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceUiState(
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val albums: List<Album> = emptyList(),
    val album: Album? = null,
    val selected: Set<Long> = emptySet(),
    val backedUp: Map<Long, Long> = emptyMap(),
) {
    fun isBackedUp(item: MediaItem) = backedUp[item.id] == item.size
    val pending get() = items.count { !isBackedUp(it) }
}

class DeviceViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as PhotoSyncApp).graph
    private val all = MutableStateFlow<List<MediaItem>?>(null)
    private val albumId = MutableStateFlow<String?>(null)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    val state = combine(all, albumId, selected, graph.index.sizes) { all, albumId, selected, sizes ->
        val list = all.orEmpty()
        val albums = graph.media.albumsOf(list)
        val album = albums.firstOrNull { it.id == albumId }
        DeviceUiState(
            loading = all == null,
            items = if (album == null) list else list.filter { it.bucketId == album.id },
            albums = albums,
            album = album,
            selected = selected,
            backedUp = sizes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    val viewMode = graph.settings.viewMode("device")
        .stateIn(viewModelScope, SharingStarted.Eagerly, ViewMode.Thumbnails)

    fun setViewMode(mode: ViewMode) = viewModelScope.launch { graph.settings.setViewMode("device", mode) }

    private var reloadJob: Job? = null

    /** Reloads from MediaStore; debounced because the observer fires in bursts. */
    fun reload(debounce: Boolean = false) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            if (debounce) delay(500)
            val items = graph.media.loadAll()
            all.value = items
            val ids = items.mapTo(HashSet()) { it.id }
            selected.update { it.intersect(ids) }
        }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload(debounce = true)
    }

    init {
        app.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer
        )
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    fun selectAlbum(id: String?) {
        albumId.value = id
        selected.value = emptySet()
    }

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun selectAllPending() = selected.update { state.value.items.filterNot(state.value::isBackedUp).mapTo(HashSet()) { it.id } }
    fun clearSelection() { selected.value = emptySet() }

    /** Returns false if the NAS isn't set up yet. */
    suspend fun backUpSelected(): Boolean {
        if (graph.settings.config.first() == null) return false
        graph.scheduler.backupNow(BackupScope.Items(selected.value))
        clearSelection()
        return true
    }

    suspend fun backUpVisible(): Boolean {
        if (graph.settings.config.first() == null) return false
        val album = state.value.album
        graph.scheduler.backupNow(if (album == null) BackupScope.All else BackupScope.Album(album.id))
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(onOpenSettings: () -> Unit, vm: DeviceViewModel = viewModel()) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(AppPermissions.mediaAccess(context)) }
    val granted = access != MediaAccess.None
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = AppPermissions.mediaAccess(context)
        if (access != MediaAccess.None) vm.reload()
    }
    LaunchedEffect(Unit) {
        if (granted) vm.reload() else launcher.launch(AppPermissions.firstLaunchRequest())
    }
    // Access can change in system settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = AppPermissions.mediaAccess(context)
        if (now != access) {
            access = now
            if (now != MediaAccess.None) vm.reload()
        }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selecting = state.selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    fun startBackup(block: suspend () -> Boolean) = scope.launch {
        if (!block()) {
            val r = snackbar.showSnackbar("Set up your NAS first", actionLabel = "Settings")
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Clear selection") }
                    },
                    title = { Text("${state.selected.size} selected") },
                    actions = {
                        IconButton(onClick = vm::selectAllPending) { Icon(Icons.Filled.SelectAll, "Select all not backed up") }
                        IconButton(onClick = { startBackup(vm::backUpSelected) }) {
                            Icon(Icons.Filled.CloudUpload, "Back up selected")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            AlbumPicker(state, vm::selectAlbum)
                            if (!state.loading) {
                                Text(
                                    "${state.items.size - state.pending} of ${state.items.size} backed up",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    actions = { ViewModeToggle(viewMode, vm::setViewMode) },
                )
            }
        },
        floatingActionButton = {
            if (granted && !selecting && state.pending > 0) {
                ExtendedFloatingActionButton(
                    onClick = { startBackup(vm::backUpVisible) },
                    icon = { Icon(Icons.Filled.CloudUpload, null) },
                    text = { Text("Back up ${state.pending}") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            BackupStatusBanner()
            if (access == MediaAccess.Partial) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                        Text(
                            "Only some photos are allowed, so only those are shown and backed up.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        )
                        TextButton(onClick = { launcher.launch(AppPermissions.mediaRequest()) }) { Text("Allow all") }
                    }
                }
            }
            when {
                !granted -> PermissionPrompt { launcher.launch(AppPermissions.firstLaunchRequest()) }
                !state.loading && state.items.isEmpty() -> CenterText("No photos or videos found")
                else -> {
                    val onClick: (MediaItem) -> Unit = { item ->
                        if (selecting) vm.toggle(item.id)
                        else runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mimeType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        }
                    }
                    val onLongClick: (MediaItem) -> Unit = { vm.toggle(it.id) }
                    when (viewMode) {
                        ViewMode.Thumbnails -> MediaGrid(state, onClick, onLongClick)
                        ViewMode.Details -> MediaList(state, selecting, onClick, onLongClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumPicker(state: DeviceUiState, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) {
            Text(state.album?.name ?: "All photos", style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All photos") }, onClick = { onSelect(null); open = false })
            state.albums.forEach { a ->
                DropdownMenuItem(text = { Text("${a.name} (${a.count})") }, onClick = { onSelect(a.id); open = false })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(state: DeviceUiState, onClick: (MediaItem) -> Unit, onLongClick: (MediaItem) -> Unit) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 88.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.items, key = { it.id }) { item ->
            val selected = item.id in state.selected
            Box(
                Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) })
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.uri).size(320).build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (selected) 10.dp else 0.dp),
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Filled.PlayCircle, "Video", tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp),
                    )
                }
                if (state.isBackedUp(item)) {
                    Icon(
                        Icons.Filled.CloudDone, "Backed up", tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(3.dp).size(14.dp),
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaList(
    state: DeviceUiState,
    selecting: Boolean,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
        items(state.items, key = { it.id }) { item ->
            val selected = item.id in state.selected
            val backedUp = state.isBackedUp(item)
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
                colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else ListItemDefaults.colors(),
                leadingContent = {
                    Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(item.uri).size(160).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.isVideo) {
                            Icon(
                                Icons.Filled.PlayCircle, "Video", tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(16.dp),
                            )
                        }
                    }
                },
                headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        "${formatDate(item.takenAt)} · ${formatSize(item.size)}\n${item.bucketName}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    when {
                        selecting -> Checkbox(checked = selected, onCheckedChange = { onClick(item) })
                        backedUp -> Icon(Icons.Filled.CloudDone, "Backed up", tint = MaterialTheme.colorScheme.primary)
                        else -> Icon(
                            Icons.Outlined.CloudOff, "Not backed up",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Allow access to your photos and videos so they can be browsed and backed up to your NAS.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text("Allow access") }
    }
}

@Composable
fun CenterText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
