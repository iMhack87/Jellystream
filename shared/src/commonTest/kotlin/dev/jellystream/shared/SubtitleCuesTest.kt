package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VttParserTest {

    @Test
    fun readsAPlainVttFile() {
        val vtt = """
            WEBVTT

            1
            00:00:02.000 --> 00:00:10.000
            Première réplique

            2
            00:00:12.500 --> 00:00:20.000
            Deuxième réplique
        """.trimIndent()

        val cues = VttParser.parse(vtt)

        assertEquals(2, cues.size)
        assertEquals(2.0, cues[0].startSeconds)
        assertEquals(10.0, cues[0].endSeconds)
        assertEquals("Première réplique", cues[0].text)
        assertEquals(12.5, cues[1].startSeconds)
    }

    @Test
    fun keepsMultiLineCaptionsTogether() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            First line
            Second line
        """.trimIndent()

        assertEquals("First line\nSecond line", VttParser.parse(vtt).single().text)
    }

    @Test
    fun acceptsTheShortTimestampForm() {
        // VTT allows MM:SS.mmm when the file is under an hour
        val cues = VttParser.parse("WEBVTT\n\n01:02.500 --> 01:05.000\nHello")

        assertEquals(62.5, cues.single().startSeconds)
        assertEquals(65.0, cues.single().endSeconds)
    }

    @Test
    fun acceptsSrtCommasToo() {
        // Jellyfin normally converts, but a hand-made file lands as-is
        val cues = VttParser.parse("00:00:01,500 --> 00:00:02,500\nHello")

        assertEquals(1.5, cues.single().startSeconds)
    }

    @Test
    fun handlesWindowsAndOldMacLineEndings() {
        val crlf = "WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nHello\r\n"
        assertEquals("Hello", VttParser.parse(crlf).single().text)

        val cr = "WEBVTT\r\r00:00:01.000 --> 00:00:02.000\rHello\r"
        assertEquals("Hello", VttParser.parse(cr).single().text)
    }

    @Test
    fun stripsInlineMarkup() {
        val cues = VttParser.parse("00:00:01.000 --> 00:00:02.000\n<i>Whispered</i> aloud")

        assertEquals("Whispered aloud", cues.single().text)
    }

    @Test
    fun ignoresCuePositioningSettings() {
        val cues = VttParser.parse("00:00:01.000 --> 00:00:02.000 line:90% align:center\nHi")

        assertEquals(1.0, cues.single().startSeconds)
        assertEquals(2.0, cues.single().endSeconds)
    }

    @Test
    fun aBrokenBlockCostsTheLineNotTheFilm() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> nonsense
            Dropped

            00:00:05.000 --> 00:00:06.000
            Kept
        """.trimIndent()

        assertEquals(listOf("Kept"), VttParser.parse(vtt).map { it.text })
    }

    @Test
    fun emptyAndGarbageInputYieldNoCuesRatherThanThrowing() {
        assertTrue(VttParser.parse("").isEmpty())
        assertTrue(VttParser.parse("not a subtitle file at all").isEmpty())
        // Zero-length and reversed cues are not worth showing
        assertTrue(VttParser.parse("00:00:05.000 --> 00:00:05.000\nHi").isEmpty())
        assertTrue(VttParser.parse("00:00:09.000 --> 00:00:05.000\nHi").isEmpty())
    }
}

class CueTimingTest {

    private val cues = listOf(
        SubtitleCue(2.0, 10.0, "first"),
        SubtitleCue(12.0, 20.0, "second"),
    )

    @Test
    fun showsTheCueCoveringThePlayhead() {
        assertEquals(listOf("first"), CueTiming.activeCues(cues, 5.0, 0.0).map { it.text })
        assertEquals(listOf("second"), CueTiming.activeCues(cues, 13.0, 0.0).map { it.text })
        assertTrue(CueTiming.activeCues(cues, 11.0, 0.0).isEmpty())
    }

    @Test
    fun theCueEndsAtItsEndTime() {
        // Half-open: at exactly 10.0 the first line is gone
        assertEquals(listOf("first"), CueTiming.activeCues(cues, 9.999, 0.0).map { it.text })
        assertTrue(CueTiming.activeCues(cues, 10.0, 0.0).isEmpty())
    }

    @Test
    fun aPositiveDelayShowsTheLineLater() {
        // Subtitles running ahead of the audio: push them back
        assertTrue(CueTiming.activeCues(cues, 5.0, 4.0).isEmpty())
        assertEquals(listOf("first"), CueTiming.activeCues(cues, 9.0, 4.0).map { it.text })
    }

    @Test
    fun aNegativeDelayShowsTheLineEarlier() {
        // The direction Media3 cannot do on its own
        assertEquals(listOf("first"), CueTiming.activeCues(cues, 1.0, -2.0).map { it.text })
        assertEquals(listOf("second"), CueTiming.activeCues(cues, 11.0, -2.0).map { it.text })
    }
}
