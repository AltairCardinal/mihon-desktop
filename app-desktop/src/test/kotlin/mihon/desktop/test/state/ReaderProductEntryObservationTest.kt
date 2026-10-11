package mihon.desktop.test.state

import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.ui.reader.ReaderScreenModel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReaderProductEntryObservationTest {
    @Test
    fun `test mode observes a product reader without a synthetic open intent and closes on unmount`() {
        val observation = ReaderState()
        val model = model(7)
        val previousMode = applicationState.testMode
        applicationState.testMode = true
        try {
            observation.mount(model)
            assertTrue(observation.isOpen)
            assertSame(model.state.value, observation.mountedSnapshot())
            observation.unmount(model)
            assertFalse(observation.isOpen)
            assertNull(observation.mountedSnapshot())
            assertTrue(observation.productionClosed)
        } finally {
            applicationState.testMode = previousMode
            model.onDispose()
        }
    }

    @Test
    fun `disposing the previous product reader cannot clear its mounted successor`() {
        val observation = ReaderState()
        val previous = model(7)
        val successor = model(8)
        val previousMode = applicationState.testMode
        applicationState.testMode = true
        try {
            observation.mount(previous)
            observation.mount(successor)
            observation.unmount(previous)
            assertTrue(observation.isOpen)
            assertSame(successor.state.value, observation.mountedSnapshot())
            observation.unmount(successor)
            assertFalse(observation.isOpen)
        } finally {
            applicationState.testMode = previousMode
            previous.onDispose()
            successor.onDispose()
        }
    }

    @Test
    fun `explicit test intent still rejects a different chapter and reset closes the observation`() {
        val observation = ReaderState()
        val wrong = model(8)
        val expected = model(7)
        val previousMode = applicationState.testMode
        applicationState.testMode = true
        try {
            observation.open(7, 0, 20, false, "M", "C", false, false)
            observation.mount(wrong)
            assertNull(observation.mountedSnapshot())
            observation.mount(expected)
            assertSame(expected.state.value, observation.mountedSnapshot())
            observation.reset()
            assertFalse(observation.isOpen)
            assertNull(observation.mountedSnapshot())
            assertFalse(observation.productionClosed)
        } finally {
            applicationState.testMode = previousMode
            wrong.onDispose()
            expected.onDispose()
        }
    }

    @Test
    fun `normal application does not enable product reader observation`() {
        val observation = ReaderState()
        val model = model(7)
        val previousMode = applicationState.testMode
        applicationState.testMode = false
        try {
            observation.mount(model)
            assertFalse(observation.isOpen)
            assertNull(observation.mountedSnapshot())
        } finally {
            applicationState.testMode = previousMode
            model.onDispose()
        }
    }

    private fun model(chapterId: Long) = ReaderScreenModel(
        initialSessionState = desktopReaderSessionState(chapterId = chapterId, pageCount = 20),
        prefs = ReaderPreferences(InMemoryPreferenceStore()),
    )
}
