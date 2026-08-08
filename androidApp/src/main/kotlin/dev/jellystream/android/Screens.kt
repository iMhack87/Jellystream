package dev.jellystream.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jellystream.shared.DownloadAvailability
import dev.jellystream.shared.DownloadState
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.ItemRatings
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.JellyseerrApi
import dev.jellystream.shared.JellyseerrResult
import dev.jellystream.shared.RequestOutcome
import dev.jellystream.shared.RequestState
import dev.jellystream.shared.SearchAvailability
import dev.jellystream.shared.SearchHit
import dev.jellystream.shared.SearchKind
import dev.jellystream.shared.UnifiedSearcher
import dev.jellystream.shared.Watchlist
import dev.jellystream.shared.WatchlistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Audience score, tomatometer and age certificate, in that order.
 *
 * Deliberately not the Rotten Tomatoes marks: the percentage carries the
 * verdict on its own, colored at the 60% line, and shipping their artwork
 * is not ours to do. Renders nothing at all when the server has no rating,
 * rather than leaving an empty strip on the page.
 */
@Composable
fun RatingsRow(ratings: ItemRatings, modifier: Modifier = Modifier) {
    if (ratings.isEmpty) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ratings.communityLabel?.let { score ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Audience rating",
                    tint = CinemaColors.RatingStar,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    score,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaColors.TextPrimary,
                )
            }
        }
        ratings.criticLabel?.let { percent ->
            Text(
                percent,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ratings.criticIsFresh == true) {
                    CinemaColors.CriticFresh
                } else {
                    CinemaColors.CriticRotten
                },
            )
        }
        ratings.officialLabel?.let { certificate ->
            Text(
                certificate,
                style = MaterialTheme.typography.labelMedium,
                color = CinemaColors.TextSecondary,
                modifier = Modifier
                    .border(
                        1.dp,
                        CinemaColors.TextSecondary.copy(alpha = 0.6f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Floating circular back/close control — iOS-style, visible affordance on
 * every pushed screen (the system back gesture still works too). Focusable
 * for the TV D-pad.
 */
@Composable
fun FloatingNavButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    contentDescription: String = "Back",
) {
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(12.dp)
            .dpadFocusEffect(CircleShape)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onClick() }
            .padding(10.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

@Composable
fun DetailScreen(
    api: JellyfinApi,
    item: BaseItem,
    onPlay: (BaseItem) -> Unit,
    onBack: () -> Unit,
    watchlist: Watchlist = Watchlist(),
    onWatchlistChange: (Watchlist) -> Unit = {},
    download: DownloadAvailability = DownloadAvailability.NOT_A_FILE,
    downloadState: DownloadState? = null,
    onDownload: () -> Unit = {},
) {
    var full by remember { mutableStateOf(item) }
    // The one thing a failed heart or watched toggle has to say
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) {
        runCatching { full = api.getItem(item.id) }
    }

    /**
     * Flip now, put it back if the server disagrees. A toggle that waits
     * for a round trip reads as a dead button on a remote — and Jellyfin
     * answers false rather than throwing, so there is nothing to catch.
     */
    fun toggleFavorite() {
        val wanted = !full.isFavorite
        full = full.withFavorite(wanted)
        actionError = null
        scope.launch {
            val ok = try {
                api.setFavorite(full.id, wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                full = full.withFavorite(!wanted)
                actionError = "Couldn't reach the server"
            }
        }
    }

    fun toggleWatched() {
        val wanted = !full.isWatched
        full = full.withWatched(wanted)
        actionError = null
        scope.launch {
            val ok = try {
                api.setWatched(full.id, wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                full = full.withWatched(!wanted)
                actionError = "Couldn't reach the server"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
        // Full-bleed backdrop melting into the background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        ) {
            AsyncImage(
                model = api.backdropUrl(full, 1280),
                contentDescription = full.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.5f to Color.Transparent,
                            1.0f to CinemaColors.Background,
                        )
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                full.name ?: "",
                style = MaterialTheme.typography.headlineMedium,
            )
            full.episodeLabel?.let {
                Text(
                    "${full.seriesName ?: ""} · $it",
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextSecondary,
                )
            }

            val meta = buildList {
                full.productionYear?.let { add(it.toString()) }
                full.runtimeMinutes?.let { add("$it min") }
            }.joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaColors.TextSecondary,
                )
            }

            RatingsRow(full.ratings)

            full.genres?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
            }

            Button(
                onClick = { onPlay(full) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .tvDefaultFocus()
                    .dpadFocusEffect(RoundedCornerShape(10.dp)),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                val resume = full.resumePositionSeconds
                Text(
                    if (resume > 60) {
                        "Resume (${(resume / 60).toInt()} min)"
                    } else {
                        "Play"
                    }
                )
            }

            // Scrollable: three labels plus a heart already overflow a
            // phone, and a control that runs off the edge is a control
            // nobody knows exists
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { toggleFavorite() },
                    modifier = Modifier.dpadFocusEffect(CircleShape),
                ) {
                    Icon(
                        if (full.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (full.isFavorite) {
                            "Remove from favourites"
                        } else {
                            "Add to favourites"
                        },
                        tint = CinemaColors.TextPrimary,
                    )
                }
                TextButton(
                    onClick = { toggleWatched() },
                    modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                ) {
                    Text(if (full.isWatched) "Mark as unwatched" else "Mark as watched")
                }
                TextButton(
                    onClick = { onWatchlistChange(watchlist.toggled(WatchlistEntry.of(full))) },
                    modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                ) {
                    Text(
                        if (watchlist.contains(full)) {
                            "Remove from watchlist"
                        } else {
                            "Add to watchlist"
                        }
                    )
                }
            }
            actionError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Offline is a phone and tablet feature: a television has the
            // server on the same network and nowhere to put 40 GB
            if (!isTvDevice()) {
                when {
                    downloadState != null -> Text(
                        downloadLabel(downloadState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                    )
                    download.canDownload -> TextButton(
                        onClick = onDownload,
                        modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                    ) {
                        Text("Download")
                    }
                    // Say why rather than show a button that would 401
                    download.explanation != null -> Text(
                        download.explanation!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }

            full.overview?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        }

        FloatingNavButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

@Composable
fun SeriesScreen(
    api: JellyfinApi,
    series: BaseItem,
    onPlay: (BaseItem) -> Unit,
    onBack: () -> Unit,
    watchlist: Watchlist = Watchlist(),
    onWatchlistChange: (Watchlist) -> Unit = {},
) {
    var show by remember(series.id) { mutableStateOf(series) }
    var seasons by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<BaseItem?>(null) }
    var episodes by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // A watchlist row can only hand over an id and a name, so the heart
    // would start empty on a title that is in fact favourited — and the
    // header would have no artwork. Ask the server what this show is.
    LaunchedEffect(series.id) {
        try {
            show = api.getItem(series.id)
        } catch (e: CancellationException) {
            // Never swallow cancellation: a stale effect must not touch state
            throw e
        } catch (_: Exception) {
            // The row's own copy is enough to list the seasons
        }
    }

    LaunchedEffect(series.id) {
        try {
            seasons = api.getSeasons(series.id)
            selectedSeason = seasons.firstOrNull()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load series"
        }
    }

    LaunchedEffect(selectedSeason?.id) {
        val season = selectedSeason ?: return@LaunchedEffect
        runCatching { episodes = api.getEpisodes(series.id, season.id) }
    }

    /**
     * Flip now, put it back if the server disagrees — same rule as the
     * detail screen, and the same reason: a long press that waits for a
     * round trip reads as one that never registered.
     */
    fun setWatched(episode: BaseItem, watched: Boolean) {
        episodes = episodes.map { if (it.id == episode.id) it.withWatched(watched) else it }
        actionError = null
        scope.launch {
            val ok = try {
                api.setWatched(episode.id, watched)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                episodes = episodes.map {
                    if (it.id == episode.id) it.withWatched(!watched) else it
                }
                actionError = "Couldn't reach the server"
            }
        }
    }

    fun toggleFavorite() {
        val wanted = !show.isFavorite
        show = show.withFavorite(wanted)
        actionError = null
        scope.launch {
            val ok = try {
                api.setFavorite(show.id, wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                show = show.withFavorite(!wanted)
                actionError = "Couldn't reach the server"
            }
        }
    }

    // Apple TV-style series page: big title over the backdrop, season
    // pills, and a horizontal shelf of landscape episode cards
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            ) {
                AsyncImage(
                    model = api.backdropUrl(show, 1280),
                    contentDescription = show.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.35f),
                                0.35f to Color.Transparent,
                                1.0f to CinemaColors.Background,
                            )
                        ),
                )
                Text(
                    show.name ?: "",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
                FloatingNavButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        item(key = "ratings") {
            // Centered under the title, like the header it belongs to
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RatingsRow(show.ratings)
            }
        }
        item(key = "actions") {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { toggleFavorite() },
                        modifier = Modifier.dpadFocusEffect(CircleShape),
                    ) {
                        Icon(
                            if (show.isFavorite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            contentDescription = if (show.isFavorite) {
                                "Remove from favourites"
                            } else {
                                "Add to favourites"
                            },
                            tint = CinemaColors.TextPrimary,
                        )
                    }
                    TextButton(
                        onClick = {
                            onWatchlistChange(watchlist.toggled(WatchlistEntry.of(show)))
                        },
                        modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                    ) {
                        Text(
                            if (watchlist.contains(show)) {
                                "Remove from watchlist"
                            } else {
                                "Add to watchlist"
                            }
                        )
                    }
                }
            }
        }
        actionError?.let {
            item(key = "action-error") {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        item(key = "seasons") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                items(seasons, key = { it.id }) { season ->
                    val selected = season.id == selectedSeason?.id
                    Text(
                        season.name ?: "Season",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) Color.Black else Color.White,
                        modifier = Modifier
                            // The screen's primary control owns initial
                            // focus on TV (same rule as Play on Detail)
                            .then(if (selected) Modifier.tvDefaultFocus() else Modifier)
                            .dpadFocusEffect(CircleShape)
                            .clip(CircleShape)
                            .background(
                                if (selected) Color.White else Color.White.copy(alpha = 0.15f)
                            )
                            .clickable { selectedSeason = season }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
        item(key = "episodes") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                items(episodes, key = { it.id }) { episode ->
                    EpisodeCard(
                        api = api,
                        episode = episode,
                        onPlay = onPlay,
                        onToggleWatched = { setWatched(episode, !episode.isWatched) },
                    )
                }
            }
        }
    }
}

/**
 * ATV episode lockup: landscape still with runtime badge and watched
 * progress, then number eyebrow, title, synopsis and air date below.
 *
 * A short press plays, unchanged. A long press — center held on the
 * remote, finger held on a phone — flips watched, because a shelf of
 * episodes is where anyone actually wants that toggle and a screenful of
 * extra buttons is not worth it.
 */
@Composable
private fun EpisodeCard(
    api: JellyfinApi,
    episode: BaseItem,
    onPlay: (BaseItem) -> Unit,
    onToggleWatched: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(290.dp)
            .dpadFocusEffect(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onLongClick = onToggleWatched,
                onClick = { onPlay(episode) },
            )
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CinemaColors.SurfaceVariant),
        ) {
            AsyncImage(
                model = api.imageUrl(episode, 600),
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // Anti-spoiler: the still stays blurred until the
                    // episode has been started or watched
                    .then(if (episode.shouldBlurPreview) Modifier.blur(14.dp) else Modifier),
            )
            if (episode.isWatched) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Watched",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(2.dp),
                )
            }
            episode.playedFraction?.let { fraction ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(CinemaColors.ProgressTrack),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.toFloat())
                            .height(4.dp)
                            .background(Color.White),
                    )
                }
            }
            episode.runtimeMinutes?.let {
                Text(
                    "$it min",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        episode.indexNumber?.let {
            Text(
                "EPISODE $it",
                style = MaterialTheme.typography.labelSmall,
                color = CinemaColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            episode.name ?: "",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.overview?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = CinemaColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        episode.premiereDateIso?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = CinemaColors.TextSecondary,
            )
        }
    }
}

