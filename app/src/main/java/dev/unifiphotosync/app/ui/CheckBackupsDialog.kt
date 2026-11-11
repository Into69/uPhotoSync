package dev.unifiphotosync.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Options for "Check backups": quick or full, and whether to fix what it finds. */
@Composable
fun CheckBackupsDialog(onStart: (full: Boolean, repair: Boolean) -> Unit, onDismiss: () -> Unit) {
    var full by remember { mutableStateOf(false) }
    var repair by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check backups") },
        text = {
            Column {
                Text(
                    "Compares this phone with the NAS: every photo and video in your backup albums should " +
                        "have a copy there, including any deleted on the NAS since.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Option(
                    selected = !full,
                    title = "Quick",
                    detail = "Each file is there with the right size. Takes seconds.",
                    onClick = { full = false },
                )
                Option(
                    selected = full,
                    title = "Full",
                    detail = "Also reads every file back and compares it byte for byte. Catches damaged copies, " +
                        "but transfers your whole backup, so it can take a long time.",
                    onClick = { full = true },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { repair = !repair }.padding(top = 8.dp),
                ) {
                    Checkbox(checked = repair, onCheckedChange = { repair = it })
                    Column {
                        Text("Fix problems")
                        Text(
                            "Upload anything missing or different again. Nothing on the NAS is deleted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(full, repair) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Option(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
