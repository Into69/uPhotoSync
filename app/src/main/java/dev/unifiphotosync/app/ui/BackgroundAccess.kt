package dev.unifiphotosync.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Snapshot of the background-related permissions; re-read whenever the screen resumes. */
private data class AccessState(
    val media: MediaAccess,
    val notifications: Boolean,
    val battery: Boolean,
    val restricted: Boolean,
) {
    val allGood get() = media == MediaAccess.Full && notifications && battery && !restricted

    companion object {
        fun read(context: Context) = AccessState(
            media = AppPermissions.mediaAccess(context),
            notifications = AppPermissions.notificationsAllowed(context),
            battery = AppPermissions.batteryUnrestricted(context),
            restricted = AppPermissions.backgroundRestricted(context),
        )
    }
}

/**
 * Status and fix buttons for everything backups need to run in the background:
 * full photo access, notifications (for the foreground upload service), battery
 * optimization exemption (so Doze doesn't hold back scheduled runs), and no
 * background restriction.
 */
@Composable
fun BackgroundAccessPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Bumped to re-read state after returning from a permission dialog or system settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val state = remember(refresh) { AccessState.read(context) }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Denied for good (or "selected photos" again): the system settings page is the only way left.
        if (AppPermissions.mediaAccess(context) != MediaAccess.Full) {
            context.startActivity(AppPermissions.appSettingsIntent(context))
        }
        refresh++
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) context.startActivity(AppPermissions.notificationSettingsIntent(context))
        refresh++
    }

    Column(modifier) {
        AccessRow(
            ok = state.media == MediaAccess.Full,
            title = "Access to all photos and videos",
            detail = when (state.media) {
                MediaAccess.Full -> "Allowed"
                MediaAccess.Partial -> "Only some photos are allowed, so only those get backed up"
                MediaAccess.None -> "Not allowed. Nothing can be backed up."
            },
            action = "Allow all",
            onFix = { mediaLauncher.launch(AppPermissions.mediaRequest()) },
        )
        AccessRow(
            ok = state.notifications,
            title = "Notifications",
            detail = if (state.notifications) "Allowed" else "Needed to show upload progress and keep long backups running",
            action = "Allow",
            onFix = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(AppPermissions.notificationSettingsIntent(context))
                }
            },
        )
        AccessRow(
            ok = state.battery,
            title = "Unrestricted battery use",
            detail = if (state.battery) "Allowed"
            else "Without it, Android can delay scheduled backups for hours while the phone is idle",
            action = "Allow",
            onFix = {
                runCatching { context.startActivity(AppPermissions.batteryExemptionIntent(context)) }
                    .onFailure { context.startActivity(AppPermissions.appSettingsIntent(context)) }
            },
        )
        AccessRow(
            ok = !state.restricted,
            title = "Background activity",
            detail = if (!state.restricted) "Allowed"
            else "Restricted in system settings. Backups can't run in the background.",
            action = "Open settings",
            onFix = { context.startActivity(AppPermissions.appSettingsIntent(context)) },
        )
    }
}

@Composable
private fun AccessRow(ok: Boolean, title: String, detail: String, action: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (ok) "OK" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) FilledTonalButton(onClick = onFix) { Text(action) }
    }
}

/**
 * Shown when automatic or scheduled backup is switched on while something it needs is
 * missing. Closes by itself once everything is allowed.
 */
@Composable
fun BackgroundAccessDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val ready = remember(refresh) { AppPermissions.readyForBackground(context) }
    LaunchedEffect(ready) { if (ready) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow background backups") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "To back up on its own, even when the app is closed, uPhotoSync needs:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                BackgroundAccessPanel()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
