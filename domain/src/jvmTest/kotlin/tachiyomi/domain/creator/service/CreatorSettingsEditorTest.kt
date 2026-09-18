package tachiyomi.domain.creator.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.UUID
import java.util.prefs.Preferences

class CreatorSettingsEditorTest {
    @Test
    fun `draft cancel success and failure preserve the only stored authority`() = runTest {
        val node = Preferences.userRoot().node("/mihon-tests/ga03-editor-${UUID.randomUUID()}")
        try {
            val store = DesktopPreferenceStore(node)
            var fail = false
            val guarded = object : PreferenceStore by store {
                override fun getString(key: String, defaultValue: String): Preference<String> {
                    val delegate = store.getString(key, defaultValue)
                    return object : Preference<String> by delegate {
                        override fun set(value: String) {
                            if (fail) error("disk full") else delegate.set(value)
                        }
                    }
                }
            }
            val preferences = CreatorDiscoveryPreferences(guarded)
            var wakes = 0
            val editor = CreatorSettingsEditor(preferences, this) { wakes++ }
            editor.open()
            editor.select(CreatorCheckFrequency.MONTHLY)
            assertEquals(CreatorCheckFrequency.DAILY, preferences.current())
            editor.cancel()
            editor.open()
            assertEquals(CreatorCheckFrequency.DAILY, editor.state.value.draft)
            editor.select(CreatorCheckFrequency.WEEKLY)
            fail = true
            editor.save().join()
            assertTrue(editor.state.value.open)
            assertEquals(CreatorCheckFrequency.WEEKLY, editor.state.value.draft)
            assertNotNull(editor.state.value.error)
            assertEquals(0, wakes)
            fail = false
            editor.save().join()
            assertFalse(editor.state.value.open)
            assertEquals(CreatorCheckFrequency.WEEKLY, preferences.current())
            assertEquals(1, wakes)
            assertTrue(editor.state.value.focusRevision > 0)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `repeated save does not duplicate wake or dismiss an active save`() = runTest {
        val node = Preferences.userRoot().node("/mihon-tests/ga03-saving-${UUID.randomUUID()}")
        try {
            val release = CompletableDeferred<Unit>()
            var wakes = 0
            val editor = CreatorSettingsEditor(CreatorDiscoveryPreferences(DesktopPreferenceStore(node)), this) {
                wakes++
                release.await()
            }
            editor.open()
            val saving = editor.save()
            runCurrent()
            editor.save().join()
            editor.cancel()
            assertTrue(editor.state.value.open)
            assertEquals(1, wakes)
            release.complete(Unit)
            saving.join()
            assertFalse(editor.state.value.open)
        } finally {
            node.removeNode()
        }
    }
}
