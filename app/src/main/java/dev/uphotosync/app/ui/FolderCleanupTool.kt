package dev.uphotosync.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.uphotosync.app.data.MediaItem
import dev.uphotosync.app.data.PhoneMediaRequest
import kotlinx.coroutines.launch

private data class PhoneFolder(val path: String, val items: List<MediaItem>, val clutter: Boolean) {
    val bytes = items.sumOf { it.size }
}

/** Folders that tend to fill up with things nobody meant to keep. */
private val CLUTTER = listOf(
    "Screenshots", "Screen recordings", "ScreenRecord", "Download", "WhatsApp", "Telegram", "Messenger",
    "Signal", "Snapchat", "Viber", "Discord", "Instagram", "Facebook", "Twitter", "Reddit", "TikTok",
)

private fun isClutter(path: String) = CLUTTER.any { path.contains(it, ignoreCase = true) }

/** Phone folders by size, with the usual clutter folders first; open one to clear out old files. */
@Composable
fun FolderCleanupTool(tools: ToolsViewModel, onBack: () -> Unit) {
    val items by tools.items.collectAsStateWithLifecycle()
    var openPath by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = remember(items) {
        items.orEmpty().groupBy { it.relativePath }
            .map { (path, list) -> PhoneFolder(path, list.sortedBy { it.takenAt }, isClutter(path)) }
            .sortedWith(compareByDescending<PhoneFolder> { it.clutter }.thenByDescending { it.bytes })
    }
    val open = folders.firstOrNull { it.path == openPath }
    if (openPath != null && open != null) {
        FolderDetail(tools, open, onBack = { openPath = null })
        return
    }

    val snackbar = remember { SnackbarHostState() }
    ToolScaffold(title = "Folder clean-up", onBack = onBack, snackbar = snackbar) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    "Folders that usually collect screenshots, downloads and forwarded media are listed first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(folders, key = { it.path }) { f ->
                ListItem(
                    modifier = Modifier.clickable { openPath = f.path },
                    leadingContent = {
                        Icon(
                            if (f.clutter) Icons.Filled.FolderSpecial else Icons.Filled.Folder, null,
                            tint = if (f.clutter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = { Text(f.path) },
                    supportingContent = {
                        Text(
                            "${plural(f.items.size, "item")} · ${formatSize(f.bytes)}" +
                                if (f.clutter) " · often safe to clean" else "",
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderDetail(tools: ToolsViewModel, folder: PhoneFolder, onBack: () -> Unit) {
    val backedUp by tools.backedUp.collectAsStateWithLifecycle()
    val toTrash by tools.toTrash.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selected by remember(folder.path) { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val chosen = folder.items.filter { it.id in selected }

    val runRequest = rememberPhoneRequest(tools.request) { ids, action ->
        selected = selected - ids.toSet()
        tools.removed(ids)
        scope.launch { snackbar.showSnackbar(deleteMessage(ids.size, action == PhoneMediaRequest.Action.Trash)) }
    }

    fun selectOlderThan(days: Long?) {
        val cutoff = days?.let { System.currentTimeMillis() - it * DAY_MS }
        selected = folder.items.filter { cutoff == null || it.takenAt < cutoff }.mapTo(HashSet()) { it.id }
    }

    ToolScaffold(
        title = folder.path.substringAfterLast('/'),
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = modifier,
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${folder.path} · ${plural(folder.items.size, "item")} · ${formatSize(folder.bytes)}, oldest first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Tick everything older than", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30L to "1 month", 90L to "3 months", 365L to "1 year", null to "Tick all").forEach { (d, label) ->
                            FilterChip(selected = false, onClick = { selectOlderThan(d) }, label = { Text(label) })
                        }
                    }
                    if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }) { Text("Untick all") }
                }
            }
            items(folder.items, key = { it.id }) { item ->
                ToolThumb(
                    uri = item.uri,
                    isVideo = item.isVideo,
                    selected = item.id in selected,
                    backedUp = backedUp.has(item),
                    label = formatDate(item.takenAt),
                    onClick = { viewerIndex = folder.items.indexOf(item) },
                    onToggle = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                )
            }
        }
    }

    viewerIndex?.let { start ->
        MediaViewer(items = viewerItemsFor(folder.items, backedUp), startIndex = start, onDismiss = { viewerIndex = null })
    }

    if (confirm) {
        ToolDeleteDialog(
            count = chosen.size,
            bytes = chosen.sumOf { it.size },
            lines = listOf("From ${folder.path}."),
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
