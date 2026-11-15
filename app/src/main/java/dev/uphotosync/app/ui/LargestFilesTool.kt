package dev.uphotosync.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.uphotosync.app.data.MediaItem
import dev.uphotosync.app.data.PhoneMediaRequest
import kotlinx.coroutines.launch

private enum class SizeFilter(val label: String) { All("Everything"), Videos("Videos"), Photos("Photos") }

/** The biggest files on the phone, largest first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LargestFilesTool(tools: ToolsViewModel, onBack: () -> Unit) {
    val items by tools.items.collectAsStateWithLifecycle()
    val backedUp by tools.backedUp.collectAsStateWithLifecycle()
    val toTrash by tools.toTrash.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(SizeFilter.All) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    val list = remember(items, filter) {
        items.orEmpty()
            .filter { when (filter) { SizeFilter.All -> true; SizeFilter.Videos -> it.isVideo; SizeFilter.Photos -> !it.isVideo } }
            .sortedByDescending { it.size }
            .take(LIMIT)
    }
    val chosen = list.filter { it.id in selected }
    val shownBytes = list.sumOf { it.size }

    val runRequest = rememberPhoneRequest(tools.request) { ids, action ->
        selected = selected - ids.toSet()
        tools.removed(ids)
        scope.launch { snackbar.showSnackbar(deleteMessage(ids.size, action == PhoneMediaRequest.Action.Trash)) }
    }

    ToolScaffold(
        title = "Largest files",
        onBack = onBack,
        snackbar = snackbar,
        bottomBar = {
            ToolActionBar(
                summary = if (chosen.isEmpty()) "Tick files to remove"
                else "${plural(chosen.size, "item")} · ${formatSize(chosen.sumOf { it.size })}",
                button = if (toTrash) "Move to trash" else "Delete",
                enabled = chosen.isNotEmpty() && !tools.request.busy,
                onClick = { confirm = true },
            )
        },
    ) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                    SizeFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
                Text(
                    if (list.isEmpty()) "Nothing here"
                    else "The ${list.size} largest take up ${formatSize(shownBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(list, key = { it.id }) { item ->
                SizeRow(
                    item = item,
                    backedUp = backedUp.has(item),
                    selected = item.id in selected,
                    onToggle = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                    onOpen = { viewerIndex = list.indexOf(item) },
                )
            }
        }
    }

    viewerIndex?.let { start ->
        MediaViewer(items = viewerItemsFor(list, backedUp), startIndex = start, onDismiss = { viewerIndex = null })
    }

    if (confirm) {
        ToolDeleteDialog(
            count = chosen.size,
            bytes = chosen.sumOf { it.size },
            lines = emptyList(),
            notBackedUp = chosen.count { !backedUp.has(it) },
            toTrash = toTrash,
            onConfirm = {
                confirm = false
                scope.launch { runRequest(chosen.map { it.target() }, tools.deleteAction()) }
            },
            onDismiss = { confirm = false },
        )
    }
}

/** One file as a list row: thumbnail, name, size, folder, NAS state and a tick box. */
@Composable
internal fun SizeRow(item: MediaItem, backedUp: Boolean, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.uri).size(160).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp),
                )
            }
        },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (backedUp) Icons.Filled.CloudDone else Icons.Outlined.CloudOff,
                    if (backedUp) "On the NAS" else "Not backed up",
                    tint = if (backedUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).padding(end = 2.dp),
                )
                Text(
                    "${formatDate(item.takenAt)} · ${item.relativePath}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatSize(item.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }
        },
    )
}

private const val LIMIT = 200
