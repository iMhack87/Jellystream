package dev.jellystream.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where one download has got to. */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,

    /** On disk and playable with the network off. */
    COMPLETE,

    /** Stopped short. The file, if any, is partial and not playable. */
    FAILED;

    val isPlayable: Boolean
        get() = this == COMPLETE
}

/**
 * One downloaded title, with enough of its metadata to live on a screen
 * that has no server to ask.
 *
 * That is the whole point: offline means the library calls fail, so a name
 * and a runtime that only existed in an API response would leave the
 * downloads list showing rows of identifiers.
 */
@Serializable
data class DownloadedItem(
    val itemId: String,
    val fileName: String,
    val name: String,
    val state: DownloadState = DownloadState.QUEUED,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    /** Series title and "S1 · E4" for an episode; null for a film. */
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val runTimeTicks: Long? = null,
    val productionYear: Int? = null,
    /**
     * Where playback had got to when it was last watched offline, in ticks.
     * Kept here so the resume point survives with no server to hold it.
     */
    val positionTicks: Long = 0,
    /** True once the position has been accepted by the server. */
    val positionSynced: Boolean = true,
) {
    val isPlayable: Boolean
        get() = state.isPlayable

    /** 0.0–1.0, or null when the total is not known yet. */
    val progress: Double?
        get() = totalBytes.takeIf { it > 0 }
            ?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0) }

    /** Second line of a downloads row: series and episode, else the year. */
    val subtitle: String?
        get() = when {
            seriesName != null && episodeLabel != null -> "$seriesName · $episodeLabel"
            seriesName != null -> seriesName
            productionYear != null -> productionYear.toString()
            else -> null
        }

    val positionSeconds: Double
        get() = positionTicks / JellyfinApi.TICKS_PER_SECOND.toDouble()

    companion object {
        /**
         * The file name a download lands under. Built from the item id, not
         * from the title: two files called "Dune (2021).mkv" from different
         * libraries would otherwise collide, and a title can contain
         * anything a file system refuses.
         */
        fun fileNameFor(itemId: String, container: String?): String {
            val extension = container?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
                ?: "mkv"
            return "$itemId.$extension"
        }

        /** Seeds a queued download from the item the user tapped. */
        fun queued(item: BaseItem, container: String?): DownloadedItem = DownloadedItem(
            itemId = item.id,
            fileName = fileNameFor(item.id, container),
            name = item.name ?: "Untitled",
            state = DownloadState.QUEUED,
            seriesName = item.seriesName,
            episodeLabel = item.episodeLabel,
            runTimeTicks = item.runTimeTicks,
            productionYear = item.productionYear,
            positionTicks = (item.resumePositionSeconds * JellyfinApi.TICKS_PER_SECOND).toLong(),
        )
    }
}

/**
 * Everything one profile has taken offline.
 *
 * Stored per profile like settings are: two accounts sharing a tablet do
 * not share a watchlist, and one signing out must not take the other's
 * files with it.
 */
@Serializable
data class PersistedDownloads(
    val items: List<DownloadedItem> = emptyList(),
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    operator fun get(itemId: String): DownloadedItem? =
        items.firstOrNull { it.itemId == itemId }

    fun stateOf(itemId: String): DownloadState? = get(itemId)?.state

    /** Adds or replaces, keeping the list in insertion order. */
    fun with(item: DownloadedItem): PersistedDownloads {
        val index = items.indexOfFirst { it.itemId == item.itemId }
        return if (index < 0) PersistedDownloads(items + item)
        else PersistedDownloads(items.toMutableList().also { it[index] = item })
    }

    fun without(itemId: String): PersistedDownloads =
        PersistedDownloads(items.filterNot { it.itemId == itemId })

    /** What a downloads screen shows: finished first, then in flight. */
    val playable: List<DownloadedItem>
        get() = items.filter { it.isPlayable }

    /**
     * Positions watched offline that the server has not been told about.
     * Replayed on the way back online, newest position wins.
     */
    val unsyncedPositions: List<DownloadedItem>
        get() = items.filter { !it.positionSynced && it.positionTicks > 0 }

    fun markPosition(itemId: String, ticks: Long): PersistedDownloads =
        get(itemId)?.let {
            with(it.copy(positionTicks = ticks, positionSynced = false))
        } ?: this

    fun markSynced(itemId: String): PersistedDownloads =
        get(itemId)?.let { with(it.copy(positionSynced = true)) } ?: this

    companion object {
        fun empty(): PersistedDownloads = PersistedDownloads()

        /**
         * Never throws: a corrupt blob costs the list, not the launch. The
         * files stay on disk and can be downloaded again.
         */
        fun fromJson(json: String): PersistedDownloads? =
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
    }
}

/** Why the Download button is not offered. */
enum class DownloadAvailability {
    /** Go ahead. */
    ALLOWED,

    /** The server forbids this account from downloading. */
    FORBIDDEN_BY_SERVER,

    /** Nothing to download — a series or a folder, not a file. */
    NOT_A_FILE;

    val canDownload: Boolean
        get() = this == ALLOWED

    /** What to tell the user instead of a button that would fail. */
    val explanation: String?
        get() = when (this) {
            ALLOWED, NOT_A_FILE -> null
            FORBIDDEN_BY_SERVER ->
                "Your Jellyfin account is not allowed to download. Ask the server owner to enable it."
        }

    companion object {
        /**
         * [downloadingEnabled] is the account's `EnableContentDownloading`
         * policy. Unknown is treated as allowed: the request either works
         * or comes back 401, which is recoverable — hiding the button on a
         * server that would have said yes is not.
         */
        fun of(item: BaseItem, downloadingEnabled: Boolean?): DownloadAvailability = when {
            !item.isPlayable -> NOT_A_FILE
            downloadingEnabled == false -> FORBIDDEN_BY_SERVER
            else -> ALLOWED
        }
    }
}
