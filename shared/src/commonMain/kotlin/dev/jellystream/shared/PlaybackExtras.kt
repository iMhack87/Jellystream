package dev.jellystream.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** One chapter marker on a film or episode. */
@Serializable
data class ChapterInfo(
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
    @SerialName("ImageTag") val imageTag: String? = null,
) {
    val startSeconds: Double
        get() = startPositionTicks / JellyfinApi.TICKS_PER_SECOND.toDouble()
}

/**
 * One person on a title — actor, director, … The id is a Jellyfin item
 * id, so the same [JellyfinApi.getItem] that loads a film loads them.
 */
@Serializable
data class PersonCredit(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

/** A named thing with an id — a genre, typically. */
@Serializable
data class NameId(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
)

/**
 * One trickplay resolution: a grid of thumbnails packed into JPEG tiles.
 *
 * [interval] is milliseconds between thumbnails, not between tiles.
 * `{index}.jpg` is a sprite of [tileWidth] × [tileHeight] thumbs.
 */
@Serializable
data class TrickplayInfo(
    @SerialName("Width") val width: Int = 0,
    @SerialName("Height") val height: Int = 0,
    @SerialName("TileWidth") val tileWidth: Int = 10,
    @SerialName("TileHeight") val tileHeight: Int = 10,
    @SerialName("ThumbnailCount") val thumbnailCount: Int = 0,
    @SerialName("Interval") val interval: Int = 10_000,
    @SerialName("Bandwidth") val bandwidth: Int = 0,
) {
    val thumbsPerTile: Int
        get() = (tileWidth * tileHeight).coerceAtLeast(1)

    val tileCount: Int
        get() = if (thumbnailCount <= 0) 0 else (thumbnailCount + thumbsPerTile - 1) / thumbsPerTile
}

/** Where in a sprite sheet a playback position lands. */
data class TrickplayCell(
    val tileIndex: Int,
    val column: Int,
    val row: Int,
    val thumbIndex: Int,
)

/**
 * Locates a thumbnail in the trickplay manifest. Pure arithmetic so the
 * player can ask on every scrub tick without hitting the network for the
 * index — only the JPEG of the current tile is fetched.
 */
object Trickplay {
    /**
     * The resolution to draw. Prefers the source we are playing, then the
     * widest sheet — a 320px thumb is still a thumb, and a missing source
     * id must not cost the preview.
     */
    fun pick(
        manifest: Map<String, Map<String, TrickplayInfo>>?,
        sourceId: String?,
    ): TrickplayInfo? {
        if (manifest.isNullOrEmpty()) return null
        val byWidth = sourceId?.let { manifest[it] }
            ?: manifest.values.maxByOrNull { src -> src.values.maxOfOrNull { it.width } ?: 0 }
            ?: return null
        return byWidth.values.maxByOrNull { it.width }
    }

    fun cellAt(positionSeconds: Double, info: TrickplayInfo): TrickplayCell? {
        if (info.interval <= 0 || info.thumbnailCount <= 0) return null
        val positionMs = (positionSeconds * 1000.0).toLong().coerceAtLeast(0)
        val thumb = (positionMs / info.interval)
            .toInt()
            .coerceIn(0, info.thumbnailCount - 1)
        val perTile = info.thumbsPerTile
        val inTile = thumb % perTile
        return TrickplayCell(
            tileIndex = thumb / perTile,
            column = inTile % info.tileWidth.coerceAtLeast(1),
            row = inTile / info.tileWidth.coerceAtLeast(1),
            thumbIndex = thumb,
        )
    }

    fun tilePath(itemId: String, width: Int, index: Int): String =
        "Videos/$itemId/Trickplay/$width/$index.jpg"
}

/**
 * What the stats overlay prints. Built from the negotiated source, not
 * from the player's live decoder, so Direct Play vs transcode is the
 * server's answer and not a guess from the mime type.
 */
data class PlaybackStats(
    val isTranscode: Boolean = false,
    val container: String? = null,
    val videoCodec: String? = null,
    val videoRange: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoBitRate: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioLanguage: String? = null,
    val frameRate: Double? = null,
) {
    val playMethod: String
        get() = if (isTranscode) "Transcode" else "Direct Play"

    /** Non-null fps for Swift; 0 means "the file didn't say". */
    fun frameRateValue(): Double = frameRate ?: 0.0

    /**
     * Short lines for the overlay. Empty entries are dropped so a file
     * missing HDR or a bitrate does not print a blank row.
     */
    fun lines(): List<String> = buildList {
        add(playMethod)
        val video = listOfNotNull(
            videoCodec?.uppercase(),
            resolution(),
            videoRange?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) && !it.equals("SDR", ignoreCase = true) },
        )
        if (video.isNotEmpty()) add(video.joinToString(" · "))
        val rate = listOfNotNull(frameRateLabel(), bitRateLabel())
        if (rate.isNotEmpty()) add(rate.joinToString(" · "))
        val audio = listOfNotNull(
            audioCodec?.uppercase(),
            audioChannels?.let { channelsLabel(it) },
            audioLanguage,
        )
        if (audio.isNotEmpty()) add(audio.joinToString(" · "))
        container?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    private fun resolution(): String? {
        val w = width ?: return null
        val h = height ?: return null
        return "${w}×$h"
    }

    private fun frameRateLabel(): String? {
        val fps = frameRate ?: return null
        if (fps <= 0) return null
        val shown = if (abs(fps - fps.roundToInt()) < 0.05) {
            fps.roundToInt().toString()
        } else {
            ((fps * 1000).roundToInt() / 1000.0).toString()
        }
        return "$shown fps"
    }

    private fun bitRateLabel(): String? {
        val bps = videoBitRate ?: return null
        if (bps <= 0) return null
        return if (bps >= 1_000_000) {
            val mbps = bps / 1_000_000.0
            "${((mbps * 10).roundToInt() / 10.0)} Mbps"
        } else {
            "${(bps / 1000)} kbps"
        }
    }

    private fun channelsLabel(count: Int): String = when (count) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count ch"
    }

    companion object {
        val Empty = PlaybackStats()

        fun of(
            isTranscode: Boolean,
            container: String?,
            streams: List<MediaStream>,
            sourceBitrate: Int?,
        ): PlaybackStats {
            val video = streams.firstOrNull { it.isVideo }
            val audio = streams.firstOrNull { it.isDefault && it.isAudio }
                ?: streams.firstOrNull { it.isAudio }
            return PlaybackStats(
                isTranscode = isTranscode,
                container = container,
                videoCodec = video?.codec,
                videoRange = video?.videoRangeType ?: video?.videoRange,
                width = video?.width,
                height = video?.height,
                videoBitRate = video?.bitRate ?: sourceBitrate,
                audioCodec = audio?.codec,
                audioChannels = audio?.channels,
                audioLanguage = audio?.language,
                frameRate = video?.averageFrameRate,
            )
        }
    }
}

