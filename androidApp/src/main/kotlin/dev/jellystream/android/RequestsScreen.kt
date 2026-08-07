package dev.jellystream.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jellystream.shared.JellyseerrApi
import dev.jellystream.shared.JellyseerrRequest
import dev.jellystream.shared.JellyseerrResult
import dev.jellystream.shared.RequestOutcome
import dev.jellystream.shared.RequestState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ask for what the library does not have, and see what you asked for.
 *
 * One screen, two modes: an empty search box shows the requests you have
 * already made, and typing turns it into a catalogue. Splitting them into
 * two screens would mean navigating away to check whether you already
 * asked for something.
 */
@Composable
fun RequestsScreen(
    seerr: JellyseerrApi,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JellyseerrResult>>(emptyList()) }
    var mine by remember { mutableStateOf<List<JellyseerrRequest>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Optimistic per-title state: the grid must react before the server
    // has caught up, or the button looks broken
    val justRequested = remember { mutableStateMapOf<Int, RequestState>() }
    val scope = rememberCoroutineScope()

    // Debounced: a search per keystroke would hammer the server, and on a
    // remote every letter is several presses anyway
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        results = seerr.search(query)
        searching = false
    }

    LaunchedEffect(Unit) { mine = seerr.myRequests() }

    fun request(tmdbId: Int, isSeries: Boolean, title: String) {
        scope.launch {
            when (val outcome = seerr.request(tmdbId, isSeries)) {
                is RequestOutcome.Sent -> {
                    justRequested[tmdbId] = RequestState.PENDING
                    notice = "Requested $title"
                    mine = seerr.myRequests()
                }
                is RequestOutcome.AlreadyRequested -> {
                    justRequested[tmdbId] = RequestState.PENDING
                    notice = "$title was already requested"
                }
                is RequestOutcome.NotSignedIn ->
                    notice = "Sign in to Jellyseerr again in Settings"
                is RequestOutcome.Failed -> notice = outcome.message
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CinemaColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search for something to request") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvDefaultFocus(),
                )
            }

            notice?.let { message ->
                item(key = "notice") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CinemaColors.Surface)
                            .padding(12.dp),
                    )
                }
            }

            if (query.isBlank()) {
                item(key = "mine-header") { SectionLabel("Your requests") }
                if (mine.isEmpty()) {
                    item(key = "mine-empty") {
                        Text(
                            "Nothing requested yet. Search above to ask for a film or a series.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextSecondary,
                        )
                    }
                }
                items(mine, key = { "req-${it.id}" }) { request ->
                    RequestRow(request)
                }
            } else {
                if (searching) {
                    item(key = "spinner") {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (results.isEmpty()) {
                    item(key = "no-results") {
                        Text(
                            "Nothing found for \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextSecondary,
                        )
                    }
                }
                items(results, key = { "res-${it.id}" }) { result ->
                    ResultRow(
                        result = result,
                        state = justRequested[result.id] ?: result.state,
                        onRequest = { request(result.id, result.isSeries, result.displayTitle) },
                    )
                }
            }
        }

        FloatingNavButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = CinemaColors.TextSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun ResultRow(
    result: JellyseerrResult,
    state: RequestState,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaColors.Surface)
            .then(
                // Only a title you can actually ask for is a target
                if (state.canRequest) {
                    Modifier
                        .dpadFocusEffect(RoundedCornerShape(12.dp), scaleOnFocus = false)
                        .clickable { onRequest() }
                } else {
                    Modifier
                }
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = JellyseerrApi.posterUrl(result.posterPath, 185),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(64.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CinemaColors.SurfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = CinemaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                result.year,
                if (result.isSeries) "Series" else "Film",
            ).joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = CinemaColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        StateChip(state)
    }
}

@Composable
private fun RequestRow(request: JellyseerrRequest) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // The requests endpoint carries no title, only a TMDb id —
                // saying what kind of thing it is beats showing a number
                if (request.isSeries) "Series request" else "Film request",
                style = MaterialTheme.typography.titleMedium,
                color = CinemaColors.TextPrimary,
            )
            request.createdAt?.take(10)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
            }
        }
        StateChip(request.state)
    }
}

/** The one place a request state turns into something on screen. */
@Composable
private fun StateChip(state: RequestState) {
    val tint = when (state) {
        RequestState.AVAILABLE -> CinemaColors.CriticFresh
        RequestState.DECLINED -> MaterialTheme.colorScheme.error
        RequestState.REQUESTABLE -> CinemaColors.TextPrimary
        else -> CinemaColors.TextSecondary
    }
    Text(
        state.label,
        style = MaterialTheme.typography.labelLarge,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CinemaColors.SurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
