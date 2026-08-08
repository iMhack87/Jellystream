package dev.jellystream.android

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jellystream.shared.JellyseerrApi
import dev.jellystream.shared.JellyseerrResult
import dev.jellystream.shared.JellyseerrTvDetails
import dev.jellystream.shared.RequestOutcome
import dev.jellystream.shared.RequestProgress
import dev.jellystream.shared.RequestState
import dev.jellystream.shared.RequestedTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many of "my requests" to carry; more than a screenful already. */
private const val REQUEST_PAGE = 30

/** How often a moving download is re-read while the screen is up. */
private const val PROGRESS_POLL_MS = 5_000L

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
    var mine by remember { mutableStateOf<List<RequestedTitle>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Which show's seasons are being picked, if any. Local state rather
    // than a navigation entry: the app's back stack is private to
    // MainActivity, and a second stack fighting the first is how Back
    // starts meaning two different things.
    var pickingSeasons by remember { mutableStateOf<JellyseerrResult?>(null) }
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

    LaunchedEffect(Unit) { mine = seerr.myRequestsDetailed(REQUEST_PAGE) }

    // A progress bar nobody refreshes is a screenshot. Keyed on whether
    // anything is moving, and the loop asks again every pass, so the
    // polling stops dead once the last download lands — leaving a screen
    // open must not keep a NAS awake all night.
    val downloading = mine.any { it.progress != null }
    LaunchedEffect(downloading) {
        while (mine.any { it.progress != null }) {
            delay(PROGRESS_POLL_MS)
            try {
                mine = seerr.myRequestsDetailed(REQUEST_PAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One unreachable poll is not worth ending the loop over;
                // the next tick asks again
            }
        }
    }

    fun request(tmdbId: Int, isSeries: Boolean, title: String) {
        scope.launch {
            when (val outcome = seerr.request(tmdbId, isSeries)) {
                is RequestOutcome.Sent -> {
                    justRequested[tmdbId] = RequestState.PENDING
                    notice = "Requested $title"
                    mine = seerr.myRequestsDetailed(REQUEST_PAGE)
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

    val picking = pickingSeasons
    if (picking != null) {
        SeasonPicker(
            seerr = seerr,
            show = picking,
            notice = notice,
            onNotice = { notice = it },
            onRequestAll = { request(picking.id, true, picking.displayTitle) },
            onBack = { pickingSeasons = null },
        )
        return
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
                item(key = "notice") { NoticeCard(message) }
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
                items(mine, key = { "req-${it.request.id}" }) { row ->
                    RequestRow(row)
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
                        onRequest = {
                            // A show is not one thing to ask for. Someone
                            // missing season 3 should not have to re-request
                            // the two the server already holds.
                            if (result.isSeries) {
                                pickingSeasons = result
                            } else {
                                request(result.id, false, result.displayTitle)
                            }
                        },
                    )
                }
            }
        }

        FloatingNavButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

/**
 * One show, one season at a time.
 *
 * Not a screen in the app's back stack: that stack is private to
 * MainActivity, and an entry there would make the picker and the requests
 * screen answer the same Back press.
 */
@Composable
private fun SeasonPicker(
    seerr: JellyseerrApi,
    show: JellyseerrResult,
    notice: String?,
    onNotice: (String) -> Unit,
    onRequestAll: () -> Unit,
    onBack: () -> Unit,
) {
    var details by remember(show.id) { mutableStateOf<JellyseerrTvDetails?>(null) }
    var loading by remember(show.id) { mutableStateOf(true) }
    // Keyed by season number, never by show: asking for season 2 must not
    // put "Awaiting approval" against season 3 as well
    val justRequested = remember(show.id) { mutableStateMapOf<Int, RequestState>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(show.id) {
        loading = true
        details = try {
            seerr.tvDetails(show.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        loading = false
    }

    // Composed only while the picker is up, so it registers after the app's
    // own handler and answers first: Back returns to the search results
    // instead of leaving the requests screen altogether.
    BackHandler(onBack = onBack)

    fun requestSeason(number: Int) {
        scope.launch {
            when (val outcome = seerr.requestSeasons(show.id, listOf(number))) {
                is RequestOutcome.Sent -> {
                    justRequested[number] = RequestState.PENDING
                    onNotice("Requested ${show.displayTitle} season $number")
                }
                is RequestOutcome.AlreadyRequested -> {
                    justRequested[number] = RequestState.PENDING
                    onNotice("Season $number was already requested")
                }
                is RequestOutcome.NotSignedIn ->
                    onNotice("Sign in to Jellyseerr again in Settings")
                is RequestOutcome.Failed -> onNotice(outcome.message)
            }
        }
    }

    val loaded = details
    Box(modifier = Modifier.fillMaxSize().background(CinemaColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Column {
                    Text(
                        loaded?.name?.takeIf { it.isNotBlank() } ?: show.displayTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = CinemaColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    (loaded?.year ?: show.year)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextSecondary,
                        )
                    }
                }
            }

            notice?.let { message ->
                item(key = "notice") { NoticeCard(message) }
            }

            when {
                loading -> item(key = "spinner") {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                loaded == null -> item(key = "failed") {
                    Text(
                        "Couldn't load seasons for ${show.displayTitle}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                    )
                }
                else -> {
                    item(key = "all-seasons") {
                        SeasonRow(
                            title = "All seasons",
                            subtitle = null,
                            state = null,
                            enabled = true,
                            isPrimary = true,
                            onClick = onRequestAll,
                        )
                    }
                    items(loaded.requestableSeasons, key = { "season-${it.seasonNumber}" }) { season ->
                        val state = justRequested[season.seasonNumber]
                            ?: loaded.stateOf(season.seasonNumber)
                        SeasonRow(
                            title = season.displayName,
                            subtitle = season.episodeCount?.let { "$it episodes" },
                            state = state,
                            enabled = state.canRequest,
                            isPrimary = false,
                            onClick = { requestSeason(season.seasonNumber) },
                        )
                    }
                }
            }
        }

        FloatingNavButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

/**
 * A row of the season picker. [isPrimary] owns the screen's single
 * [tvDefaultFocus] — two of them race for initial focus on a television.
 */
@Composable
private fun SeasonRow(
    title: String,
    subtitle: String?,
    state: RequestState?,
    enabled: Boolean,
    isPrimary: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isPrimary) Modifier.tvDefaultFocus() else Modifier)
            .then(
                // Only a season you can actually ask for is a target
                if (enabled) {
                    Modifier.dpadFocusEffect(RoundedCornerShape(12.dp), scaleOnFocus = false)
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaColors.Surface)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = CinemaColors.TextPrimary,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
            }
        }
        state?.let {
            Spacer(Modifier.width(12.dp))
            StateChip(it)
        }
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

/** Whatever the last request attempt had to say, on either screen. */
@Composable
private fun NoticeCard(message: String) {
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
        PosterThumb(result.posterPath)
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
private fun RequestRow(row: RequestedTitle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaColors.Surface)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterThumb(row.posterPath)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = CinemaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CinemaColors.TextSecondary,
            )
            row.progress?.let { ProgressStrip(it) }
        }
        Spacer(Modifier.width(12.dp))
        StateChip(row.state)
    }
}

/**
 * Poster, or a plain tile when there is none.
 *
 * Coil handed a null model renders its error state, which on a dark row
 * reads as a broken image rather than as "this title has no artwork".
 */
@Composable
private fun PosterThumb(posterPath: String?) {
    val url = JellyseerrApi.posterUrl(posterPath, 185)
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CinemaColors.SurfaceVariant),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** How far along the download is: one bar, one line. */
@Composable
private fun ProgressStrip(progress: RequestProgress) {
    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CinemaColors.ProgressTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.fraction.toFloat().coerceIn(0f, 1f))
                    .height(4.dp)
                    // A paused or stalled grab keeps its percentage, but a
                    // bar in the accent colour claims it is still moving
                    .background(
                        if (progress.isStalled) {
                            CinemaColors.TextSecondary
                        } else {
                            CinemaColors.Accent
                        }
                    ),
            )
        }
        Text(
            progress.summary,
            style = MaterialTheme.typography.labelMedium,
            color = CinemaColors.TextSecondary,
        )
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
