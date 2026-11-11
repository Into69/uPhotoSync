package dev.uphotosync.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.uphotosync.app.data.BackupPrefs
import dev.uphotosync.app.data.WifiNetwork

/**
 * Under "Only on Wi-Fi": limit automatic and scheduled backups to named networks (SSIDs).
 * An empty list means any Wi-Fi network.
 */
@Composable
fun WifiNetworkSettings(prefs: BackupPrefs, enabled: Boolean, onChange: (BackupPrefs) -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    fun addCurrent() {
        val ssid = WifiNetwork.currentSsid(context)
        note = when {
            ssid == null && !AppPermissions.locationEnabled(context) -> "Turn on location so Android shows the Wi-Fi name"
            ssid == null -> "Not connected to Wi-Fi, or Android is hiding its name"
            ssid in prefs.wifiSsids -> "\"$ssid\" is already in the list"
            else -> null.also { onChange(prefs.copy(wifiSsids = prefs.wifiSsids + ssid)) }
        }
    }

    // Reading the current Wi-Fi name needs precise location first.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (AppPermissions.preciseLocation(context)) addCurrent()
        else note = "Android only shows the Wi-Fi name with precise location. You can type the name instead."
    }

    Column(Modifier.padding(start = 16.dp)) {
        Text(
            "Only on these Wi-Fi networks" + if (prefs.wifiSsids.isEmpty()) " (any)" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        prefs.wifiSsids.sorted().forEach { ssid ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, null, tint = MaterialTheme.colorScheme.primary)
                Text(ssid, Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = { onChange(prefs.copy(wifiSsids = prefs.wifiSsids - ssid)) }, enabled = enabled) {
                    Icon(Icons.Filled.Close, "Remove $ssid")
                }
            }
        }
        OutlinedButton(
            onClick = {
                note = null
                if (AppPermissions.preciseLocation(context)) addCurrent()
                else locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            },
            enabled = enabled,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(Icons.Filled.Wifi, null)
            Text("Add current network", Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Or type a network name") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    // Wi-Fi names are case-sensitive and may contain spaces, so keep them as typed.
                    val name = typed.trim()
                    onChange(prefs.copy(wifiSsids = prefs.wifiSsids + name))
                    typed = ""
                },
                enabled = enabled && typed.isNotBlank(),
            ) { Text("Add") }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (prefs.wifiSsids.isNotEmpty()) {
            Text(
                "Automatic and scheduled backups are skipped on other networks. \"Back up\" still works anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
