package dev.uphotosync.app.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.uphotosync.app.data.CrashReport
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    /** Tab asked for by a notification tap; consumed once shown. */
    private val requestedTab = mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start; after rotation the saved tab wins.
        if (savedInstanceState == null) requestedTab.value = tabFrom(intent)
        setContent {
            AppTheme {
                MainScreen(requestedTab = requestedTab.value, onTabShown = { requestedTab.value = null })
                CrashReportPrompt()
            }
        }
    }

    /** A notification tapped while the app is already open (singleTop) lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFrom(intent)?.let { requestedTab.value = it }
    }

    private fun tabFrom(intent: Intent?): Tab? =
        intent?.getStringExtra(EXTRA_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }

    companion object {
        private const val EXTRA_TAB = "tab"

        /**
         * Tapping a notification: brings the app back (resuming it if it's already running)
         * on [tab].
         */
        fun openIntent(context: Context, tab: Tab): PendingIntent = PendingIntent.getActivity(
            context,
            tab.ordinal, // one PendingIntent per tab, so their extras don't overwrite each other
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TAB, tab.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** After a crash, the next launch offers to share what went wrong (nothing is sent otherwise). */
@Composable
private fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReport.pending(context)) }
    val text = report ?: return
    fun dismiss() {
        CrashReport.clear(context)
        report = null
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("uPhotoSync closed unexpectedly") },
        text = { Text("The last time it ran, the app crashed. Share the error report so the problem can be fixed?") },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "uPhotoSync crash report")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, "Share crash report")) }
                dismiss()
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("Not now") } },
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

enum class Tab(val label: String, val icon: ImageVector) {
    Device("Device", Icons.Filled.PhotoLibrary),
    Nas("NAS", Icons.Filled.Storage),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
private fun MainScreen(requestedTab: Tab?, onTabShown: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Device) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onTabShown()
        }
    }
    Scaffold(
        // Only the bottom bar's space is handled here. Each tab's own top app bar pads for
        // the status bar; if this Scaffold consumed it, the tabs would draw under it.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            when (tab) {
                Tab.Device -> DeviceScreen(onOpenSettings = { tab = Tab.Settings })
                Tab.Nas -> NasScreen(onOpenSettings = { tab = Tab.Settings })
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}
