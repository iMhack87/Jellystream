package dev.jellystream.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the user can tune, for ONE profile. Settings are deliberately
 * per profile and not per install: two accounts sharing a device rarely
 * want the same libraries, the same subtitle language or the same request
 * server.
 *
 * Every field carries a default, so an older blob missing a field still
 * decodes — settings must never be the reason an app fails to start.
 */
@Serializable
data class AppSettings(
    /**
     * Ask the server to transcode even when the file would Direct Play.
     * The escape hatch for a source the local decoder mishandles; off by
     * default, since Direct Play is the whole point of the app.
     */
    val alwaysTranscode: Boolean = false,

    /**
     * Per-library answers to "show this on the home screen", by view id.
     *
     * Only what the user actually decided is stored. A library that is
     * absent falls back to [LibraryVisibility.showsByDefault], so a
     * library added on the server later appears on its own instead of
     * staying invisible until someone finds this screen.
     */
    val libraryOverrides: Map<String, Boolean> = emptyMap(),
) {
    // Kotlin default arguments and data-class copy() don't survive the
    // bridge to Swift, so every mutation gets a named helper here rather
    // than a copy() call site the Apple app can't spell.
    fun withAlwaysTranscode(value: Boolean): AppSettings = copy(alwaysTranscode = value)

    /** Does this library belong on the home screen? */
    fun showsLibrary(view: BaseItem): Boolean =
        libraryOverrides[view.id] ?: LibraryVisibility.showsByDefault(view)

    fun withLibraryShown(view: BaseItem, shown: Boolean): AppSettings =
        copy(libraryOverrides = libraryOverrides + (view.id to shown))

    /** The libraries to build home rows from, in server order. */
    fun visibleLibraries(views: List<BaseItem>): List<BaseItem> =
        views.filter { showsLibrary(it) }

    companion object {
        val Defaults = AppSettings()

        /**
         * Swift-callable constructor. Kotlin default arguments don't cross
         * the bridge, so `AppSettings()` is unspellable from Swift and every
         * new field would otherwise break the Apple call sites.
         */
        fun defaults(): AppSettings = Defaults
    }
}

/**
 * Which of the server's libraries a video player has any business showing.
 *
 * Jellyfin happily serves music, photos, books and playlists next to the
 * films, and a home screen that lists all of them buries what the user
 * came for. These start hidden — one switch away in Settings, never gone.
 *
 * The rule is a short exclusion list rather than a list of allowed types
 * on purpose: an unknown or future collection type shows up, which is the
 * recoverable mistake. Hiding a library nobody can find again is not.
 */
object LibraryVisibility {
    /**
     * Collection types a video player has nothing to do with. Music
     * *videos* are not among them — those are films to this app; only
     * the audio-only library is.
     */
    private val NON_VIDEO = setOf("music", "books", "photos", "playlists", "livetv")

    fun showsByDefault(view: BaseItem): Boolean =
        view.collectionType?.lowercase() !in NON_VIDEO
}

/**
 * The whole settings file: one [AppSettings] per profile, keyed by
 * [PersistedSession.profileKey]. Platforms persist the JSON blob (plain
 * app-private storage — nothing here is a secret); the wire format lives
 * in shared, never in platform code.
 */
@Serializable
data class PersistedSettings(
    val byProfile: Map<String, AppSettings> = emptyMap(),
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    /** An unknown profile gets the defaults — never null, never a failure. */
    fun forProfile(profileKey: String): AppSettings =
        byProfile[profileKey] ?: AppSettings.Defaults

    fun withSettings(profileKey: String, settings: AppSettings): PersistedSettings =
        PersistedSettings(byProfile + (profileKey to settings))

    /** Drops a profile's settings — called when the account is signed out. */
    fun withoutProfile(profileKey: String): PersistedSettings =
        PersistedSettings(byProfile - profileKey)

    companion object {
        fun empty(): PersistedSettings = PersistedSettings()

        /**
         * Never throws: a corrupt or truncated blob yields null and the
         * caller falls back to defaults. Losing preferences is annoying;
         * refusing to launch is not acceptable.
         */
        fun fromJson(json: String): PersistedSettings? =
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
    }
}
