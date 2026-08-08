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
    /**
     * Only the seasons Jellyseerr has an opinion about — a show it has
     * never touched carries an empty list, not one entry per season.
     */
    @SerialName("seasons") val seasons: List<JellyseerrSeasonStatus> = emptyList(),
    @SerialName("downloadStatus") val downloadStatus: List<JellyseerrDownload> = emptyList(),
)

/**
 * What Sonarr or Radarr is fetching right now, as Jellyseerr relays it.
 *
 * Undocumented in the OpenAPI spec: the field is a plain property on the
 * Media entity filled in after every load, so it rides along on any
 * endpoint that returns media and is never announced. Sizes are read as
 * doubles on purpose — one server sending `1234.0` where another sends
 * `1234` would otherwise fail the whole list and blank the screen.
 */
@Serializable
data class JellyseerrDownload(
    @SerialName("size") val size: Double = 0.0,
    /** Bytes still to come; it counts DOWN to zero. */
    @SerialName("sizeLeft") val sizeLeft: Double = 0.0,
    /** Sonarr's own vocabulary, passed through untouched. */
    @SerialName("status") val status: String? = null,
    /** .NET TimeSpan, e.g. "00:12:34" or "1.03:00:00". Absent when stalled. */
    @SerialName("timeLeft") val timeLeft: String? = null,
    @SerialName("estimatedCompletionTime") val estimatedCompletionTime: String? = null,
    /** The release name, not the title anyone would recognise. */
    @SerialName("title") val title: String? = null,
    @SerialName("mediaType") val mediaType: String? = null,
    @SerialName("externalId") val externalId: Int? = null,
    /** Series only — a film never carries one. */
    @SerialName("episode") val episode: JellyseerrDownloadEpisode? = null,
)

@Serializable
data class JellyseerrDownloadEpisode(
    @SerialName("seasonNumber") val seasonNumber: Int? = null,
    @SerialName("episodeNumber") val episodeNumber: Int? = null,
)

/**
 * Jellyseerr's per-season verdict, carried on the media row.
 *
 * [status] is a MediaStatus, the same enum as the title-level status —
 * NOT the request status, despite sharing the field name with it.
 */
@Serializable
data class JellyseerrSeasonStatus(
    @SerialName("seasonNumber") val seasonNumber: Int = 0,
    @SerialName("status") val status: Int? = null,
)

/**
 * One season of a show as TMDb knows it — every season that exists,
 * whether or not anybody has it.
 */
@Serializable
data class JellyseerrSeason(
    @SerialName("seasonNumber") val seasonNumber: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("episodeCount") val episodeCount: Int? = null,
    @SerialName("airDate") val airDate: String? = null,
    @SerialName("posterPath") val posterPath: String? = null,
) {
    /** Season 0 is TMDb's bucket for specials. */
    val isSpecials: Boolean
        get() = seasonNumber == 0

    val year: String?
        get() = airDate?.takeIf { it.length >= 4 }?.take(4)

    /** TMDb localises the name; fall back rather than show an empty pill. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: if (isSpecials) "Specials" else "Season $seasonNumber"
}

/**
 * A show's detail page: the seasons that exist, and what Jellyseerr
 * makes of each one.
 */
@Serializable
data class JellyseerrTvDetails(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("posterPath") val posterPath: String? = null,
    @SerialName("firstAirDate") val firstAirDate: String? = null,
    @SerialName("seasons") val seasons: List<JellyseerrSeason> = emptyList(),
    @SerialName("mediaInfo") val mediaInfo: JellyseerrMediaInfo? = null,
) {
    /**
     * Specials are dropped: asking for "all" excludes season 0 server-side,
     * so offering it in a picker would promise something the button on the
     * same screen does not deliver.
     */
    val requestableSeasons: List<JellyseerrSeason>
        get() = seasons.filter { !it.isSpecials }.sortedBy { it.seasonNumber }

    /** Every season number that exists upstream, specials excluded. */
    val seasonNumbers: List<Int>
        get() = requestableSeasons.map { it.seasonNumber }

    /**
     * Where one season stands. The media row lists only seasons Jellyseerr
     * knows, so an absent entry means nobody has asked — which is exactly
     * what [RequestState.of] calls REQUESTABLE.
     */
    fun stateOf(seasonNumber: Int): RequestState =
        RequestState.of(mediaInfo?.seasons?.firstOrNull { it.seasonNumber == seasonNumber }?.status)

    /**
     * Whether asking for this one season would do anything.
     *
     * Narrower than [RequestState.canRequest], and deliberately so: that
     * rule allows a partly-available *title*, because the seasons nobody
     * has are still worth asking for. A partly-available *season* is a
     * different thing — Jellyseerr drops every season whose status is
     * anything but unknown, so the request comes back refused. Offering
     * the button anyway is a promise the server will not keep.
     */
    fun canRequestSeason(seasonNumber: Int): Boolean =
        stateOf(seasonNumber) == RequestState.REQUESTABLE

    val year: String?
        get() = firstAirDate?.takeIf { it.length >= 4 }?.take(4)
}

/** A film's detail page — fetched only to put a name on a request row. */
@Serializable
data class JellyseerrMovieDetails(
    @SerialName("id") val id: Int = 0,
    @SerialName("title") val title: String? = null,
    @SerialName("posterPath") val posterPath: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("mediaInfo") val mediaInfo: JellyseerrMediaInfo? = null,
) {
    val year: String?
        get() = releaseDate?.takeIf { it.length >= 4 }?.take(4)
}

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
    @SerialName("downloadStatus") val downloadStatus: List<JellyseerrDownload> = emptyList(),
)

