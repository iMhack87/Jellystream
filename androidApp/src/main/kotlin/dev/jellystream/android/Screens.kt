package dev.jellystream.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(api: JellyfinApi, item: BaseItem, onPlay: (BaseItem) -> Unit) {
    var full by remember { mutableStateOf(item) }
    LaunchedEffect(item.id) {
        runCatching { full = api.getItem(item.id) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = api.imageUrl(full, 600),
            contentDescription = full.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(200.dp)
                .height(300.dp)
                .clip(RoundedCornerShape(12.dp)),
        )

        Text(
            full.name ?: "",
            style = MaterialTheme.typography.headlineMedium,
        )
        full.episodeLabel?.let {
            Text("${full.seriesName ?: ""} · $it", style = MaterialTheme.typography.titleMedium)
        }

        val meta = buildList {
            full.productionYear?.let { add(it.toString()) }
            full.runtimeMinutes?.let { add("$it min") }
            full.communityRating?.let { add("★ %.1f".format(it)) }
        }.joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodyMedium)
        }

        full.genres?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Button(onClick = { onPlay(full) }) {
            val resume = full.resumePositionSeconds
            Text(
                if (resume > 60) {
                    "Resume (${(resume / 60).toInt()} min)"
                } else {
                    "Play"
                }
            )
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

@Composable
fun SeriesScreen(api: JellyfinApi, series: BaseItem, onPlay: (BaseItem) -> Unit) {
    var seasons by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<BaseItem?>(null) }
    var episodes by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(series.name ?: "", style = MaterialTheme.typography.headlineMedium)
        }
        error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it.id }) { season ->
                    FilterChip(
                        selected = season.id == selectedSeason?.id,
                        onClick = { selectedSeason = season },
                        label = { Text(season.name ?: "Season") },
                    )
                }
            }
        }
        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(api, episode, onPlay)
        }
    }
}

@Composable
private fun EpisodeRow(api: JellyfinApi, episode: BaseItem, onPlay: (BaseItem) -> Unit) {
    Surface(
        onClick = { onPlay(episode) },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = api.imageUrl(episode, 300),
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column {
                episode.episodeLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    episode.name ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.runtimeMinutes?.let {
                    Text("$it min", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SearchScreen(api: JellyfinApi, onOpen: (BaseItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(400) // debounce
        searching = true
        runCatching { results = api.search(query, 24) }
        searching = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (searching) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results, key = { it.id }) { item ->
                SearchResultCard(api, item, onOpen)
            }
        }
    }
}

@Composable
private fun SearchResultCard(api: JellyfinApi, item: BaseItem, onOpen: (BaseItem) -> Unit) {
    Surface(onClick = { onOpen(item) }, shape = RoundedCornerShape(8.dp)) {
        Column {
            AsyncImage(
                model = api.imageUrl(item, 300),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Text(
                item.name ?: "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}
