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
        assertFalse(
            WatchlistEntry(title = "A", year = "2001")
                .isSameAs(WatchlistEntry(title = "B", year = "2001")),
        )
        // A remake is not the original, here as everywhere else
        assertFalse(
            WatchlistEntry(title = "Dune", year = "1984")
                .isSameAs(WatchlistEntry(title = "Dune", year = "2021")),
        )
        // TMDb numbers films and shows separately: 550 is two things
        assertFalse(
            WatchlistEntry(tmdbId = 550, title = "A", isSeries = false)
                .isSameAs(WatchlistEntry(tmdbId = 550, title = "B", isSeries = true)),
        )
        // Nothing to go on at all
        assertFalse(WatchlistEntry().isSameAs(WatchlistEntry()))
    }

    @Test
    fun theNameIsTheFallbackWhenNeitherSideKnowsTheOthersId() {
        // ProviderIds only comes back on a single-item fetch, so anything
        // picked off a shelf has no TMDb id — while an entry added from
        // search has no item id. Without this the list holds the same show
        // twice and reconciled() then stamps one item id onto both, which
        // is a duplicate key in a lazy row, which is a crash.
        val fromSearch = WatchlistEntry(tmdbId = 87739, title = "Silo", year = "2023", isSeries = true)
        val fromShelf = WatchlistEntry(itemId = "abc", title = "Silo", year = "2023", isSeries = true)

        assertTrue(fromSearch.isSameAs(fromShelf))
        assertEquals(1, Watchlist().with(fromSearch).with(fromShelf).entries.size)
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
        val seen = Arrivals.seen(
            listOf(request(1, 3, "Severance")),
            AnnouncedArrivals(),
            announcing = emptyList(),
        )

        val landed = Arrivals.landed(listOf(request(1, 5, "Severance")), seen, firstLook = false)
        assertEquals(1, landed.size)
        assertEquals("Severance has arrived", landed.first().message)

        // And not a second time
        val after = Arrivals.seenAfterShowing(landed.first(), seen)
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
    fun theSeenSetRemembersEverythingItHasAnnounced() {
        val seen = Arrivals.seen(
            listOf(request(1, 5, "A"), request(2, 5, "B")),
            AnnouncedArrivals(),
            announcing = emptyList(),
        )
        assertEquals(setOf(1, 2), seen.requestIds)

        // B has dropped out of the page the poll asks for. It must NOT be
        // forgotten: the poll only ever sees the newest requests, so an
        // older id is missing from every answer, and forgetting it means
        // announcing that title again the day it comes back into view.
        val later = Arrivals.seen(listOf(request(1, 5, "A")), seen, announcing = emptyList())
        assertEquals(setOf(1, 2), later.requestIds)
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

class WatchlistReconcileTest {

    @Test
    fun oneItemIdIsNeverStampedOntoTwoEntries() {
        // Two entries for one show is already wrong; two entries carrying
        // the SAME item id is a duplicate key in a lazy row, which is a
        // crash on the home screen that persists across restarts.
        val list = Watchlist(
            listOf(
                WatchlistEntry(tmdbId = 87739, title = "Silo", year = "2023", isSeries = true),
                WatchlistEntry(itemId = "abc", title = "Silo", year = "2023", isSeries = true),
            )
        )

        val reconciled = list.reconciled(listOf(show("Silo", 2023, id = "abc")))

        assertEquals(1, reconciled.entries.size)
        assertEquals(1, reconciled.entries.mapNotNull { it.itemId }.toSet().size)
    }

    @Test
    fun anEntryWithNoYearStillFindsItsTitle() {
        // Saved from Jellyseerr before a release date was known: matching
        // on name-and-year alone leaves it un-openable for ever
        val list = Watchlist().with(WatchlistEntry(tmdbId = 1, title = "Silo", isSeries = true))

        val reconciled = list.reconciled(listOf(show("Silo", 2023, id = "abc")))

        assertEquals("abc", reconciled.entries.first().itemId)
    }

    @Test
    fun reconcilingIsStableOnceEverythingHasAnId() {
        val list = Watchlist().with(WatchlistEntry(tmdbId = 1, title = "Silo", isSeries = true))
        val onServer = listOf(show("Silo", 2023, id = "abc"))

        val once = list.reconciled(onServer)

        // Converges: a second pass changes nothing, so nothing writes in a loop
        assertEquals(once, once.reconciled(onServer))
    }
}

class AnnouncedCapTest {

    @Test
    fun theSetKeepsTheNewestAndForgetsTheOldest() {
        val many = AnnouncedArrivals((1..600).toSet())

        val capped = many.capped(500)

        assertEquals(500, capped.requestIds.size)
        assertTrue(600 in capped.requestIds)
        assertFalse(1 in capped.requestIds)
    }

    @Test
    fun somethingOutsideTheCurrentPageIsNotForgotten() {
        // The poll asks for one page of the newest requests, so an older
        // announced id is absent from every answer. Forgetting it means
        // announcing that title again the moment it slides back into view.
        val announced = AnnouncedArrivals(setOf(1, 2, 3))
        val page = listOf(
            RequestedTitle(
                JellyseerrRequest(
                    id = 9, status = 2,
                    media = JellyseerrRequestMedia(tmdbId = 9, mediaType = "movie", status = 5),
                ),
                title = "New",
            )
        )

        val seen = Arrivals.seen(page, announced, announcing = emptyList())

        assertTrue(seen.requestIds.containsAll(setOf(1, 2, 3, 9)))
    }
}

class ArrivalBookkeepingTest {

    private fun row(id: Int, status: Int) = RequestedTitle(
        JellyseerrRequest(
            id = id, status = 2,
            media = JellyseerrRequestMedia(tmdbId = id, mediaType = "movie", status = status),
        ),
        title = "Title $id",
    )

    @Test
    fun aNoticeStillQueuedIsNotYetWrittenOffAsAnnounced() {
        // Recording it at poll time means a queue dropped before it ever
        // appeared — a profile switch, the app killed — loses that notice
        // for good, and nothing raises it again
        val requests = listOf(row(1, 5))
        val landed = Arrivals.landed(requests, AnnouncedArrivals(), firstLook = false)

        val afterPoll = Arrivals.seen(requests, AnnouncedArrivals(), landed)
        assertFalse(1 in afterPoll.requestIds)

        val afterShowing = Arrivals.seenAfterShowing(landed.first(), afterPoll)
        assertTrue(1 in afterShowing.requestIds)
    }

    @Test
    fun aFirstLookIsStillRecordedWhole() {
        // Nothing is being announced, so everything available is written
        // off immediately — otherwise the second poll announces the lot
        val requests = listOf(row(1, 5), row(2, 5))

        val seen = Arrivals.seen(requests, AnnouncedArrivals(), announcing = emptyList())

        assertEquals(setOf(1, 2), seen.requestIds)
    }

    @Test
    fun aBlobFromAnotherJellyseerrCountsAsNothingStored() {
        // Request ids are one server's numbering. Replaying them against
        // another silences real arrivals and invents ones that never were.
        val fromOldServer = AnnouncedArrivals(setOf(1, 2, 3), server = "https://old.example")

        val forNew = fromOldServer.forServer("https://new.example")
        assertTrue(forNew.requestIds.isEmpty())
        assertEquals("https://new.example", forNew.server)

        // And the same server keeps everything
        assertEquals(fromOldServer, fromOldServer.forServer("https://old.example"))
    }

    @Test
    fun aPartlyAvailableShowIsNotAnArrival() {
        // One season landed, another has not: "it has arrived" is a lie
        val partly = listOf(row(1, 4))

        assertTrue(Arrivals.landed(partly, AnnouncedArrivals(), firstLook = false).isEmpty())
    }
}
