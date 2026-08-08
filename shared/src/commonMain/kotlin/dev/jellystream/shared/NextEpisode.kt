package dev.jellystream.shared

import kotlinx.coroutines.CancellationException

/**
 * The episode the player offers when the current one runs out.
 *
 * Resolved while the episode is still playing, like [NextSeasonOffer]: a
 * card that only starts asking as the credits roll arrives after the
 * remote is back down.
 */
data class NextEpisodeOffer(
    val episode: BaseItem,
    /** True when it is the first episode of the season after this one. */
    val startsNewSeason: Boolean,
) {
    val heading: String
        get() = if (startsNewSeason) "Next season" else "Up next"

    /** "S1 · E2 · The Title", or as much of it as the server sent. */
    val label: String
        get() = listOfNotNull(episode.episodeLabel, episode.name)
            .joinToString(" · ")
            .ifEmpty { "Next episode" }

    /**
     * Where it would start from, in seconds. Non-zero only for an episode
     * somebody already began and left — the resume point is the server's,
     * and auto-play must not silently ignore it.
     */
    val resumeSeconds: Double
        get() = episode.resumePositionSeconds
}

/**
 * Which episode follows this one, decided without touching the network so
 * it can be tested.
 *
 * Ordering is by episode number, never by the order the server happened to
 * return: `Shows/{id}/Episodes` is sorted today, and a client that relies
 * on that is one server-side sort option away from playing episode 7 after
 * episode 3.
 */
object NextEpisode {

    /**
     * How long the card counts down before playing on its own.
     *
     * Ten seconds: long enough to read one line and reach for the remote,
     * short enough that someone who wanted the next episode isn't sitting
     * through a countdown to get it.
     */
    const val AUTOPLAY_SECONDS = 10

    /**
     * The next episode of the SAME season, or null when this was its last.
     *
     * [episodes] is one season's worth; anything belonging to another
     * season is ignored rather than trusted, so handing this the whole
     * series cannot make it jump a season behind the caller's back.
     */
    fun afterInSeason(current: BaseItem, episodes: List<BaseItem>): BaseItem? {
        val number = current.indexNumber ?: return null
        val season = current.parentIndexNumber
        return episodes
            .filter { it.id != current.id }
            .filter { candidate ->
                // Same season when both say which one they are in. A null
                // on either side means the server did not number it, and
                // the season list it came from is the only thing left to
                // go on.
                season == null || candidate.parentIndexNumber == null ||
                    candidate.parentIndexNumber == season
            }
            .mapNotNull { candidate -> candidate.indexNumber?.let { it to candidate } }
            .filter { (candidateNumber, _) -> candidateNumber > number }
            .minByOrNull { (candidateNumber, _) -> candidateNumber }
            ?.second
    }

    /** The lowest-numbered episode of a season — where a new one starts. */
    fun firstOfSeason(episodes: List<BaseItem>): BaseItem? =
        episodes
            .mapNotNull { episode -> episode.indexNumber?.let { it to episode } }
            .minByOrNull { (number, _) -> number }
            ?.second
}

/**
 * Resolves [NextEpisodeOffer] against Jellyfin.
 *
 * Two calls in the common case, three when a season ends — and never the
 * whole series: `Shows/{id}/Episodes` without a season is every episode a
 * show ever had, which is a lot of JSON to carry for one card.
 */
class NextEpisodeAdvisor(private val jellyfin: JellyfinApi) {

    /**
     * Null whenever there is nothing sensible to play next, or anything at
     * all goes wrong. This is decoration on top of playback: it must never
     * be the reason an episode fails to play.
     */
    @Throws(Throwable::class)
    suspend fun after(episode: BaseItem): NextEpisodeOffer? {
        if (episode.type != "Episode") return null
        val seriesId = episode.seriesId ?: return null
        if (episode.indexNumber == null) return null

        return try {
            val seasons = jellyfin.getSeasons(seriesId)
            val season = episode.parentIndexNumber
            val currentSeason = seasons.firstOrNull { it.indexNumber == season }
                // Nothing to enumerate against: without a season we cannot
                // ask the server for its episodes, and guessing is worse
                // than staying quiet.
                ?: return null

            val inSeason = jellyfin.getEpisodes(seriesId, currentSeason.id)
            NextEpisode.afterInSeason(episode, inSeason)?.let {
                return NextEpisodeOffer(episode = it, startsNewSeason = false)
            }

            // Season over. Only the season immediately after it counts: a
            // server holding 1 and 3 must not answer "up next: season 3"
            // after the season 1 finale — that is a spoiler and the wrong
            // answer. Season 2 is missing, and asking Jellyseerr for it is
            // exactly what NextSeasonAdvisor is for.
            if (season == null || season < 1) return null
            val next = seasons.firstOrNull { it.indexNumber == season + 1 } ?: return null
            val nextEpisodes = jellyfin.getEpisodes(seriesId, next.id)
            NextEpisode.firstOfSeason(nextEpisodes)
                ?.let { NextEpisodeOffer(episode = it, startsNewSeason = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
