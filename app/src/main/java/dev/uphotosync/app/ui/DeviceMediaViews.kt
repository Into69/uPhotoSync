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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaGrid(state: DeviceUiState, onClick: (MediaItem) -> Unit, onLongClick: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val groups = remember(state.items, state.sort) { monthGroups(state.items, state.sort.byDate) }
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 88.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (month, list) ->
        if (month != null) {
            item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) { MonthHeader(month, list.size) }
        }
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
    GridScrollbar(gridState) { f -> devicePosition(state, f) }
    }
}

/** Scrollbar bubble for the Device tab: month, letter or size, following the sort. */
private fun devicePosition(state: DeviceUiState, fraction: Float): String? = positionLabel(
    state.items, fraction,
    sortByDate = state.sort.byDate,
    sortByName = state.sort == SortOrder.NameAZ || state.sort == SortOrder.NameZA,
    name = { it.name }, date = { it.takenAt }, size = { it.size },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaList(
    state: DeviceUiState,
    selecting: Boolean,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val groups = remember(state.items, state.sort) { monthGroups(state.items, state.sort.byDate) }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
        groups.forEach { (month, list) ->
        if (month != null) stickyHeader(key = "month-$month") { MonthHeader(month, list.size) }
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
    ListScrollbar(listState) { f -> devicePosition(state, f) }
    }
}

/** Items grouped by the month they were taken, newest first (the list is already sorted). */
/**
 * Items grouped by the month they were taken, in the list's order, for month headers. Sorted
 * by name or size, months would repeat all over, so it's one group with no header (null).
 */
private fun monthGroups(items: List<MediaItem>, byDate: Boolean): List<Pair<YearMonth?, List<MediaItem>>> =
    if (!byDate) listOf(null to items)
    else items.groupBy<MediaItem, YearMonth?> {
        YearMonth.from(Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()))
    }.toList()

private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy")

@Composable
internal fun MonthHeader(month: YearMonth, count: Int) {
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
fun CenterText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
