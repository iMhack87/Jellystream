package dev.jellystream.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class AuthenticationResult(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("User") val user: UserDto? = null,
)

/** A server URL that answered `System/Info/Public`, plus what it answered. */
data class ResolvedServer(
    val baseUrl: String,
    val info: PublicSystemInfo,
)

/** An authenticated session against one server. */
data class UserSession(
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
    val userName: String?,
    val serverName: String?,
)

@Serializable
data class UserItemData(
    /** Jellyfin ticks: 1 tick = 100 ns, so 1 second = 10_000_000 ticks. */
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("Played") val played: Boolean? = null,
)

@Serializable
data class BaseItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("UserData") val userData: UserItemData? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
) {
    /** Direct playback targets — the single source of truth for both platforms. */
    val isPlayable: Boolean
        get() = type == "Movie" || type == "Episode"

    /** Resume position in seconds, 0.0 when unwatched. */
    val resumePositionSeconds: Double
        get() = (userData?.playbackPositionTicks ?: 0L) / 10_000_000.0

    val isSeries: Boolean
        get() = type == "Series"

    /** Whole minutes, null when the server didn't send a runtime. */
    val runtimeMinutes: Int?
        get() = runTimeTicks?.let { (it / (60 * JellyfinApi.TICKS_PER_SECOND)).toInt() }

    /** "S2 · E5" style label for episodes, null otherwise. */
    val episodeLabel: String?
        get() = if (type == "Episode" && indexNumber != null) {
            buildString {
                parentIndexNumber?.let { append("S$it · ") }
                append("E$indexNumber")
            }
        } else {
            null
        }
}

@Serializable
internal data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<BaseItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
internal data class AuthenticateByNameRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)
