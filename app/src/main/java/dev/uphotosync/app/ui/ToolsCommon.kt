package dev.uphotosync.app.ui

import android.app.Activity
import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.data.MediaItem
import dev.uphotosync.app.data.PhoneMediaRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The phone's media and backup state, shared by every tool on the Tools tab. */
class ToolsViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as PhotoSyncApp).graph
    private val _items = MutableStateFlow<List<MediaItem>?>(null)

    /** Every photo and video on the phone; null until first loaded. */
    val items: StateFlow<List<MediaItem>?> = _items.asStateFlow()

    /** Media id -> size recorded on the NAS (see BackupIndex). */
    val backedUp = graph.index.sizes

    /** Phone deletes go to Android's trash (setting, Android 11+). */
    val toTrash = graph.settings.prefs.map { it.phoneTrash && trashSupported() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, trashSupported())

    val request = PhoneMediaRequest(app)

    fun reload() = viewModelScope.launch { _items.value = graph.media.loadAll() }

    /** Drops [ids] straight away (they're gone), then re-reads the phone to be sure. */
    fun removed(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val gone = ids.toHashSet()
        _items.value = _items.value?.filterNot { it.id in gone }
        reload()
    }

    suspend fun deleteAction(): PhoneMediaRequest.Action =
        if (graph.settings.prefs.first().phoneTrash) PhoneMediaRequest.Action.Trash else PhoneMediaRequest.Action.Delete
}

fun Map<Long, Long>.has(item: MediaItem) = this[item.id] == item.size

fun MediaItem.target() = PhoneMediaRequest.Target(id, uri)

/**
 * Runs a [PhoneMediaRequest] through Android's prompts. Returns a function that starts a batch;
 * [onFinished] gets the ids acted on (empty if cancelled) and the action used.
 */
@Composable
fun rememberPhoneRequest(
    request: PhoneMediaRequest,
    onFinished: (ids: List<Long>, action: PhoneMediaRequest.Action) -> Unit,
): (List<PhoneMediaRequest.Target>, PhoneMediaRequest.Action) -> Unit {
    val scope = rememberCoroutineScope()
    lateinit var launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    fun showOrFinish(sender: IntentSender?, confirmed: Boolean = false) {
        if (sender != null) {
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        } else if (request.busy) {
            val action = request.action
            onFinished(request.finish(confirmed), action)
        }
    }
    launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> showOrFinish(null, confirmed = ok)
            // Android 10: the user allowed this photo; carry on with the rest.
            ok -> scope.launch { showOrFinish(request.continueDelete()) }
            else -> showOrFinish(null)
        }
    }
    return { targets, action ->
        if (!request.busy) scope.launch { showOrFinish(request.start(targets, action)) }
    }
}

/** Screen frame for one tool: back arrow, title, optional bottom action bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions,
            )
        },
        bottomBar = { bottomBar?.invoke() },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

/** Bottom bar with a summary on the left and one main (destructive) button. */
@Composable
fun ToolActionBar(summary: String, button: String, enabled: Boolean, onClick: () -> Unit) {
    BottomAppBar {
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(end = 12.dp),
        ) { Text(button) }
    }
}

/**
 * A square thumbnail in the Device tab's style: video and backed-up marks, plus a selection
 * circle. [label] is a small caption chip at the bottom (e.g. "Exact copy", "2.1 GB").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolThumb(
    uri: Uri,
    isVideo: Boolean,
    selected: Boolean,
    backedUp: Boolean?,
    label: String?,
    modifier: Modifier = Modifier,
    labelStrong: Boolean = false,
    selectable: Boolean = true,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onToggle),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri).size(320).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (selected) 8.dp else 0.dp),
        )
        if (isVideo) {
            Icon(
                Icons.Filled.PlayCircle, "Video", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(26.dp),
            )
        }
        if (backedUp == true) {
            Icon(
                Icons.Filled.CloudDone, "Backed up", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(3.dp).size(14.dp),
            )
        }
        if (selectable) {
            IconButton(onClick = onToggle, modifier = Modifier.align(Alignment.TopStart).size(36.dp)) {
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface, CircleShape),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Circle, "Not selected", tint = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.25f), CircleShape),
                    )
                }
            }
        }
        if (label != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (labelStrong) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f),
                contentColor = if (labelStrong) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Empty or loading message centred in a tool's content area. */
@Composable
fun ToolMessage(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row { Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** Viewer items for phone media, with the NAS state in the subtitle. */
fun viewerItemsFor(items: List<MediaItem>, backedUp: Map<Long, Long>, extra: (MediaItem) -> String? = { null }) =
    items.map {
        ViewerItem(
            key = it.id,
            model = it.uri,
            title = it.name,
            subtitle = listOfNotNull(
                extra(it),
                formatDate(it.takenAt),
                formatSize(it.size),
                it.relativePath,
                if (backedUp.has(it)) "On the NAS" else "Not backed up",
            ).joinToString(" · "),
            isVideo = it.isVideo,
            video = it.uri.takeIf { _ -> it.isVideo },
        )
    }

/**
 * Confirmation before a tool removes files from the phone. [lines] say what's being removed
 * (e.g. "3 exact copies…"); files not on the NAS get a warning. Android asks once more after.
 */
@Composable
fun ToolDeleteDialog(
    count: Int,
    bytes: Long,
    lines: List<String>,
    notBackedUp: Int,
    toTrash: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (toTrash) "Move ${plural(count, "item")} to the trash?" else "Delete ${plural(count, "item")}?") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (notBackedUp > 0) {
                    Text(
                        "${plural(notBackedUp, "item isn't", "items aren't")} on the NAS. " +
                            if (toTrash) "They'll only be in the phone's trash, for 30 days."
                            else "Deleting will lose ${if (notBackedUp == 1) "it" else "them"} for good.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Frees ${formatSize(bytes)} on this phone" +
                        if (toTrash) " once the trash is emptied (Android does that after 30 days)." else ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(if (toTrash) "Move to trash" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
