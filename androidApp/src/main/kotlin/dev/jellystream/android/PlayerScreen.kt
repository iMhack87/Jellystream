package dev.jellystream.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Outlives any composable: the final Stopped report is launched from
 * onDispose, at which point the screen's own coroutine scope is already
 * being cancelled — a screen-tied scope would drop the request.
 */
private val playbackReportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(api: JellyfinApi, item: BaseItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val streamUrl = remember { api.streamUrl(item) }
    if (streamUrl == null) {
        // No session — nothing to play; close via an effect, never mid-composition
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val player = remember {
        // Token travels as a header, never in the URL (proxy/player logs)
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            api.streamAuthorizationHeader()?.let {
                setDefaultRequestProperties(mapOf("Authorization" to it))
            }
        }
        // Hardware/platform decoders first; FFmpeg fills the gaps the device
        // can't decode (TrueHD, DTS-HD MA, …) — Direct Play without transcode
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
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
                    JellyfinApi.millisecondsToTicks(player.currentPosition),
                    isPaused = !player.isPlaying,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val positionTicks = JellyfinApi.millisecondsToTicks(player.currentPosition)
            player.release()
            playbackReportScope.launch {
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
                // Opens the built-in text-track dialog; audio tracks live
                // under the settings (gear) button of the same controller
                setShowSubtitleButton(true)
            }
        },
    )
}
