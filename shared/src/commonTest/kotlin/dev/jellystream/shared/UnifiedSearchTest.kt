package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun film(name: String, year: Int?, id: String = name) =
    BaseItem(id = id, name = name, type = "Movie", productionYear = year)

private fun show(name: String, year: Int?, id: String = name) =
    BaseItem(id = id, name = name, type = "Series", productionYear = year)

private fun catalogue(
    id: Int,
    title: String,
    year: String?,
    series: Boolean = false,
    status: Int? = null,
) = JellyseerrResult(
    id = id,
    mediaType = if (series) "tv" else "movie",
    title = if (series) null else title,
    name = if (series) title else null,
    releaseDate = if (series) null else year?.let { "$it-01-01" },
    firstAirDate = if (series) year?.let { "$it-01-01" } else null,
    mediaInfo = status?.let { JellyseerrMediaInfo(status = it) },
)

class UnifiedSearchMergeTest {

    @Test
    fun theServerComesFirstAndTheCatalogueFillsInTheRest() {
        val hits = UnifiedSearch.merge(
            onServer = listOf(film("Dune", 2021)),
            requestable = listOf(catalogue(693134, "Dune: Part Two", "2024")),
        )

        assertEquals(listOf("Dune", "Dune: Part Two"), hits.map { it.title })
        assertTrue(hits[0].isOnServer)
        assertFalse(hits[1].isOnServer)
    }

    @Test
    fun oneTitleOnBothSidesIsOneRow() {
        // Jellyfin's search DTO carries no TMDb id, so name and year is
        // all there is to match on — and two rows for one film is exactly
        // what unifying the search was supposed to stop
        val hits = UnifiedSearch.merge(
            onServer = listOf(film("Dune", 2021)),
            requestable = listOf(catalogue(438631, "Dune", "2021", status = 5)),
        )

        assertEquals(1, hits.size)
        assertTrue(hits[0].isOnServer)
        // The merged row keeps the id, which is what lets a partly
        // available show still offer its missing seasons
        assertEquals(438631, hits[0].tmdbId)
    }

    @Test
    fun articlesAndPunctuationDoNotMakeItADifferentFilm() {
        assertEquals(
            UnifiedSearch.matchKey("The Batman", "2022"),
            UnifiedSearch.matchKey("batman", "2022"),
        )
        assertEquals(
            UnifiedSearch.matchKey("Spider-Man: No Way Home", "2021"),
            UnifiedSearch.matchKey("Spider Man  No Way Home", "2021"),
        )
    }

    @Test
    fun aRemakeIsNotTheOriginal() {
        // Same name, different year: two real films, two rows
        val hits = UnifiedSearch.merge(
            onServer = listOf(film("Dune", 1984)),
            requestable = listOf(catalogue(438631, "Dune", "2021")),
        )

        assertEquals(2, hits.size)
    }

    @Test
    fun episodesNeverAppearInTheResults() {
        // Searching a show should offer the show, not forty rows of it
        val episode = BaseItem(id = "e1", name = "Dune", type = "Episode", productionYear = 2021)

        assertTrue(UnifiedSearch.merge(listOf(episode), emptyList()).isEmpty())
    }

    @Test
    fun peopleAreAlreadyGoneByTheTimeTheyGetHere() {
        // JellyseerrApi.search drops them; this is just proof that a row
        // with no usable identity does not slip through the merge
        val hits = UnifiedSearch.merge(emptyList(), listOf(catalogue(1, "Denis Villeneuve", null)))

        assertEquals(1, hits.size)
        assertEquals("Denis Villeneuve", hits[0].title)
    }

    @Test
    fun theFiltersNarrowBothAxesIndependently() {
        val onServer = listOf(film("Dune", 2021), show("Severance", 2022))
        val requestable = listOf(
            catalogue(693134, "Dune: Part Two", "2024"),
            catalogue(1399, "Game of Thrones", "2011", series = true),
        )

        assertEquals(4, UnifiedSearch.merge(onServer, requestable).size)
        assertEquals(
            listOf("Dune", "Dune: Part Two"),
            UnifiedSearch.merge(onServer, requestable, kind = SearchKind.FILMS).map { it.title },
        )
        assertEquals(
            listOf("Severance", "Game of Thrones"),
            UnifiedSearch.merge(onServer, requestable, kind = SearchKind.SERIES).map { it.title },
        )
        assertEquals(
            listOf("Dune", "Severance"),
            UnifiedSearch.merge(onServer, requestable, availability = SearchAvailability.ON_SERVER)
                .map { it.title },
        )
        assertEquals(
            listOf("Dune: Part Two", "Game of Thrones"),
            UnifiedSearch.merge(onServer, requestable, availability = SearchAvailability.REQUESTABLE)
                .map { it.title },
        )
    }

