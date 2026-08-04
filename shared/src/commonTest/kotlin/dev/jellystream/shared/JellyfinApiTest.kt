package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
