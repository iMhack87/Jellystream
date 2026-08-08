package dev.jellystream.shared

/**
 * How far along a request is, as one bar and one line of text.
 *
 * Jellyseerr reports a *list* of grabs — a season being fetched is one
 * entry per episode — and a screen only ever wants a single answer. The
 * arithmetic lives here so Android and Apple cannot drift on what "62%"
 * means, and so it can be tested without a server.
 *
 * Deliberately not named DownloadProgress: [DownloadState] already means
 * "this file is on the device", which is the opposite end of the app.
 */
data class RequestProgress(
    /** 0.0–1.0 across every file in the batch, by bytes. */
    val fraction: Double,
    /** Longest wait among the files, or null when nothing reports one. */
    val remainingSeconds: Long?,
    /** How many files are in flight — a season is not one download. */
    val fileCount: Int,
    /** Sonarr's own word for it, lowercased; empty when it said nothing. */
    val status: String,
) {
    /** "62%" — whole numbers only; a decimal on a progress bar is noise. */
    val percentLabel: String
        get() = "${(fraction * 100).toInt()}%"

    /** "12 min left", or null when the server is not estimating. */
    val remainingLabel: String?
        get() = remainingSeconds?.let { formatRemaining(it) }

    /**
     * Sonarr keeps reporting a queue item after the bytes are in, while it
     * imports and renames. Saying "100%" then is honest; saying "0 min
     * left" next to it is not.
     */
    val isFinishing: Boolean
        get() = fraction >= 1.0

    /**
     * A paused or stalled grab still has a percentage, but the bar must
     * not pretend it is moving.
     */
    val isStalled: Boolean
        get() = status == "paused" || status == "delay" || status == "warning" || status == "failed"

    /** The whole thing in one line: "62% · 12 min left". */
    val summary: String
        get() = listOfNotNull(
            percentLabel,
            remainingLabel.takeIf { !isFinishing },
            "Finishing up".takeIf { isFinishing },
        ).joinToString(" · ")

    companion object {
        /**
         * Folds a batch of grabs into one bar, or null when there is
         * nothing to show.
         *
         * Entries with no size are dropped rather than counted as zero:
         * Sonarr reports a size of 0 for an item it has only just queued,
         * and averaging that in would drag a nearly finished season back
         * down to half.
         */
        fun of(downloads: List<JellyseerrDownload>): RequestProgress? {
            val sized = downloads.filter { it.size > 0.0 }
            if (sized.isEmpty()) return null

            val total = sized.sumOf { it.size }
            // sizeLeft counts down, and Sonarr has been seen reporting it
            // slightly above size on a re-grab — clamp before dividing.
            val left = sized.sumOf { it.sizeLeft.coerceIn(0.0, it.size) }
            val fraction = ((total - left) / total).coerceIn(0.0, 1.0)

            val remaining = sized.mapNotNull { parseTimeLeft(it.timeLeft) }.maxOrNull()

            return RequestProgress(
                fraction = fraction,
                remainingSeconds = remaining,
                fileCount = sized.size,
                status = sized.firstOrNull { it.status != null }?.status?.lowercase().orEmpty(),
            )
        }

        /**
         * Reads a .NET TimeSpan — "00:12:34", "1.03:00:00", or either of
         * those with a fractional-seconds tail.
         *
         * Sonarr passes its own field straight through and simply omits it
         * when a download stalls, so anything unrecognised has to mean
         * "no estimate" rather than an exception: a request screen that
         * throws on a stalled torrent is worse than one that hides a label.
         */
        internal fun parseTimeLeft(raw: String?): Long? {
            var rest = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            // A dot before the first colon is the day part; a dot after it
            // is fractional seconds. "1.03:00:00" is 1 day, 3 hours.
            var days = 0L
            val dot = rest.indexOf('.')
            val colon = rest.indexOf(':')
            if (dot >= 0 && (colon < 0 || dot < colon)) {
                days = rest.substring(0, dot).toLongOrNull() ?: return null
                rest = rest.substring(dot + 1)
            }
            rest = rest.substringBefore('.')

            val parts = rest.split(':')
            if (parts.size != 3) return null
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toLongOrNull() ?: return null
            if (days < 0 || hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null

            return ((days * 24 + hours) * 60 + minutes) * 60 + seconds
        }

        /**
         * Rounds *up* everywhere: forty seconds left should read "1 min
         * left", never "0 min left", which looks like a stuck download.
         */
        internal fun formatRemaining(seconds: Long): String {
            if (seconds <= 0L) return "Any moment now"
            if (seconds < 60L) return "Under a minute left"

            // Round up to whole minutes ONCE, then split. Rounding each
            // unit on its own is how "1 h 60 min left" gets shipped.
            val totalMinutes = (seconds + 59) / 60
            if (totalMinutes < 60) return "$totalMinutes min left"

            val totalHours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (totalHours < 24) {
                return if (minutes == 0L) "$totalHours h left" else "$totalHours h $minutes min left"
            }

            // Past a day the estimate is guesswork anyway — round to the
            // nearest day so 25 hours does not read as "2 days".
            val days = (totalHours + 12) / 24
            return if (days <= 1L) "About a day left" else "About $days days left"
        }
    }
}