    @Test
    fun aRowOnTheServerHasNothingToRequest() {
        val hits = UnifiedSearch.merge(listOf(film("Dune", 2021)), emptyList())

        assertNull(hits[0].requestState)
        assertEquals("Film · 2021 · on the server", hits[0].subtitle)
    }

    @Test
    fun aRowOnlyTheCatalogueKnowsCarriesItsRequestState() {
        val hits = UnifiedSearch.merge(
            emptyList(),
            listOf(catalogue(1399, "Game of Thrones", "2011", series = true, status = 3)),
        )

        assertEquals(RequestState.PROCESSING, hits[0].requestState)
        assertEquals("Series · 2011", hits[0].subtitle)
    }
}

class WatchlistTest {

    private val dune = WatchlistEntry(tmdbId = 693134, title = "Dune: Part Two", year = "2024")
    private val onServer = WatchlistEntry(itemId = "abc", title = "Dune", year = "2021")

    @Test
    fun addingTwiceMovesItUpRatherThanDuplicatingIt() {
        val list = Watchlist().with(dune).with(onServer).with(dune)

        assertEquals(2, list.entries.size)
        assertEquals("Dune: Part Two", list.entries.first().title)
    }

    @Test
    fun eitherIdentifierIsEnoughToRecogniseATitle() {
        // The same film arrives as a TMDb id from search and later as an
        // item id from the library; ending up with it twice is the bug
        val fromSearch = WatchlistEntry(tmdbId = 42, title = "X")
        val fromLibrary = WatchlistEntry(itemId = "x1", tmdbId = 42, title = "X")

        assertTrue(Watchlist().with(fromSearch).contains(fromLibrary))
        assertEquals(1, Watchlist().with(fromSearch).with(fromLibrary).entries.size)
    }

    @Test
    fun twoTitlesWithNothingInCommonStayApart() {
        assertFalse(WatchlistEntry(tmdbId = 1).isSameAs(WatchlistEntry(tmdbId = 2)))
        // Two entries that know nothing about each other are not the same
        assertFalse(WatchlistEntry(title = "A").isSameAs(WatchlistEntry(title = "A")))
    }

    @Test
    fun togglingIsAddingThenRemoving() {
        val once = Watchlist().toggled(dune)
        assertTrue(once.contains(dune))
        assertFalse(once.toggled(dune).contains(dune))
    }

    @Test
    fun anEntryPicksUpAnItemIdOnceTheDownloadLands() {
        // Added from search it knows only a TMDb id, and nothing on the
        // home screen can open one of those
        val list = Watchlist().with(WatchlistEntry(tmdbId = 693134, title = "Dune: Part Two", year = "2024"))

        val reconciled = list.reconciled(listOf(film("Dune: Part Two", 2024, id = "landed")))

        assertEquals("landed", reconciled.entries.first().itemId)
        assertTrue(reconciled.entries.first().isOnServer)
    }

    @Test
    fun reconcilingLeavesEverythingElseAlone() {
        val list = Watchlist().with(onServer).with(dune)

        val reconciled = list.reconciled(listOf(film("Something Else", 1999)))

        assertEquals(list, reconciled)
    }

    @Test
    fun theListSurvivesTheJsonRoundTrip() {
        val list = Watchlist().with(dune).with(onServer)

        assertEquals(list, Watchlist.fromJson(list.toJson()))
    }

    @Test
    fun aBlobWrittenBeforeThisFeatureStillDecodes() {
        assertEquals(Watchlist(), Watchlist.fromJson("{}"))
        assertNull(Watchlist.fromJson("not json"))
        // And one from a newer build with a field we do not know
        assertEquals(
            1,
            Watchlist.fromJson("""{"entries":[{"tmdbId":1,"title":"X","mood":"grim"}]}""")?.entries?.size,
        )
    }
}

class ArrivalsTest {

