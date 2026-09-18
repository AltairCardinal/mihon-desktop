package eu.kanade.tachiyomi.ui.reader

import android.os.Looper
import android.widget.FrameLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidAdaptiveReaderViewportTest {
    @Test
    fun `old global zero migrates to automatic while manga default remains inheritance`() {
        val store = AndroidPreferenceStore(RuntimeEnvironment.getApplication())
        store.getInt("pref_default_reading_mode_key", -1).set(0)
        assertEquals(7, ReaderPreferences(store).defaultReadingMode().get())
        assertEquals(7, ReaderPreferences(store).defaultReadingMode().get())
        assertEquals(0, ReadingMode.DEFAULT.flagValue)
    }

    @Test
    fun `stable resize target does not debounce forever and hysteresis ignores overlay`() {
        val view = FrameLayout(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 900, 1000)
        val changes = mutableListOf<Boolean>()
        AdaptiveReaderViewport(view, onLayoutChanged = changes::add).use { controller ->
            assertFalse(controller.configure(true))
            view.layout(0, 0, 1350, 1000)
            idle(100)
            view.layout(0, 0, 1500, 1000)
            idle(49)
            assertTrue(changes.isEmpty())
            idle(1)
            assertEquals(listOf(true), changes)
            view.layout(0, 0, 1300, 1000)
            view.addView(FrameLayout(view.context))
            idle(200)
            assertTrue(controller.dualPage)
            view.layout(0, 0, 1250, 1000)
            idle(150)
            assertEquals(listOf(true, false), changes)
        }
    }

    @Test
    fun `invalid size manual selection and disposal cancel pending automatic changes`() {
        val view = FrameLayout(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 900, 1000)
        val changes = mutableListOf<Boolean>()
        val controller = AdaptiveReaderViewport(view, onLayoutChanged = changes::add)
        controller.configure(true)
        view.layout(0, 0, 1400, 1000)
        view.layout(0, 0, 0, 1000)
        idle(200)
        assertTrue(changes.isEmpty())
        view.layout(0, 0, 1400, 1000)
        controller.configure(false)
        idle(200)
        assertTrue(changes.isEmpty())
        view.layout(0, 0, 900, 1000)
        controller.configure(true)
        view.layout(0, 0, 1400, 1000)
        controller.close()
        idle(200)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `restored automatic layout retains hysteresis and saved manual preference remains explicit`() {
        val view = FrameLayout(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1300, 1000)
        AdaptiveReaderViewport(view, restoredDualPage = true) {}.use { assertTrue(it.configure(true)) }
        val store = AndroidPreferenceStore(RuntimeEnvironment.getApplication())
        val preferences = ReaderPreferences(store)
        assertEquals(ReadingMode.AUTO.flagValue, preferences.defaultReadingMode().get())
        preferences.defaultReadingMode().set(ReadingMode.RIGHT_TO_LEFT.flagValue)
        assertEquals(ReadingMode.RIGHT_TO_LEFT.flagValue, ReaderPreferences(store).defaultReadingMode().get())
        assertTrue(ReadingMode.isPagerType(ReadingMode.DEFAULT.flagValue))
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
}