/**
 * One search across the library AND the request catalogue.
 *
 * Two screens meant that asking for something you did not have started in
 * Settings, which is a detour nobody takes. Here they are one list and the
 * only difference is what the row does when pressed.
 *
 * With no Jellyseerr configured — or one that will not answer — this is
 * exactly the old library search: [UnifiedSearcher] drops the half it
 * could not reach rather than failing the search.
 */
@Composable
fun SearchScreen(
    api: JellyfinApi,
    seerr: JellyseerrApi,
    watchlist: Watchlist,
    onWatchlistChange: (Watchlist) -> Unit,
    onOpen: (BaseItem) -> Unit,
    onBack: () -> Unit,
) {
    val searcher = remember(api, seerr) { UnifiedSearcher(api, seerr) }
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SearchKind.ALL) }
    var availability by remember { mutableStateOf(SearchAvailability.ALL) }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Optimistic per-title state: the chip must react before Jellyseerr
    // has caught up, or the row looks like it ignored the press
    val justRequested = remember { mutableStateMapOf<Int, RequestState>() }
    // Which show's seasons are being picked, if any. Local state rather
    // than a navigation entry: the app's back stack is private to
    // MainActivity, and a second stack fighting the first is how Back
    // starts meaning two different things.
    var pickingSeasons by remember { mutableStateOf<JellyseerrResult?>(null) }
    val scope = rememberCoroutineScope()

    // The debounce belongs to the typing alone: a filter pill has to
    // answer at once, and a search per keystroke would hammer both servers
    LaunchedEffect(query) {
        if (query.length < 2) {
            debounced = ""
            return@LaunchedEffect
        }
        delay(400)
        debounced = query
    }

    LaunchedEffect(debounced, kind, availability) {
        if (debounced.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        try {
            results = searcher.search(debounced, kind, availability)
        } catch (e: CancellationException) {
            // Never swallow cancellation: a stale effect must not touch state
            throw e
        } catch (_: Exception) {
        }
        searching = false
    }

    fun request(tmdbId: Int, isSeries: Boolean, title: String) {
        scope.launch {
            when (val outcome = seerr.request(tmdbId, isSeries)) {
                is RequestOutcome.Sent -> {
                    justRequested[tmdbId] = RequestState.PENDING
                    notice = "Requested $title"
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingNavButton(onClick = onBack)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().tvDefaultFocus(),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            FilterPill("All", kind == SearchKind.ALL) { kind = SearchKind.ALL }
            FilterPill("Films", kind == SearchKind.FILMS) { kind = SearchKind.FILMS }
            FilterPill("Series", kind == SearchKind.SERIES) { kind = SearchKind.SERIES }
        }
        // Only worth asking when there is somewhere to ask: without a
        // Jellyseerr, "Requestable" is a filter that can never match
        // anything, which reads as broken rather than as unconfigured.
        if (seerr.isConfigured) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                FilterPill("All", availability == SearchAvailability.ALL) {
                    availability = SearchAvailability.ALL
                }
                FilterPill("On the server", availability == SearchAvailability.ON_SERVER) {
                    availability = SearchAvailability.ON_SERVER
                }
                FilterPill("Requestable", availability == SearchAvailability.REQUESTABLE) {
                    availability = SearchAvailability.REQUESTABLE
                }
            }
        }

        if (searching) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            notice?.let { message ->
                item(key = "notice") { NoticeCard(message) }
            }
            items(
                results,
                // A hit is one server's row or the other's, never both
                key = { it.jellyfin?.id ?: "tmdb-${it.jellyseerr?.id}" },
            ) { hit ->
                val state = hit.tmdbId?.let { justRequested[it] } ?: hit.requestState
                SearchHitRow(
                    api = api,
                    hit = hit,
                    state = state,
                    inWatchlist = watchlist.contains(WatchlistEntry.of(hit)),
                    onOpen = { hit.jellyfin?.let(onOpen) },
                    onRequest = {
                        // A show is not one thing to ask for. Someone
                        // missing season 3 should not have to re-request
                        // the two the server already holds.
                        val result = hit.jellyseerr ?: return@SearchHitRow
                        if (hit.isSeries) {
                            pickingSeasons = result
                        } else {
                            request(result.id, false, hit.title)
                        }
                    },
                    onToggleWatchlist = {
                        onWatchlistChange(watchlist.toggled(WatchlistEntry.of(hit)))
                    },
                )
            }
        }
    }
}

