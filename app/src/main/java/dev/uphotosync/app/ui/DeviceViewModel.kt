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

data class DeviceUiState(
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val albums: List<Album> = emptyList(),
    val album: Album? = null,
    val selected: Set<Long> = emptySet(),
    val backedUp: Map<Long, Long> = emptyMap(),
    val sort: SortOrder = SortOrder.DateNewest,
    /** File-name search; blank shows everything. */
    val query: String = "",
    /** Album id -> how many of its items are backed up, for the album picker. */
    val albumBacked: Map<String, Int> = emptyMap(),
) {
    fun isBackedUp(item: MediaItem) = backedUp[item.id] == item.size
    val pending get() = items.count { !isBackedUp(it) }
}

/** Shown before a backup that won't fit on the NAS. */
data class SpaceShortfall(val needed: Long, val free: Long)

class DeviceViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as PhotoSyncApp).graph
    private val all = MutableStateFlow<List<MediaItem>?>(null)
    private val albumId = MutableStateFlow<String?>(null)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    private val query = MutableStateFlow("")

    private data class Filters(val albumId: String?, val query: String, val sort: SortOrder)

    private val filters = combine(albumId, query, graph.settings.sortOrder("device", SortOrder.DateNewest)) { a, q, s ->
        Filters(a, q, s)
    }

    val state = combine(all, filters, selected, graph.index.sizes) { all, f, selected, sizes ->
        val list = all.orEmpty()
        val albums = graph.media.albumsOf(list)
        val album = albums.firstOrNull { it.id == f.albumId }
        val inAlbum = if (album == null) list else list.filter { it.bucketId == album.id }
        val q = f.query.trim()
        val matching = if (q.isEmpty()) inAlbum else inAlbum.filter { it.name.contains(q, ignoreCase = true) }
        DeviceUiState(
            loading = all == null,
            items = sorted(matching, f.sort),
            albums = albums,
            album = album,
            selected = selected,
            backedUp = sizes,
            sort = f.sort,
            query = f.query,
            albumBacked = list.filter { sizes[it.id] == it.size }.groupingBy { it.bucketId }.eachCount(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    /** The selected items, for checking space before backing them up or moving them. */
    fun selectedItems(): List<MediaItem> = all.value.orEmpty().filter { it.id in selected.value }

    /**
     * Whether [items] (those not backed up yet) would fit on the NAS. Null if they fit, or if the
     * free space can't be read; files already on the NAS take no space, so this is an upper bound.
     */
    suspend fun spaceShortfall(items: List<MediaItem>): SpaceShortfall? {
        val sizes = graph.index.sizes.value
        val needed = items.filter { sizes[it.id] != it.size }.sumOf { it.size }
        if (needed == 0L || graph.settings.config.first() == null) return null
        // Don't hold up the tap if the NAS is slow or out of reach (away from home): give up after a
        // few seconds and just start; the backup itself still only uploads what fits.
        val lookup = graph.appScope.async { runCatching { graph.nas.space() }.getOrNull() }
        val space = withTimeoutOrNull(SPACE_CHECK_TIMEOUT_MS) { lookup.await() }?.takeIf { it.total > 0 } ?: return null
        // The backup keeps a safety margin free, so warn against the same figure.
        val usable = (space.free - NAS_SPACE_MARGIN).coerceAtLeast(0)
        return if (needed > usable) SpaceShortfall(needed, usable) else null
    }

    val viewMode = graph.settings.viewMode("device")
        .stateIn(viewModelScope, SharingStarted.Eagerly, ViewMode.Thumbnails)

    fun setViewMode(mode: ViewMode) = viewModelScope.launch { graph.settings.setViewMode("device", mode) }

    private var reloadJob: Job? = null

    /** Reloads from MediaStore; debounced because the observer fires in bursts. */
    private val _phoneSpace = MutableStateFlow<StorageSpace?>(null)

    /** The phone's storage, refreshed whenever the media list is (e.g. after deleting). */
    val phoneSpace = _phoneSpace.asStateFlow()

    fun reload(debounce: Boolean = false) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            if (debounce) delay(500)
            val items = graph.media.loadAll()
            all.value = items
            val ids = items.mapTo(HashSet()) { it.id }
            selected.update { it.intersect(ids) }
            _phoneSpace.value = withContext(Dispatchers.IO) { PhoneStorage.read(getApplication()) }
        }
    }

    /** While the screen isn't showing, changes only mark the list stale; it reloads on return. */
    private var onScreen = true
    private var stale = false

    fun setOnScreen(visible: Boolean) {
        onScreen = visible
        if (visible && stale) {
            stale = false
            reload()
        }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (onScreen) reload(debounce = true) else stale = true
        }
    }

    init {
        app.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer
        )
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    fun setSort(order: SortOrder) = viewModelScope.launch { graph.settings.setSortOrder("device", order) }

    private fun sorted(items: List<MediaItem>, order: SortOrder): List<MediaItem> = when (order) {
        SortOrder.DateNewest -> items.sortedByDescending { it.takenAt }
        SortOrder.DateOldest -> items.sortedBy { it.takenAt }
        SortOrder.NameAZ -> items.sortedBy { it.name.lowercase() }
        SortOrder.NameZA -> items.sortedByDescending { it.name.lowercase() }
        SortOrder.SizeLargest -> items.sortedByDescending { it.size }
        SortOrder.SizeSmallest -> items.sortedBy { it.size }
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

    private val deleteRequest = PhoneMediaRequest(app)
    private var deleteFromSelection = false

    /** Phone deletes go to Android's trash (Android 11+, if turned on in Settings). */
    val deletesGoToTrash = graph.settings.prefs.map { it.phoneTrash && trashSupported() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, trashSupported())

    /** True while a delete (and its system confirmation) is in progress; only one at a time. */
    val deleting get() = deleteRequest.busy

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
        val batch = items ?: all.value.orEmpty().filter { it.id in ids }
        if (batch.isEmpty()) return null
        deleteFromSelection = items == null
        // Trashed files can be restored from the gallery (or Tools > Phone trash) for 30 days.
        val action = if (graph.settings.prefs.first().phoneTrash) PhoneMediaRequest.Action.Trash
        else PhoneMediaRequest.Action.Delete
        return deleteRequest.start(batch.map { PhoneMediaRequest.Target(it.id, it.uri) }, action)
    }

    /** Android 10: carries on deleting after the user allowed one photo. */
    suspend fun continueDelete(): IntentSender? = deleteRequest.continueDelete()

    /** Called when deleting is finished or cancelled; returns a message for the user. */
    fun finishDelete(confirmedBatch: Boolean): String {
        val toTrash = deleteRequest.action == PhoneMediaRequest.Action.Trash
        val deletedIds = deleteRequest.finish(confirmedBatch)
        val n = deletedIds.size
        // A finished move: these no longer need deleting.
        graph.moves.remove(deletedIds)
        // Only clear the selection if that's what was being deleted (not a finished move).
        if (deleteFromSelection) clearSelection()
        reload()
        return deleteMessage(n, toTrash)
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
        val s = state.value
        graph.scheduler.backupNow(
            when {
                // Searching: just the matching files that aren't backed up yet.
                s.query.isNotBlank() -> BackupScope.Items(s.items.filterNot(s::isBackedUp).map { it.id })
                s.album == null -> BackupScope.All
                else -> BackupScope.Album(s.album.id)
            }
        )
        return true
    }
}

/** How long "Back up" waits to read the NAS's free space before starting anyway. */
internal const val SPACE_CHECK_TIMEOUT_MS = 3_000L
