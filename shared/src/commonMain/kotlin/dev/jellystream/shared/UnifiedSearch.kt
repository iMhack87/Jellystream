package dev.jellystream.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * One row of a search that does not care which server the answer came
 * from.
 *
 * Splitting "what you have" from "what you could ask for" across two
 * screens meant going into Settings to request something — which is a
 * detour nobody takes. Here they are the same list, and the only
 * difference is what the row's button does.
 */
data class SearchHit(
    /** Set when the item is on the Jellyfin server and can be played. */
    val jellyfin: BaseItem? = null,
    /** Set when Jellyseerr knows the title, whether or not we have it. */
    val jellyseerr: JellyseerrResult? = null,
) {
    val isOnServer: Boolean
        get() = jellyfin != null

    val title: String
        get() = jellyfin?.name ?: jellyseerr?.displayTitle ?: "Untitled"

    val year: String?
        get() = jellyfin?.productionYear?.toString() ?: jellyseerr?.year

    val isSeries: Boolean
        get() = jellyfin?.isSeries ?: jellyseerr?.isSeries ?: false

    /** TMDb id when anything knows one — the only cross-server identity. */
    val tmdbId: Int?
        get() = jellyseerr?.id ?: jellyfin?.tmdbId

    /**
     * What the row's button should offer. Null on the server: there is
     * nothing to ask for, the thing is right there.
     */
    val requestState: RequestState?
        get() = if (isOnServer) null else jellyseerr?.state

    /** "Film · 2024" / "Series · 2022 · on the server" */
    val subtitle: String
        get() = listOfNotNull(
            if (isSeries) "Series" else "Film",
            year,
            if (isOnServer) "on the server" else null,
        ).joinToString(" · ")
}

/** Which rows a search shows. Both axes default to everything. */
enum class SearchKind { ALL, FILMS, SERIES }

enum class SearchAvailability {
    ALL,

    /** Only what can be played right now. */
    ON_SERVER,

    /** Only what would have to be asked for. */
    REQUESTABLE,
}

/**
 * Merges the two servers' answers into one list.
 *
 * Everything here is pure so the merge rules can be tested without a
 * server, which matters because the interesting part is not the fetching.
 */
object UnifiedSearch {

    /**
     * Jellyfin's search endpoint returns trimmed DTOs with no ProviderIds,
     * so a title on the server and the same title in Jellyseerr cannot be
     * matched by id. Name and year is what is left.
     *
     * Normalising hard on purpose: "The Batman" and "Batman, The" and
     * "batman" are one film to a person looking at two rows for it.
     */
    fun matchKey(title: String?, year: String?): String {
        val name = title.orEmpty()
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() && it !in NOISE_WORDS }
            .joinToString(" ")
        return if (year.isNullOrBlank()) name else "$name|$year"
    }

    private val NOISE_WORDS = setOf("the", "a", "an", "le", "la", "les", "un", "une")

    /**
     * One list, server items first, each Jellyseerr title folded into its
     * server twin when there is one.
     *
     * Folded rather than dropped: the merged row keeps the TMDb id, which
     * is what lets a partly-available show still offer its missing
     * seasons even though it is "on the server".
     */
    fun merge(
        onServer: List<BaseItem>,
        requestable: List<JellyseerrResult>,
        kind: SearchKind = SearchKind.ALL,
        availability: SearchAvailability = SearchAvailability.ALL,
    ): List<SearchHit> {
        // Episodes are dropped: a search for a show should offer the show,
        // not forty rows of it. The series is reachable from there.
        val items = onServer.filter { it.type == "Movie" || it.type == "Series" }

        val byKey = mutableMapOf<String, Int>()
        // Keyed by id AND kind: TMDb numbers films and shows in separate
        // sequences, so film 550 and series 550 are unrelated titles and
        // a bare integer is not an identity.
        val byTmdb = mutableMapOf<Pair<Int, Boolean>, Int>()
        val hits = mutableListOf<SearchHit>()
        for (item in items) {
            byKey[matchKey(item.name, item.productionYear?.toString())] = hits.size
            // The search call asks for ProviderIds, so this is usually
            // present — and it is the only match that cannot be wrong.
            item.tmdbId?.let { byTmdb[it to item.isSeries] = hits.size }
            hits.add(SearchHit(jellyfin = item))
        }

        for (result in requestable) {
            // Id first, name second. Matching on name and year alone
            // merges a remake into its original; matching on the id
            // cannot, and the fallback only runs on servers too old or
            // too unscanned to have one.
            val at = byTmdb[result.id to result.isSeries]
                ?: byKey[matchKey(result.displayTitle, result.year)]
            if (at != null) {
                hits[at] = hits[at].copy(jellyseerr = result)
            } else {
                hits.add(SearchHit(jellyseerr = result))
            }
        }

        return hits.filter { it.matches(kind) && it.matches(availability) }
    }

    private fun SearchHit.matches(kind: SearchKind): Boolean = when (kind) {
        SearchKind.ALL -> true
        SearchKind.FILMS -> !isSeries
        SearchKind.SERIES -> isSeries
    }

    private fun SearchHit.matches(availability: SearchAvailability): Boolean =
        when (availability) {
            SearchAvailability.ALL -> true
            SearchAvailability.ON_SERVER -> isOnServer
            SearchAvailability.REQUESTABLE -> !isOnServer
        }
}

/**
 * Runs the two searches side by side and merges them.
 *
 * Jellyseerr is optional: without a server configured this is exactly the
 * old library search, and a Jellyseerr that will not answer costs the
 * requestable half, never the results.
 */
class UnifiedSearcher(
    private val jellyfin: JellyfinApi,
    private val seerr: JellyseerrApi,
) {
    @Throws(Throwable::class)
    suspend fun search(
        query: String,
        kind: SearchKind,
        availability: SearchAvailability,
    ): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return try {
            coroutineScope {
                val library = async {
                    // The library half is the one people expect to work,
                    // so it degrades on its own rather than taking the
                    // whole search down with it.
                    try {
                        jellyfin.search(query, SEARCH_LIMIT)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                val catalogue = async {
                    if (seerr.isConfigured) seerr.search(query) else emptyList()
                }
                UnifiedSearch.merge(
                    onServer = library.await(),
                    requestable = catalogue.await(),
                    kind = kind,
                    availability = availability,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val SEARCH_LIMIT = 24
    }
}
