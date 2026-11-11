package dev.unifiphotosync.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import dev.unifiphotosync.app.data.ViewMode
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

private val dateFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

fun formatDate(millis: Long): String = if (millis > 0) dateFormat.format(Date(millis)) else ""
