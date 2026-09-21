package dev.jellystream.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.jellystream.shared.ChapterInfo
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.PlaybackStats
import dev.jellystream.shared.TrickplayInfo

@Composable
fun StatsOverlay(
    stats: PlaybackStats,
    usingMpv: Boolean,
    modifier: Modifier = Modifier,
) {
    val lines = stats.lines().toMutableList()
    if (usingMpv) lines.add("mpv")
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { line ->
            Text(line, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
fun ChapterStrip(
    api: JellyfinApi,
    itemId: String,
    chapters: List<ChapterInfo>,
    trickplay: TrickplayInfo?,
    onSeek: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(CinemaColors.Surface.copy(alpha = 0.95f))
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Chapters", color = Color.White)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            chapters.forEachIndexed { index, chapter ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .dpadFocusEffect(RoundedCornerShape(8.dp))
                        .clickable { onSeek(chapter.startSeconds) }
                        .padding(4.dp),
                ) {
                    val image = api.chapterImageUrl(itemId, index, chapter.imageTag, 280)
                        ?: trickplay?.let { api.trickplayTileUrl(itemId, it.width, 0) }
                    AsyncImage(
                        model = image,
                        contentDescription = chapter.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CinemaColors.SurfaceVariant),
                    )
                    Text(
                        chapter.name ?: "Chapter ${index + 1}",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerToolRow(
    onToggleStats: () -> Unit,
    onChapters: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = onToggleStats,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Text("Info", color = Color.White)
        }
        if (onChapters != null) {
            TextButton(
                onClick = onChapters,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Text("Chapters", color = Color.White)
            }
        }
        CastRouteButton()
    }
}
