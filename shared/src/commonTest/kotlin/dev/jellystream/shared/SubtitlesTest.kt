package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageCodeTest {

    @Test
    fun theTwoThreeLetterStandardsAreTheSameLanguage() {
        // 639-2/B vs 639-2/T — muxers use either, in the same library
        assertTrue(LanguageCode.matches("fre", "fra"))
        assertTrue(LanguageCode.matches("ger", "deu"))
        assertTrue(LanguageCode.matches("cze", "ces"))
    }

    @Test
    fun twoLetterAndThreeLetterCodesAgree() {
        assertTrue(LanguageCode.matches("fr", "fre"))
        assertTrue(LanguageCode.matches("en", "eng"))
        assertTrue(LanguageCode.matches("ja", "jpn"))
    }

    @Test
    fun caseAndRegionAreIgnored() {
        assertTrue(LanguageCode.matches("FR", "fra"))
        assertTrue(LanguageCode.matches("pt-BR", "por"))
        assertTrue(LanguageCode.matches("en_US", "eng"))
    }

    @Test
    fun differentLanguagesDoNotMatch() {
        assertFalse(LanguageCode.matches("fre", "eng"))
        assertFalse(LanguageCode.matches("es", "por"))
    }

    @Test
    fun unknownIsNeverALanguage() {
        // "und" is what a muxer writes when it has no idea; treating it as
        // a match would switch on a random track
        assertFalse(LanguageCode.matches("und", "und"))
        assertFalse(LanguageCode.matches("und", "fre"))
        assertFalse(LanguageCode.matches(null, null))
        assertFalse(LanguageCode.matches("", "fre"))
        assertNull(LanguageCode.normalize("und"))
        assertNull(LanguageCode.normalize("  "))
    }
}

class SubtitleSelectionTest {

    private fun sub(
        index: Int,
        language: String?,
        forced: Boolean = false,
        hearingImpaired: Boolean = false,
    ) = MediaStream(
        index = index,
        type = "Subtitle",
        language = language,
        isForced = forced,
        isHearingImpaired = hearingImpaired,
    )

    private fun choose(
        subtitles: List<MediaStream>,
        audio: String?,
        preferred: String? = "fre",
        mode: SubtitleMode = SubtitleMode.SMART,
    ) = SubtitleSelection.choose(subtitles, audio, preferred, mode)

    @Test
    fun smart_foreignAudioGetsFullSubtitles() {
        val tracks = listOf(sub(1, "eng"), sub(2, "fre"))

        assertEquals(2, choose(tracks, audio = "eng")?.index)
    }

    @Test
    fun smart_audioYouUnderstandGetsOnlyTheForcedTrack() {
        val tracks = listOf(sub(1, "fre"), sub(2, "fre", forced = true))

        // A full French track over French audio is exactly what nobody wants
        assertEquals(2, choose(tracks, audio = "fra")?.index)
    }

    @Test
    fun smart_audioYouUnderstandAndNoForcedTrackMeansNoSubtitles() {
        val tracks = listOf(sub(1, "fre"), sub(2, "eng"))

        assertNull(choose(tracks, audio = "fre"))
    }

    @Test
    fun smart_fallsBackToForcedWhenNoFullTrackExists() {
        val tracks = listOf(sub(1, "eng"), sub(2, "fre", forced = true))

        assertEquals(2, choose(tracks, audio = "jpn")?.index)
    }

    @Test
    fun smart_leavesSubtitlesOffWhenTheLanguageIsAbsent() {
        // Turning on Japanese subtitles because French is missing helps
        // nobody
        val tracks = listOf(sub(1, "eng"), sub(2, "jpn"))

        assertNull(choose(tracks, audio = "jpn"))
    }

    @Test
    fun plainTracksBeatHearingImpairedOnes() {
        val tracks = listOf(sub(1, "fre", hearingImpaired = true), sub(2, "fre"))

        assertEquals(2, choose(tracks, audio = "eng")?.index)
    }

    @Test
    fun hearingImpairedIsStillBetterThanNothing() {
        val tracks = listOf(sub(1, "eng"), sub(2, "fre", hearingImpaired = true))

        assertEquals(2, choose(tracks, audio = "eng")?.index)
    }

    @Test
    fun anUntaggedForcedTrackCounts() {
        // Muxers leave the language blank on forced tracks constantly
        val tracks = listOf(sub(1, "eng"), sub(2, null, forced = true))

        assertEquals(2, choose(tracks, audio = "fre")?.index)
    }

    @Test
    fun offNeverSelectsAnything() {
        val tracks = listOf(sub(1, "fre"), sub(2, "fre", forced = true))

        assertNull(choose(tracks, audio = "eng", mode = SubtitleMode.OFF))
    }

    @Test
    fun forcedOnlyIgnoresFullTracksWhateverTheAudio() {
        val tracks = listOf(sub(1, "fre"), sub(2, "fre", forced = true))

        assertEquals(2, choose(tracks, audio = "eng", mode = SubtitleMode.FORCED_ONLY)?.index)
        assertNull(
            choose(listOf(sub(1, "fre")), audio = "eng", mode = SubtitleMode.FORCED_ONLY)
        )
    }

    @Test
    fun alwaysKeepsFullSubtitlesOverAudioYouUnderstand() {
        val tracks = listOf(sub(1, "fre"), sub(2, "fre", forced = true))

        assertEquals(1, choose(tracks, audio = "fre", mode = SubtitleMode.ALWAYS)?.index)
    }

    @Test
    fun audioAndSubtitleCodesFromDifferentStandardsStillMatch() {
        // The trap: "fra" audio next to "fre" subtitles in one file
        val tracks = listOf(sub(1, "fre", forced = true), sub(2, "fre"))

        assertEquals(1, choose(tracks, audio = "fra")?.index)
    }

    @Test
    fun untaggedAudioIsTreatedAsForeign() {
        // "und" audio is not proof you understand it — subtitles on
        val tracks = listOf(sub(1, "fre"))

        assertEquals(1, choose(tracks, audio = "und")?.index)
        assertEquals(1, choose(tracks, audio = null)?.index)
    }

    @Test
    fun noSubtitlesAtAllIsNotAFailure() {
        assertNull(choose(emptyList(), audio = "eng"))
    }

    @Test
    fun nonSubtitleStreamsAreNeverSelected() {
        val streams = listOf(
            MediaStream(index = 1, type = "Audio", language = "fre"),
            MediaStream(index = 2, type = "Video"),
        )

        assertNull(choose(streams, audio = "eng"))
    }

    @Test
    fun noPreferredLanguageMeansForcedTracksOnly() {
        // Nothing to match a full track against; the forced one is still
        // right, since it exists precisely for viewers of any language
        val tracks = listOf(sub(1, "eng"), sub(2, "eng", forced = true))

        assertEquals(2, choose(tracks, audio = "jpn", preferred = null)?.index)
    }
}
