package dev.uphotosync.app.ui

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
import android.content.IntentSender
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.uphotosync.app.data.PhoneMediaRequest
import dev.uphotosync.app.data.PhoneStorage
import dev.uphotosync.app.data.SortOrder
import dev.uphotosync.app.data.StorageSpace
import kotlinx.coroutines.flow.asStateFlow
import dev.uphotosync.app.data.ViewMode
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
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.backup.BackupScope
import dev.uphotosync.app.backup.NAS_SPACE_MARGIN
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import dev.uphotosync.app.data.Album
import dev.uphotosync.app.data.MediaItem
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
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.setOnScreen(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.setOnScreen(false) }
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
    // Search: a field in the top bar that filters by file name as you type.
    var searching by rememberSaveable { mutableStateOf(false) }
    fun closeSearch() {
        searching = false
        vm.setQuery("")
    }
    BackHandler(enabled = searching && !selecting) { closeSearch() }

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

    // A backup that won't fit on the NAS asks first; the job then uploads what fits.
    var lowSpace by remember { mutableStateOf<Pair<SpaceShortfall, suspend () -> Boolean>?>(null) }

    fun runBackup(block: suspend () -> Boolean) = scope.launch {
        if (!block()) {
            val r = snackbar.showSnackbar("Set up your NAS first", actionLabel = "Settings")
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }

    /** Starts a backup of [items] via [block], checking first that they'll fit on the NAS. */
    fun startBackup(items: List<MediaItem>, block: suspend () -> Boolean) = scope.launch {
        val shortfall = vm.spaceShortfall(items)
        if (shortfall != null) lowSpace = shortfall to block else runBackup(block)
    }

    lowSpace?.let { (short, block) ->
        AlertDialog(
            onDismissRequest = { lowSpace = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Not enough space on the NAS") },
            text = {
                Text(
                    "Up to ${formatSize(short.needed)} to back up, but only ${formatSize(short.free)} is free on the NAS. " +
                        "Files that fit will be backed up; the rest wait until there's room."
                )
            },
            confirmButton = {
                TextButton(onClick = { lowSpace = null; runBackup(block) }) { Text("Back up what fits") }
            },
            dismissButton = { TextButton(onClick = { lowSpace = null }) { Text("Cancel") } },
        )
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
                TextButton(onClick = { confirmMove = false; startBackup(vm.selectedItems(), vm::moveSelected) }) { Text("Move") }
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
                        IconButton(onClick = { startBackup(vm.selectedItems(), vm::backUpSelected) }) {
                            Icon(Icons.Filled.CloudUpload, "Back up selected")
                        }
                    },
                )
            } else if (searching) {
                FileSearchBar(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    onClose = ::closeSearch,
                    hint = "Search file names" + (state.album?.let { " in ${it.name}" } ?: ""),
                    resultText = if (state.query.isBlank()) null else "${state.items.size} found",
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
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        SortMenu(state.sort) { vm.setSort(it) }
                        ViewModeToggle(viewMode, vm::setViewMode)
                    },
                )
            }
        },
        floatingActionButton = {
            if (granted && !selecting && state.pending > 0) {
                ExtendedFloatingActionButton(
                    onClick = { startBackup(state.items, vm::backUpVisible) },
                    icon = { Icon(Icons.Filled.CloudUpload, null) },
                    text = { Text("Back up ${state.pending}") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            BackupStatusBanner()
            val phoneSpace by vm.phoneSpace.collectAsStateWithLifecycle()
            StorageLine("Phone", phoneSpace, isNas = false)
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
                    video = it.uri.takeIf { _ -> it.isVideo },
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
