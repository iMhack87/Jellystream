package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which episode follows this one.
 *
 * The failure that matters here is not "no card": it is a card that plays
 * the WRONG episode. Auto-play means nobody is watching it happen.
 */
class NextEpisodeTest {

    private fun episode(
        id: String,
        season: Int? = 1,
        number: Int?,
        name: String? = null,
    ) = BaseItem(
        id = id,
        name = name,
        type = "Episode",
        indexNumber = number,
        parentIndexNumber = season,
    )

    @Test
    fun theNextEpisodeIsTheNextNumber() {
        val current = episode("e1", number = 1)
        val season = listOf(current, episode("e2", number = 2), episode("e3", number = 3))
        assertEquals("e2", NextEpisode.afterInSeason(current, season)?.id)
    }

    @Test
    fun orderComesFromTheNumbersNotFromTheServersOrder() {
        // Same season, returned shuffled — a server-side sort option, a
        // cache, or a plain bug is enough to produce this, and following
        // the list order would play episode 7 after episode 3.
        val current = episode("e3", number = 3)
        val shuffled = listOf(
            episode("e7", number = 7),
            episode("e4", number = 4),
            current,
            episode("e9", number = 9),
        )
        assertEquals("e4", NextEpisode.afterInSeason(current, shuffled)?.id)
    }

    @Test
    fun aGapInTheNumbersIsNotAWallToStopAt() {
        // Episode 4 is missing from the server (never downloaded, or
        // deleted). Stopping there would strand the viewer mid-season.
        val current = episode("e3", number = 3)
        val season = listOf(current, episode("e5", number = 5))
        assertEquals("e5", NextEpisode.afterInSeason(current, season)?.id)
    }

    @Test
    fun theLastEpisodeOfASeasonHasNoNextWithinIt() {
        val current = episode("e8", number = 8)
        val season = (1..8).map { episode("e$it", number = it) }
        assertNull(NextEpisode.afterInSeason(current, season))
    }

    @Test
    fun episodesOfAnotherSeasonAreNeverTheAnswerHere() {
        // Handing this the whole series must not make it jump a season
        // behind the advisor's back — crossing seasons is a separate,
        // deliberate step with its own rule.
        val current = episode("s1e8", season = 1, number = 8)
        val everything = listOf(
            current,
            episode("s2e1", season = 2, number = 1),
            episode("s2e2", season = 2, number = 2),
        )
        assertNull(NextEpisode.afterInSeason(current, everything))
    }

    @Test
    fun specialsDoNotBecomeTheNextEpisodeOfARealSeason() {
        // Season 0 is where Jellyfin files specials, recaps and behind-the
        // -scenes reels. Auto-playing one after a finale is the cheapest
        // way to make people turn the feature off.
        val current = episode("s1e8", season = 1, number = 8)
        val withSpecials = listOf(
            current,
            episode("s0e9", season = 0, number = 9),
            episode("s0e12", season = 0, number = 12),
        )
        assertNull(NextEpisode.afterInSeason(current, withSpecials))
    }

    @Test
    fun anUnnumberedEpisodeIsNotSomethingToPlayNext() {
        val current = episode("e1", number = 1)
        val season = listOf(current, episode("e?", number = null))
        assertNull(NextEpisode.afterInSeason(current, season))
    }

    @Test
    fun anUnnumberedCurrentEpisodeHasNoPlaceToCountFrom() {
        val current = episode("e?", number = null)
        val season = listOf(current, episode("e2", number = 2))
        assertNull(NextEpisode.afterInSeason(current, season))
    }

    @Test
    fun theSameEpisodeIsNeverItsOwnSequel() {
        // A duplicate id at the same number is the one way a naive
        // "first candidate after me" could hand back what just played.
        val current = episode("e2", number = 2)
        val season = listOf(current, episode("e2", number = 2))
        assertNull(NextEpisode.afterInSeason(current, season))
    }

    @Test
    fun aNewSeasonStartsAtItsLowestNumberedEpisode() {
        val season = listOf(
            episode("s2e3", season = 2, number = 3),
            episode("s2e1", season = 2, number = 1),
            episode("s2e2", season = 2, number = 2),
        )
        assertEquals("s2e1", NextEpisode.firstOfSeason(season)?.id)
    }

    @Test
    fun aSeasonThatStartsAtEpisodeZeroStillStartsSomewhere() {
        // Some shows number a pilot 0; picking "1" by convention would
        // skip it.
        val season = listOf(episode("pilot", season = 2, number = 0), episode("s2e1", season = 2, number = 1))
        assertEquals("pilot", NextEpisode.firstOfSeason(season)?.id)
    }

    @Test
    fun anEmptySeasonHasNothingToStartWith() {
        assertNull(NextEpisode.firstOfSeason(emptyList()))
        assertNull(NextEpisode.firstOfSeason(listOf(episode("e?", season = 2, number = null))))
    }

    @Test
    fun theCardSaysWhichEpisodeItIsAboutToPlay() {
        val offer = NextEpisodeOffer(
            episode = episode("e2", season = 1, number = 2, name = "Half Loop"),
            startsNewSeason = false,
        )
        assertEquals("Up next", offer.heading)
        assertEquals("S1 · E2 · Half Loop", offer.label)
    }

    @Test
    fun crossingIntoANewSeasonSaysSoInsteadOfPretendingItIsMoreOfTheSame() {
        val offer = NextEpisodeOffer(
            episode = episode("s2e1", season = 2, number = 1, name = "Good News About Hell"),
            startsNewSeason = true,
        )
        assertEquals("Next season", offer.heading)
        assertTrue(offer.label.startsWith("S2 · E1"))
    }

    @Test
    fun aNamelessEpisodeStillGetsSomethingToPutOnTheCard() {
        val offer = NextEpisodeOffer(episode = episode("x", season = null, number = null), startsNewSeason = false)
        assertEquals("Next episode", offer.label)
    }

    @Test
    fun theResumePointOfTheNextEpisodeIsCarriedNotInvented() {
        val started = BaseItem(
            id = "e2",
            type = "Episode",
            indexNumber = 2,
            parentIndexNumber = 1,
            userData = UserItemData(playbackPositionTicks = 3 * JellyfinApi.TICKS_PER_SECOND),
        )
        assertEquals(3.0, NextEpisodeOffer(started, startsNewSeason = false).resumeSeconds)
    }
}
