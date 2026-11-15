package dev.uphotosync.app.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.uphotosync.app.data.PhoneMediaRequest
import dev.uphotosync.app.data.PhoneTrash
import dev.uphotosync.app.data.TrashedItem
import kotlinx.coroutines.launch

/** Android's media trash: restore things, delete them for good, or empty it now. */
@Composable
fun TrashTool(tools: ToolsViewModel, onBack: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        // The Tools tab hides this on Android 10, which has no trash.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    TrashContent(tools, onBack)
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun TrashContent(tools: ToolsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var trash by remember { mutableStateOf<List<TrashedItem>?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    /** Delete for good: these items (the selection, or everything for "Empty trash"). */
    var confirmDelete by remember { mutableStateOf<List<TrashedItem>?>(null) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    fun load() = scope.launch {
        trash = runCatching { PhoneTrash.load(context) }.getOrElse { emptyList() }
        selected = selected.intersect(trash.orEmpty().map { it.id }.toSet())
    }
    LaunchedEffect(Unit) { load() }

    val runRequest = rememberPhoneRequest(tools.request) { ids, action ->
        selected = selected - ids.toSet()
        load()
        // Restored files are back in the phone's library; the other tools should see them.
        tools.reload()
        val n = ids.size
        val what = plural(n, "item")
        scope.launch {
            snackbar.showSnackbar(
                when {
                    n == 0 -> "Nothing changed"
                    action == PhoneMediaRequest.Action.Restore -> "Restored $what"
                    else -> "Deleted $what for good"
                }
            )
        }
    }

    val list = trash.orEmpty()
    val chosen = list.filter { it.id in selected }

    ToolScaffold(
        title = "Phone trash",
        onBack = onBack,
        snackbar = snackbar,
        bottomBar = if (chosen.isNotEmpty()) {
            {
                BottomAppBar {
                    Text(
                        "${plural(chosen.size, "item")} · ${formatSize(chosen.sumOf { it.size })}",
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    )
                    OutlinedButton(
                        onClick = { runRequest(chosen.map { PhoneMediaRequest.Target(it.id, it.uri) }, PhoneMediaRequest.Action.Restore) },
                        enabled = !tools.request.busy,
                    ) { Text("Restore") }
                    Button(
                        onClick = { confirmDelete = chosen },
                        enabled = !tools.request.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Delete") }
                }
            }
        } else null,
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
                        when {
                            trash == null -> "Loading…"
                            list.isEmpty() -> "The trash is empty."
                            else -> "${plural(list.size, "item")} · ${formatSize(list.sumOf { it.size })}. " +
                                "Android deletes each one for good 30 days after it was trashed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (list.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { selected = list.mapTo(HashSet()) { it.id } }) { Text("Tick all") }
                            if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }) { Text("Untick all") }
                            TextButton(onClick = { confirmDelete = list }, enabled = !tools.request.busy) {
                                Text("Empty trash", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            items(list, key = { it.id }) { item ->
                ToolThumb(
                    uri = item.uri,
                    isVideo = item.isVideo,
                    selected = item.id in selected,
                    backedUp = null,
                    label = daysLeft(item.expiresAt),
                    onClick = { viewerIndex = list.indexOf(item) },
                    onToggle = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                )
            }
        }
    }

    viewerIndex?.let { start ->
        MediaViewer(
            items = list.map {
                ViewerItem(
                    key = it.id, model = it.uri, title = it.name,
                    subtitle = "${formatSize(it.size)} · ${daysLeft(it.expiresAt)}",
                    isVideo = it.isVideo, video = it.uri.takeIf { _ -> it.isVideo },
                )
            },
            startIndex = start,
            onDismiss = { viewerIndex = null },
        )
    }

    confirmDelete?.let { doomed ->
        val all = doomed.size == list.size
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (all) "Empty the trash?" else "Delete ${plural(doomed.size, "item")} for good?") },
            text = {
                Text(
                    "${plural(doomed.size, "item")} (${formatSize(doomed.sumOf { it.size })}) will be deleted from this " +
                        "phone permanently. Copies already on the NAS aren't affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    runRequest(doomed.map { PhoneMediaRequest.Target(it.id, it.uri) }, PhoneMediaRequest.Action.Delete)
                }) { Text("Delete for good", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun daysLeft(expiresAt: Long): String {
    val days = ((expiresAt - System.currentTimeMillis()) / DAY_MS).coerceAtLeast(0)
    return when (days) {
        0L -> "Goes today"
        1L -> "1 day left"
        else -> "$days days left"
    }
}
