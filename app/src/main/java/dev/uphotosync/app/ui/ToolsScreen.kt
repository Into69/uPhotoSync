package dev.uphotosync.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tool { Duplicates, FreeUp, Largest, Folders, Trash }

/** Tools tab: phone-only clean-up. Nothing here touches the NAS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(vm: ToolsViewModel = viewModel()) {
    var open by rememberSaveable { mutableStateOf<Tool?>(null) }
    var freeUpDays by rememberSaveable { mutableStateOf(FREE_UP_DEFAULT_DAYS) }
    // A notification asked for a tool (e.g. "you can free up 2 GB"): open it once.
    val link by ToolLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        if (l.tool == ToolLink.FREE_UP) {
            if (l.days > 0) freeUpDays = l.days
            open = Tool.FreeUp
        }
        ToolLink.pending.value = null
    }
    // Re-read the phone every time the tab comes back, so counts match what's there now.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.reload() }

    when (open) {
        Tool.Duplicates -> return DuplicatesTool(vm, onBack = { open = null })
        Tool.FreeUp -> return FreeUpSpaceTool(vm, initialDays = freeUpDays, onBack = { open = null })
        Tool.Largest -> return LargestFilesTool(vm, onBack = { open = null })
        Tool.Folders -> return FolderCleanupTool(vm, onBack = { open = null })
        Tool.Trash -> return TrashTool(vm, onBack = { open = null })
        null -> Unit
    }

    val items by vm.items.collectAsStateWithLifecycle()
    val backedUp by vm.backedUp.collectAsStateWithLifecycle()
    val freeable = remember(items, backedUp) {
        val cutoff = System.currentTimeMillis() - FREE_UP_DEFAULT_DAYS * DAY_MS
        items.orEmpty().filter { backedUp.has(it) && it.takenAt < cutoff }.sumOf { it.size }
    }
    val total = remember(items) { items.orEmpty().sumOf { it.size } }

    Scaffold(topBar = { TopAppBar(title = { Text("Tools") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Tidy up photos and videos on this phone. Nothing here changes the NAS." +
                    (items?.let { " ${plural(it.size, "item")} · ${formatSize(total)}." } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            ToolCard(
                Icons.Filled.ContentCopy, "Duplicates",
                "Exact copies and similar photos, side by side, so you can keep the best one",
            ) { open = Tool.Duplicates }
            ToolCard(
                Icons.Filled.CleaningServices, "Free up space",
                "Remove photos and videos that are safely on the NAS" +
                    if (freeable > 0) ". About ${formatSize(freeable)} older than 6 months" else "",
            ) { open = Tool.FreeUp }
            ToolCard(Icons.Filled.Straighten, "Largest files", "Your biggest videos and photos first") { open = Tool.Largest }
            ToolCard(
                Icons.Filled.FolderOpen, "Folder clean-up",
                "Screenshots, WhatsApp, Downloads and other folders that fill up over time",
            ) { open = Tool.Folders }
            if (trashSupported()) {
                ToolCard(
                    Icons.Filled.AutoDelete, "Phone trash",
                    "See what's waiting to be deleted, restore it, or empty it now",
                ) { open = Tool.Trash }
            }
        }
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal const val DAY_MS = 24L * 60 * 60 * 1000
internal const val FREE_UP_DEFAULT_DAYS = 180L
