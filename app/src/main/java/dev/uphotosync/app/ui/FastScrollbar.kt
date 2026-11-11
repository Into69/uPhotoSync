package dev.uphotosync.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 48.dp
private val TOUCH_WIDTH = 36.dp
private const val HIDE_AFTER_MS = 1500L

/**
 * A scroll indicator for long lists: a slim thumb at the right edge that shows while scrolling
 * and fades out after a moment. Dragging the thumb jumps through the list, with a bubble
 * showing where you are ([label], given how far down the list, 0 to 1). Only the thumb itself
 * takes touches, and only while shown, so the items under it work as usual.
 */
@Composable
fun BoxScope.FastScrollbar(
    totalItems: Int,
    firstVisible: Int,
    visibleItems: Int,
    scrolling: Boolean,
    onJump: (Float) -> Unit,
    label: ((Float) -> String?)? = null,
) {
    // Short lists don't need one.
    if (totalItems <= 0 || visibleItems <= 0 || totalItems <= visibleItems * 2) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true
        else {
            delay(HIDE_AFTER_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(250), label = "scrollbar")
    val thumbWidth by animateDpAsState(if (dragging) 8.dp else 5.dp, label = "thumb")

    val scrollFraction = (firstVisible.toFloat() / (totalItems - visibleItems).coerceAtLeast(1)).coerceIn(0f, 1f)
    val fraction = if (dragging) dragFraction else scrollFraction
    val currentScrollFraction by rememberUpdatedState(scrollFraction)
    val jump by rememberUpdatedState(onJump)

    var trackHeight by remember { mutableIntStateOf(0) }
    val thumbPx = with(LocalDensity.current) { THUMB_HEIGHT.toPx() }
    val thumbY = ((trackHeight - thumbPx).coerceAtLeast(0f) * fraction).roundToInt()

    Box(
        Modifier.align(Alignment.TopEnd).fillMaxHeight().width(TOUCH_WIDTH)
            .onSizeChanged { trackHeight = it.height },
    ) {
        Box(
            Modifier
                .offset { IntOffset(0, thumbY) }
                .align(Alignment.TopEnd)
                .size(TOUCH_WIDTH, THUMB_HEIGHT)
                .then(
                    if (!shown) Modifier
                    else Modifier.pointerInput(totalItems, trackHeight) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragFraction = currentScrollFraction
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, dy ->
                            change.consume()
                            val span = (trackHeight - thumbPx).coerceAtLeast(1f)
                            dragFraction = (dragFraction + dy / span).coerceIn(0f, 1f)
                            jump(dragFraction)
                        }
                    }
                ),
        ) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).width(thumbWidth).height(THUMB_HEIGHT)
                    .alpha(alpha)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.7f),
                        RoundedCornerShape(50),
                    ),
            )
        }
    }

    // Where you are, next to the thumb while dragging: a month, a letter or a size.
    val text = if (dragging) label?.invoke(fraction) else null
    if (text != null) {
        val bubbleOffset = with(LocalDensity.current) { (TOUCH_WIDTH + 8.dp).roundToPx() }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-bubbleOffset, thumbY + 6) },
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** [FastScrollbar] for a grid. */
@Composable
fun BoxScope.GridScrollbar(state: LazyGridState, label: ((Float) -> String?)? = null) {
    val scope = rememberCoroutineScope()
    val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visible by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    FastScrollbar(
        totalItems = total,
        firstVisible = state.firstVisibleItemIndex,
        visibleItems = visible,
        scrolling = state.isScrollInProgress,
        onJump = { f -> scope.launch { state.scrollToItem(((total - 1) * f).roundToInt().coerceAtLeast(0)) } },
        label = label,
    )
}

/** [FastScrollbar] for a list. */
@Composable
fun BoxScope.ListScrollbar(state: LazyListState, label: ((Float) -> String?)? = null) {
    val scope = rememberCoroutineScope()
    val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visible by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    FastScrollbar(
        totalItems = total,
        firstVisible = state.firstVisibleItemIndex,
        visibleItems = visible,
        scrolling = state.isScrollInProgress,
        onJump = { f -> scope.launch { state.scrollToItem(((total - 1) * f).roundToInt().coerceAtLeast(0)) } },
        label = label,
    )
}

/**
 * The bubble text for the item [fraction] of the way down [items]: its month when sorted by
 * date, its first letter by name, its size by size.
 */
fun <T> positionLabel(
    items: List<T>,
    fraction: Float,
    sortByDate: Boolean,
    sortByName: Boolean,
    name: (T) -> String,
    date: (T) -> Long,
    size: (T) -> Long,
): String? {
    if (items.isEmpty()) return null
    val item = items[((items.size - 1) * fraction).roundToInt().coerceIn(0, items.lastIndex)]
    return when {
        sortByDate -> java.time.Instant.ofEpochMilli(date(item)).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"))
        sortByName -> name(item).firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        else -> formatSize(size(item))
    }
}
