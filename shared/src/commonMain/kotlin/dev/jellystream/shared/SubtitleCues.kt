package dev.jellystream.shared

/** One line of subtitle, in media time. */
data class SubtitleCue(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
)

/**
 * WebVTT reader, used when the player has to place the lines itself.
 *
 * Android's player has no way to shift subtitle timing — Media3 hands a
 * cue over at the moment it starts, so it can be held back but never
 * brought forward. Owning the cue list is what makes both directions
 * possible, and Jellyfin will convert any text track to VTT on request.
 *
 * Deliberately tolerant: a subtitle file that trips the parser must cost
 * the line, not the film.
 */
object VttParser {

    /** Never throws. Malformed blocks are skipped, not fatal. */
    fun parse(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        // \r\n, \n and the odd \r all appear in the wild
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n")

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.contains("-->")) {
                index++
                continue
            }
            val times = parseTiming(line)
            if (times == null) {
                index++
                continue
            }
            // Everything up to the blank line is the caption itself
            val text = StringBuilder()
            index++
            while (index < lines.size && lines[index].isNotBlank()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(stripTags(lines[index]))
                index++
            }
            val body = text.toString().trim()
            if (body.isNotEmpty() && times.second > times.first) {
                cues.add(SubtitleCue(times.first, times.second, body))
            }
        }
        return cues
    }

    /** "00:01:02.500 --> 00:01:04.000 line:90%" — trailing settings ignored. */
    private fun parseTiming(line: String): Pair<Double, Double>? {
        val parts = line.split("-->")
        if (parts.size != 2) return null
        val start = parseTimestamp(parts[0].trim()) ?: return null
        val end = parseTimestamp(parts[1].trim().substringBefore(' ')) ?: return null
        return start to end
    }

    /** Accepts HH:MM:SS.mmm and MM:SS.mmm; VTT allows both, and so does SRT with a comma. */
    private fun parseTimestamp(raw: String): Double? {
        val cleaned = raw.replace(',', '.').trim()
        val pieces = cleaned.split(':')
        if (pieces.size !in 2..3) return null
        val seconds = pieces.last().toDoubleOrNull() ?: return null
        val minutes = pieces[pieces.size - 2].toIntOrNull() ?: return null
        val hours = if (pieces.size == 3) pieces[0].toIntOrNull() ?: return null else 0
        return hours * 3600 + minutes * 60 + seconds
    }

    /** Drops <i>, <b>, <c.yellow> and friends — we render plain text. */
    private fun stripTags(line: String): String =
        line.replace(TAG, "").trim()

    private val TAG = Regex("<[^>]*>")
}

/**
 * The cues to show at a given moment, once the viewer's resync is applied.
 *
 * A positive delay shows a line later, which is what "the subtitles are
 * ahead of the audio" needs.
 */
object CueTiming {
    fun activeCues(
        cues: List<SubtitleCue>,
        positionSeconds: Double,
        delaySeconds: Double,
    ): List<SubtitleCue> {
        val at = positionSeconds - delaySeconds
        return cues.filter { at >= it.startSeconds && at < it.endSeconds }
    }
}