    private fun request(id: Int, status: Int, title: String, seasons: List<Int> = emptyList()) =
        RequestedTitle(
            JellyseerrRequest(
                id = id,
                status = 2,
                media = JellyseerrRequestMedia(tmdbId = id, mediaType = "tv", status = status),
                seasons = seasons.map { JellyseerrRequestSeason(id = it, seasonNumber = it, status = 5) },
            ),
            title = title,
        )

    @Test
    fun theFirstLookNeverAnnouncesAnything() {
        // Everything already available on a fresh install was not waited
        // for; announcing it is a lie the moment the app is reinstalled
        val available = listOf(request(1, 5, "Severance"))

        assertTrue(Arrivals.landed(available, AnnouncedArrivals(), firstLook = true).isEmpty())
    }

    @Test
    fun somethingThatLandsWhileWatchingIsAnnouncedOnce() {
        val seen = Arrivals.seen(listOf(request(1, 3, "Severance")), AnnouncedArrivals())

        val landed = Arrivals.landed(listOf(request(1, 5, "Severance")), seen, firstLook = false)
        assertEquals(1, landed.size)
        assertEquals("Severance has arrived", landed.first().message)

        // And not a second time
        val after = Arrivals.seen(listOf(request(1, 5, "Severance")), seen)
        assertTrue(Arrivals.landed(listOf(request(1, 5, "Severance")), after, firstLook = false).isEmpty())
    }

    @Test
    fun aSeasonSaysWhichSeason() {
        val landed = Arrivals.landed(
            listOf(request(1, 5, "Severance", seasons = listOf(2))),
            AnnouncedArrivals(),
            firstLook = false,
        )

        assertEquals("Severance season 2 has arrived", landed.first().message)
    }

    @Test
    fun somethingStillDownloadingIsNotAnArrival() {
        val landed = Arrivals.landed(
            listOf(request(1, 3, "Severance"), request(2, 2, "Dune")),
            AnnouncedArrivals(),
            firstLook = false,
        )

        assertTrue(landed.isEmpty())
    }

    @Test
    fun theSeenSetForgetsRequestsThatNoLongerExist() {
        // Otherwise it grows for ever on a server where requests are tidied up
        val seen = Arrivals.seen(
            listOf(request(1, 5, "A"), request(2, 5, "B")),
            AnnouncedArrivals(),
        )
        assertEquals(setOf(1, 2), seen.requestIds)

        val pruned = Arrivals.seen(listOf(request(1, 5, "A")), seen)
        assertEquals(setOf(1), pruned.requestIds)
    }

    @Test
    fun theSeenSetSurvivesTheJsonRoundTrip() {
        val seen = AnnouncedArrivals(setOf(3, 1, 2))

        assertEquals(seen, AnnouncedArrivals.fromJson(seen.toJson()))
        assertEquals(AnnouncedArrivals(), AnnouncedArrivals.fromJson("{}"))
    }
}

class MergeByIdTest {

    @Test
    fun anIdBeatsANameEveryTime() {
        // Same film, listed under a different title on the server —
        // localised names are the normal case, not the exception
        val onServer = BaseItem(
            id = "x", name = "Dune, deuxième partie", type = "Movie",
            productionYear = 2024, providerIds = mapOf("Tmdb" to "693134"),
        )

        val hits = UnifiedSearch.merge(
            listOf(onServer),
            listOf(catalogue(693134, "Dune: Part Two", "2024")),
        )

        assertEquals(1, hits.size)
        assertTrue(hits[0].isOnServer)
    }

    @Test
    fun aRemakeSurvivesTheMergeWhenBothSidesHaveIds() {
        // The name-and-year fallback would still keep these apart, but an
        // id match must not be able to pull them together either
        val original = BaseItem(
            id = "old", name = "Dune", type = "Movie",
            productionYear = 1984, providerIds = mapOf("Tmdb" to "841"),
        )

        val hits = UnifiedSearch.merge(listOf(original), listOf(catalogue(438631, "Dune", "2021")))

        assertEquals(2, hits.size)
    }

    @Test
    fun aServerTooOldToReportIdsStillMerges() {
        // Older Jellyfin, or an unscanned item: no ProviderIds at all
        val hits = UnifiedSearch.merge(
            listOf(film("Dune", 2021)),
            listOf(catalogue(438631, "Dune", "2021")),
        )

        assertEquals(1, hits.size)
    }
}