/**
 * A season named by a request.
 *
 * [status] is a MediaRequestStatus here — 1 pending, 2 approved,
 * 3 declined, 4 failed, 5 completed — and not the MediaStatus that
 * [JellyseerrSeasonStatus] carries under the very same field name.
 */
@Serializable
data class JellyseerrRequestSeason(
    @SerialName("id") val id: Int = 0,
    @SerialName("seasonNumber") val seasonNumber: Int = 0,
    @SerialName("status") val status: Int? = null,
)

/** One row of "my requests". */
@Serializable
data class JellyseerrRequest(
    @SerialName("id") val id: Int,
    @SerialName("status") val status: Int? = null,
    @SerialName("media") val media: JellyseerrRequestMedia? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("seasons") val seasons: List<JellyseerrRequestSeason> = emptyList(),
) {
    val state: RequestState
        get() = RequestState.of(media?.status, status)

    val isSeries: Boolean
        get() = media?.mediaType == "tv"

    /**
     * How far along this request is, or null when nothing is moving.
     *
     * Scoped to the seasons this row asked for. The download list hangs
     * off the *media*, not the request, so two season requests on one
     * show would otherwise show the same aggregate bar twice — each
     * claiming the other's progress as its own.
     */
    val progress: RequestProgress?
        get() {
            val grabs = media?.downloadStatus ?: emptyList()
            val wanted = seasons.map { it.seasonNumber }.toSet()
            val mine = if (wanted.isEmpty()) {
                grabs
            } else {
                grabs.filter { it.episode?.seasonNumber in wanted }
            }
            return RequestProgress.of(mine)
        }

    /**
     * Whether this row can still change on its own — the condition for
     * polling. Progress alone is too late: a request approved a second
     * ago has no grab yet, and if nothing watches for one, the bar never
     * turns up at all.
     */
    val isSettling: Boolean
        get() = state == RequestState.PENDING || state == RequestState.PROCESSING

    /**
     * "Season 2" / "Seasons 2, 3" — what this row actually asked for.
     * Null for a film, and for a series requested before Jellyseerr
     * started reporting seasons.
     */
    val seasonsLabel: String?
        get() {
            val numbers = seasons.map { it.seasonNumber }.filter { it > 0 }.sorted().distinct()
            return when {
                numbers.isEmpty() -> null
                numbers.size == 1 -> "Season ${numbers.first()}"
                else -> "Seasons " + numbers.joinToString(", ")
            }
        }
}

@Serializable
internal data class JellyseerrRequestPage(
    @SerialName("results") val results: List<JellyseerrRequest> = emptyList(),
)

/**
 * A request with a name on it.
 *
 * The request list answers with a TMDb id and nothing else — no title, no
 * poster — which is why an unenriched row can only say "Series request".
 * Jellyseerr's own web client fetches the detail endpoint per row to fill
 * that in, and so does [JellyseerrApi.myRequestsDetailed].
 */
data class RequestedTitle(
    val request: JellyseerrRequest,
    val title: String? = null,
    val posterPath: String? = null,
    val year: String? = null,
) {
    /** Never blank: an unreachable detail endpoint still gets a row. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: if (isSeries) "Series request" else "Film request"

    val isSeries: Boolean
        get() = request.isSeries

    val state: RequestState
        get() = request.state

    val progress: RequestProgress?
        get() = request.progress

    /** Whether this row is still worth polling for. */
    val isSettling: Boolean
        get() = request.isSettling

    /** "Series · 2022 · Season 2" — everything under the title, in one line. */
    val subtitle: String
        get() = listOfNotNull(
            if (isSeries) "Series" else "Film",
            year,
            request.seasonsLabel,
        ).joinToString(" · ")
}

@Serializable
internal data class JellyseerrUser(
    @SerialName("id") val id: Int? = null,
    @SerialName("displayName") val displayName: String? = null,
)