/** Season-pill styling, reused for the two search filter rows. */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .dpadFocusEffect(CircleShape)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * One result, whichever server it came from.
 *
 * [state] is null when the title is on the server: there is nothing to ask
 * for, the thing is right there. The watchlist button is offered either
 * way — wanting to watch something is not the same as having it.
 */
@Composable
private fun SearchHitRow(
    api: JellyfinApi,
    hit: SearchHit,
    state: RequestState?,
    inWatchlist: Boolean,
    onOpen: () -> Unit,
    onRequest: () -> Unit,
    onToggleWatchlist: () -> Unit,
) {
    // Only a row that leads somewhere is a target: a title that is neither
    // on the server nor requestable has nothing to do with a press
    val actionable = hit.isOnServer || state?.canRequest == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (actionable) {
                    Modifier.dpadFocusEffect(RoundedCornerShape(12.dp), scaleOnFocus = false)
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaColors.Surface)
            .then(
                if (actionable) {
                    Modifier.clickable { if (hit.isOnServer) onOpen() else onRequest() }
                } else {
                    Modifier
                }
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The library's own artwork when we hold the item; the
        // catalogue's otherwise — a Jellyseerr row has no Jellyfin image
        PosterTile(
            hit.jellyfin?.let { api.imageUrl(it, 200) }
                ?: JellyseerrApi.posterUrl(hit.jellyseerr?.posterPath, 185)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                hit.title,
                style = MaterialTheme.typography.titleMedium,
                color = CinemaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CinemaColors.TextSecondary,
            )
        }
        IconButton(
            onClick = onToggleWatchlist,
            modifier = Modifier.dpadFocusEffect(CircleShape),
        ) {
            Icon(
                if (inWatchlist) Icons.Default.Check else Icons.Default.Add,
                contentDescription = if (inWatchlist) {
                    "Remove from watchlist"
                } else {
                    "Add to watchlist"
                },
                tint = CinemaColors.TextPrimary,
            )
        }
        state?.let {
            Spacer(Modifier.width(4.dp))
            StateChip(it)
        }
    }
}

private fun downloadLabel(state: DownloadState): String = when (state) {
    DownloadState.QUEUED -> "Queued for download"
    DownloadState.DOWNLOADING -> "Downloading…"
    DownloadState.COMPLETE -> "Available offline"
    DownloadState.FAILED -> "Download failed — tap Download to retry"
}
