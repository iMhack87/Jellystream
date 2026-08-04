package dev.jellystream.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.PlaybackPlan
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
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var forceTranscode by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(item.id, forceTranscode) {
        plan = api.getPlaybackPlan(item, forceTranscode)
    }

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val currentPlan = plan
        if (failed) {
            // Both Direct Play and the server transcode failed — say so
            // instead of leaving a frozen black screen
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("This item could not be played", color = Color.White)
                Button(onClick = onClose) { Text("Close") }
            }
        } else if (currentPlan == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            // key() tears the player down and rebuilds it when the plan
            // changes (Direct Play -> transcode fallback)
            key(currentPlan.url) {
                PlayerSurface(
                    api = api,
                    item = item,
                    plan = currentPlan,
                    onDirectPlayFailed = {
                        if (!currentPlan.isTranscode && !forceTranscode) {
                            forceTranscode = true
                            plan = null
                        } else {
                            failed = true
                        }
                    },
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    api: JellyfinApi,
    item: BaseItem,
    plan: PlaybackPlan,
    onDirectPlayFailed: () -> Unit,
) {
    val context = LocalContext.current

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
                val subtitleConfigs = plan.externalSubtitles.mapNotNull { sub ->
                    val mime = subtitleMimeType(sub.codec) ?: return@mapNotNull null
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                        .setMimeType(mime)
                        .setLanguage(sub.language)
                        .setLabel(sub.title ?: sub.language ?: "External")
                        .build()
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(plan.url)
                    .setSubtitleConfigurations(subtitleConfigs)
                    .build()
                setMediaItem(mediaItem)
                prepare()
                // A transcode already starts at the resume point server-side
                // (StartTimeTicks); seeking again would double-apply it
                val resumeMs = (item.resumePositionSeconds * 1000).toLong()
                if (resumeMs > 0 && !plan.isTranscode) seekTo(resumeMs)
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onDirectPlayFailed()
                    }
                })
            }
    }

    // Report start once, then position every 5 s while the screen is up
    LaunchedEffect(item.id) {
        runCatching { api.reportPlaybackStart(item.id, plan.playSessionId) }
        while (true) {
            delay(5_000)
            runCatching {
                api.reportPlaybackProgress(
                    item.id,
                    JellyfinApi.millisecondsToTicks(player.currentPosition),
                    isPaused = !player.isPlaying,
                    playSessionId = plan.playSessionId,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val positionTicks = JellyfinApi.millisecondsToTicks(player.currentPosition)
            val playSessionId = plan.playSessionId
            player.release()
            playbackReportScope.launch {
                // PlaySessionId lets the server kill any transcode job
                runCatching { api.reportPlaybackStopped(item.id, positionTicks, playSessionId) }
            }
        }
    }

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

private fun subtitleMimeType(codec: String?): String? = when (codec?.lowercase()) {
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    else -> null
}
