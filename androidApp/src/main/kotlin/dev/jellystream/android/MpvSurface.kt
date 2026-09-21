package dev.jellystream.android

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.JellyseerrApi
import dev.jellystream.shared.MediaSegment
import dev.jellystream.shared.NextEpisodeOffer
import dev.jellystream.shared.NextSeasonOffer
import dev.jellystream.shared.PlaybackPlan
import dev.jellystream.shared.SkipSegments
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Direct Play via libmpv, used only when Media3 has already refused the
 * file. Same URL and Authorization header as ExoPlayer; a second engine
 * rather than a transcode, which is the whole point of the fallback.
 *
 * Written against `dev.jdtech.mpv.MPVLib` (MIT). Do not copy Findroid's
 * GPL player wrapper.
 */
@Composable
fun MpvPlaybackLayer(
    api: JellyfinApi,
    seerr: JellyseerrApi,
    item: BaseItem,
    plan: PlaybackPlan,
    segments: List<MediaSegment>,
    offer: NextSeasonOffer?,
    nextEpisode: NextEpisodeOffer?,
    onPlayNext: (BaseItem) -> Unit,
    onError: () -> Unit,
) {
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    var positionMs by remember { mutableStateOf(0L) }
    var ended by remember { mutableStateOf(false) }
    var offerDismissed by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var holder by remember { mutableStateOf<MpvHolder?>(null) }
    val isTv = remember {
        context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_LEANBACK,
        )
    }

    fun mediaPositionTicks(): Long =
        JellyfinApi.millisecondsToTicks(
            positionMs + (plan.startOffsetSeconds * 1000).toLong(),
        )

    LaunchedEffect(item.id) {
        runCatching { api.reportPlaybackStart(item.id, plan.playSessionId, plan.playMethod) }
        while (true) {
            delay(5_000)
            runCatching {
                api.reportPlaybackProgress(
                    item.id,
                    mediaPositionTicks(),
                    isPaused = false,
                    playSessionId = plan.playSessionId,
                    playMethod = plan.playMethod,
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val ticks = mediaPositionTicks()
            playbackReportScope.launch {
                runCatching {
                    api.reportPlaybackStopped(item.id, ticks, plan.playSessionId, plan.playMethod)
                }
            }
        }
    }

    var activeSegment by remember { mutableStateOf<MediaSegment?>(null) }
    LaunchedEffect(segments) {
        while (true) {
            val mediaSeconds = plan.startOffsetSeconds + positionMs / 1000.0
            activeSegment = SkipSegments.activeSegment(segments, mediaSeconds)
            delay(250)
        }
    }

    LaunchedEffect(chromeVisible, showStats) {
        if (chromeVisible && !showStats && !isTv) {
            delay(4_000)
            chromeVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { chromeVisible = true },
    ) {
        MpvSurface(
            api = api,
            plan = plan,
            resumeSeconds = item.resumePositionSeconds,
            onPositionMs = { positionMs = it },
            onEnded = { ended = true },
            onError = onError,
            onReady = { holder = it },
        )
        if (chromeVisible || isTv) {
            PlayerToolRow(
                onToggleStats = { showStats = !showStats },
                onChapters = null,
                showCast = !isTv,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
        if (showStats) {
            StatsOverlay(
                stats = plan.stats,
                usingMpv = true,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 72.dp, top = 16.dp),
            )
        }
        if (activeSegment != null && !ended) {
            SkipSegmentButton(
                label = if (activeSegment?.isOutro == true) "Skip Credits" else "Skip Intro",
                onClick = {
                    val segment = activeSegment ?: return@SkipSegmentButton
                    holder?.seekToMs(((segment.endSeconds - plan.startOffsetSeconds) * 1000).toLong())
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 28.dp, vertical = 56.dp),
            )
        }
        if (ended && (nextEpisode != null || offer != null) && !offerDismissed) {
            EndOfEpisodeCard(
                nextEpisode = nextEpisode,
                offer = offer,
                autoPlay = settings.autoPlayNextEpisode,
                seerr = seerr,
                onPlayNext = onPlayNext,
                onDismiss = { offerDismissed = true },
            )
        }
    }
}

@Composable
fun MpvSurface(
    api: JellyfinApi,
    plan: PlaybackPlan,
    resumeSeconds: Double,
    onPositionMs: (Long) -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onReady: (MpvHolder) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = remember {
        MpvHolder(api, plan, resumeSeconds, onPositionMs, onEnded, onError)
    }
    LaunchedEffect(holder) { onReady(holder) }
    DisposableEffect(plan.url) {
        holder.start(context)
        onDispose { holder.destroy() }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.attach(this)
            }
        },
    )
}

class MpvHolder(
    private val api: JellyfinApi,
    private val plan: PlaybackPlan,
    private val resumeSeconds: Double,
    private val onPositionMs: (Long) -> Unit,
    private val onEnded: () -> Unit,
    private val onError: () -> Unit,
) : SurfaceHolder.Callback, MPVLib.EventObserver {
    private var mpv: MPVLib? = null
    private var surfaceView: SurfaceView? = null
    private var ended = false

    fun attach(view: SurfaceView) {
        surfaceView = view
        view.holder.addCallback(this)
    }

    fun start(context: android.content.Context) {
        if (mpv != null) return
        val created = try {
            MPVLib.create(context)
        } catch (t: Throwable) {
            onError()
            return
        } ?: run {
            onError()
            return
        }
        mpv = created
        created.setOptionString("vo", "gpu")
        created.setOptionString("gpu-context", "android")
        created.setOptionString("hwdec", "mediacodec")
        created.setOptionString("keep-open", "yes")
        api.streamAuthorizationHeader()?.let { auth ->
            created.setOptionString("http-header-fields", "Authorization: $auth")
        }
        if (resumeSeconds > 1 && !plan.isTranscode) {
            created.setOptionString("start", resumeSeconds.toString())
        }
        created.init()
        created.addObserver(this)
        created.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        created.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        created.command(arrayOf("loadfile", plan.url))
        plan.externalSubtitles.forEach { sub ->
            created.command(arrayOf("sub-add", sub.url, "auto"))
        }
    }

    fun seekToMs(ms: Long) {
        mpv?.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute"))
    }

    fun destroy() {
        val view = surfaceView
        view?.holder?.removeCallback(this)
        mpv?.detachSurface()
        mpv?.removeObserver(this)
        mpv?.destroy()
        mpv = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mpv?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mpv?.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mpv?.detachSurface()
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Double) {
        if (property == "time-pos") onPositionMs((value * 1000).toLong())
    }
    override fun eventProperty(property: String, value: Boolean) {
        if (property == "eof-reached" && value && !ended) {
            ended = true
            onEnded()
        }
    }
    override fun eventProperty(property: String, value: String) {}
    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE && !ended) {
            // A clean eof is reported via eof-reached; this catches decode
            // failures so we can still fall back to a transcode.
            val remaining = mpv?.getPropertyDouble("time-remaining")
            if (remaining != null && remaining > 1.0) onError()
        }
    }
}
