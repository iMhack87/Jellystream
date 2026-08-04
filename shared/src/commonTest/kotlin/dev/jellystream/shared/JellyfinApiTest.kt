package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JellyfinApiTest {

    @Test
    fun normalizeServerUrl_stripsTrailingSlashAndDefaultsToHttps() {
        assertEquals("https://demo.jellyfin.org", JellyfinApi.normalizeServerUrl("demo.jellyfin.org/"))
        assertEquals("http://192.168.1.10:8096", JellyfinApi.normalizeServerUrl(" http://192.168.1.10:8096/ "))
        assertEquals("https://jf.example.com", JellyfinApi.normalizeServerUrl("https://jf.example.com"))
    }

    @Test
    fun candidateUrls_schemelessInputTriesHttpsThenHttp() {
        // Bare LAN address — the typical self-hosted case — must fall back to plain http
        assertEquals(
            listOf("https://192.168.1.10:8096", "http://192.168.1.10:8096"),
            JellyfinApi.candidateUrls("192.168.1.10:8096/"),
        )
        assertEquals(
            listOf("http://192.168.1.10:8096"),
            JellyfinApi.candidateUrls("http://192.168.1.10:8096"),
        )
        assertEquals(
            listOf("https://jf.example.com"),
            JellyfinApi.candidateUrls("https://jf.example.com/"),
        )
    }

    @Test
    fun imageUrl_isNullWithoutSession() {
        val api = JellyfinApi(deviceName = "test", deviceId = "test-id")
        val item = BaseItem(id = "abc", imageTags = mapOf("Primary" to "tag1"))
        assertNull(api.imageUrl(item, 400))
    }

    @Test
    fun streamUrl_isNullWithoutSession() {
        val api = JellyfinApi(deviceName = "test", deviceId = "test-id")
        assertNull(api.streamUrl(BaseItem(id = "abc", type = "Movie")))
    }

    @Test
    fun tickConversions_matchJellyfinWireFormat() {
        assertEquals(10_000_000L, JellyfinApi.secondsToTicks(1.0))
        assertEquals(10_000_000L, JellyfinApi.millisecondsToTicks(1_000))
        assertEquals(28_552_310_000L, JellyfinApi.secondsToTicks(2855.231))
    }

    @Test
    fun resumePositionSeconds_readsUserDataTicks() {
        val watched = BaseItem(
            id = "a",
            userData = UserItemData(playbackPositionTicks = 28_350_000_000L),
        )
        assertEquals(2835.0, watched.resumePositionSeconds)
        assertEquals(0.0, BaseItem(id = "b").resumePositionSeconds)
    }

    @Test
    fun isPlayable_moviesAndEpisodesOnly() {
        assertEquals(true, BaseItem(id = "a", type = "Movie").isPlayable)
        assertEquals(true, BaseItem(id = "b", type = "Episode").isPlayable)
        assertEquals(false, BaseItem(id = "c", type = "MusicAlbum").isPlayable)
        assertEquals(false, BaseItem(id = "d", type = null).isPlayable)
    }
}
