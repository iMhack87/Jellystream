package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestStateTest {

    @Test
    fun mediaStatusMapsToWhatTheScreenShows() {
        assertEquals(RequestState.AVAILABLE, RequestState.of(5))
        assertEquals(RequestState.PARTIALLY_AVAILABLE, RequestState.of(4))
        assertEquals(RequestState.PROCESSING, RequestState.of(3))
        assertEquals(RequestState.PENDING, RequestState.of(2))
        assertEquals(RequestState.REQUESTABLE, RequestState.of(1))
    }

    @Test
    fun nothingKnownMeansRequestable() {
        // Jellyseerr sends no mediaInfo at all for a title nobody asked for
        assertEquals(RequestState.REQUESTABLE, RequestState.of(null))
    }

    @Test
    fun anUnknownStatusFallsToRequestableRatherThanAvailable() {
        // A future Jellyseerr value must not hide the button: asking for
        // something already there costs one rejected call, while a wrongly
        // greyed-out title has no way back
        assertEquals(RequestState.REQUESTABLE, RequestState.of(99))
        assertEquals(RequestState.REQUESTABLE, RequestState.of(0))
        assertEquals(RequestState.REQUESTABLE, RequestState.of(-1))
    }

    @Test
    fun aDeclinedRequestOutranksTheMediaRow() {
        // The media stays "unknown" after a refusal; only the request says so
        assertEquals(RequestState.DECLINED, RequestState.of(mediaStatus = 1, requestStatus = 3))
        assertEquals(RequestState.DECLINED, RequestState.of(mediaStatus = null, requestStatus = 3))
    }

    @Test
    fun aPendingRequestCountsBeforeTheMediaRowCatchesUp() {
        assertEquals(RequestState.PENDING, RequestState.of(mediaStatus = null, requestStatus = 1))
    }

    @Test
    fun onlyTheStatesWorthAskingAboutOfferTheButton() {
        assertTrue(RequestState.REQUESTABLE.canRequest)
        // Missing seasons are still worth asking for
        assertTrue(RequestState.PARTIALLY_AVAILABLE.canRequest)
        // Asking again after a refusal is the user's call, not ours
        assertTrue(RequestState.DECLINED.canRequest)

        assertFalse(RequestState.AVAILABLE.canRequest)
        assertFalse(RequestState.PENDING.canRequest)
        assertFalse(RequestState.PROCESSING.canRequest)
    }

    @Test
    fun everyStateSaysSomethingToTheUser() {
        RequestState.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}

class JellyseerrResultTest {

    @Test
    fun filmsCarryATitleAndShowsCarryAName() {
        val film = JellyseerrResult(id = 1, mediaType = "movie", title = "Dune")
        val show = JellyseerrResult(id = 2, mediaType = "tv", name = "Severance")

        assertEquals("Dune", film.displayTitle)
        assertEquals("Severance", show.displayTitle)
        assertFalse(film.isSeries)
        assertTrue(show.isSeries)
    }

    @Test
    fun anUntitledResultStillRenders() {
        assertEquals("Untitled", JellyseerrResult(id = 3).displayTitle)
    }

    @Test
    fun theYearComesFromWhicheverDateFieldIsSet() {
        assertEquals("2021", JellyseerrResult(id = 1, releaseDate = "2021-09-15").year)
        assertEquals("2022", JellyseerrResult(id = 2, firstAirDate = "2022-02-18").year)
        assertNull(JellyseerrResult(id = 3).year)
        // Jellyseerr sends an empty string for an unknown date
        assertNull(JellyseerrResult(id = 4, releaseDate = "").year)
    }

    @Test
    fun peopleComeBackFromSearchAndCannotBeRequested() {
        val person = JellyseerrResult(id = 5, mediaType = "person", name = "Denis Villeneuve")

        assertFalse(person.isRequestableKind)
        assertTrue(JellyseerrResult(id = 6, mediaType = "movie").isRequestableKind)
        assertTrue(JellyseerrResult(id = 7, mediaType = "tv").isRequestableKind)
    }

    @Test
    fun aResultKnowsItsOwnState() {
        val available = JellyseerrResult(
            id = 1,
            mediaType = "movie",
            mediaInfo = JellyseerrMediaInfo(status = 5),
        )
        val fresh = JellyseerrResult(id = 2, mediaType = "movie")

        assertEquals(RequestState.AVAILABLE, available.state)
        assertEquals(RequestState.REQUESTABLE, fresh.state)
    }
}

class JellyseerrRequestTest {

    @Test
    fun aRequestCombinesItsOwnStatusWithTheMediaRow() {
        val declined = JellyseerrRequest(
            id = 1,
            status = 3,
            media = JellyseerrRequestMedia(tmdbId = 10, mediaType = "movie", status = 1),
        )
        val downloading = JellyseerrRequest(
            id = 2,
            status = 2,
            media = JellyseerrRequestMedia(tmdbId = 11, mediaType = "tv", status = 3),
        )

        assertEquals(RequestState.DECLINED, declined.state)
        assertEquals(RequestState.PROCESSING, downloading.state)
        assertTrue(downloading.isSeries)
        assertFalse(declined.isSeries)
    }
}

class JellyseerrLinkPersistenceTest {

    private val session = PersistedSession(
        deviceId = "dev-1",
        session = UserSession(
            baseUrl = "https://jf.example.com",
            userId = "u1",
            accessToken = "token",
            userName = "alice",
            serverName = "Home",
        ),
    )

    @Test
    fun aProfileStartsWithNoJellyseerr() {
        assertNull(session.jellyseerr)
    }

    @Test
    fun theServerUrlIsNormalizedLikeTheMainOne() {
        val linked = session.withJellyseerrServer("seerr.example.com/")

        assertEquals("https://seerr.example.com", linked.jellyseerr?.baseUrl)
        assertFalse(linked.jellyseerr!!.isSignedIn)
    }

    @Test
    fun signingInStoresTheCookieAndNotThePassword() {
        val signedIn = session
            .withJellyseerrServer("https://seerr.example.com")
            .withJellyseerrSession("connect.sid=abc")

        assertTrue(signedIn.jellyseerr!!.isSignedIn)
        assertEquals("connect.sid=abc", signedIn.jellyseerr!!.sessionCookie)
        // Nothing anywhere in the blob should resemble a password
        assertFalse(signedIn.toJson().contains("password"))
    }

    @Test
    fun pointingAtAnotherServerDropsTheOldCookie() {
        // It belongs to the old host and would only ever 401 elsewhere
        val moved = session
            .withJellyseerrServer("https://seerr.example.com")
            .withJellyseerrSession("connect.sid=abc")
            .withJellyseerrServer("https://other.example.com")

        assertEquals("https://other.example.com", moved.jellyseerr?.baseUrl)
        assertFalse(moved.jellyseerr!!.isSignedIn)
    }

    @Test
    fun re_enteringTheSameServerKeepsTheSession() {
        val same = session
            .withJellyseerrServer("https://seerr.example.com")
            .withJellyseerrSession("connect.sid=abc")
            .withJellyseerrServer("seerr.example.com/")

        assertTrue(same.jellyseerr!!.isSignedIn)
    }

    @Test
    fun clearingTheServerUnlinksTheProfile() {
        val cleared = session
            .withJellyseerrServer("https://seerr.example.com")
            .withJellyseerrServer("   ")

        assertNull(cleared.jellyseerr)
    }

    @Test
    fun theLinkSurvivesTheJsonRoundTrip() {
        val linked = session
            .withJellyseerrServer("https://seerr.example.com")
            .withJellyseerrSession("connect.sid=abc")

        val decoded = PersistedSession.fromJson(linked.toJson())!!

        assertEquals("https://seerr.example.com", decoded.jellyseerr?.baseUrl)
        assertEquals("connect.sid=abc", decoded.jellyseerr?.sessionCookie)
    }

    @Test
    fun aBlobWrittenBeforeThisFeatureStillDecodes() {
        val old = """{"deviceId":"dev-1","session":{"baseUrl":"https://jf.example.com","userId":"u1","accessToken":"token","userName":"alice","serverName":"Home"}}"""

        val decoded = PersistedSession.fromJson(old)

        assertEquals("alice", decoded?.displayName)
        assertNull(decoded?.jellyseerr)
    }
}

class JellyseerrTvDetailsTest {

    private val details = JellyseerrTvDetails(
        id = 95396,
        name = "Severance",
        firstAirDate = "2022-02-18",
        seasons = listOf(
            JellyseerrSeason(seasonNumber = 0, name = "Specials", episodeCount = 3),
            JellyseerrSeason(seasonNumber = 2, name = "Season 2", episodeCount = 10),
            JellyseerrSeason(seasonNumber = 1, name = "Season 1", episodeCount = 9),
        ),
        mediaInfo = JellyseerrMediaInfo(
            status = 4,
            seasons = listOf(JellyseerrSeasonStatus(seasonNumber = 1, status = 5)),
        ),
    )

    @Test
    fun specialsAreLeftOutBecauseAskingForAllExcludesThem() {
        // Offering season 0 in a picker would promise something the
        // "request everything" button on the same screen does not deliver
        assertEquals(listOf(1, 2), details.seasonNumbers)
    }

    @Test
    fun seasonsComeBackInOrderWhateverOrderTheServerSentThem() {
        assertEquals(listOf(1, 2), details.requestableSeasons.map { it.seasonNumber })
    }

    @Test
    fun aSeasonJellyseerrHasNeverHeardOfIsRequestable() {
        // The media row is sparse: it lists only seasons somebody has
        // touched, so an absent entry means nobody has asked yet
        assertEquals(RequestState.AVAILABLE, details.stateOf(1))
        assertEquals(RequestState.REQUESTABLE, details.stateOf(2))
        assertEquals(RequestState.REQUESTABLE, details.stateOf(99))
    }

    @Test
    fun aShowNobodyHasTouchedHasNoMediaRowAtAll() {
        val untouched = JellyseerrTvDetails(id = 1, name = "New Show")

        assertEquals(RequestState.REQUESTABLE, untouched.stateOf(1))
        assertTrue(untouched.seasonNumbers.isEmpty())
    }

    @Test
    fun aSeasonWithoutANameStillHasSomethingToPutOnAPill() {
        assertEquals("Season 4", JellyseerrSeason(seasonNumber = 4).displayName)
        assertEquals("Specials", JellyseerrSeason(seasonNumber = 0).displayName)
        assertEquals("Saison 2", JellyseerrSeason(seasonNumber = 2, name = "Saison 2").displayName)
    }
}

class RequestedTitleTest {

    private fun row(
        isSeries: Boolean,
        seasons: List<Int> = emptyList(),
        downloads: List<JellyseerrDownload> = emptyList(),
    ) = JellyseerrRequest(
        id = 1,
        status = 2,
        media = JellyseerrRequestMedia(
            tmdbId = 95396,
            mediaType = if (isSeries) "tv" else "movie",
            status = 3,
            downloadStatus = downloads,
        ),
        seasons = seasons.map { JellyseerrRequestSeason(id = it, seasonNumber = it, status = 2) },
    )

    @Test
    fun aRowWithoutADetailLookupStillSaysWhatItIs() {
        // The request endpoint answers with a TMDb id and no name; a
        // failed lookup must cost the title, not the row
        assertEquals("Series request", RequestedTitle(row(isSeries = true)).displayTitle)
        assertEquals("Film request", RequestedTitle(row(isSeries = false)).displayTitle)
    }

    @Test
    fun aResolvedTitleReadsAsItsOwnName() {
        val enriched = RequestedTitle(row(isSeries = true), title = "Severance", year = "2022")

        assertEquals("Severance", enriched.displayTitle)
        assertEquals("Series · 2022", enriched.subtitle)
    }

    @Test
    fun aPartialRequestSaysWhichSeasonsItAskedFor() {
        assertEquals("Season 2", row(isSeries = true, seasons = listOf(2)).seasonsLabel)
        assertEquals("Seasons 2, 3", row(isSeries = true, seasons = listOf(3, 2)).seasonsLabel)
        // A film, and a series requested before Jellyseerr reported seasons
        assertNull(row(isSeries = false).seasonsLabel)
        assertNull(row(isSeries = true).seasonsLabel)
    }

    @Test
    fun theSeasonsAskedForShowUpUnderTheTitle() {
        val enriched = RequestedTitle(
            row(isSeries = true, seasons = listOf(2)),
            title = "Severance",
            year = "2022",
        )

        assertEquals("Series · 2022 · Season 2", enriched.subtitle)
    }

    @Test
    fun progressRidesAlongOnTheMediaBlock() {
        val enriched = RequestedTitle(
            row(
                isSeries = false,
                downloads = listOf(JellyseerrDownload(size = 100.0, sizeLeft = 25.0, timeLeft = "00:05:00")),
            ),
        )

        assertEquals("75% · 5 min left", enriched.progress?.summary)
    }

    @Test
    fun aRequestNobodyIsDownloadingHasNoBar() {
        assertNull(RequestedTitle(row(isSeries = true)).progress)
    }
}

class BaseItemTmdbIdTest {

    @Test
    fun theTmdbIdIsReadWhateverCasingTheServerUses() {
        // The key has drifted between Jellyfin versions
        assertEquals(95396, BaseItem(id = "a", providerIds = mapOf("Tmdb" to "95396")).tmdbId)
        assertEquals(95396, BaseItem(id = "a", providerIds = mapOf("TMDB" to "95396")).tmdbId)
        assertEquals(95396, BaseItem(id = "a", providerIds = mapOf("tmdb" to "95396")).tmdbId)
    }

    @Test
    fun aListEndpointItemSimplyHasNone() {
        // ProviderIds is trimmed out of the DTO unless a single-item fetch
        // asks for it — a missing id must not be an exception
        assertNull(BaseItem(id = "a").tmdbId)
        assertNull(BaseItem(id = "a", providerIds = emptyMap()).tmdbId)
        assertNull(BaseItem(id = "a", providerIds = mapOf("Imdb" to "tt11280740")).tmdbId)
    }

    @Test
    fun somethingThatIsNotANumberIsNotAnId() {
        // Jellyseerr wants an Int; Jellyfin sends strings
        assertNull(BaseItem(id = "a", providerIds = mapOf("Tmdb" to "")).tmdbId)
        assertNull(BaseItem(id = "a", providerIds = mapOf("Tmdb" to "tt1234")).tmdbId)
    }
}

class RequestPollingAndScopeTest {

    private fun grab(season: Int?, size: Double, left: Double) = JellyseerrDownload(
        size = size,
        sizeLeft = left,
        status = "downloading",
        episode = season?.let { JellyseerrDownloadEpisode(seasonNumber = it, episodeNumber = 1) },
    )

    private fun seriesRequest(seasons: List<Int>, grabs: List<JellyseerrDownload>) =
        JellyseerrRequest(
            id = 1,
            status = 2,
            media = JellyseerrRequestMedia(
                tmdbId = 1399, mediaType = "tv", status = 3, downloadStatus = grabs,
            ),
            seasons = seasons.map { JellyseerrRequestSeason(id = it, seasonNumber = it, status = 2) },
        )

    @Test
    fun aSeasonRequestOwnsOnlyItsOwnSeasonsProgress() {
        // The download list hangs off the media, not the request: two
        // season requests on one show would each claim the other's bytes
        val grabs = listOf(
            grab(season = 2, size = 100.0, left = 0.0),
            grab(season = 3, size = 100.0, left = 100.0),
        )

        assertEquals(1.0, seriesRequest(listOf(2), grabs).progress?.fraction)
        assertEquals(0.0, seriesRequest(listOf(3), grabs).progress?.fraction)
        // A whole-show request has no seasons named and takes the lot
        assertEquals(0.5, seriesRequest(emptyList(), grabs).progress?.fraction)
    }

    @Test
    fun aSeasonWithNothingOfItsOwnInFlightHasNoBar() {
        val elsewhere = listOf(grab(season = 5, size = 100.0, left = 50.0))

        assertNull(seriesRequest(listOf(2), elsewhere).progress)
    }

    @Test
    fun pollingWatchesTheStateNotTheBar() {
        // A request approved a second ago has no grab yet. Waiting for a
        // progress bar before polling means the bar never turns up.
        assertTrue(seriesRequest(listOf(2), emptyList()).isSettling)

        val done = JellyseerrRequest(
            id = 2, status = 2,
            media = JellyseerrRequestMedia(tmdbId = 1, mediaType = "movie", status = 5),
        )
        assertFalse(done.isSettling)
    }
}

class SeasonRequestabilityTest {

    private val details = JellyseerrTvDetails(
        id = 1,
        seasons = listOf(
            JellyseerrSeason(seasonNumber = 1),
            JellyseerrSeason(seasonNumber = 2),
            JellyseerrSeason(seasonNumber = 3),
        ),
        mediaInfo = JellyseerrMediaInfo(
            status = 4,
            seasons = listOf(
                JellyseerrSeasonStatus(seasonNumber = 1, status = 5),
                JellyseerrSeasonStatus(seasonNumber = 2, status = 4),
            ),
        ),
    )

    @Test
    fun aPartlyAvailableSeasonIsNotWorthOffering() {
        // Jellyseerr drops every season whose status is anything but
        // unknown, so this request comes back refused however requestable
        // the title-level rule says it looks
        assertEquals(RequestState.PARTIALLY_AVAILABLE, details.stateOf(2))
        assertTrue(details.stateOf(2).canRequest)
        assertFalse(details.canRequestSeason(2))
    }

    @Test
    fun aSeasonNobodyHasTouchedIsTheOnlyOneWorthATap() {
        assertTrue(details.canRequestSeason(3))
        assertFalse(details.canRequestSeason(1))
    }
}
