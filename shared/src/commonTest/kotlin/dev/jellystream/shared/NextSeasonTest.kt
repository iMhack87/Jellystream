package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that decides whether finishing an episode ends with an offer.
 *
 * Every case here is a way the prompt could become the thing people
 * reflexively dismiss, which is the only real failure mode it has.
 */
class NextSeasonTest {

    private fun offer(
        season: Int? = 1,
        episode: Int? = 8,
        inSeason: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 8),
        onServer: List<Int> = listOf(1),
        upstream: List<Int> = listOf(1, 2),
    ) = NextSeason.seasonToOffer(season, episode, inSeason, onServer, upstream)

    @Test
    fun theLastEpisodeOfTheLastSeasonWeHoldIsTheWholePoint() {
        assertEquals(2, offer())
    }

    @Test
    fun theOfferComesTwoEpisodesOutSoTheDownloadHasAHeadStart() {
        // A season takes hours to fetch. Asking as the final credits roll
        // means the answer lands after the viewer has gone to bed.
        assertEquals(2, offer(episode = 7))
    }

    @Test
    fun midSeasonStaysQuiet() {
        // Six more episodes to watch; asking now is noise, and a prompt
        // people learn to dismiss is worse than no prompt
        assertNull(offer(episode = 1))
        assertNull(offer(episode = 6))
    }

    @Test
    fun aTwoEpisodeSeasonIsAllWindowAndThatIsFine() {
        // Nothing to protect against here: both episodes are near the end
        assertEquals(2, offer(episode = 1, inSeason = listOf(1, 2)))
        assertEquals(2, offer(episode = 2, inSeason = listOf(1, 2)))
    }

    @Test
    fun theWindowCountsEpisodesLeftNotEpisodeNumbers() {
        // Jellyfin can hand back a season with gaps; what matters is how
        // many episodes remain, not the arithmetic distance to the last
        assertEquals(2, offer(episode = 4, inSeason = listOf(1, 4, 9)))
        assertNull(offer(episode = 1, inSeason = listOf(1, 4, 9)))
    }

    @Test
    fun aSeasonAlreadyOnTheServerIsAPlayNextNotARequest() {
        assertNull(offer(onServer = listOf(1, 2)))
    }

    @Test
    fun theEndOfAFinishedShowEndsInSilence() {
        // Nothing upstream to ask for — offering would promise a season
        // that does not exist
        assertNull(offer(upstream = listOf(1)))
        assertNull(offer(upstream = emptyList()))
    }

    @Test
    fun aGapInTheLibraryIsStillWorthOffering() {
        // Seasons 1 and 3 on the server, watching the end of 1: season 2
        // is the missing one, and it is the one to ask for
        assertEquals(2, offer(onServer = listOf(1, 3), upstream = listOf(1, 2, 3)))
    }

    @Test
    fun specialsHaveNoNextSeason() {
        assertNull(offer(season = 0, inSeason = listOf(1), episode = 1, upstream = listOf(0, 1, 2)))
    }

    @Test
    fun anUnnumberedEpisodeCannotBeTheLastOfAnything() {
        assertNull(offer(episode = null))
        assertNull(offer(season = null))
        assertNull(offer(inSeason = emptyList()))
    }

    @Test
    fun anEpisodeNumberedPastTheListStillCounts() {
        // Jellyfin can hand back a season whose episode list is missing
        // entries; being at or past the highest number we know is enough
        assertEquals(2, offer(episode = 12, inSeason = listOf(1, 2, 8)))
    }
}

class NextSeasonOfferCopyTest {

    private fun offer(alreadyRequested: Boolean, episodesLeft: Int) = NextSeasonOffer(
        seriesTmdbId = 95396,
        seriesName = "Severance",
        seasonNumber = 2,
        alreadyRequested = alreadyRequested,
        episodesLeft = episodesLeft,
    )

    @Test
    fun theEndOfTheSeasonSaysSo() {
        val card = offer(alreadyRequested = false, episodesLeft = 0)

        assertEquals("Season 2 isn't on the server", card.title)
        assertTrue(card.body.contains("last episode of season 1 of Severance"))
    }

    @Test
    fun aHeadStartOfferSaysHowMuchIsLeftRatherThanClaimingTheSeasonIsOver() {
        // Fired one episode early, "that was the last episode" is a lie
        val card = offer(alreadyRequested = false, episodesLeft = 1)

        assertEquals("One episode left of season 1 of Severance.", card.body)
    }

    @Test
    fun aSeasonAlreadyAskedForOffersNothingToDo() {
        val card = offer(alreadyRequested = true, episodesLeft = 0)

        assertEquals("Season 2 is on the way", card.title)
        assertTrue(card.body.contains("already requested"))
    }
}

class EpisodesLeftTest {

    @Test
    fun whatIsLeftIsCountedNotSubtracted() {
        assertEquals(0, NextSeason.episodesLeftAfter(8, listOf(1, 2, 8)))
        assertEquals(1, NextSeason.episodesLeftAfter(2, listOf(1, 2, 8)))
        assertEquals(2, NextSeason.episodesLeftAfter(1, listOf(1, 2, 8)))
        // Past the end of what the server holds
        assertEquals(0, NextSeason.episodesLeftAfter(12, listOf(1, 2, 8)))
    }
}
