package dev.jellystream.android

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.RemotePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Chromecast talks to the Default Media Receiver with an HLS URL. The
 * phone keeps Direct Play; the TV cannot read MKV, so the stream is the
 * transcode the server already knows how to produce.
 *
 * Play Services is optional: a Fire TV / AOSP build simply hides the
 * button rather than crashing at launch.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(RemotePlayback.CAST_DEFAULT_RECEIVER)
            .setCastMediaOptions(CastMediaOptions.Builder().build())
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> =
        emptyList()
}

fun castAvailable(context: Context): Boolean =
    GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

@Composable
fun CastRouteButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!castAvailable(context)) return
    AndroidView(
        modifier = modifier.size(40.dp),
        factory = { ctx ->
            val themed = android.view.ContextThemeWrapper(
                ctx,
                androidx.appcompat.R.style.Theme_AppCompat_NoActionBar,
            )
            MediaRouteButton(themed).also {
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it) }
            }
        },
    )
}

fun listenForCastStart(
    context: Context,
    onStart: () -> Unit,
): () -> Unit {
    if (!castAvailable(context)) return {}
    val manager = runCatching {
        CastContext.getSharedInstance(context).sessionManager
    }.getOrNull() ?: return {}
    val listener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
        override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) = onStart()
        override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) = onStart()
        override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
        override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
    }
    manager.addSessionManagerListener(listener, com.google.android.gms.cast.framework.CastSession::class.java)
    return { manager.removeSessionManagerListener(listener, com.google.android.gms.cast.framework.CastSession::class.java) }
}

fun loadOnCast(
    context: Context,
    api: JellyfinApi,
    item: BaseItem,
    positionMs: Long,
) {
    if (!castAvailable(context)) return
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch {
        val session = runCatching {
            CastContext.getSharedInstance(context).sessionManager.currentCastSession
        }.getOrNull() ?: return@launch
        val client = session.remoteMediaClient ?: return@launch
        val plan = runCatching { api.getPlaybackPlan(item, forceTranscode = true) }.getOrNull()
            ?: return@launch
        val url = api.headerlessUrl(plan) ?: return@launch
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.name ?: "")
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("application/x-mpegURL")
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setCurrentTime(positionMs)
            .setAutoplay(true)
            .build()
        runCatching { client.load(request) }
    }
}
