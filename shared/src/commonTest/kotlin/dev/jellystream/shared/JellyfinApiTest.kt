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
    fun runtimeMinutes_convertsTicks() {
        // 97 min = 97 * 60 * 10_000_000 ticks
        assertEquals(97, BaseItem(id = "a", runTimeTicks = 58_200_000_000L).runtimeMinutes)
        assertEquals(null, BaseItem(id = "b").runtimeMinutes)
    }

    @Test
    fun episodeLabel_formatsSeasonAndEpisode() {
        assertEquals(
            "S2 · E5",
            BaseItem(id = "a", type = "Episode", indexNumber = 5, parentIndexNumber = 2).episodeLabel,
        )
        assertEquals(
            "E5",
            BaseItem(id = "b", type = "Episode", indexNumber = 5).episodeLabel,
        )
        assertEquals(null, BaseItem(id = "c", type = "Movie").episodeLabel)
        assertEquals(null, BaseItem(id = "d", type = "Episode").episodeLabel)
    }

    @Test
    fun activeSegment_findsIntroAtPosition() {
        val intro = MediaSegment(type = "Intro", startTicks = 100_000_000L, endTicks = 900_000_000L) // 10s → 90s
        val outro = MediaSegment(type = "Outro", startTicks = 12_000_000_000L, endTicks = 13_000_000_000L) // 1200s → 1300s
        val segments = listOf(intro, outro)

        assertNull(SkipSegments.activeSegment(segments, 5.0)) // before intro
        assertEquals(intro, SkipSegments.activeSegment(segments, 10.0)) // enters at start
        assertEquals(intro, SkipSegments.activeSegment(segments, 60.0))
        assertNull(SkipSegments.activeSegment(segments, 89.0)) // inside tail margin
        assertNull(SkipSegments.activeSegment(segments, 100.0)) // between segments
        assertEquals(outro, SkipSegments.activeSegment(segments, 1250.0))
    }

    @Test
    fun activeSegment_ignoresNonSkippableTypes() {
        val recap = MediaSegment(type = "Recap", startTicks = 0L, endTicks = 600_000_000L)
        val commercial = MediaSegment(type = "Commercial", startTicks = 0L, endTicks = 600_000_000L)
        val unknown = MediaSegment(type = null, startTicks = 0L, endTicks = 600_000_000L)
        assertNull(SkipSegments.activeSegment(listOf(recap, commercial, unknown), 30.0))
    }

    @Test
    fun activeSegment_ignoresTooShortSegments() {
        // 4 s intro — under MIN_SEGMENT_SECONDS, the button would just flash
        val blip = MediaSegment(type = "Intro", startTicks = 100_000_000L, endTicks = 140_000_000L)
        assertNull(SkipSegments.activeSegment(listOf(blip), 11.0))
    }

    @Test
    fun mediaSegment_convertsTicksToSeconds() {
        val segment = MediaSegment(type = "Intro", startTicks = 150_000_000L, endTicks = 1_050_000_000L)
        assertEquals(15.0, segment.startSeconds)
        assertEquals(105.0, segment.endSeconds)
        assertEquals(true, segment.isIntro)
        assertEquals(false, segment.isOutro)
    }

    @Test
    fun persistedProfiles_roundTripsThroughJson() {
        val profiles = PersistedProfiles(
            listOf(
                PersistedSession(
                    deviceId = "dev-1",
                    session = UserSession("http://a:8096", "u1", "t1", "alice", "Server A"),
                ),
                PersistedSession(
                    deviceId = "dev-2",
                    session = UserSession("http://b:8096", "u2", "t2", "bob", null),
                ),
            )
        )
        assertEquals(profiles, PersistedProfiles.fromJson(profiles.toJson()))
        assertNull(PersistedProfiles.fromJson("not json"))
    }

    @Test
    fun withProfile_replacesSameServerAndUser() {
        val original = PersistedSession(
            deviceId = "dev-1",
            session = UserSession("http://a:8096", "u1", "old-token", "alice", null),
        )
        val renewed = PersistedSession(
            deviceId = "dev-1b",
            session = UserSession("http://a:8096", "u1", "new-token", "alice", null),
        )
        val other = PersistedSession(
            deviceId = "dev-2",
            session = UserSession("http://a:8096", "u2", "t2", "bob", null),
        )

        val store = PersistedProfiles(emptyList())
            .withProfile(original)
            .withProfile(other)
            .withProfile(renewed) // same server+user as original → replaces it

        assertEquals(2, store.profiles.size)
        assertEquals(
            "new-token",
            store.profiles.first { it.profileKey == renewed.profileKey }.session.accessToken,
        )

        val afterRemove = store.withoutProfile(other)
        assertEquals(listOf(renewed), afterRemove.profiles)
    }

    @Test
    fun profileLabels_fallBackGracefully() {
        val named = PersistedSession(
            deviceId = "d",
            session = UserSession("https://jf.example.com", "u", "t", "matthieu", "Salon"),
        )
        assertEquals("matthieu", named.displayName)
        assertEquals("Salon", named.serverLabel)
        assertEquals("M", named.initial)

        val bare = PersistedSession(
            deviceId = "d",
            session = UserSession("https://jf.example.com", "u", "t", null, null),
        )
        assertEquals("User", bare.displayName)
        assertEquals("jf.example.com", bare.serverLabel)
        assertEquals("U", bare.initial)
    }

    @Test
    fun isInsecureDowngrade_flagsSchemelessHttpFallbackOnly() {
        // Scheme-less input that fell back to http → warn before sending credentials
        assertEquals(
            true,
            JellyfinApi.isInsecureDowngrade("192.168.1.10:8096", "http://192.168.1.10:8096"),
        )
        // Explicit http is the user's own choice
        assertEquals(
            false,
            JellyfinApi.isInsecureDowngrade("http://192.168.1.10:8096", "http://192.168.1.10:8096"),
        )
        // https resolutions are never a downgrade
        assertEquals(
            false,
            JellyfinApi.isInsecureDowngrade("jf.example.com", "https://jf.example.com"),
        )
        assertEquals(
            false,
            JellyfinApi.isInsecureDowngrade(" 192.168.1.10:8096 ", "https://192.168.1.10:8096"),
        )
    }

    @Test
    fun isPlayable_moviesAndEpisodesOnly() {
        assertEquals(true, BaseItem(id = "a", type = "Movie").isPlayable)
        assertEquals(true, BaseItem(id = "b", type = "Episode").isPlayable)
        assertEquals(false, BaseItem(id = "c", type = "MusicAlbum").isPlayable)
        assertEquals(false, BaseItem(id = "d", type = null).isPlayable)
    }
}
