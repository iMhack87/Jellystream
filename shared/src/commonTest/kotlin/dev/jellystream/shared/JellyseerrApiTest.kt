package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
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
        val body = JellyseerrApi().requestBody(tmdbId = 693134, isSeries = false)

        assertEquals("movie", body["mediaType"]?.jsonPrimitive?.content)
        assertEquals("693134", body["mediaId"]?.jsonPrimitive?.content)
        // "seasons" on a film is what Jellyseerr rejects with a 500
        assertNull(body["seasons"])
    }

    @Test
    fun aSeriesIsRequestedWhole() {
        val body = JellyseerrApi().requestBody(tmdbId = 95396, isSeries = true)

        assertEquals("tv", body["mediaType"]?.jsonPrimitive?.content)
        assertEquals("all", body["seasons"]?.jsonPrimitive?.content)
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
        assertTrue(api.request(1, isSeries = false) is RequestOutcome.Failed)
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
