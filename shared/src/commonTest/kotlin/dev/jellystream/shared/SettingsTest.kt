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
}
