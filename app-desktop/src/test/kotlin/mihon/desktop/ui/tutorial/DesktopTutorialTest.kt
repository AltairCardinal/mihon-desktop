package mihon.desktop.ui.tutorial

import kotlinx.coroutines.CancellationException
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import java.util.prefs.Preferences

class DesktopTutorialTest {
    private val tutorial = DesktopTutorial.READER_ADJUST_SPREAD
    private val root = Preferences.userRoot().node("/mihon/tutorial-state/${System.nanoTime()}")
    private val store = DesktopPreferenceStore(root)

    @AfterEach
    fun cleanup() = root.removeNode()

    @Test
    fun `tutorial confirmation is local application state and retains future identifiers`() {
        val preference = DesktopAppPreferences(store).acknowledgedTutorials
        assertTrue(Preference.isAppState(preference.key()))
        preference.set(setOf("future-tutorial"))
        val state = DesktopTutorialState(preference)
        assertTrue(state.shouldShow(tutorial, true))
        assertFalse(state.shouldShow(tutorial, false))
        assertTrue(state.shouldShow(tutorial, true), "Temporary hiding must not consume a tutorial")
        assertTrue(state.acknowledge(tutorial))
        assertEquals(setOf("future-tutorial", tutorial.id), preference.get())
        val restored = DesktopTutorialState(DesktopAppPreferences(store).acknowledgedTutorials)
        assertFalse(restored.shouldShow(tutorial, true))
    }

    @Test
    fun `preference write failure dismisses only current session`() {
        val preference = store.getStringSet("tutorials", emptySet())
        val failing = object : Preference<Set<String>> by preference {
            override fun set(value: Set<String>) = throw IllegalStateException("write rejected")
        }
        val state = DesktopTutorialState(failing)
        assertFalse(state.acknowledge(tutorial))
        assertFalse(state.shouldShow(tutorial, true))
        assertTrue(DesktopTutorialState(preference).shouldShow(tutorial, true))
    }

    @Test
    fun `cancellation is not swallowed as a preference failure`() {
        val preference = store.getStringSet("tutorials", emptySet())
        val cancelled = object : Preference<Set<String>> by preference {
            override fun set(value: Set<String>) = throw CancellationException()
        }
        assertThrows(CancellationException::class.java) {
            DesktopTutorialState(cancelled).acknowledge(tutorial)
        }
    }
}
