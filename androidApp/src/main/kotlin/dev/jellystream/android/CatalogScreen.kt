package dev.jellystream.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.Copy
import dev.jellystream.shared.CatalogQuery
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.PersonCredit
import kotlinx.coroutines.CancellationException

/**
 * One screen for a collection, a genre, or a person: a title and a grid
 * of posters. The query is decided in shared from the item's type so the
 * two apps cannot disagree about what "this genre" means.
 */
@Composable
fun CatalogScreen(
    api: JellyfinApi,
    item: BaseItem,
    onOpen: (BaseItem) -> Unit,
    onBack: () -> Unit,
) {
    var items by remember { mutableStateOf<List<BaseItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val isTv = LocalContext.current.packageManager
        .hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    LaunchedEffect(item.id) {
        try {
            items = CatalogQuery.load(api, item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this"
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CinemaColors.Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                item.name ?: "",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 72.dp, top = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            when {
                error != null -> Text(
                    error!!,
                    color = CinemaColors.TextSecondary,
                    modifier = Modifier.padding(24.dp),
                )
                items == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(48.dp),
                )
                items!!.isEmpty() -> Text(
                    Copy.nothingInHere,
                    color = CinemaColors.TextSecondary,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (isTv) 180.dp else 140.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items!!, key = { it.id }) { child ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .dpadFocusEffect(RoundedCornerShape(10.dp))
                                .clickable(enabled = child.isBrowsable) { onOpen(child) },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AsyncImage(
                                model = api.imageUrl(child, 400),
                                contentDescription = child.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaColors.SurfaceVariant),
                            )
                            Text(
                                child.name ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = CinemaColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
        FloatingNavButton(
            onClick = onBack,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = Copy.back,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
fun PersonRow(
    api: JellyfinApi,
    people: List<PersonCredit>,
    onOpen: (BaseItem) -> Unit,
) {
    val shown = CatalogQuery.actors(people)
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Copy.cast, style = MaterialTheme.typography.titleMedium)
        LazyRow(
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown.size) { index ->
                val person = shown[index]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(96.dp)
                        .dpadFocusEffect(RoundedCornerShape(8.dp))
                        .clickable {
                            onOpen(
                                BaseItem(
                                    id = person.id!!,
                                    name = person.name,
                                    type = "Person",
                                    primaryImageTag = person.primaryImageTag,
                                ),
                            )
                        },
                ) {
                    AsyncImage(
                        model = api.personImageUrl(person, 200),
                        contentDescription = person.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(CinemaColors.SurfaceVariant),
                    )
                    Text(
                        person.name ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    person.role?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
