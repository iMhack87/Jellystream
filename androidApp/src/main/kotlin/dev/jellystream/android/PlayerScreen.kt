package dev.jellystream.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TICKS_PER_MS = 10_000L

@Composable
fun PlayerScreen(api: JellyfinApi, item: BaseItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val streamUrl = remember { api.streamUrl(item) } ?: run {
        onClose()
        return
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            val resumeMs = (item.resumePositionSeconds * 1000).toLong()
            if (resumeMs > 0) seekTo(resumeMs)
            playWhenReady = true
        }
    }

    // Report start once, then position every 5 s while the screen is up
    LaunchedEffect(item.id) {
        runCatching { api.reportPlaybackStart(item.id) }
        while (true) {
            delay(5_000)
            runCatching {
                api.reportPlaybackProgress(
                    item.id,
                    player.currentPosition * TICKS_PER_MS,
                    isPaused = !player.isPlaying,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val positionTicks = player.currentPosition * TICKS_PER_MS
            player.release()
            // Fire-and-forget: the resume point must survive closing the player
            scope.launch {
                runCatching { api.reportPlaybackStopped(item.id, positionTicks) }
            }
        }
    }

    BackHandler(onBack = onClose)

    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                keepScreenOn = true
            }
        },
    )
}
