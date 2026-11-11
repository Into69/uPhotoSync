package dev.uphotosync.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.uphotosync.app.PhotoSyncApp
import dev.uphotosync.app.smb.NasDataSource
import dev.uphotosync.app.smb.NasFile

/**
 * A video in the viewer: its thumbnail with a play button, then the video itself with the
 * standard controls. Phone videos play from the phone; NAS videos stream over SMB. Swiping to
 * another page pauses it, and the player is released when the page goes away.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPage(item: ViewerItem, isCurrent: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    var player by remember(item.key) { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(item.key) {
        onDispose {
            player?.release()
            player = null
        }
    }
    LaunchedEffect(isCurrent) { if (!isCurrent) player?.pause() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val p = player
        if (p == null) {
            if (item.model != null) {
                AsyncImage(
                    model = item.model,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(item.key) { detectTapGestures(onTap = { onTap() }) },
                )
            } else {
                Icon(Icons.Filled.Movie, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(96.dp))
            }
            IconButton(onClick = { player = createPlayer(context, item.video) }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Filled.PlayCircle, "Play", tint = Color.White, modifier = Modifier.size(80.dp))
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = p
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun createPlayer(context: Context, video: Any?): ExoPlayer {
    val exo = ExoPlayer.Builder(context).build()
    when (video) {
        is NasFile -> {
            // Streamed straight from the NAS: starts at once, and seeking reads just that part.
            val nas = (context.applicationContext as PhotoSyncApp).graph.nas
            val source = ProgressiveMediaSource.Factory(NasDataSource.Factory(nas))
                .createMediaSource(MediaItem.fromUri(NasDataSource.uriFor(video.path)))
            exo.setMediaSource(source)
        }
        is Uri -> exo.setMediaItem(MediaItem.fromUri(video))
    }
    exo.prepare()
    exo.playWhenReady = true
    return exo
}
