package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun forProfile_unknownProfileGetsDefaults() {
        assertEquals(AppSettings.Defaults, PersistedSettings.empty().forProfile("nobody"))
        assertFalse(PersistedSettings.empty().forProfile("nobody").alwaysTranscode)
    }

    @Test
    fun withSettings_isPerProfile() {
        val settings = PersistedSettings.empty()
            .withSettings("srv|alice", AppSettings.Defaults.withAlwaysTranscode(true))

        assertTrue(settings.forProfile("srv|alice").alwaysTranscode)
        // A second account on the same install keeps its own preferences
        assertFalse(settings.forProfile("srv|bob").alwaysTranscode)
    }

    @Test
    fun withoutProfile_dropsOnlyThatProfile() {
        val settings = PersistedSettings.empty()
            .withSettings("srv|alice", AppSettings.Defaults.withAlwaysTranscode(true))
            .withSettings("srv|bob", AppSettings.Defaults.withAlwaysTranscode(true))
            .withoutProfile("srv|alice")

        assertFalse(settings.forProfile("srv|alice").alwaysTranscode)
        assertTrue(settings.forProfile("srv|bob").alwaysTranscode)
    }

    @Test
    fun json_roundTrips() {
        val original = PersistedSettings.empty()
            .withSettings("srv|alice", AppSettings.Defaults.withAlwaysTranscode(true))

        assertEquals(original, PersistedSettings.fromJson(original.toJson()))
    }

    @Test
    fun fromJson_toleratesFieldsWrittenByANewerBuild() {
        val fromTheFuture =
            """{"byProfile":{"srv|alice":{"alwaysTranscode":true,"subtitleSize":1.5}},"nextThing":42}"""

        val decoded = PersistedSettings.fromJson(fromTheFuture)

        assertTrue(decoded!!.forProfile("srv|alice").alwaysTranscode)
    }

    @Test
    fun fromJson_returnsNullOnGarbageRatherThanThrowing() {
        // Callers fall back to defaults: bad settings must never block launch
        assertNull(PersistedSettings.fromJson("not json at all"))
    }

    private fun view(id: String, name: String, type: String?) =
        BaseItem(id = id, name = name, type = "CollectionFolder", collectionType = type)

    @Test
    fun videoLibrariesShowByDefault() {
        assertTrue(AppSettings.Defaults.showsLibrary(view("1", "Movies", "movies")))
        assertTrue(AppSettings.Defaults.showsLibrary(view("2", "Shows", "tvshows")))
        // Music *videos* are films to this app, unlike the audio library
        assertTrue(AppSettings.Defaults.showsLibrary(view("3", "Clips", "musicvideos")))
        assertTrue(AppSettings.Defaults.showsLibrary(view("4", "Home movies", "homevideos")))
    }

    @Test
    fun audioAndStillsAreHiddenByDefault() {
        // All four exist on demo.jellyfin.org and cannot be played here
        assertFalse(AppSettings.Defaults.showsLibrary(view("5", "Music", "music")))
        assertFalse(AppSettings.Defaults.showsLibrary(view("6", "Playlists", "playlists")))
        assertFalse(AppSettings.Defaults.showsLibrary(view("7", "Photos", "photos")))
        assertFalse(AppSettings.Defaults.showsLibrary(view("8", "Books", "books")))
    }

    @Test
    fun collectionTypeIsMatchedCaseInsensitively() {
        assertFalse(AppSettings.Defaults.showsLibrary(view("9", "Music", "Music")))
    }

    @Test
    fun unknownAndMixedLibrariesShowRatherThanVanish() {
        // A mixed folder sends no collection type, and new Jellyfin
        // versions invent types — showing one too many is recoverable,
        // hiding a library nobody can find again is not
        assertTrue(AppSettings.Defaults.showsLibrary(view("10", "Mixed", null)))
        assertTrue(AppSettings.Defaults.showsLibrary(view("11", "Something new", "holograms")))
    }

    @Test
    fun theUserOverridesEitherDefault() {
        val music = view("5", "Music", "music")
        val movies = view("1", "Movies", "movies")
        val settings = AppSettings.Defaults
            .withLibraryShown(music, true)
            .withLibraryShown(movies, false)

        assertTrue(settings.showsLibrary(music))
        assertFalse(settings.showsLibrary(movies))
    }

    @Test
    fun visibleLibrariesKeepsServerOrder() {
        val views = listOf(
            view("1", "Movies", "movies"),
            view("5", "Music", "music"),
            view("6", "Playlists", "playlists"),
            view("2", "Shows", "tvshows"),
        )

        assertEquals(listOf("Movies", "Shows"), AppSettings.Defaults.visibleLibraries(views).map { it.name })
    }

    @Test
    fun libraryChoicesSurviveTheJsonRoundTrip() {
        val settings = AppSettings.Defaults.withLibraryShown(view("5", "Music", "music"), true)
        val blob = PersistedSettings.empty().withSettings("srv|alice", settings).toJson()

        val decoded = PersistedSettings.fromJson(blob)!!.forProfile("srv|alice")

        assertTrue(decoded.showsLibrary(view("5", "Music", "music")))
    }

    @Test
    fun anOlderBlobWithoutLibrariesStillDecodes() {
        // Settings written before this feature existed
        val old = """{"byProfile":{"srv|alice":{"alwaysTranscode":true}}}"""

        val decoded = PersistedSettings.fromJson(old)!!.forProfile("srv|alice")

        assertTrue(decoded.alwaysTranscode)
        assertTrue(decoded.showsLibrary(view("1", "Movies", "movies")))
        assertFalse(decoded.showsLibrary(view("5", "Music", "music")))
    }

    @Test
    fun subtitleDefaultsAreSmartAndUnscaled() {
        assertEquals(SubtitleMode.SMART, AppSettings.Defaults.subtitleMode)
        assertNull(AppSettings.Defaults.subtitleLanguage)
        assertEquals(1.0, AppSettings.Defaults.subtitleScale)
    }

    @Test
    fun anUnknownModeCostsThatSettingAndNothingElse() {
        // Written by a future build. Decoding the blob must not fail:
        // that would take every other preference down with it.
        val fromTheFuture = """{"byProfile":{"srv|alice":{"alwaysTranscode":true,"subtitleModeName":"TELEPATHY","subtitleScale":1.5}}}"""

        val decoded = PersistedSettings.fromJson(fromTheFuture)!!.forProfile("srv|alice")

        assertEquals(SubtitleMode.SMART, decoded.subtitleMode)
        assertTrue(decoded.alwaysTranscode)
        assertEquals(1.5, decoded.subtitleScale)
    }

    @Test
    fun subtitleScaleIsClampedBothWays() {
        // Invisible subtitles and screen-filling ones are both unrecoverable
        assertEquals(AppSettings.MIN_SUBTITLE_SCALE, AppSettings.Defaults.withSubtitleScale(0.0).subtitleScale)
        assertEquals(AppSettings.MAX_SUBTITLE_SCALE, AppSettings.Defaults.withSubtitleScale(99.0).subtitleScale)
        assertEquals(1.4, AppSettings.Defaults.withSubtitleScale(1.4).subtitleScale)
    }

    @Test
    fun aBlankSubtitleLanguageClearsThePreference() {
        assertEquals("fre", AppSettings.Defaults.withSubtitleLanguage(" fre ").subtitleLanguage)
        assertNull(AppSettings.Defaults.withSubtitleLanguage("  ").subtitleLanguage)
        assertNull(AppSettings.Defaults.withSubtitleLanguage(null).subtitleLanguage)
    }

    @Test
    fun subtitlePreferencesSurviveTheJsonRoundTrip() {
        val settings = AppSettings.Defaults
            .withSubtitleLanguage("fre")
            .withSubtitleMode(SubtitleMode.ALWAYS)
            .withSubtitleScale(1.25)
        val blob = PersistedSettings.empty().withSettings("srv|alice", settings).toJson()

        val decoded = PersistedSettings.fromJson(blob)!!.forProfile("srv|alice")

        assertEquals("fre", decoded.subtitleLanguage)
        assertEquals(SubtitleMode.ALWAYS, decoded.subtitleMode)
        assertEquals(1.25, decoded.subtitleScale)
    }

    @Test
    fun settingsPickTheTrackForTheProfile() {
        val settings = AppSettings.Defaults.withSubtitleLanguage("fre")
        val tracks = listOf(
            MediaStream(index = 1, type = "Subtitle", language = "eng"),
            MediaStream(index = 2, type = "Subtitle", language = "fra"),
        )

        assertEquals(2, settings.chooseSubtitle(tracks, audioLanguage = "eng")?.index)
        assertNull(settings.chooseSubtitle(tracks, audioLanguage = "fre"))
    }
}
