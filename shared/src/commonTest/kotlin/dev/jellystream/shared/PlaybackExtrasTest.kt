package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackExtrasTest {

    @Test
    fun statsPrintDirectPlayAndSkipEmptyRows() {
        val stats = PlaybackStats(
            isTranscode = false,
            container = "mkv",
            videoCodec = "hevc",
            videoRange = "HDR10",
            width = 3840,
            height = 2160,
            videoBitRate = 45_200_000,
            audioCodec = "truehd",
            audioChannels = 8,
            audioLanguage = "fra",
            frameRate = 23.976,
        )
        val lines = stats.lines()
        assertEquals("Direct Play", lines.first())
        assertTrue(lines.any { it.contains("HEVC") && it.contains("3840×2160") && it.contains("HDR10") })
        assertTrue(lines.any { it.contains("fps") && it.contains("Mbps") })
        assertTrue(lines.any { it.contains("TRUEHD") && it.contains("7.1") && it.contains("fra") })
        assertTrue("mkv" in lines)
    }

    @Test
    fun statsOfPicksDefaultAudioAndVideo() {
        val streams = listOf(
            MediaStream(type = "Video", codec = "av1", width = 1920, height = 1080, bitRate = 8_000_000, averageFrameRate = 24.0, videoRangeType = "SDR"),
            MediaStream(type = "Audio", codec = "aac", language = "eng", channels = 2, isDefault = false),
            MediaStream(type = "Audio", codec = "ac3", language = "fra", channels = 6, isDefault = true),
        )
        val stats = PlaybackStats.of(isTranscode = true, container = "ts", streams = streams, sourceBitrate = null)
        assertEquals("Transcode", stats.playMethod)
        assertEquals("av1", stats.videoCodec)
        assertEquals("ac3", stats.audioCodec)
        assertEquals("fra", stats.audioLanguage)
        assertEquals(6, stats.audioChannels)
        assertTrue(stats.lines().none { it.contains("SDR") })
    }

    @Test
    fun trickplayPicksTheWidestSheetOfThePlayingSource() {
        val small = TrickplayInfo(width = 320, thumbnailCount = 10, interval = 10_000, tileWidth = 10, tileHeight = 10)
        val wide = TrickplayInfo(width = 640, thumbnailCount = 10, interval = 10_000, tileWidth = 10, tileHeight = 10)
        val manifest = mapOf(
            "src-a" to mapOf("320" to small),
            "src-b" to mapOf("320" to small, "640" to wide),
        )
        assertEquals(640, Trickplay.pick(manifest, "src-b")?.width)
        assertEquals(320, Trickplay.pick(manifest, "src-a")?.width)
        assertEquals(640, Trickplay.pick(manifest, null)?.width)
        assertNull(Trickplay.pick(emptyMap(), "src-a"))
    }

    @Test
    fun trickplayCellLandsOnTheRightTileAndColumn() {
        val info = TrickplayInfo(
            width = 320,
            height = 180,
            tileWidth = 10,
            tileHeight = 10,
            thumbnailCount = 250,
            interval = 10_000,
        )
        // 0s → thumb 0 → tile 0, col 0, row 0
        val start = Trickplay.cellAt(0.0, info)!!
        assertEquals(0, start.tileIndex)
        assertEquals(0, start.column)
        assertEquals(0, start.row)
        // 105s → 105000/10000 = 10 → still tile 0, col 0, row 1 (10 thumbs per row)
        val later = Trickplay.cellAt(105.0, info)!!
        assertEquals(0, later.tileIndex)
        assertEquals(0, later.column)
        assertEquals(1, later.row)
        // 1000s → thumb 100 → tile 1, col 0, row 0
        val nextTile = Trickplay.cellAt(1_000.0, info)!!
        assertEquals(1, nextTile.tileIndex)
        assertEquals(0, nextTile.column)
        assertEquals(0, nextTile.row)
    }

    @Test
    fun displayRefreshSnapsTwentyFourPToCinemaAndFiftyToPal() {
        assertEquals(23.976, DisplayRefresh.preferredRate(23.976))
        assertEquals(24.0, DisplayRefresh.preferredRate(24.0))
        assertEquals(25.0, DisplayRefresh.preferredRate(25.0))
        assertEquals(50.0, DisplayRefresh.preferredRate(50.0))
        assertEquals(59.94, DisplayRefresh.preferredRate(59.94))
        assertNull(DisplayRefresh.preferredRate(null))
        assertNull(DisplayRefresh.preferredRate(0.0))
    }

    @Test
    fun headerlessUrlUsesApiKeyNotTheLegacySpellling() {
        val url = RemotePlayback.appendApiKey(
            "http://nas:8096/Videos/a/stream?static=true",
            "token-1",
        )
        assertEquals(
            "http://nas:8096/Videos/a/stream?static=true&ApiKey=token-1",
            url,
        )
        assertEquals(
            "http://nas:8096/master.m3u8?ApiKey=token-1",
            RemotePlayback.appendApiKey("http://nas:8096/master.m3u8", "token-1"),
        )
    }

    @Test
    fun boxSetsPeopleAndGenresAreBrowsable() {
        assertTrue(BaseItem(id = "a", type = "BoxSet").isBrowsable)
        assertTrue(BaseItem(id = "b", type = "Person").isBrowsable)
        assertTrue(BaseItem(id = "c", type = "Genre").isBrowsable)
        assertTrue(BaseItem(id = "d", type = "Movie").isBrowsable)
        assertTrue(!BaseItem(id = "e", type = "Audio").isBrowsable)
    }

    @Test
    fun actorsPreferTheActingCredits() {
        val people = listOf(
            PersonCredit(id = "1", name = "Director", type = "Director"),
            PersonCredit(id = "2", name = "Star", type = "Actor", role = "Ellen"),
            PersonCredit(id = "3", name = "Guest", type = "GuestStar"),
        )
        val actors = CatalogQuery.actors(people)
        assertEquals(listOf("Star", "Guest"), actors.map { it.name })
    }

    @Test
    fun chapterStartIsInSeconds() {
        val chapter = ChapterInfo(startPositionTicks = 120L * JellyfinApi.TICKS_PER_SECOND, name = "The turn")
        assertEquals(120.0, chapter.startSeconds)
        assertEquals("The turn", chapter.name)
    }
}
