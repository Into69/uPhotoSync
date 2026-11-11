package dev.uphotosync.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.uphotosync.app.data.SortOrder
import dev.uphotosync.app.data.ViewMode
import java.text.DateFormat
import java.util.Date

/** Top-bar button that flips between thumbnails and details; shows the mode you'd switch to. */
@Composable
fun ViewModeToggle(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    when (mode) {
        ViewMode.Thumbnails -> IconButton(onClick = { onChange(ViewMode.Details) }) {
            Icon(Icons.AutoMirrored.Filled.ViewList, "Show details")
        }
        ViewMode.Details -> IconButton(onClick = { onChange(ViewMode.Thumbnails) }) {
            Icon(Icons.Filled.GridView, "Show thumbnails")
        }
    }
}

/** Top-bar sort button with a menu of [SortOrder]s; the current one is ticked. */
@Composable
fun SortMenu(current: SortOrder, onChange: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    onClick = { onChange(order); open = false },
                    trailingIcon = { if (order == current) Icon(Icons.Filled.Check, "Selected") },
                )
            }
        }
    }
}

private val dateFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

fun formatDate(millis: Long): String = if (millis > 0) dateFormat.format(Date(millis)) else ""
