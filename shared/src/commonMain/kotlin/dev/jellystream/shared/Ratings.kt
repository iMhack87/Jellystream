package dev.jellystream.shared

import kotlin.math.roundToInt

/**
 * The rating badges a detail screen shows, decided once for every platform.
 *
 * Jellyfin carries three unrelated numbers under similar names, and mixing
 * them up is the easy mistake:
 *  - `CommunityRating` is an audience average out of 10 (TMDb-sourced),
 *  - `CriticRating` is the Rotten Tomatoes tomatometer, a percentage,
 *  - `OfficialRating` is an age certificate string ("PG", "TV-MA", "NR").
 *
 * Formatting lives here so a title reads "7.2" on the TV and "7.2" on the
 * phone, never "7.2" and "7.20".
 */
data class ItemRatings(
    val communityScore: Double?,
    val criticScore: Int?,
    val officialRating: String?,
) {
    /** Audience score out of 10, one decimal: "7.2". */
    val communityLabel: String?
        get() = communityScore
            ?.takeIf { it > 0 }
            ?.coerceIn(0.0, 10.0)
            ?.let { oneDecimal(it) }

    /** Tomatometer as a percentage: "94%". */
    val criticLabel: String?
        get() = criticScore?.takeIf { it in 0..100 }?.let { "$it%" }

    /**
     * Fresh above the tomatometer's 60% line, rotten below — null when the
     * server has no critic score, so a platform never colors a missing one.
     */
    val criticIsFresh: Boolean?
        get() = criticScore?.takeIf { it in 0..100 }?.let { it >= FRESH_THRESHOLD }

    /** Age certificate, blank strings treated as absent. */
    val officialLabel: String?
        get() = officialRating?.trim()?.takeIf { it.isNotEmpty() }

    /** Nothing to show — the row should not take vertical space at all. */
    val isEmpty: Boolean
        get() = communityLabel == null && criticLabel == null && officialLabel == null

    companion object {
        /** Rotten Tomatoes calls a title Fresh at 60% and up. */
        const val FRESH_THRESHOLD: Int = 60

        /**
         * "7.2" — one decimal, no locale. Kotlin/Native has no
         * `String.format`, and the platforms must agree character for
         * character anyway.
         */
        private fun oneDecimal(value: Double): String {
            val tenths = (value * 10).roundToInt()
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}
