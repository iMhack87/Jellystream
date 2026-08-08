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
) {
    fun with(ids: Collection<Int>): AnnouncedArrivals =
        AnnouncedArrivals(requestIds + ids)

    /**
     * Forgets requests that no longer exist, so the set cannot grow for
     * ever on a server where requests get deleted.
     */
    fun prunedTo(known: Collection<Int>): AnnouncedArrivals =
        AnnouncedArrivals(requestIds intersect known.toSet())

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
     * The set to persist after a poll: everything available is recorded
     * whether or not it was announced, so a first look stays silent for
     * good rather than announcing on the second poll instead.
     */
    fun seen(requests: List<RequestedTitle>, announced: AnnouncedArrivals): AnnouncedArrivals =
        announced
            .with(requests.filter { it.state == RequestState.AVAILABLE }.map { it.request.id })
            .prunedTo(requests.map { it.request.id })
}
