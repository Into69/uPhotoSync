package dev.uphotosync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/** Something the viewer can show: a phone photo (content Uri) or a NAS file (NasFile). */
data class ViewerItem(
    val key: Any,
    /** Coil model: a content Uri or a NasFile. Null shows a placeholder (e.g. NAS videos). */
    val model: Any?,
    val title: String,
    val subtitle: String,
    val isVideo: Boolean,
    /** For videos: what to play, a content Uri (phone) or a NasFile (streamed from the NAS). */
    val video: Any? = null,
)

/**
 * Full-screen viewer: swipe between [items], pinch or double-tap to zoom. [topActions] are
 * small icons beside the title (kept out of the way of the photo); [actions] are a bottom
 * bar, shown only if given. [onPlay] handles tapping a video's play button.
 */
@Composable
fun MediaViewer(
    items: List<ViewerItem>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onPlay: ((ViewerItem) -> Unit)? = null,
    topActions: (@Composable RowScope.(ViewerItem) -> Unit)? = null,
    actions: (@Composable RowScope.(ViewerItem) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onDismiss)
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
        // Zoom per page; swiping is turned off while the visible photo is zoomed in, so a drag pans instead.
        val zoom = remember { mutableStateMapOf<Int, Float>() }
        var showChrome by remember { mutableStateOf(true) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = (zoom[pager.currentPage] ?: 1f) <= 1f,
                key = { items[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                if (item.isVideo && item.video != null) {
                    VideoPage(item, isCurrent = page == pager.currentPage, onTap = { showChrome = !showChrome })
                    return@HorizontalPager
                }
                ZoomablePage(
                    item = item,
                    onScale = { zoom[page] = it },
                    onTap = { showChrome = !showChrome },
                    onPlay = onPlay,
                )
            }

            val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
            if (showChrome) {
                // Top: close, name and details.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding(),
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(current.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            current.subtitle, color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "${pager.currentPage + 1} / ${items.size}", color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = if (topActions == null) 16.dp else 0.dp),
                    )
                    topActions?.invoke(this, current)
                }
                // Bottom: actions for this item, if the caller has any.
                if (actions != null) {
                    Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f)).navigationBarsPadding().padding(vertical = 4.dp),
                    ) { actions.invoke(this, current) }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePage(
    item: ViewerItem,
    onScale: (Float) -> Unit,
    onTap: () -> Unit,
    onPlay: ((ViewerItem) -> Unit)?,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun setScale(s: Float) {
        scale = s.coerceIn(1f, 5f)
        if (scale == 1f) offset = Offset.Zero
        onScale(scale)
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(item.key) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (!item.isVideo) setScale(if (scale > 1f) 1f else 2.5f) },
                )
            }
            .pointerInput(item.key) {
                if (item.isVideo) return@pointerInput
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    setScale(scale * gestureZoom)
                    if (scale > 1f) offset += pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (item.model != null) {
            AsyncImage(
                model = item.model,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        } else {
            Icon(Icons.Filled.Movie, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(96.dp))
        }
        if (item.isVideo && onPlay != null) {
            IconButton(onClick = { onPlay(item) }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Filled.PlayCircle, "Play", tint = Color.White, modifier = Modifier.size(80.dp))
            }
        }
    }
}
