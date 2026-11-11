package dev.uphotosync.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.uphotosync.app.data.StorageSpace

/**
 * One slim line with a usage bar: "NAS · 1.2 TB free of 4.0 TB". Turns red when less than
 * 10% is left. Shows nothing until the numbers are known.
 */
@Composable
fun StorageLine(label: String, space: StorageSpace?, isNas: Boolean, modifier: Modifier = Modifier) {
    if (space == null || space.total <= 0) return
    val warn = space.low
    val color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val fraction by animateFloatAsState(space.usedFraction, label = "space")
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isNas) Icons.Filled.Storage else Icons.Filled.PhoneAndroid, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp),
            )
            Text(
                "$label · ${formatSize(space.free)} free of ${formatSize(space.total)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
            if (warn) {
                Text("Low space", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp),
        )
    }
}
