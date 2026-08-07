package dev.jellystream.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a title's request situation means to the person looking at it.
 *
 * Jellyseerr answers with two unrelated integer enums — one for how far
 * the media has got, one for how the request itself was handled — and the
 * screen only ever wants a single answer: can I ask for this, and if not,
 * why not.
 */
enum class RequestState {
    /** Already on the server; the library screens own it now. */
    AVAILABLE,

    /** Some seasons landed, the rest has not. Still worth asking for. */
    PARTIALLY_AVAILABLE,

    /** Approved and downloading. */
    PROCESSING,

    /** Asked for, waiting on someone to approve. */
    PENDING,

    /** Someone said no. Asking again is the user's call, not ours. */
    DECLINED,

    /** Nothing yet. */
    REQUESTABLE;

    /** Short label, identical on every platform. */
    val label: String
        get() = when (this) {
            AVAILABLE -> "Available"
            PARTIALLY_AVAILABLE -> "Partly available"
            PROCESSING -> "Downloading"
            PENDING -> "Awaiting approval"
            DECLINED -> "Declined"
            REQUESTABLE -> "Request"
        }

    /** Whether the Request button does anything. */
    val canRequest: Boolean
        get() = this == REQUESTABLE || this == PARTIALLY_AVAILABLE || this == DECLINED

    companion object {
        // Jellyseerr's MediaStatus, straight off the wire
        private const val MEDIA_UNKNOWN = 1
        private const val MEDIA_PENDING = 2
        private const val MEDIA_PROCESSING = 3
        private const val MEDIA_PARTIALLY_AVAILABLE = 4
        private const val MEDIA_AVAILABLE = 5

        // Jellyseerr's MediaRequestStatus
        private const val REQUEST_PENDING = 1
        private const val REQUEST_DECLINED = 3

        /**
         * [mediaStatus] is `mediaInfo.status`, absent for anything nobody
         * has ever asked for. [requestStatus] is the most recent request's
         * own status, which is the only place a refusal shows up.
         *
         * An unrecognised value falls to [REQUESTABLE] on purpose: asking
         * for something already there costs a rejected call, while wrongly
         * calling it available hides the button with no way back.
         */
        fun of(mediaStatus: Int?, requestStatus: Int? = null): RequestState {
            if (requestStatus == REQUEST_DECLINED) return DECLINED
            return when (mediaStatus) {
                MEDIA_AVAILABLE -> AVAILABLE
                MEDIA_PARTIALLY_AVAILABLE -> PARTIALLY_AVAILABLE
                MEDIA_PROCESSING -> PROCESSING
                MEDIA_PENDING -> PENDING
                MEDIA_UNKNOWN, null ->
                    // A request can sit pending before the media row has
                    // caught up, and that still means "already asked for"
                    if (requestStatus == REQUEST_PENDING) PENDING else REQUESTABLE
                else -> REQUESTABLE
            }
        }
    }
}

@Serializable
data class JellyseerrMediaInfo(
    @SerialName("status") val status: Int? = null,
    @SerialName("tmdbId") val tmdbId: Int? = null,
)

/** One search hit. Jellyseerr names a film "title" and a show "name". */
@Serializable
data class JellyseerrResult(
    @SerialName("id") val id: Int,
    @SerialName("mediaType") val mediaType: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("posterPath") val posterPath: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("firstAirDate") val firstAirDate: String? = null,
    @SerialName("mediaInfo") val mediaInfo: JellyseerrMediaInfo? = null,
) {
    val displayTitle: String
        get() = title ?: name ?: "Untitled"

    /** Four digits, or null — the full date is noise in a result row. */
    val year: String?
        get() = (releaseDate ?: firstAirDate)?.takeIf { it.length >= 4 }?.take(4)

    val isSeries: Boolean
        get() = mediaType == "tv"

    /** People come back from the same endpoint and cannot be requested. */
    val isRequestableKind: Boolean
        get() = mediaType == "tv" || mediaType == "movie"

    val state: RequestState
        get() = RequestState.of(mediaInfo?.status)
}

@Serializable
internal data class JellyseerrSearchResponse(
    @SerialName("results") val results: List<JellyseerrResult> = emptyList(),
    @SerialName("totalPages") val totalPages: Int = 1,
)

/** The media block carried by a request, thinner than a search hit. */
@Serializable
data class JellyseerrRequestMedia(
    @SerialName("tmdbId") val tmdbId: Int? = null,
    @SerialName("mediaType") val mediaType: String? = null,
    @SerialName("status") val status: Int? = null,
)

/** One row of "my requests". */
@Serializable
data class JellyseerrRequest(
    @SerialName("id") val id: Int,
    @SerialName("status") val status: Int? = null,
    @SerialName("media") val media: JellyseerrRequestMedia? = null,
    @SerialName("createdAt") val createdAt: String? = null,
) {
    val state: RequestState
        get() = RequestState.of(media?.status, status)

    val isSeries: Boolean
        get() = media?.mediaType == "tv"
}

@Serializable
internal data class JellyseerrRequestPage(
    @SerialName("results") val results: List<JellyseerrRequest> = emptyList(),
)

@Serializable
internal data class JellyseerrUser(
    @SerialName("id") val id: Int? = null,
    @SerialName("displayName") val displayName: String? = null,
)
