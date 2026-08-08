package dev.jellystream.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Something you asked for has landed. One line, shown once. */
data class Arrival(
    val requestId: Int,
    val title: String,
    val isSeries: Boolean,
    val seasonsLabel: String?,
) {
    /** "Severance season 2 has arrived" */
    val message: String
        get() = buildString {
            append(title)
            seasonsLabel?.let { append(" ").append(it.lowercase()) }
            append(" has arrived")
        }
}

/**
 * Which requests have already been announced.
 *
 * Persisted, and that is the entire point: without it, every cold start
 * re-announces everything that has ever been requested, and the toast
 * becomes something people learn to ignore within a day.
 */
@Serializable
data class AnnouncedArrivals(
    @SerialName("requestIds") val requestIds: Set<Int> = emptySet(),
    /**
     * Which Jellyseerr these ids came from.
     *
     * Request ids are that server's numbering and nobody else's. Point
     * the profile at a different Jellyseerr and the stored ids silence
     * arrivals that should be announced and announce ones that already
     * happened — the same integers meaning different titles.
     */
    @SerialName("server") val server: String? = null,
) {
    fun with(ids: Collection<Int>): AnnouncedArrivals =
        copy(requestIds = requestIds + ids)

    /**
     * What to start from for [server]: this set if it belongs to it, an
     * empty one otherwise. An empty one is a first look, which announces
     * nothing — right, because a new server's existing titles were not
     * waited for by anyone here.
     */
    fun forServer(server: String?): AnnouncedArrivals =
        if (this.server == server) this else AnnouncedArrivals(server = server)

    /**
     * Keeps the newest [limit] ids, so the set cannot grow for ever.
     *
     * Deliberately NOT "forget anything missing from the last answer":
     * the poll asks for one page of the newest requests, so an announced
     * id outside that window is absent from every single answer. Pruning
     * against it drops the id, and the moment that request slides back
     * into the page — someone tidies up the newer ones — it is announced
     * a second time. Jellyseerr's ids only ever climb, so the highest are
     * the ones worth keeping.
     */
    fun capped(limit: Int): AnnouncedArrivals =
        if (requestIds.size <= limit) this
        else copy(requestIds = requestIds.sortedDescending().take(limit).toSet())

    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): AnnouncedArrivals? =
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
    }
}

/**
 * Decides what is worth interrupting someone for.
 *
 * Pure, because the interesting part is what NOT to announce: a request
 * that was already available the first time we looked was not waited for,
 * and saying "has arrived" about it is a lie the second the app is
 * installed on a new device.
 */
object Arrivals {

    /**
     * Titles that have become available and have not been announced.
     *
     * [firstLook] is true on the very first poll after a fresh install or
     * a new profile: everything already available is recorded silently,
     * because none of it arrived while anyone was watching.
     */
    fun landed(
        requests: List<RequestedTitle>,
        announced: AnnouncedArrivals,
        firstLook: Boolean,
    ): List<Arrival> {
        if (firstLook) return emptyList()
        return requests
            .filter { it.state == RequestState.AVAILABLE }
            .filter { it.request.id !in announced.requestIds }
            .map {
                Arrival(
                    requestId = it.request.id,
                    title = it.displayTitle,
                    isSeries = it.isSeries,
                    seasonsLabel = it.request.seasonsLabel,
                )
            }
    }

    /**
     * The set to persist right after a poll.
     *
     * Everything available EXCEPT what is being announced. A first look
     * is recorded whole, so it stays silent for good instead of
     * announcing on the second poll. But a title whose notice is still
     * queued is deliberately left out: writing it here means a queue
     * dropped before it ever appeared — a profile switch, the app being
     * killed — loses that notice for good, and nothing will ever raise
     * it again. Those ids are recorded by [seenAfterShowing], once the
     * notice has actually been on screen.
     */
    fun seen(
        requests: List<RequestedTitle>,
        announced: AnnouncedArrivals,
        announcing: List<Arrival>,
    ): AnnouncedArrivals {
        val queued = announcing.map { it.requestId }.toSet()
        return announced
            .with(
                requests
                    .filter { it.state == RequestState.AVAILABLE }
                    .map { it.request.id }
                    .filterNot { it in queued }
            )
            .capped(REMEMBERED)
    }

    /** Records one notice that has now been shown. */
    fun seenAfterShowing(arrival: Arrival, announced: AnnouncedArrivals): AnnouncedArrivals =
        announced.with(listOf(arrival.requestId)).capped(REMEMBERED)

    /**
     * How many announced ids to keep. Comfortably more than a page of
     * requests, so nothing still visible can be forgotten and announced
     * again, and small enough that the blob stays trivial.
     */
    const val REMEMBERED = 500
}
