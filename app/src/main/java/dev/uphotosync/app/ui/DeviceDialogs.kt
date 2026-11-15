package dev.uphotosync.app.ui

import android.app.Application
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.app.Activity
import android.content.IntentSender
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.text.style.TextOverflow
import dev.uphotosync.app.data.PhoneMediaRequest
import dev.uphotosync.app.data.PhoneStorage
import dev.uphotosync.app.data.SortOrder
import dev.uphotosync.app.data.StorageSpace
import kotlinx.coroutines.flow.asStateFlow
import dev.uphotosync.app.data.ViewMode
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.backup.BackupScope
import dev.uphotosync.app.backup.NAS_SPACE_MARGIN
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import dev.uphotosync.app.data.Album
import dev.uphotosync.app.data.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android's trash for media (createTrashRequest) arrived in Android 11. */
fun trashSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/** "Moved 3 items to the trash…" / "Deleted 3 items from this phone" after a phone delete. */
fun deleteMessage(n: Int, toTrash: Boolean): String {
    val items = if (n == 1) "item" else "items"
    return when {
        n == 0 -> "Nothing deleted"
        toTrash -> "Moved $n $items to the trash (restorable for 30 days)"
        else -> "Deleted $n $items from this phone"
    }
}

@Composable
internal fun UnbackedDeleteDialog(count: Int, toTrash: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Not backed up yet") },
        text = {
            Text(
                "$count of the selected ${if (count == 1) "item isn't" else "items aren't"} on the NAS yet. " +
                    (if (toTrash) "They'll only be in the phone's trash, for 30 days. "
                    else "Deleting from the phone will lose ${if (count == 1) "it" else "them"} for good. ") +
                    "Back up first, or delete anyway?"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete anyway", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AlbumPicker(state: DeviceUiState, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) {
            Text(state.album?.name ?: "All photos", style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All photos") }, onClick = { onSelect(null); open = false })
            state.albums.forEach { a ->
                val backed = state.albumBacked[a.id] ?: 0
                val percent = if (a.count > 0) backed * 100 / a.count else 0
                val complete = backed >= a.count
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${a.name} (${a.count})")
                            Text(
                                if (complete) "All backed up" else "$percent% backed up",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (complete) Icon(Icons.Filled.CloudDone, "All backed up", tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = { onSelect(a.id); open = false },
                )
            }
        }
    }
}

@Composable
internal fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Allow access to your photos and videos so they can be browsed and backed up to your NAS.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text("Allow access") }
    }
}