/**
 * Snaps a file's frame rate onto a display mode the TV can actually
 * switch to. 23.976 and 24 are not the same picture; rounding to 60 Hz
 * is how 24p films judder.
 */
object DisplayRefresh {
    val CANDIDATES: List<Double> = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)

    fun preferredRate(frameRate: Double?): Double? {
        if (frameRate == null || frameRate <= 0.0) return null
        return CANDIDATES.minBy { abs(it - frameRate) }
    }

    /** Swift-callable: 0 means "don't switch". */
    fun preferredRateValue(frameRate: Double): Double = preferredRate(frameRate) ?: 0.0
}

/**
 * Remote display (AirPlay, Chromecast) cannot send a MediaBrowser header
 * and cannot Direct Play MKV. The phone/tablet still Direct Plays; the
 * other screen gets HLS with the token in the query string.
 *
 * Jellyfin 10.12+ accepts `ApiKey=` and ignores `api_key=` by default.
 */
object RemotePlayback {
    const val CAST_NAMESPACE: String = "urn:x-cast:com.connectsdk"
    const val CAST_RECEIVER_STABLE: String = "F007D354"
    const val CAST_DEFAULT_RECEIVER: String = "CC1AD845"

    fun appendApiKey(url: String, accessToken: String): String {
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}ApiKey=$accessToken"
    }
}
