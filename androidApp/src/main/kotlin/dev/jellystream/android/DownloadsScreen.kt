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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jellystream.shared.DownloadState
import dev.jellystream.shared.DownloadedItem
import dev.jellystream.shared.PersistedDownloads

/**
 * What this profile has taken offline.
 *
 * Everything here comes from the stored blob, never from the server —
 * this is the one screen that has to work with the network off.
 */
@Composable
fun DownloadsScreen(
    downloads: PersistedDownloads,
    onPlay: (DownloadedItem) -> Unit,
    onRemove: (DownloadedItem) -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(CinemaColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CinemaColors.TextPrimary,
                )
            }
            if (downloads.items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing downloaded yet. Open a film or an episode and tap "
                            + "Download to keep it on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }
            items(downloads.items, key = { it.itemId }) { item ->
                DownloadRow(
                    item = item,
                    onPlay = { onPlay(item) },
                    onRemove = { onRemove(item) },
                )
            }
        }
        FloatingNavButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun DownloadRow(
    item: DownloadedItem,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaColors.Surface)
            .then(
                // Only a finished file is a target; a partial one plays as
                // a broken film, which is worse than no button
                if (item.isPlayable) {
                    Modifier
                        .dpadFocusEffect(RoundedCornerShape(12.dp), scaleOnFocus = false)
                        .clickable { onPlay() }
                } else {
                    Modifier
                }
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                statusLabel(item),
                style = MaterialTheme.typography.labelLarge,
                color = when (item.state) {
                    DownloadState.COMPLETE -> CinemaColors.CriticFresh
                    DownloadState.FAILED -> MaterialTheme.colorScheme.error
                    else -> CinemaColors.TextSecondary
                },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Remove",
                style = MaterialTheme.typography.labelLarge,
                color = CinemaColors.TextSecondary,
                modifier = Modifier
                    .dpadFocusEffect(RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onRemove() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        item.progress?.takeIf { item.state == DownloadState.DOWNLOADING }?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun statusLabel(item: DownloadedItem): String = when (item.state) {
    DownloadState.QUEUED -> "Queued"
    DownloadState.DOWNLOADING ->
        item.progress?.let { "${(it * 100).toInt()}%" } ?: "Downloading"
    DownloadState.COMPLETE -> "On device"
    DownloadState.FAILED -> "Failed"
}
