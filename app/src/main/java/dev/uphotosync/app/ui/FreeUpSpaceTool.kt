package dev.uphotosync.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
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
import dev.uphotosync.app.smb.NasClient
import dev.uphotosync.app.smb.NasNotConfiguredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Photos and videos already backed up, older than a chosen age. Before anything is removed,
 * each one is looked up on the NAS again (same size, in the current destination); anything
 * that can't be confirmed there is left on the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeUpSpaceTool(tools: ToolsViewModel, initialDays: Long = FREE_UP_DEFAULT_DAYS, onBack: () -> Unit) {
    val items by tools.items.collectAsStateWithLifecycle()
    val backedUp by tools.backedUp.collectAsStateWithLifecycle()
    val toTrash by tools.toTrash.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var days by rememberSaveable { mutableStateOf(initialDays) }
    // Everything in range starts ticked; this holds the ones the user unticked.
    var unticked by remember { mutableStateOf(emptySet<Long>()) }
    var checking by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    val candidates = remember(items, backedUp, days) {
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        items.orEmpty().filter { backedUp.has(it) && (days == 0L || it.takenAt < cutoff) }.sortedBy { it.takenAt }
    }
    val chosen = candidates.filter { it.id !in unticked }
    val bytes = chosen.sumOf { it.size }

    val runRequest = rememberPhoneRequest(tools.request) { ids, action ->
        tools.removed(ids)
        scope.launch { snackbar.showSnackbar(deleteMessage(ids.size, action == PhoneMediaRequest.Action.Trash)) }
    }

    fun checkAndConfirm() {
        checking = true
        scope.launch {
            try {
                val ok = tools.graph.engine.confirmOnNas(chosen)
                val sure = chosen.filter { it.id in ok }
                if (sure.isEmpty()) {
                    snackbar.showSnackbar("None of these could be found on the NAS at the same size. Run a backup, then try again.")
                } else {
                    confirmed = sure to (chosen.size - sure.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NasNotConfiguredException) {
                snackbar.showSnackbar("Set up the NAS in Settings first")
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't check the NAS (${NasClient.describe(e)}). Nothing was removed.")
            } finally {
                checking = false
            }
        }
    }

    ToolScaffold(
        title = "Free up space",
        onBack = onBack,
        snackbar = snackbar,
        bottomBar = {
            ToolActionBar(
                summary = when {
                    checking -> "Checking the NAS…"
                    chosen.isEmpty() -> "Nothing to remove"
                    else -> "${plural(chosen.size, "item")} · ${formatSize(bytes)}"
                },
                button = "Remove from phone",
                enabled = chosen.isNotEmpty() && !checking && !tools.request.busy,
                onClick = ::checkAndConfirm,
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
                        "Photos and videos already on the NAS. They're checked on the NAS again before " +
                            "anything is removed, and the NAS copies are never touched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Older than", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30L to "1 month", 90L to "3 months", 180L to "6 months", 365L to "1 year", 0L to "Any age")
                            .forEach { (d, label) ->
                                FilterChip(selected = days == d, onClick = { days = d; unticked = emptySet() }, label = { Text(label) })
                            }
                    }
                    if (candidates.isNotEmpty()) {
                        FlowRow {
                            TextButton(onClick = { unticked = emptySet() }) { Text("Tick all") }
                            TextButton(onClick = { unticked = candidates.mapTo(HashSet()) { it.id } }) { Text("Untick all") }
                        }
                    } else if (items != null) {
                        Text(
                            "Nothing backed up from that far back.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
            items(candidates, key = { it.id }) { item ->
                fun toggle() {
                    unticked = if (item.id in unticked) unticked - item.id else unticked + item.id
                }
                ToolThumb(
                    uri = item.uri,
                    isVideo = item.isVideo,
                    selected = item.id !in unticked,
                    backedUp = true,
                    label = formatSize(item.size),
                    onClick = { viewerIndex = candidates.indexOf(item) },
                    onToggle = ::toggle,
                )
            }
        }
    }

    viewerIndex?.let { start ->
        MediaViewer(
            items = viewerItemsFor(candidates, backedUp),
            startIndex = start,
            onDismiss = { viewerIndex = null },
        )
    }

    confirmed?.let { (sure, missing) ->
        ToolDeleteDialog(
            count = sure.size,
            bytes = sure.sumOf { it.size },
            lines = listOfNotNull(
                "${plural(sure.size, "item")} confirmed on the NAS just now. The NAS copies stay.",
                missing.takeIf { it > 0 }?.let {
                    "${plural(it, "item")} couldn't be found on the NAS at the same size, so ${if (it == 1) "it stays" else "they stay"} on the phone. " +
                        "Run a backup, then try again."
                },
            ),
            notBackedUp = 0,
            toTrash = toTrash,
            onConfirm = {
                confirmed = null
                scope.launch { runRequest(sure.map { it.target() }, tools.deleteAction()) }
            },
            onDismiss = { confirmed = null },
        )
    }
}
