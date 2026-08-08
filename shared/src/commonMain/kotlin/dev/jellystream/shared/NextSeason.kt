package dev.jellystream.shared

import kotlinx.coroutines.CancellationException

/**
 * What the player offers when an episode runs out.
 *
 * Built before playback ends, not at the moment it does: resolving it
 * costs four round trips, and a card that appears three seconds into the
 * credits has already missed the person reaching for the remote.
 */
data class NextSeasonOffer(
    val seriesTmdbId: Int,
    val seriesName: String,
    val seasonNumber: Int,
    /** Already asked for — the card says so instead of asking twice. */
    val alreadyRequested: Boolean,
    /** Episodes of the current season still unwatched after this one. */
    val episodesLeft: Int,
) {
    val title: String
        get() = if (alreadyRequested) {
            "Season $seasonNumber is on the way"
        } else {
            "Season $seasonNumber isn't on the server"
        }

    val body: String
        get() = when {
            alreadyRequested -> "$seriesName · already requested, nothing to do."
            episodesLeft == 1 ->
                "One episode left of season ${seasonNumber - 1} of $seriesName."
            episodesLeft > 1 ->
                "$episodesLeft episodes left of season ${seasonNumber - 1} of $seriesName."
            else ->
                "That was the last episode of season ${seasonNumber - 1} of $seriesName."
        }
}

/**
 * Whether finishing this episode should end with an offer, decided
 * without touching the network so it can be tested.
 */
object NextSeason {

    /**
     * How close to the end of a season the offer starts appearing.
     *
     * Two, not one: a season takes hours to download, and asking as the
     * final credits roll means the answer arrives long after the viewer
     * has given up and gone to bed. One episode of lead time is enough
     * for the next season to be waiting.
     */
    const val PROMPT_WITHIN_LAST_EPISODES = 2

    /**
     * Episodes of this season still to come after [episodeNumber].
     *
     * [seasonEpisodeCount] is how long the season actually is, upstream.
     * Counting only what the server holds is the trap: a season still
     * downloading — the very state the requests screen exists to show —
     * has three episodes on the shelf out of nine, and the third would
     * look like the end of the season. It is not, and offering the NEXT
     * season there is both a lie and the wrong thing to ask for.
     *
     * Null when nobody knows the real length, in which case the shelf is
     * the best guess available.
     */
    fun episodesLeftAfter(
        episodeNumber: Int,
        episodeNumbersInSeason: List<Int>,
        seasonEpisodeCount: Int?,
    ): Int {
        val onShelf = episodeNumbersInSeason.count { it > episodeNumber }
        val total = seasonEpisodeCount?.takeIf { it > 0 } ?: return onShelf
        // Anything the season has beyond this episode is still ahead of
        // the viewer, downloaded or not.
        return maxOf(onShelf, total - episodeNumber).coerceAtLeast(0)
    }

    /**
     * Whether the viewer is close enough to the end of the season to be
     * worth asking. Shared by the pure rule and by the network-side
     * early exit, so the two cannot drift.
     */
    fun isNearEndOfSeason(
        episodeNumber: Int,
        episodeNumbersInSeason: List<Int>,
        seasonEpisodeCount: Int?,
    ): Boolean =
        episodesLeftAfter(episodeNumber, episodeNumbersInSeason, seasonEpisodeCount) <
            PROMPT_WITHIN_LAST_EPISODES

