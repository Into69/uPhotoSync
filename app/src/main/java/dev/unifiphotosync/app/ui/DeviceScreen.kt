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
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
import kotlinx.coroutines.flow.map
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

    /** How many selected items have no copy on the NAS yet, to warn before deleting. */
    fun selectedNotBackedUp(): Int {
        val s = state.value
        return s.items.count { it.id in s.selected && !s.isBackedUp(it) }
    }

    private var pendingDelete: List<MediaItem> = emptyList()
    private val deletedIds = ArrayList<Long>()
    private var deleteFromSelection = false
    private var deleteToTrash = false

    /** Phone deletes go to Android's trash (Android 11+, if turned on in Settings). */
    val deletesGoToTrash = graph.settings.prefs.map { it.phoneTrash && trashSupported() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, trashSupported())

    /** True while a delete (and its system confirmation) is in progress; only one at a time. */
    var deleting = false
        private set

    /**
     * "Move to NAS" items whose NAS copy has been verified, still on the phone and waiting
     * for the user to confirm deleting them.
     */
    val moveReady = combine(all, graph.moves.verified, graph.index.sizes) { all, verified, sizes ->
        all.orEmpty().filter { stillSafeToDelete(it, verified, sizes) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A moved file may only be deleted from the phone while its verified NAS copy is still
     * recorded at the same size (not deleted on the NAS, not found damaged) and the phone
     * file is unchanged since it was verified.
     */
    private fun stillSafeToDelete(item: MediaItem, verified: Map<Long, Long>, sizes: Map<Long, Long>) =
        verified[item.id].let { at -> at != null && at == item.modifiedAt } && sizes[item.id] == item.size

    init {
        // Drop moves that are no longer safe (or whose file is gone), so they aren't offered later.
        viewModelScope.launch {
            combine(all, graph.moves.verified, graph.index.sizes) { all, verified, sizes ->
                if (all == null) emptyList()
                else {
                    val byId = all.associateBy { it.id }
                    verified.keys.filter { id -> byId[id]?.let { !stillSafeToDelete(it, verified, sizes) } ?: true }
                }
            }.collect { stale -> graph.moves.remove(stale) }
        }
    }

    /** Starts a move: upload, verify byte for byte, then delete from the phone. Manual only. */
    suspend fun moveSelected(): Boolean {
        if (graph.settings.config.first() == null) return false
        graph.scheduler.backupNow(BackupScope.Items(selected.value), move = true)
        clearSelection()
        return true
    }

    /** "Keep on phone": the files stay on both the phone and the NAS. */
    fun keepMovedFiles() = graph.moves.clear()

    /**
     * Starts deleting from the phone: [items], or the selection (NAS copies stay). Returns
     * Android's confirmation prompt to show, or null if everything was deleted without one.
     */
    suspend fun startDelete(items: List<MediaItem>? = null): IntentSender? {
        // A second delete would overwrite this one's batch and misreport what was deleted.
        if (deleting) return null
        val ids = selected.value
        deleteFromSelection = items == null
        pendingDelete = items ?: all.value.orEmpty().filter { it.id in ids }
        deletedIds.clear()
        if (pendingDelete.isEmpty()) return null
        deleting = true
        val resolver = getApplication<Application>().contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // One system dialog for the whole batch; the system deletes (or trashes) them if the
            // user agrees. Trashed files can be restored from the gallery for 30 days.
            deleteToTrash = graph.settings.prefs.first().phoneTrash
            val uris = pendingDelete.map { it.uri }
            if (deleteToTrash) MediaStore.createTrashRequest(resolver, uris, true).intentSender
            else MediaStore.createDeleteRequest(resolver, uris).intentSender
        } else {
            deleteToTrash = false
            continueDelete()
        }
    }

    /**
     * Android 10: delete one by one. For each photo the app didn't create, Android asks
     * the user first; returns that prompt, and is called again once it's been allowed.
     */
    suspend fun continueDelete(): IntentSender? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        while (pendingDelete.isNotEmpty()) {
            val item = pendingDelete.first()
            try {
                resolver.delete(item.uri, null, null)
                deletedIds += item.id
            } catch (e: RecoverableSecurityException) {
                return@withContext e.userAction.actionIntent.intentSender
            } catch (e: Exception) {
                // Already gone, or the provider refused outright: skip it rather than crash.
            }
            pendingDelete = pendingDelete.drop(1)
        }
        null
    }

    /** Called when deleting is finished or cancelled; returns a message for the user. */
    fun finishDelete(confirmedBatch: Boolean): String {
        // Android 11+: the system did the deleting, so count the whole batch if it was confirmed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && confirmedBatch) {
            deletedIds.clear()
            deletedIds += pendingDelete.map { it.id }
        }
        val n = deletedIds.size
        // A finished move: these no longer need deleting.
        graph.moves.remove(deletedIds)
        pendingDelete = emptyList()
        deletedIds.clear()
        // Only clear the selection if that's what was being deleted (not a finished move).
        if (deleteFromSelection) clearSelection()
        deleting = false
        reload()
        val items = if (n == 1) "item" else "items"
        return when {
            n == 0 -> "Nothing deleted"
            deleteToTrash -> "Moved $n $items to the trash (restorable for 30 days)"
            else -> "Deleted $n $items from this phone"
        }
    }

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

    // Deleting photos the app didn't create needs the user's OK in a system dialog.
    var confirmUnbacked by remember { mutableStateOf(0) }
    // True while Android's delete confirmation is showing.
    var deleteOpen by remember { mutableStateOf(false) }
    // Position in the list of the photo open in the full-screen viewer.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    lateinit var deleteLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    fun showOrFinish(sender: IntentSender?, confirmedBatch: Boolean = false) {
        if (sender != null) {
            deleteOpen = true
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            val message = if (vm.deleting) vm.finishDelete(confirmedBatch) else null
            deleteOpen = false
            if (message != null) scope.launch { snackbar.showSnackbar(message) }
        }
    }
    deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> showOrFinish(null, confirmedBatch = ok)
            // Android 10: the user allowed this photo; carry on with the rest.
            ok -> scope.launch { showOrFinish(vm.continueDelete()) }
            else -> showOrFinish(null)
        }
    }
    fun startDelete(items: List<MediaItem>? = null) {
        // One delete at a time; a second would be mistaken for the first one finishing.
        if (vm.deleting) return
        scope.launch { showOrFinish(vm.startDelete(items)) }
    }

    // "Move to NAS": once copies are verified, ask Android to delete them from the phone.
    val moveReady by vm.moveReady.collectAsStateWithLifecycle()
    var confirmMove by remember { mutableStateOf(false) }
    var lastPrompted by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(moveReady, deleteOpen) {
        if (deleteOpen) return@LaunchedEffect
        // Prompt once per newly verified batch while the app is open; the banner stays as a fallback.
        val ids = moveReady.mapTo(HashSet()) { it.id }
        if (ids.isNotEmpty() && !lastPrompted.containsAll(ids)) {
            lastPrompted = ids
            startDelete(moveReady)
        }
    }

    fun startBackup(block: suspend () -> Boolean) = scope.launch {
        if (!block()) {
            val r = snackbar.showSnackbar("Set up your NAS first", actionLabel = "Settings")
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }

    if (confirmMove) {
        val n = state.selected.size
        AlertDialog(
            onDismissRequest = { confirmMove = false },
            icon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
            title = { Text("Move $n ${if (n == 1) "item" else "items"} to the NAS?") },
            text = {
                Text(
                    "Each file is uploaded, then read back from the NAS and checked byte for byte. " +
                        "Only files that match exactly are deleted from this phone, after you confirm. " +
                        "Anything that can't be verified stays on the phone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmMove = false; startBackup(vm::moveSelected) }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { confirmMove = false }) { Text("Cancel") } },
        )
    }

    if (confirmUnbacked > 0) {
        UnbackedDeleteDialog(
            count = confirmUnbacked,
            toTrash = vm.deletesGoToTrash.value,
            onConfirm = { confirmUnbacked = 0; startDelete() },
            onDismiss = { confirmUnbacked = 0 },
        )
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
                        IconButton(onClick = { confirmMove = true }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to NAS")
                        }
                        IconButton(onClick = {
                            // Warn first if any of these would be lost for good (no NAS copy yet).
                            val unbacked = vm.selectedNotBackedUp()
                            if (unbacked > 0) confirmUnbacked = unbacked else startDelete()
                        }) { Icon(Icons.Filled.Delete, "Delete from phone") }
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
            if (moveReady.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                        Text(
                            "${moveReady.size} moved ${if (moveReady.size == 1) "file is" else "files are"} " +
                                "verified on the NAS. Delete from this phone to finish the move.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.align(Alignment.End)) {
                            TextButton(onClick = vm::keepMovedFiles) { Text("Keep on phone") }
                            TextButton(onClick = { startDelete(moveReady) }) { Text("Delete now") }
                        }
                    }
                }
            }
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
                        else viewerIndex = state.items.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
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

    viewerIndex?.let { start ->
        val viewerItems = remember(state.items, state.backedUp) {
            state.items.map {
                ViewerItem(
                    key = it.id,
                    model = it.uri,
                    title = it.name,
                    subtitle = "${formatDate(it.takenAt)} · ${formatSize(it.size)} · " +
                        if (state.isBackedUp(it)) "On the NAS" else "Not backed up yet",
                    isVideo = it.isVideo,
                )
            }
        }
        // [chooser]: let the user pick the app every time ("Open with"); otherwise use the default.
        fun openExternally(v: ViewerItem, chooser: Boolean) {
            val item = state.items.firstOrNull { it.id == v.key } ?: return
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(if (chooser) Intent.createChooser(view, "Open with") else view) }
        }
        MediaViewer(
            items = viewerItems,
            startIndex = start,
            onDismiss = { viewerIndex = null },
            // Videos play in the phone's default video app.
            onPlay = { openExternally(it, chooser = false) },
            topActions = { current ->
                // Small and slightly dimmed so it doesn't compete with the photo, but a full-size
                // touch target; it hides with the rest of the controls on a tap.
                IconButton(onClick = { openExternally(current, chooser = true) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open with another app",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
    }
}

/** Android's trash for media (createTrashRequest) arrived in Android 11. */
fun trashSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

@Composable
private fun UnbackedDeleteDialog(count: Int, toTrash: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Not backed up yet") },
        text = {
            Text(
                "$count of the selected ${if (count == 1) "item isn't" else "items aren't"} on the NAS yet. " +
                    (if (toTrash) "They'll only be in the phone's trash, for 30 days. "
                    else "Deleting from the phone will lose ${if (count == 1) "it" else "them"} for good. ") +
                    "Back up first, or delete anyway?"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete anyway", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    val groups = remember(state.items) { monthGroups(state.items) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 88.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (month, list) ->
        item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) { MonthHeader(month, list.size) }
        items(list, key = { it.id }) { item ->
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
    val groups = remember(state.items) { monthGroups(state.items) }
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
        groups.forEach { (month, list) ->
        stickyHeader(key = "month-$month") { MonthHeader(month, list.size) }
        items(list, key = { it.id }) { item ->
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
}

/** Items grouped by the month they were taken, newest first (the list is already sorted). */
private fun monthGroups(items: List<MediaItem>): List<Pair<YearMonth, List<MediaItem>>> =
    items.groupBy { YearMonth.from(Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault())) }.toList()

private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy")

@Composable
private fun MonthHeader(month: YearMonth, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(month.format(monthFormat), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
