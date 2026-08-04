package dev.jellystream.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
@Serializable
data class UserSession(
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
    val userName: String?,
    val serverName: String?,
) {
    /** Stable identity of a profile: one account on one server. */
    val profileKey: String
        get() = "$baseUrl|$userId"
}

/**
 * What survives an app restart: the session plus the device id it was
 * created with (Jellyfin ties sessions to DeviceId, so it must be stable).
 * Platforms persist the JSON blob (Keychain on Apple, private prefs on
 * Android) — the wire format lives here, never in platform code.
 */
@Serializable
data class PersistedSession(
    val deviceId: String,
    val session: UserSession,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    /** Stable identity of a profile: one account on one server. */
    val profileKey: String
        get() = session.profileKey

    /** What the profile picker shows. */
    val displayName: String
        get() = session.userName ?: "User"

    val serverLabel: String
        get() = session.serverName
            ?: session.baseUrl.removePrefix("https://").removePrefix("http://")

    /** Single uppercase letter for the avatar circle. */
    val initial: String
        get() = (displayName.firstOrNull() ?: 'U').uppercaseChar().toString()

    companion object {
        fun fromJson(json: String): PersistedSession? =
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
    }
}

/**
 * Every account this install knows, wire format owned by shared — the
 * platforms persist the JSON blob under a dedicated "profiles" key and
 * migrate any legacy single-session blob into a one-profile list. Each
 * profile keeps its own deviceId: Jellyfin ties sessions to DeviceId,
 * and two users sharing one id would revoke each other's tokens.
 */
@Serializable
data class PersistedProfiles(
    val profiles: List<PersistedSession> = emptyList(),
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    /** Adds a profile, replacing any existing one for the same server+user. */
    fun withProfile(profile: PersistedSession): PersistedProfiles =
        PersistedProfiles(
            profiles.filterNot { it.profileKey == profile.profileKey } + profile
        )

    fun withoutProfile(profile: PersistedSession): PersistedProfiles =
        PersistedProfiles(profiles.filterNot { it.profileKey == profile.profileKey })

    companion object {
        fun fromJson(json: String): PersistedProfiles? =
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
    }
}

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
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String>? = null,
) {
    /** Direct playback targets — the single source of truth for both platforms. */
    val isPlayable: Boolean
        get() = type == "Movie" || type == "Episode"

    /** Resume position in seconds, 0.0 when unwatched. */
    val resumePositionSeconds: Double
        get() = (userData?.playbackPositionTicks ?: 0L) / 10_000_000.0

    /** Watched fraction 0.0–1.0 for progress bars, null when not started. */
    val playedFraction: Double?
        get() {
            val position = userData?.playbackPositionTicks ?: return null
            val runtime = runTimeTicks ?: return null
            if (position <= 0 || runtime <= 0) return null
            return (position.toDouble() / runtime).coerceIn(0.0, 1.0)
        }

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
data class QuickConnectState(
    @SerialName("Secret") val secret: String,
    @SerialName("Code") val code: String,
    @SerialName("Authenticated") val authenticated: Boolean = false,
)

@Serializable
internal data class QuickConnectAuthRequest(
    @SerialName("Secret") val secret: String,
)

@Serializable
data class MediaStream(
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = true,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream>? = null,
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

/** An external text subtitle the player must side-load. */
data class ExternalSubtitle(
    val url: String,
    val language: String?,
    val title: String?,
    val codec: String?,
)

/** The server-negotiated way to play one item. */
data class PlaybackPlan(
    val url: String,
    val isTranscode: Boolean,
    val externalSubtitles: List<ExternalSubtitle>,
    /** Identifies the transcode job so Stopped can terminate it server-side. */
    val playSessionId: String? = null,
    /**
     * Where the stream's clock starts within the media, in seconds. A
     * transcode window opens at the resume point (StartTimeTicks), so the
     * player's position is window-relative; media time = player position +
     * this offset. 0 for Direct Play, where the clock is absolute.
     */
    val startOffsetSeconds: Double = 0.0,
)

@Serializable
internal data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

/**
 * One entry of `GET /MediaSegments/{itemId}` (Jellyfin 10.10+), fed
 * server-side by plugins like Intro Skipper. Type is kept as the wire
 * string — servers may grow new values ("Recap", "Commercial", …).
 */
@Serializable
data class MediaSegment(
    @SerialName("Id") val id: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("StartTicks") val startTicks: Long? = null,
    @SerialName("EndTicks") val endTicks: Long? = null,
) {
    val startSeconds: Double
        get() = (startTicks ?: 0L) / 10_000_000.0

    val endSeconds: Double
        get() = (endTicks ?: 0L) / 10_000_000.0

    val isIntro: Boolean get() = type == "Intro"
    val isOutro: Boolean get() = type == "Outro"
}

@Serializable
data class MediaSegmentsResult(
    @SerialName("Items") val items: List<MediaSegment> = emptyList(),
)

/**
 * When to offer the skip button — the single decision point for all four
 * platforms. A segment is skippable while playback sits inside it, minus
 * a tail margin (skipping the last moments is pointless and the button
 * would flash), and only if it is long enough to be worth a button.
 */
object SkipSegments {
    /** Segments shorter than this never get a button. */
    const val MIN_SEGMENT_SECONDS = 5.0

    /** The button hides this many seconds before the segment ends. */
    const val TAIL_MARGIN_SECONDS = 2.0

    /**
     * The Intro/Outro segment the given playback position is inside, or
     * null when no button should be shown. [positionSeconds] must be in
     * media time (add the transcode window offset when the server started
     * the stream mid-file).
     */
    fun activeSegment(segments: List<MediaSegment>, positionSeconds: Double): MediaSegment? =
        segments.firstOrNull { segment ->
            (segment.isIntro || segment.isOutro) &&
                segment.endSeconds - segment.startSeconds >= MIN_SEGMENT_SECONDS &&
                positionSeconds >= segment.startSeconds &&
                positionSeconds < segment.endSeconds - TAIL_MARGIN_SECONDS
        }
}

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
