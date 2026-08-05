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
) {
    // Kotlin default arguments and data-class copy() don't survive the
    // bridge to Swift, so every mutation gets a named helper here rather
    // than a copy() call site the Apple app can't spell.
    fun withAlwaysTranscode(value: Boolean): AppSettings = copy(alwaysTranscode = value)

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
