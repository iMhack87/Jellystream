package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingsTest {

    @Test
    fun communityScoreKeepsOneDecimal() {
        // Values seen on demo.jellyfin.org: 7.2, 8 and 7.011 all live
        // in the same library and must read alike
        assertEquals("7.2", ItemRatings(7.2, null, null).communityLabel)
        assertEquals("8.0", ItemRatings(8.0, null, null).communityLabel)
        assertEquals("7.0", ItemRatings(7.011, null, null).communityLabel)
        assertEquals("6.7", ItemRatings(6.65, null, null).communityLabel)
    }

    @Test
    fun communityScoreIsAbsentWhenUnrated() {
        assertNull(ItemRatings(null, null, null).communityLabel)
        // The server sends 0 for "nobody voted", not "everyone hated it"
        assertNull(ItemRatings(0.0, null, null).communityLabel)
    }

    @Test
    fun tomatometerReadsAsAPercentage() {
        assertEquals("94%", ItemRatings(null, 94, null).criticLabel)
        assertEquals("100%", ItemRatings(null, 100, null).criticLabel)
        assertEquals("0%", ItemRatings(null, 0, null).criticLabel)
    }

    @Test
    fun freshnessFollowsTheSixtyPercentLine() {
        // Jungle Book (54) is rotten and Dracula (94) is fresh on the demo
        assertEquals(false, ItemRatings(null, 54, null).criticIsFresh)
        assertEquals(true, ItemRatings(null, 60, null).criticIsFresh)
        assertEquals(true, ItemRatings(null, 94, null).criticIsFresh)
        assertEquals(60, ItemRatings.FRESH_THRESHOLD)
    }

    @Test
    fun noCriticScoreMeansNoColor() {
        // null, not false: an absent tomatometer must not paint as rotten
        assertNull(ItemRatings(7.2, null, null).criticIsFresh)
        assertNull(ItemRatings(7.2, null, null).criticLabel)
    }

    @Test
    fun outOfRangeScoresAreIgnored() {
        assertNull(ItemRatings(null, 140, null).criticLabel)
        assertNull(ItemRatings(null, -3, null).criticIsFresh)
        assertEquals("10.0", ItemRatings(11.5, null, null).communityLabel)
    }

    @Test
    fun blankCertificateCountsAsAbsent() {
        assertEquals("PG", ItemRatings(null, null, "PG").officialLabel)
        assertEquals("NR", ItemRatings(null, null, " NR ").officialLabel)
        assertNull(ItemRatings(null, null, "   ").officialLabel)
        assertNull(ItemRatings(null, null, null).officialLabel)
    }

    @Test
    fun emptyRatingsHideTheWholeRow() {
        assertTrue(ItemRatings(null, null, null).isEmpty)
        assertTrue(ItemRatings(0.0, null, "").isEmpty)
        assertFalse(ItemRatings(null, null, "PG").isEmpty)
        assertFalse(ItemRatings(null, 54, null).isEmpty)
    }

    @Test
    fun itemExposesItsOwnRatings() {
        val dracula = BaseItem(
            id = "1",
            name = "Dracula",
            communityRating = 7.2,
            criticRating = 94,
            officialRating = "NR",
        )
        assertEquals("7.2", dracula.ratings.communityLabel)
        assertEquals("94%", dracula.ratings.criticLabel)
        assertEquals(true, dracula.ratings.criticIsFresh)
        assertEquals("NR", dracula.ratings.officialLabel)
    }
}
