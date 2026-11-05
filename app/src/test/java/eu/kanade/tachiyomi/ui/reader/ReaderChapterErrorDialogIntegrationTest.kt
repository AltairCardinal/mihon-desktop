package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ReaderChapterErrorDialogIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `O9 overlay commit diagnostics follow enabled and remain silent when disabled`() =
        withOverlayDiagnostics { logs ->
            withReader { reader, model ->
                drainOverlayDiagnostics(logs, reader)
                assertTrue("Actual ROOT commit marker is missing", overlayCommits(logs, reader, "ROOT") > 0)
                assertTrue(
                    "Actual brightness wrapper commit marker is missing",
                    overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER") > 0,
                )
                val rootBefore = overlayCommits(logs, reader, "ROOT")
                val wrapperBeforeUnrelated = overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER")
                compose.runOnIdle { model.showMenus(true) }
                compose.waitForIdle()
                drainOverlayDiagnostics(logs, reader)
                assertTrue(overlayCommits(logs, reader, "ROOT") > rootBefore)
                assertEquals(
                    "Narrow brightness input keeps this wrapper idle for an unrelated real State event",
                    wrapperBeforeUnrelated,
                    overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER"),
                )
                val brightnessBefore = overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER")
                compose.runOnIdle { model.setBrightnessOverlayValue(-30) }
                compose.waitForIdle()
                drainOverlayDiagnostics(logs, reader)
                assertTrue(overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER") > brightnessBefore)
                compose.onNodeWithText(MR.strings.action_retry.getString(reader)).assertIsDisplayed()
                compose.onNodeWithText(MR.strings.action_close.getString(reader)).assertIsDisplayed()
                val rootEnabled = overlayCommits(logs, reader, "ROOT")
                val brightnessEnabled = overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER")
                ReaderOpenLatencyProbe.enabled = false
                compose.runOnIdle {
                    model.showMenus(false)
                    model.setBrightnessOverlayValue(-60)
                }
                compose.waitForIdle()
                drainOverlayDiagnostics(logs, reader)
                assertEquals(rootEnabled, overlayCommits(logs, reader, "ROOT"))
                assertEquals(brightnessEnabled, overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER"))
                compose.onNodeWithText(MR.strings.action_close.getString(reader)).performClick()
                compose.runOnIdle { assertTrue(reader.isFinishing) }
            }
        }

    // Stage two runs only after the actual body markers have their own genuine RED/GREEN.
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O9 unrelated state keeps brightness wrapper idle while actual brightness still draws`() =
        withOverlayDiagnostics { logs ->
            withReader { reader, model ->
                drainOverlayDiagnostics(logs, reader)
                assertTrue(overlayCommits(logs, reader, "ROOT") > 0)
                assertTrue(overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER") > 0)
                val rootBefore = overlayCommits(logs, reader, "ROOT")
                val brightnessBefore = overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER")
                compose.runOnIdle { model.showMenus(true) }
                compose.waitForIdle()
                drainOverlayDiagnostics(logs, reader)
                assertTrue(overlayCommits(logs, reader, "ROOT") > rootBefore)
                assertEquals(brightnessBefore, overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER"))
                val beforePixel = overlayCenterPixel(reader)
                compose.runOnIdle { model.setBrightnessOverlayValue(-50) }
                compose.waitForIdle()
                drainOverlayDiagnostics(logs, reader)
                assertTrue(overlayCommits(logs, reader, "BRIGHTNESS_WRAPPER") > brightnessBefore)
                org.junit.Assert.assertNotEquals(beforePixel, overlayCenterPixel(reader))
                compose.onNodeWithText(MR.strings.action_retry.getString(reader)).assertIsDisplayed()
                compose.onNodeWithText(MR.strings.action_close.getString(reader)).assertIsDisplayed()
            }
        }

    private fun overlayCenterPixel(reader: ReaderActivity): Int {
        var pixel = 0
        compose.runOnIdle {
            val view = reader.binding.composeOverlay
            assertTrue("The real overlay must be laid out before pixel observation", view.width > 0 && view.height > 0)
            val image = android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            try {
                view.draw(android.graphics.Canvas(image))
                pixel = image.getPixel(view.width / 2, view.height / 3)
            } finally {
                image.recycle()
            }
        }
        return pixel
    }

    private fun overlayCommits(logs: java.io.File, reader: ReaderActivity, context: String): Int =
        overlayLogLines(logs).count {
            "event=overlay_composed " in it && "context=$context" in it &&
                "session=${reader.diagnosticSession} " in it
        }

    private fun overlayLogLines(logs: java.io.File): List<String> =
        logs.listFiles().orEmpty().flatMap { it.readLines(Charsets.UTF_8) }

    private fun drainOverlayDiagnostics(logs: java.io.File, reader: ReaderActivity) {
        val serial = System.nanoTime()
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.record(
            "o9_test_drain",
            fields = mapOf("serial" to serial),
            session = reader.diagnosticSession,
        )
        compose.waitUntil(timeoutMillis = 5_000) {
            overlayLogLines(logs).any { "event=o9_test_drain " in it && "serial=$serial" in it }
        }
    }

    private fun withOverlayDiagnostics(block: (java.io.File) -> Unit) {
        val logs = java.nio.file.Files.createTempDirectory("reader-o9-overlay").toFile()
        val previousSink = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink
        val sink = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnosticSink(directory = { logs })
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = sink
        ReaderOpenLatencyProbe.configure(true, 1, 1, "o9-overlay", 4)
        try {
            block(logs)
        } finally {
            ReaderOpenLatencyProbe.configure(false, -1, -1, "off")
            eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = previousSink
            sink.close()
            logs.deleteRecursively()
        }
    }

    @Test
    fun `initial chapter error has a visible close action which finishes the reader`() = withReader { reader, _ ->
        compose.onNodeWithText(MR.strings.action_close.getString(reader)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(reader.isFinishing) }
    }

    @Test
    fun `system back from initial chapter error finishes the reader`() = withReader { reader, _ ->
        pressDialogBack()
        compose.runOnIdle { assertTrue(reader.isFinishing) }
    }

    @Test
    fun `closing a later chapter error keeps the previously loaded chapter`() =
        withReader(loaded = true) { reader, model ->
            val chapter = model.state.value.currentChapter
            compose.onNodeWithText(MR.strings.action_close.getString(reader)).performClick()
            compose.runOnIdle {
                assertFalse(reader.isFinishing)
                assertNull(model.state.value.dialog)
                assertSame(chapter, model.state.value.currentChapter)
            }
        }

    @Test
    fun `system back from a later chapter error returns to the previously loaded chapter`() =
        withReader(loaded = true) { reader, model ->
            val chapter = model.state.value.currentChapter
            pressDialogBack()
            compose.runOnIdle {
                assertFalse(reader.isFinishing)
                assertNull(model.state.value.dialog)
                assertSame(chapter, model.state.value.currentChapter)
            }
        }

    @Test
    fun `retry remains available and reloads the same chapter without finishing`() =
        withReader(loaded = true) { reader, model ->
            val chapterId = model.state.value.currentChapter!!.chapter.id
            compose.onNodeWithText(MR.strings.action_retry.getString(reader)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { model.state.value.dialog == null }
            compose.runOnIdle {
                assertFalse(reader.isFinishing)
                assertEquals(chapterId, model.state.value.currentChapter!!.chapter.id)
            }
        }

    private fun pressDialogBack() {
        compose.runOnIdle {
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun withReader(loaded: Boolean = false, block: (ReaderActivity, ReaderViewModel) -> Unit) = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val store = InMemoryPreferenceStore()
        val application = RuntimeEnvironment.getApplication()
        Injekt.addSingleton(ReaderPreferences(store))
        Injekt.addSingleton(UiPreferences(store))
        Injekt.addSingleton(BasePreferences(application, store))
        Injekt.addSingleton(SecurityPreferences(store))
        Injekt.addSingleton(application)
        Injekt.addSingleton<SourceManager>(mockk { every { isInitialized } returns MutableStateFlow(false) })
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = ReaderSyncResumeWiringTest.Fixture()
        val reader = Robolectric.buildActivity(ReaderActivity::class.java).get()
        ReflectionHelpers.setField(reader, "viewModel\$delegate", lazyOf(fixture.model))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            if (loaded) assertTrue(fixture.model.init(1, 1).getOrThrow())
            ReflectionHelpers.callInstanceMethod<Unit>(
                reader,
                "setInitialChapterError",
                ReflectionHelpers.ClassParameter.from(Throwable::class.java, IOException("Source not installed")),
            )
            reader.binding = ReaderActivityBinding.inflate(host.get().layoutInflater)
            // Mount the actual production overlay, including its dialog action wiring.
            ReflectionHelpers.callInstanceMethod<Unit>(
                reader,
                "setComposeOverlay",
                ReflectionHelpers.ClassParameter.from(ReaderActivityBinding::class.java, reader.binding),
            )
            host.get().setContentView(reader.binding.root)
            compose.waitForIdle()
            compose.onNodeWithText(MR.strings.action_retry.getString(reader)).assertIsDisplayed()
            block(reader, fixture.model)
        } finally {
            host.pause().stop().destroy()
            fixture.close()
            Dispatchers.resetMain()
            Injekt = previous
        }
    }
}
