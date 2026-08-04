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
}
