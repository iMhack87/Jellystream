package dev.jellystream.android

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.SubtitleView
import dev.jellystream.shared.LanguageCode
import dev.jellystream.shared.MediaStream
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.MediaSegment
import dev.jellystream.shared.PlaybackPlan
import dev.jellystream.shared.SkipSegments
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
    // Seeded from the profile's settings; the Direct Play failure path can
    // still flip it on for this item alone
    val alwaysTranscode = LocalAppSettings.current.alwaysTranscode
    var forceTranscode by remember { mutableStateOf(alwaysTranscode) }
    var failed by remember { mutableStateOf(false) }
    var segments by remember { mutableStateOf<List<MediaSegment>>(emptyList()) }

    LaunchedEffect(item.id, forceTranscode) {
        plan = api.getPlaybackPlan(item, forceTranscode)
    }

    // Intro/outro markers — getMediaSegments returns [] when the server has
    // no segment provider, so the button simply never shows
    LaunchedEffect(item.id) {
        segments = api.getMediaSegments(item.id)
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
                    segments = segments,
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

        // Visible exit affordance, same as iOS — the back gesture works too
        FloatingNavButton(
            onClick = onClose,
            icon = Icons.Default.Close,
            contentDescription = "Close player",
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    api: JellyfinApi,
    item: BaseItem,
    plan: PlaybackPlan,
    segments: List<MediaSegment>,
    onDirectPlayFailed: () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val subtitleScale = settings.subtitleScale

    // Segments are in media time; the plan says where the stream clock
    // starts (transcode windows open at the resume point)
    val positionOffsetSeconds = plan.startOffsetSeconds

    // Decided in shared from this profile's preference and the audio that
    // will play; null means "start with subtitles off"
    val desiredSubtitle = remember(plan, settings) {
        settings.chooseSubtitle(plan.subtitleStreams, plan.audioLanguage)
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

                    // The track list only exists once demuxing has started,
                    // which is why the default is applied here and not at
                    // build time. It arrives in instalments though — the
                    // first callback carried video and audio only, and
                    // settling for it left ExoPlayer's own pick (the forced
                    // English track) on screen. So: keep trying until the
                    // wanted track is actually there, then stop for good and
                    // leave the panel to the viewer.
                    private var defaultApplied = false

                    override fun onTracksChanged(tracks: Tracks) {
                        if (defaultApplied) return
                        defaultApplied =
                            applySubtitleDefault(this@apply, tracks, desiredSubtitle)
                    }
                })
            }
    }

    // Reports must be in media time: a resumed transcode's player clock is
    // window-relative, so the raw position would rewind the resume point
    fun mediaPositionTicks(): Long =
        JellyfinApi.millisecondsToTicks(
            player.currentPosition + (positionOffsetSeconds * 1000).toLong()
        )

    // Report start once, then position every 5 s while the screen is up
    LaunchedEffect(item.id) {
        runCatching { api.reportPlaybackStart(item.id, plan.playSessionId) }
        while (true) {
            delay(5_000)
            runCatching {
                api.reportPlaybackProgress(
                    item.id,
                    mediaPositionTicks(),
                    isPaused = !player.isPlaying,
                    playSessionId = plan.playSessionId,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val positionTicks = mediaPositionTicks()
            val playSessionId = plan.playSessionId
            player.release()
            playbackReportScope.launch {
                // PlaySessionId lets the server kill any transcode job
                runCatching { api.reportPlaybackStopped(item.id, positionTicks, playSessionId) }
            }
        }
    }

    // Which segment the playhead is inside right now (null = no button)
    var activeSegment by remember { mutableStateOf<MediaSegment?>(null) }
    LaunchedEffect(segments) {
        while (true) {
            val mediaPositionSeconds =
                positionOffsetSeconds + player.currentPosition / 1000.0
            activeSegment = SkipSegments.activeSegment(segments, mediaPositionSeconds)
            delay(250)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    keepScreenOn = true
                    // Opens the built-in text-track dialog; audio tracks live
                    // under the settings (gear) button of the same controller
                    setShowSubtitleButton(true)
                    // Fraction of the viewport height, so one setting reads
                    // the same on a phone and across the room on a TV
                    subtitleView?.setFractionalTextSize(
                        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale.toFloat()
                    )
                }
            },
        )

        // Retains the last segment so the label survives the exit animation
        var shownSegment by remember { mutableStateOf<MediaSegment?>(null) }
        activeSegment?.let { shownSegment = it }

        AnimatedVisibility(
            visible = activeSegment != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 28.dp, vertical = 56.dp),
        ) {
            SkipSegmentButton(
                label = if (shownSegment?.isOutro == true) "Skip Credits" else "Skip Intro",
                onClick = {
                    val segment = activeSegment ?: return@SkipSegmentButton
                    activeSegment = null
                    player.seekTo(
                        ((segment.endSeconds - positionOffsetSeconds) * 1000).toLong()
                    )
                },
            )
        }
    }
}

/**
 * Apple TV+-style skip pill: translucent over the video, inverts to a solid
 * white capsule when the D-pad focuses it. Grabs focus on appear on TV so
 * a single center press skips; inert focus-wise on touch devices.
 */
@Composable
private fun SkipSegmentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isTv = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        label = "skipFocusScale",
    )

    LaunchedEffect(Unit) {
        if (isTv) focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.55f))
            // Subtle ring so the pill reads over letterboxed (black) video
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Switches on the track the shared picker chose, or leaves subtitles off.
 *
 * Media3 selects text by preference, not by Jellyfin stream index, so the
 * match is made on what both sides actually carry: the language, and
 * whether the track is forced. Side-loaded external subtitles land in the
 * same list, tagged with the language we gave their configuration.
 */
private fun applySubtitleDefault(
    player: Player,
    tracks: Tracks,
    desired: MediaStream?,
): Boolean {
    val base = player.trackSelectionParameters.buildUpon()
    if (desired == null) {
        // Off, not "whatever ExoPlayer would have picked"
        player.trackSelectionParameters = base
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        return true
    }
    val match = tracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { i ->
                val format = group.getTrackFormat(i)
                val forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                LanguageCode.matches(format.language, desired.language) &&
                    forced == desired.isForced
            }?.let { group to it }
        }
        // Not here yet: the text tracks may still be being parsed. Say so,
        // so the caller asks again rather than settling for ExoPlayer's own
        // pick — which prefers a forced track matching the audio.
        ?: return false

    val (group, trackIndex) = match
    player.trackSelectionParameters = base
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .build()
    return true
}

private fun subtitleMimeType(codec: String?): String? = when (codec?.lowercase()) {
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    else -> null
}
