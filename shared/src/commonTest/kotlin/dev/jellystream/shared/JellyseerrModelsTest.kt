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
