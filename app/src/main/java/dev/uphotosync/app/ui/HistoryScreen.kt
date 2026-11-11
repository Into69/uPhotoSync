package dev.uphotosync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.data.HistoryEntry
import dev.uphotosync.app.data.HistoryEntry.Outcome

/** Settings > History: the last 200 backups, moves, checks and downloads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val history = (LocalContext.current.applicationContext as PhotoSyncApp).graph.history
    val entries by history.entries.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("History") },
                actions = {
                    if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.padding(padding)) { CenterText("Nothing yet. Backups, moves and checks will be listed here.") }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(entries, key = { it.id }) { e ->
                    HistoryRow(e)
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This only clears the log. Your backups aren't affected.") },
            confirmButton = { TextButton(onClick = { history.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry) {
    // Tap to show the details (and errors, if any).
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = when (e.outcome) {
        Outcome.Success -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        Outcome.Partial -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
        Outcome.Failed -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        Outcome.Skipped -> Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp, 12.dp).animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, e.outcome.name, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(e.kind, style = MaterialTheme.typography.titleSmall)
            Text(formatDate(e.finishedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(e.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            if (expanded) {
                val details = buildList {
                    add("Took " + duration(e.finishedAt - e.startedAt))
                    if (e.bytes > 0) add("Transferred " + formatSize(e.bytes))
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                e.errors.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else if (e.errors.isNotEmpty()) {
                Text(
                    "${e.errors.size} ${if (e.errors.size == 1) "error" else "errors"}, tap for details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun duration(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min ${s % 60}s"
        else -> "${s / 3600} h ${s % 3600 / 60} min"
    }
}
