package dev.jellystream.android

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jellystream.shared.AnnouncedArrivals
import dev.jellystream.shared.Arrival
import dev.jellystream.shared.RequestedTitle
import kotlinx.coroutines.delay

/** How long one arrival notice stays up. */
private const val ARRIVAL_TOAST_MS = 5_000L

/** How often the app asks Jellyseerr whether anything has landed. */
const val ARRIVAL_POLL_MS = 60_000L

/**
 * App-private storage for the announced-arrivals blob (the shared module
 * owns the JSON). Same shape and the same per-profile key as the downloads
 * and watchlist stores.
 *
 * Without this the app re-announces every title ever requested on each
 * cold start, and the notice becomes something people learn to ignore
 * within a day.
 */
class ArrivalStore(context: Context, profileKey: String) {
    private val prefs =
        context.getSharedPreferences("jellystream_arrivals", Context.MODE_PRIVATE)
    private val key = "arrivals:$profileKey"

    /**
     * Null means this profile has never been polled — the caller turns
     * that into a first look, which announces nothing. A blob we cannot
     * read reads as null too: the cost is one silent poll, never a wrong
     * "has arrived".
     */
    fun load(): AnnouncedArrivals? =
        prefs.getString(key, null)?.let { AnnouncedArrivals.fromJson(it) }

    fun save(announced: AnnouncedArrivals) {
        prefs.edit().putString(key, announced.toJson()).apply()
    }
}

/**
 * The queue of notices waiting for their five seconds.
 *
 * Owned above the whole app rather than by a screen: the notice has to
 * appear over the player, which is an overlay composed *inside* the
 * signed-in subtree, and that subtree is dropped whole on a profile
 * switch.
 */
class ArrivalCenter {
    /**
     * The head is what is on screen; [ArrivalToastHost] pops it when its
     * time is up. One list, so there is no second piece of state that
     * could disagree with it.
     */
    val pending = mutableStateListOf<Arrival>()

    /**
     * What the last poll saw, for whoever else wants it.
     *
     * The home row shows the same requests this poll already fetched, so
     * it reads them from here instead of asking again — one poll, one
     * truth, and no notice saying a title has arrived above a row that
     * still says nought per cent.
     */
    var requests by mutableStateOf<List<RequestedTitle>>(emptyList())
        internal set

    /**
     * Forgets everything. Called when the profile changes: this object
     * outlives the signed-in screen on purpose, so without it the next
     * account inherits the last one's queued notices and requests.
     */
    fun reset() {
        pending.clear()
        requests = emptyList()
    }

    fun announce(arrivals: List<Arrival>) {
        // A title queued but not yet shown must not be queued again by
        // the poll that runs while it waits
        pending.addAll(
            arrivals.filterNot { landed -> pending.any { it.requestId == landed.requestId } }
        )
    }
}

/**
 * The notice itself: a corner card that shows up and goes away again.
 *
 * Deliberately neither focusable nor clickable. On a television anything
 * focusable steals the D-pad mid-episode, and a target that appears under
 * the finger for five seconds is a mis-tap waiting to happen.
 */
@Composable
fun ArrivalToastHost(center: ArrivalCenter, modifier: Modifier = Modifier) {
    val showing = center.pending.firstOrNull()
    // Expiring means popping the head, which re-keys this effect for
    // whatever is behind it — several titles landing in one poll queue up
    // instead of overwriting each other.
    LaunchedEffect(showing) {
        if (showing == null) return@LaunchedEffect
        delay(ARRIVAL_TOAST_MS)
        center.pending.remove(showing)
    }
    if (showing == null) return

    Box(
        modifier = modifier
            // statusBars, not safeDrawing: the IME inset belongs to the
            // latter, and a notice that respects it jumps across the
            // screen the moment a keyboard opens
            .statusBarsPadding()
            // Below the account bar on home and below the subtitle-sync
            // strip in the player: no corner is free on every screen, and
            // the two things this must never cover are the ones people
            // are already reaching for.
            .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 16.dp)
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            showing.message,
            style = MaterialTheme.typography.bodyMedium,
            color = CinemaColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
