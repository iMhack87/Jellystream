package dev.jellystream.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing you mean to watch.
 *
 * Deliberately not the same as a favourite. A favourite is Jellyfin's own
 * flag — "I like this", it lives on the server and every client sees it.
 * A watchlist entry is "I want to see this", and the whole reason it
 * cannot be a server flag is that it must be able to hold a title the
 * server does not have yet.
 *
 * Identity is therefore two-headed: [itemId] for something in the
 * library, [tmdbId] for something only Jellyseerr knows. An entry added
 * before the download landed keeps its TMDb id and picks up an item id
 * the first time it is seen on the server.
 */
@Serializable
data class WatchlistEntry(
    @SerialName("itemId") val itemId: String? = null,
    @SerialName("tmdbId") val tmdbId: Int? = null,
    @SerialName("title") val title: String = "",
    @SerialName("year") val year: String? = null,
    @SerialName("isSeries") val isSeries: Boolean = false,
    /** TMDb path, for the rows that have no Jellyfin artwork to show. */
    @SerialName("posterPath") val posterPath: String? = null,
) {
    /** Whether this entry can be played right now. */
    val isOnServer: Boolean
        get() = itemId != null

    /**
     * The title, normalised, for when neither side knows the other's id.
     *
     * Kind is part of it: TMDb numbers its films and its shows separately,
     * so film 550 and series 550 are different things.
     */
    internal val matchKey: String?
        get() = title.takeIf { it.isNotBlank() }
            ?.let { UnifiedSearch.matchKey(it, year) + if (isSeries) "|tv" else "|film" }

    /**
     * Whether two entries are the same title.
     *
     * Either identifier is enough — the same film arrives as a TMDb id
     * from search and later as an item id from the library. But BOTH
     * sides having the same identifier populated is exactly what cannot
     * be relied on: ProviderIds only ever comes back on a single-item
     * fetch, so anything picked off a shelf carries no TMDb id at all.
     * Without the name fallback the list happily holds the same show
     * twice, and [Watchlist.reconciled] then stamps one item id onto both.
     */
    fun isSameAs(other: WatchlistEntry): Boolean {
        if (itemId != null && itemId == other.itemId) return true
        if (tmdbId != null && tmdbId == other.tmdbId && isSeries == other.isSeries) return true
        val key = matchKey ?: return false
        return key == other.matchKey
    }

    companion object {
        fun of(item: BaseItem): WatchlistEntry = WatchlistEntry(
            itemId = item.id,
            tmdbId = item.tmdbId,
            title = item.name ?: "Untitled",
            year = item.productionYear?.toString(),
            isSeries = item.isSeries,
        )

        fun of(result: JellyseerrResult): WatchlistEntry = WatchlistEntry(
            tmdbId = result.id,
            title = result.displayTitle,
            year = result.year,
            isSeries = result.isSeries,
            posterPath = result.posterPath,
        )

        fun of(hit: SearchHit): WatchlistEntry =
            hit.jellyfin?.let { item ->
                // Keep the TMDb id the search half knew, so the entry
                // still means something if the file is later removed
                of(item).copy(tmdbId = hit.tmdbId, posterPath = hit.jellyseerr?.posterPath)
            } ?: of(hit.jellyseerr!!)
    }
}

/**
 * The list itself, persisted per profile.
 *
 * Newest first, because a watchlist is read from the top and the thing
 * you just added is the thing you were thinking about.
 */
@Serializable
data class Watchlist(
    @SerialName("entries") val entries: List<WatchlistEntry> = emptyList(),
) {
    fun contains(entry: WatchlistEntry): Boolean = entries.any { it.isSameAs(entry) }

    fun contains(item: BaseItem): Boolean = contains(WatchlistEntry.of(item))

    /** Adding something already on the list moves it to the top rather than duplicating it. */
    fun with(entry: WatchlistEntry): Watchlist =
        Watchlist(listOf(entry) + entries.filterNot { it.isSameAs(entry) })

    fun without(entry: WatchlistEntry): Watchlist =
        Watchlist(entries.filterNot { it.isSameAs(entry) })

    fun toggled(entry: WatchlistEntry): Watchlist =
        if (contains(entry)) without(entry) else with(entry)

    /**
     * Fills in item ids for entries whose title has since landed.
     *
     * Without this an entry added from search stays un-playable for ever:
     * it only knows a TMDb id, and nothing on the home screen can open a
     * TMDb id.
     */
    fun reconciled(onServer: List<BaseItem>): Watchlist {
        if (entries.none { it.itemId == null }) return this

        val byKey = onServer.associateBy {
            UnifiedSearch.matchKey(it.name, it.productionYear?.toString())
        }
        // A second index without the year. An entry saved from Jellyseerr
        // before its release date was known has no year, and would never
        // match a library item that has one — leaving it un-openable for
        // ever, which is exactly the state this function exists to end.
        val byName = onServer.associateBy { UnifiedSearch.matchKey(it.name, null) }

        // Item ids already spoken for. Two entries carrying the same id is
        // a duplicate key in a lazy row, which is a crash rather than a
        // cosmetic problem.
        val taken = entries.mapNotNull { it.itemId }.toMutableSet()

        val out = mutableListOf<WatchlistEntry>()
        for (entry in entries) {
            if (entry.itemId != null) {
                out.add(entry)
                continue
            }
            val match = byKey[UnifiedSearch.matchKey(entry.title, entry.year)]
                ?: byName[UnifiedSearch.matchKey(entry.title, null)]
            when {
                match == null -> out.add(entry)
                // Another entry is already this item: the two are the same
                // title reached by different routes, so keep the one that
                // can be opened and drop this one rather than mint a twin.
                match.id in taken -> Unit
                else -> {
                    taken.add(match.id)
                    out.add(entry.copy(itemId = match.id))
                }
            }
        }
        return Watchlist(out)
    }

    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): Watchlist? =
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
    }
}
