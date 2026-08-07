package dev.jellystream.shared

/**
 * What to do about subtitles before the user touches anything.
 *
 * [SMART] is the default and the reason this file exists: subtitles you
 * need are on, subtitles you don't are off, without a trip to the track
 * panel at the start of every title.
 */
enum class SubtitleMode {
    /** Never pick a track. The panel still works. */
    OFF,

    /** Only the forced track — the alien dialogue, not the whole film. */
    FORCED_ONLY,

    /**
     * Forced subtitles when you understand the audio, full subtitles when
     * you don't. The behaviour people know from Apple TV and Plex.
     */
    SMART,

    /** Always a full track in the preferred language when one exists. */
    ALWAYS;

    /** How the mode reads in a settings row, same words on every platform. */
    val label: String
        get() = when (this) {
            OFF -> "Off"
            FORCED_ONLY -> "Forced only"
            SMART -> "Smart"
            ALWAYS -> "Always on"
        }

    companion object {
        /** Cycles through the modes — one tap per step, no picker needed. */
        fun next(current: SubtitleMode): SubtitleMode =
            entries[(entries.indexOf(current) + 1) % entries.size]
    }
}

/**
 * Language codes as they actually arrive. Jellyfin passes through whatever
 * the container says, so the same language shows up as "fr", "fre" or
 * "fra" across two files in the same library — comparing the raw strings
 * silently breaks the smart default on half of them.
 */
object LanguageCode {
    // ISO 639-2/B vs 639-2/T: the two three-letter standards disagree on
    // exactly these languages, and muxers pick either one
    private val EQUIVALENTS = listOf(
        setOf("fr", "fre", "fra"),
        setOf("de", "ger", "deu"),
        setOf("nl", "dut", "nld"),
        setOf("el", "gre", "ell"),
        setOf("is", "ice", "isl"),
        setOf("hy", "arm", "hye"),
        setOf("ka", "geo", "kat"),
        setOf("mk", "mac", "mkd"),
        setOf("mi", "mao", "mri"),
        setOf("ms", "may", "msa"),
        setOf("my", "bur", "mya"),
        setOf("fa", "per", "fas"),
        setOf("ro", "rum", "ron"),
        setOf("sk", "slo", "slk"),
        setOf("sq", "alb", "sqi"),
        setOf("bo", "tib", "bod"),
        setOf("cs", "cze", "ces"),
        setOf("cy", "wel", "cym"),
        setOf("eu", "baq", "eus"),
        setOf("zh", "chi", "zho"),
        setOf("en", "eng"),
        setOf("es", "spa"),
        setOf("it", "ita"),
        setOf("pt", "por"),
        setOf("ja", "jpn"),
        setOf("ru", "rus"),
    )

    /**
     * Same language? Case and region tags are ignored ("pt-BR" matches
     * "por"), and an unknown or missing code never matches anything —
     * "und" is not a language, and guessing is worse than not selecting.
     */
    fun matches(a: String?, b: String?): Boolean {
        val left = normalize(a) ?: return false
        val right = normalize(b) ?: return false
        if (left == right) return true
        return EQUIVALENTS.any { left in it && right in it }
    }

    /** Lowercased, region stripped; null for blank and for "und"/"unknown". */
    fun normalize(code: String?): String? =
        code?.trim()
            ?.lowercase()
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" }
}

/** One entry of the subtitle-language picker. */
data class LanguageChoice(val code: String?, val label: String)

/**
 * The shortlist both platforms offer, in one place so the codes and the
 * order match. A free-text field would be worse on every screen this app
 * runs on — a TV remote least of all.
 *
 * Codes are ISO 639-2/B, the variant Jellyfin hands back most often;
 * [LanguageCode] absorbs the difference when a file disagrees.
 */
object SubtitleLanguages {
    /** null follows the device's own language — the right default. */
    val CHOICES: List<LanguageChoice> = listOf(
        LanguageChoice(null, "Device language"),
        LanguageChoice("eng", "English"),
        LanguageChoice("fre", "French"),
        LanguageChoice("spa", "Spanish"),
        LanguageChoice("ger", "German"),
        LanguageChoice("ita", "Italian"),
        LanguageChoice("por", "Portuguese"),
        LanguageChoice("dut", "Dutch"),
        LanguageChoice("jpn", "Japanese"),
        LanguageChoice("kor", "Korean"),
        LanguageChoice("chi", "Chinese"),
        LanguageChoice("rus", "Russian"),
        LanguageChoice("ara", "Arabic"),
    )

    fun labelFor(code: String?): String =
        CHOICES.firstOrNull { LanguageCode.matches(it.code, code) }?.label
            ?: code?.uppercase()
            ?: CHOICES.first().label
}

/**
 * Which subtitle track to switch on, decided once for every platform.
 *
 * Both players can already list and select tracks; what they could not do
 * is start a film with the right one already on.
 */
object SubtitleSelection {

    /**
     * The track to enable, or null for none.
     *
     * [audioLanguage] is the language of the audio track that will play —
     * the whole point of [SubtitleMode.SMART] is that the answer depends
     * on it.
     */
    fun choose(
        subtitles: List<MediaStream>,
        audioLanguage: String?,
        preferredLanguage: String?,
        mode: SubtitleMode,
    ): MediaStream? {
        val candidates = subtitles.filter { it.isSubtitle }
        if (candidates.isEmpty()) return null

        return when (mode) {
            SubtitleMode.OFF -> null
            SubtitleMode.FORCED_ONLY -> forced(candidates, preferredLanguage)
            SubtitleMode.ALWAYS -> full(candidates, preferredLanguage)
                ?: forced(candidates, preferredLanguage)
            SubtitleMode.SMART ->
                // Understanding the audio is the whole test: you only
                // need the forced track for the parts that are not in
                // that language
                if (LanguageCode.matches(audioLanguage, preferredLanguage)) {
                    forced(candidates, preferredLanguage)
                } else {
                    full(candidates, preferredLanguage) ?: forced(candidates, preferredLanguage)
                }
        }
    }

    /** A forced track, in the preferred language when there is a choice. */
    private fun forced(candidates: List<MediaStream>, preferred: String?): MediaStream? {
        val forcedTracks = candidates.filter { it.isForced }
        if (forcedTracks.isEmpty()) return null
        // Nothing to match against: a forced track is the author saying
        // this text is not optional, so honour it rather than drop it
        if (LanguageCode.normalize(preferred) == null) {
            return forcedTracks.firstOrNull { it.isDefault } ?: forcedTracks.first()
        }
        return forcedTracks.firstOrNull { LanguageCode.matches(it.language, preferred) }
        // A forced track with no language tag is still the forced track:
        // muxers leave it blank constantly
            ?: forcedTracks.firstOrNull { LanguageCode.normalize(it.language) == null }
    }

    /**
     * A full track to read the film by. Hearing-impaired tracks lose to
     * plain ones — they carry "[door creaks]" and most viewers have not
     * asked for that — but they are better than nothing.
     */
    private fun full(candidates: List<MediaStream>, preferred: String?): MediaStream? {
        val inLanguage = candidates.filter {
            !it.isForced && LanguageCode.matches(it.language, preferred)
        }
        return inLanguage.firstOrNull { !it.isHearingImpaired }
            ?: inLanguage.firstOrNull()
    }
}