    /**
     * The season worth offering, or null to stay quiet.
     *
     * Quiet is the default on purpose. A prompt that fires in the middle
     * of a season, or for a season the server already has, is worse than
     * no prompt at all — it trains people to dismiss it.
     *
     * @param episodeSeason the finished episode's season number
     * @param episodeNumber the finished episode's number within that season
     * @param episodeNumbersInSeason every episode of that season on the server
     * @param seasonEpisodeCount how long the season really is, upstream
     * @param seasonsOnServer season numbers Jellyfin holds for this show
     * @param seasonsUpstream season numbers TMDb says exist, specials excluded
     */
    fun seasonToOffer(
        episodeSeason: Int?,
        episodeNumber: Int?,
        episodeNumbersInSeason: List<Int>,
        seasonEpisodeCount: Int?,
        seasonsOnServer: List<Int>,
        seasonsUpstream: List<Int>,
    ): Int? {
        // Specials (season 0) have no "next", and neither does an item
        // the server never numbered.
        if (episodeSeason == null || episodeSeason < 1) return null
        if (episodeNumber == null) return null
        if (episodeNumbersInSeason.isEmpty()) return null

        // Near enough to the end that a download would land in time.
        if (!isNearEndOfSeason(episodeNumber, episodeNumbersInSeason, seasonEpisodeCount)) return null

        val next = episodeSeason + 1
        // Already downloaded: this is a "play next", not a "request next",
        // and the two must never be confused.
        if (seasonsOnServer.contains(next)) return null
        // Never offer to fetch a season that does not exist. Finishing the
        // final season of a finished show must end in silence.
        if (!seasonsUpstream.contains(next)) return null

        return next
    }
}

/**
 * Resolves [NextSeasonOffer] against the two servers.
 *
 * Lives in shared rather than in each app because it is four calls in a
 * precise order, with an early exit at every step — written twice it
 * would drift twice.
 */
class NextSeasonAdvisor(
    private val jellyfin: JellyfinApi,
    private val seerr: JellyseerrApi,
) {
    /**
     * Null whenever anything is missing, unreachable or simply not worth
     * asking about. This is decoration on top of playback: it must never
     * be the reason an episode fails to play.
     */
    @Throws(Throwable::class)
    suspend fun offerAfter(episode: BaseItem): NextSeasonOffer? {
        if (!seerr.isSignedIn) return null
        if (episode.type != "Episode") return null
        val seriesId = episode.seriesId ?: return null
        val season = episode.parentIndexNumber ?: return null
        val episodeNumber = episode.indexNumber ?: return null
        if (season < 1) return null

        return try {
            val seasons = jellyfin.getSeasons(seriesId)
            val onServer = seasons.mapNotNull { it.indexNumber }
            // Cheapest possible bail-out, and by far the commonest case.
            if (onServer.contains(season + 1)) return null

            val thisSeason = seasons.firstOrNull { it.indexNumber == season } ?: return null
            val episodeNumbers = jellyfin.getEpisodes(seriesId, thisSeason.id).mapNotNull { it.indexNumber }
            if (episodeNumbers.isEmpty()) return null
            // Bail before the two remaining round trips when the viewer is
            // still well inside the season. Counting the shelf alone can
            // only UNDERSTATE what is left, so a "no" here is final; the
            // real length is applied below, where it can still say no.
            if (!NextSeason.isNearEndOfSeason(episodeNumber, episodeNumbers, null)) return null

            // The series item is the only place Jellyfin keeps the TMDb id,
            // and only a single-item fetch carries ProviderIds at all.
            val series = jellyfin.getItem(seriesId) ?: return null
            val tmdbId = series.tmdbId ?: return null

            val details = seerr.tvDetails(tmdbId) ?: return null
            // How long the season really is. Without it, a season halfway
            // through downloading looks finished at its third episode.
            val episodeCount = details.seasons
                .firstOrNull { it.seasonNumber == season }
                ?.episodeCount
            val offered = NextSeason.seasonToOffer(
                episodeSeason = season,
                episodeNumber = episodeNumber,
                episodeNumbersInSeason = episodeNumbers,
                seasonEpisodeCount = episodeCount,
                seasonsOnServer = onServer,
                seasonsUpstream = details.seasonNumbers,
            ) ?: return null

            val alreadyRequested = !details.stateOf(offered).canRequest
            val left = NextSeason.episodesLeftAfter(episodeNumber, episodeNumbers, episodeCount)
            // Nothing to say and nothing to do: it is already on its way
            // and there is still an episode to watch. Save the news for
            // the moment they actually run out.
            if (alreadyRequested && left > 0) return null

            NextSeasonOffer(
                seriesTmdbId = tmdbId,
                seriesName = series.name ?: episode.seriesName ?: "this show",
                seasonNumber = offered,
                alreadyRequested = alreadyRequested,
                episodesLeft = left,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
