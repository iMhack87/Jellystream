package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class JellyseerrApiTest {

    @Test
    fun anUnconfiguredClientAnswersWithoutReachingTheNetwork() {
        val api = JellyseerrApi()

        assertFalse(api.isConfigured)
        assertFalse(api.isSignedIn)
    }

    @Test
    fun configuringNormalizesTheServerUrlLikeJellyfinDoes() {
        val api = JellyseerrApi()

        // Same rules as the main server field: a bare host, a stray slash
        api.configure("seerr.example.com/", "connect.sid=abc")

        assertTrue(api.isConfigured)
        assertTrue(api.isSignedIn)
    }

    @Test
    fun aServerWithoutASessionIsConfiguredButNotSignedIn() {
        val api = JellyseerrApi()

        api.configure("https://seerr.example.com", null)

        assertTrue(api.isConfigured)
        assertFalse(api.isSignedIn)
    }

    @Test
    fun signingOutDropsTheSessionAndKeepsTheServer() {
        val api = JellyseerrApi()
        api.configure("https://seerr.example.com", "connect.sid=abc")

        api.signOut()

        assertTrue(api.isConfigured)
        assertFalse(api.isSignedIn)
    }

    @Test
    fun aFilmIsRequestedByTmdbIdAndNothingElse() {
        val body = JellyseerrApi().requestBody(tmdbId = 693134, isSeries = false, seasons = null)

        assertEquals("movie", body["mediaType"]?.jsonPrimitive?.content)
        assertEquals("693134", body["mediaId"]?.jsonPrimitive?.content)
        // "seasons" on a film is what Jellyseerr rejects with a 500
        assertNull(body["seasons"])
    }

    @Test
    fun aSeriesWithNoSeasonsNamedIsRequestedWhole() {
        val body = JellyseerrApi().requestBody(tmdbId = 95396, isSeries = true, seasons = null)

        assertEquals("tv", body["mediaType"]?.jsonPrimitive?.content)
        assertEquals("all", body["seasons"]?.jsonPrimitive?.content)
        // An empty pick means the same thing as no pick, never an empty
        // array — Jellyseerr answers that with "no seasons available"
        assertEquals(
            "all",
            JellyseerrApi().requestBody(95396, isSeries = true, seasons = emptyList())["seasons"]
                ?.jsonPrimitive?.content,
        )
    }

    @Test
    fun namedSeasonsGoOverTheWireAsNumbersNotObjects() {
        val body = JellyseerrApi().requestBody(tmdbId = 95396, isSeries = true, seasons = listOf(2, 3))

        assertEquals("tv", body["mediaType"]?.jsonPrimitive?.content)
        // Jellyseerr takes season NUMBERS here — not TMDb season ids, and
        // not the {seasonNumber} objects it hands back on a request row
        assertEquals(
            listOf("2", "3"),
            body["seasons"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun askingForOnlyTheSpecialsIsRefusedRatherThanTurnedIntoTheWholeShow() = runTest {
        val api = JellyseerrApi()
        api.configure("https://seerr.example.com", "connect.sid=abc")

        // Season 0 is what "all" excludes; letting it fall through to the
        // empty-list branch would silently ask for every season instead
        assertTrue(api.requestSeasons(95396, listOf(0)) is RequestOutcome.Failed)
        assertTrue(api.requestSeasons(95396, emptyList()) is RequestOutcome.Failed)
    }

    @Test
    fun postersPointAtTmdbNotAtJellyseerr() {
        // Jellyseerr hands back a TMDb-relative path; resolving it against
        // the Jellyseerr host yields a 404 and an empty grid
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc.jpg",
            JellyseerrApi.posterUrl("/abc.jpg"),
        )
        // Some payloads omit the leading slash
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc.jpg",
            JellyseerrApi.posterUrl("abc.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            JellyseerrApi.posterUrl("/abc.jpg", width = 500),
        )
    }

    @Test
    fun aMissingPosterIsNullRatherThanABrokenUrl() {
        assertNull(JellyseerrApi.posterUrl(null))
        assertNull(JellyseerrApi.posterUrl(""))
        assertNull(JellyseerrApi.posterUrl("   "))
    }

    @Test
    fun callsWithoutAServerFailQuietlyInsteadOfThrowing() = runTest {
        val api = JellyseerrApi()

        assertTrue(api.search("dune").isEmpty())
        assertTrue(api.myRequests().isEmpty())
        assertTrue(api.myRequestsDetailed(30).isEmpty())
        assertTrue(api.request(1, isSeries = false) is RequestOutcome.Failed)
        assertTrue(api.requestSeasons(1, listOf(2)) is RequestOutcome.Failed)
        assertNull(api.tvDetails(1))
        assertNull(api.movieDetails(1))
        assertNull(api.signIn("someone", "secret"))
    }

    @Test
    fun requestingWithoutASessionSaysSoRatherThanFailing() = runTest {
        val api = JellyseerrApi()
        api.configure("https://seerr.example.com", null)

        assertEquals(RequestOutcome.NotSignedIn, api.request(1, isSeries = false))
    }

    @Test
    fun anEmptyQueryNeverLeavesTheDevice() = runTest {
        val api = JellyseerrApi()
        api.configure("https://seerr.example.com", "connect.sid=abc")

        assertTrue(api.search("").isEmpty())
        assertTrue(api.search("   ").isEmpty())
    }
}
