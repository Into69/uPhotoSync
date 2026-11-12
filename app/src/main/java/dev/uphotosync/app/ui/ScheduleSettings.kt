package dev.uphotosync.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.uphotosync.app.backup.BackupSchedule
import dev.uphotosync.app.data.BackupPrefs
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** "Scheduled backup" switch, the time to run, and which days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSettings(prefs: BackupPrefs, onChange: (BackupPrefs) -> Unit) {
    var pickingTime by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Scheduled backup")
                Text(
                    "Back up everything at a set time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = prefs.scheduled, onCheckedChange = { onChange(prefs.copy(scheduled = it)) })
        }
        if (prefs.scheduled) {
            OutlinedButton(onClick = { pickingTime = true }, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Filled.Schedule, null)
                Text(BackupSchedule.formatTime(prefs.scheduleMinutes), Modifier.padding(start = 8.dp))
            }
            // Monday-first; tapping a chip toggles that day.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val on = day.value in prefs.scheduleDays
                    FilterChip(
                        selected = on,
                        onClick = {
                            val days = if (on) prefs.scheduleDays - day.value else prefs.scheduleDays + day.value
                            onChange(prefs.copy(scheduleDays = days))
                        },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                    )
                }
            }
            // How late a run may still start (e.g. waiting to be plugged in) before that day is skipped.
            Text(
                "Start within",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2, 3, 4, 6).forEach { hours ->
                    FilterChip(
                        selected = prefs.scheduleWindowHours == hours,
                        onClick = { onChange(prefs.copy(scheduleWindowHours = hours)) },
                        label = { Text("$hours h") },
                    )
                }
            }
            Text(
                "If it can't start by " +
                    BackupSchedule.formatTime((prefs.scheduleMinutes + prefs.scheduleWindowHours * 60) % (24 * 60)) +
                    " (e.g. not plugged in), that day is skipped",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val next = BackupSchedule.next(LocalDateTime.now(), prefs.scheduleMinutes, prefs.scheduleDays)
            Text(
                if (next == null) "Pick at least one day"
                else "Next: " + next.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
                style = MaterialTheme.typography.bodySmall,
                color = if (next == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pickingTime) {
        TimeDialog(
            minutes = prefs.scheduleMinutes,
            onConfirm = { onChange(prefs.copy(scheduleMinutes = it)); pickingTime = false },
            onDismiss = { pickingTime = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minutes: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = minutes / 60,
        initialMinute = minutes % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup time") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
