package dev.uphotosync.app.ui

import android.app.Application
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.uphotosync.app.data.DuplicateFinder
import dev.uphotosync.app.data.MediaItem
import dev.uphotosync.app.data.PhoneMediaRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicatesState(
    /** Show byte-for-byte copies. */
    val showExact: Boolean = true,
    /** Look for (and show) similar photos too. */
    val showSimilar: Boolean = true,
    val sensitivity: Int = DuplicateFinder.NORMAL,
    val scanning: DuplicateFinder.Progress? = null,
    /** Null until a scan has finished. */
    val groups: List<DuplicateFinder.Group>? = null,
    /** Whether the last scan looked for similar photos, and how closely. */
    val scannedSimilar: Int? = null,
    /** Group (by suggested keep) -> the file the user chose to keep instead. */
    val keepOverride: Map<Long, Long> = emptyMap(),
    /** Files marked for deletion. */
    val selected: Set<Long> = emptySet(),
    val error: String? = null,
) {
    fun keepOf(g: DuplicateFinder.Group): MediaItem {
        val id = keepOverride[g.suggestedKeep] ?: g.suggestedKeep
        return g.items.firstOrNull { it.id == id } ?: g.items.first()
    }

    /** The files shown under a group's kept one, after the Exact / Similar filters. */
    fun visibleOthers(g: DuplicateFinder.Group): List<DuplicateFinder.Match> =
        g.others(keepOf(g)).filter { if (it.exact) showExact else showSimilar }

    val visibleGroups get() = groups.orEmpty().filter { visibleOthers(it).isNotEmpty() }
}

class DuplicatesViewModel(app: Application) : AndroidViewModel(app) {
    private val finder = DuplicateFinder(app)
    private val _state = MutableStateFlow(DuplicatesState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun scan(items: List<MediaItem>) {
        job?.cancel()
        val s = _state.value
        val similar = s.showSimilar
        val distance = s.sensitivity
        _state.update { it.copy(scanning = DuplicateFinder.Progress("Starting", 0, 0), error = null) }
        job = viewModelScope.launch {
            try {
                val groups = finder.scan(items, similar, distance) { p -> _state.update { it.copy(scanning = p) } }
                _state.update { cur ->
                    cur.copy(
                        scanning = null,
                        groups = groups,
                        scannedSimilar = if (similar) distance else null,
                        keepOverride = emptyMap(),
                        // Exact copies are certain, so they start ticked; similar ones are the user's call.
                        selected = groups.flatMap { g -> g.others(g.items.first()).filter { it.exact }.map { it.item.id } }.toSet(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scanning = null, error = e.message ?: "Scan failed") }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(scanning = null) }
    }

    fun setShowExact(on: Boolean) = _state.update { it.copy(showExact = on || !it.showSimilar) }

    /** Similar photos need their own pass, so turning them on (or changing how close) rescans. */
    fun setSimilar(on: Boolean, items: List<MediaItem>) {
        _state.update { it.copy(showSimilar = on, showExact = it.showExact || !on) }
        if (on && _state.value.groups != null && _state.value.scannedSimilar != _state.value.sensitivity) scan(items)
    }

    fun setSensitivity(bits: Int, items: List<MediaItem>) {
        _state.update { it.copy(sensitivity = bits) }
        if (_state.value.groups != null && _state.value.showSimilar) scan(items)
    }

    fun toggle(id: Long) = _state.update { s ->
        s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    /** Keep [item] instead: it can't be marked for deletion, and the old pick becomes a candidate. */
    fun keep(group: DuplicateFinder.Group, item: MediaItem) = _state.update { s ->
        s.copy(keepOverride = s.keepOverride + (group.suggestedKeep to item.id), selected = s.selected - item.id)
    }

    /** Select (or clear) every visible file of one kind. */
    fun selectAll(exact: Boolean, on: Boolean) = _state.update { s ->
        val ids = s.visibleGroups.flatMap { g -> s.visibleOthers(g).filter { it.exact == exact }.map { it.item.id } }
        s.copy(selected = if (on) s.selected + ids else s.selected - ids.toSet())
    }

    /** Files that were deleted drop out; groups with nothing left to compare go. */
    fun removed(ids: Collection<Long>) {
        val gone = ids.toHashSet()
        _state.update { s ->
            s.copy(
                groups = s.groups?.map { it.without(gone) }?.filter { it.items.size > 1 },
                selected = s.selected - gone,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DuplicatesTool(tools: ToolsViewModel, onBack: () -> Unit, vm: DuplicatesViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val items by tools.items.collectAsStateWithLifecycle()
    val backedUp by tools.backedUp.collectAsStateWithLifecycle()
    val toTrash by tools.toTrash.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<DuplicateFinder.Group, Int>?>(null) }

    val runRequest = rememberPhoneRequest(tools.request) { ids, action ->
        vm.removed(ids)
        tools.removed(ids)
        scope.launch { snackbar.showSnackbar(deleteMessage(ids.size, action == PhoneMediaRequest.Action.Trash)) }
    }

    // Selected files that are still on screen (a filter can hide some; those aren't deleted).
    val chosen = remember(s) {
        s.visibleGroups.flatMap { g -> s.visibleOthers(g).filter { it.item.id in s.selected } }
    }
    val chosenBytes = chosen.sumOf { it.item.size }

    ToolScaffold(
        title = "Duplicates",
        onBack = onBack,
        snackbar = snackbar,
        bottomBar = if (s.groups != null && s.scanning == null) {
            {
                ToolActionBar(
                    summary = if (chosen.isEmpty()) "Tick the copies to remove"
                    else "${plural(chosen.size, "item")} · ${formatSize(chosenBytes)}",
                    button = if (toTrash) "Move to trash" else "Delete",
                    enabled = chosen.isNotEmpty() && !tools.request.busy,
                    onClick = { confirm = true },
                )
            }
        } else null,
    ) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "options") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Look for", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.showExact, onClick = { vm.setShowExact(!s.showExact) }, label = { Text("Exact copies") })
                        FilterChip(
                            selected = s.showSimilar,
                            onClick = { vm.setSimilar(!s.showSimilar, items.orEmpty()) },
                            label = { Text("Similar photos") },
                        )
                    }
                    if (s.showSimilar) {
                        Text("How similar", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                DuplicateFinder.STRICT to "Nearly identical",
                                DuplicateFinder.NORMAL to "Very similar",
                                DuplicateFinder.LOOSE to "Somewhat similar",
                            ).forEach { (bits, label) ->
                                FilterChip(
                                    selected = s.sensitivity == bits,
                                    onClick = { vm.setSensitivity(bits, items.orEmpty()) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    Text(
                        "Exact copies are byte-for-byte the same file. Similar photos only look alike " +
                            "(burst shots, re-saves, resized copies), so check them before deleting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.scanning == null) {
                        Button(
                            onClick = { vm.scan(items.orEmpty()) },
                            enabled = items != null,
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text(if (s.groups == null) "Scan phone" else "Scan again") }
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            s.scanning?.let { p -> item(key = "progress") { ScanProgressCard(p, onStop = vm::stop) } }

            val groups = s.visibleGroups
            if (s.groups != null && s.scanning == null) {
                item(key = "summary") {
                    val exactCount = groups.sumOf { g -> s.visibleOthers(g).count { it.exact } }
                    val similarCount = groups.sumOf { g -> s.visibleOthers(g).count { !it.exact } }
                    Column {
                        Text(
                            if (groups.isEmpty()) "No duplicates found" else plural(groups.size, "group"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (groups.isNotEmpty()) {
                            Text(
                                listOfNotNull(
                                    exactCount.takeIf { it > 0 }?.let { plural(it, "exact copy", "exact copies") },
                                    similarCount.takeIf { it > 0 }?.let { plural(it, "similar photo") },
                                ).joinToString(" · ") + ". Tap a photo to compare; ★ marks the one kept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (exactCount > 0) {
                                    TextButton(onClick = { vm.selectAll(exact = true, on = true) }) { Text("Tick all exact") }
                                }
                                if (similarCount > 0) {
                                    TextButton(onClick = { vm.selectAll(exact = false, on = true) }) { Text("Tick all similar") }
                                    TextButton(onClick = { vm.selectAll(exact = false, on = false) }) { Text("Untick similar") }
                                }
                            }
                        }
                    }
                }
                items(groups, key = { "g" + it.suggestedKeep }) { g ->
                    DuplicateGroupCard(
                        group = g,
                        keep = s.keepOf(g),
                        others = s.visibleOthers(g),
                        selected = s.selected,
                        backedUp = backedUp,
                        onToggle = vm::toggle,
                        onOpen = { index -> viewer = g to index },
                    )
                }
            }
        }
    }

    viewer?.let { (g, start) ->
        val keep = s.keepOf(g)
        val shown = listOf(keep) + s.visibleOthers(g).map { it.item }
        MediaViewer(
            items = viewerItemsFor(shown, backedUp) { item ->
                if (item.id == keep.id) "★ Kept"
                else g.relation(item, keep).let { if (it.exact) "Exact copy" else "Similar ${it.similarity}%" }
            },
            startIndex = start,
            onDismiss = { viewer = null },
            actions = { current ->
                val item = shown.firstOrNull { it.id == current.key } ?: return@MediaViewer
                if (item.id == keep.id) {
                    Text("This is the one kept", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(12.dp))
                } else {
                    TextButton(onClick = { vm.keep(g, item) }) {
                        Icon(Icons.Filled.Star, null)
                        Text("Keep this instead", Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { vm.toggle(item.id) }) {
                        Text(if (item.id in s.selected) "Don't remove" else "Remove")
                    }
                }
            },
        )
    }

    if (confirm) {
        val exact = chosen.count { it.exact }
        val similar = chosen.size - exact
        ToolDeleteDialog(
            count = chosen.size,
            bytes = chosenBytes,
            lines = listOfNotNull(
                exact.takeIf { it > 0 }?.let {
                    "${plural(it, "exact copy", "exact copies")}: identical to the file being kept, nothing is lost."
                },
                similar.takeIf { it > 0 }?.let {
                    "${plural(it, "similar photo")}: not identical to the one kept. Make sure you don't need ${if (it == 1) "it" else "them"}."
                },
            ),
            notBackedUp = chosen.count { !backedUp.has(it.item) },
            toTrash = toTrash,
            onConfirm = {
                confirm = false
                scope.launch { runRequest(chosen.map { it.item.target() }, tools.deleteAction()) }
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateFinder.Group,
    keep: MediaItem,
    others: List<DuplicateFinder.Match>,
    selected: Set<Long>,
    backedUp: Map<Long, Long>,
    onToggle: (Long) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val exact = others.count { it.exact }
    val kind = when {
        exact == others.size -> "Exact copies"
        exact == 0 -> "Similar photos"
        else -> "Exact copies and similar photos"
    }
    val frees = others.filter { it.item.id in selected }.sumOf { it.item.size }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp).animateContentSize()) {
            Row {
                Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text("$kind · ${others.size + 1}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (frees > 0) "Removing the ticked ones frees ${formatSize(frees)}" else "Nothing ticked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                item(key = keep.id) {
                    ThumbWithCaption(keep, caption = "★ Keep", strong = true, selected = false, selectable = false, backedUp = backedUp, onClick = { onOpen(0) }, onToggle = {})
                }
                items(others.size) { i ->
                    val m = others[i]
                    ThumbWithCaption(
                        m.item,
                        caption = if (m.exact) "Exact copy" else "Similar ${m.similarity}%",
                        strong = false,
                        selected = m.item.id in selected,
                        selectable = true,
                        backedUp = backedUp,
                        onClick = { onOpen(i + 1) },
                        onToggle = { onToggle(m.item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbWithCaption(
    item: MediaItem,
    caption: String,
    strong: Boolean,
    selected: Boolean,
    selectable: Boolean,
    backedUp: Map<Long, Long>,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Column(Modifier.width(124.dp)) {
        ToolThumb(
            uri = item.uri,
            isVideo = item.isVideo,
            selected = selected,
            backedUp = backedUp.has(item),
            label = caption,
            labelStrong = strong,
            selectable = selectable,
            onClick = onClick,
            onToggle = onToggle,
        )
        Text(
            item.relativePath,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            formatSize(item.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scan progress in the same card style as the backup banner. */
@Composable
private fun ScanProgressCard(p: DuplicateFinder.Progress, onStop: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp).animateContentSize()) {
            Header(
                icon = Icons.Filled.ContentCopy,
                title = p.step,
                subtitle = if (p.total > 0) "${p.done} of ${p.total}" else "Getting ready…",
                busy = true,
                onStop = onStop,
            )
            if (p.total > 0) {
                val fraction = (p.done.toFloat() / p.total).coerceIn(0f, 1f)
                val shown by animateFloatAsState(fraction, tween(600, easing = FastOutSlowInEasing), label = "scan")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    SmoothBar(shown, height = 10.dp, modifier = Modifier.weight(1f))
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            } else {
                SmoothBar(null, height = 6.dp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            Text(
                "The first scan reads every photo; later scans only look at new ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
