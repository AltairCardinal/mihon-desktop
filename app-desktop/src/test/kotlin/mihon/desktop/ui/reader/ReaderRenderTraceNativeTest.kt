package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.diagnostics.ReaderRenderTracing
import mihon.desktop.platform.setWindowsWindowCloaked
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Real ReaderViewport, real image decoder, real window and real fullscreen adapter. No UI automation. */
@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReaderRenderTraceNativeTest {
    @TempDir
    lateinit var output: Path

    @Test
    fun `normal reader diagnostics capture PNG frames and image geometry across fullscreen and automatic layout`() {
        for (mode in listOf(ReadingMode.LTR, ReadingMode.AUTO)) capture(mode)
    }

    @Test
    fun `frame budget exhaustion is explicitly partial while the real reader still works`() {
        val key = "mihon.reader.renderTraceMaxFrames"
        val previous = System.getProperty(key)
        System.setProperty(key, "1")
        try {
            capture(ReadingMode.LTR, partial = true)
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    private fun capture(mode: ReadingMode, partial: Boolean = false) {
        val root = System.getenv("MIHON_READER_TRACE_TEST_OUTPUT")?.let(Path::of) ?: output
        val directory = root.resolve(mode.name + if (partial) "-bounded" else "")
        val previous = System.getProperty("mihon.reader.renderTraceDir")
        ReaderRenderTracing.configure(arrayOf("--reader-render-trace=$directory"))
        val preferences = Preferences.userRoot().node("/mihon/render-trace-test/${System.nanoTime()}")
        val store = DesktopPreferenceStore(preferences.node("store"))
        val appPreferences = DesktopAppPreferences(store, preferences.node("app"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = desktopReaderSessionState(pageCount = 3, initialPage = 1)
        val model = ReaderScreenModel(
            prefs = ReaderPreferences(store, preferences.node("reader")).apply {
                readingMode = mode
                isAutoSpreadMatching = false
            },
            initialSessionState = session,
        )
        val pages = session.snapshot.activeChapter.pages.associate { page ->
            val image = BufferedImage(160, 240, BufferedImage.TYPE_INT_RGB)
            val color = when (page.id.sourcePageIndex) {
                1 -> java.awt.Color.RED.rgb
                2 -> java.awt.Color.GREEN.rgb
                else -> java.awt.Color.BLUE.rgb
            }
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, color)
            requireNotNull(page.encodedPageRef) to ByteArrayOutputStream().also {
                assertTrue(ImageIO.write(image, "png", it))
            }.toByteArray()
        }
        val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock(System::nanoTime))
        val content = DesktopReaderPageContentOwner(scope, { ref -> pages.getValue(ref) }, reporter)
        val pipeline = DesktopReaderPageImagePipeline(scope, content, reporter)
        val owner = DesktopReaderPresentationImageOwner(scope, pipeline, ReaderPageIoObserver(reporter))
        owner.beginGeneration(session.snapshot.generation)
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.appPreferences } returns appPreferences
        }
        val window = onEdt {
            ComposeWindow().apply {
                setBounds(40, 40, 360, 640)
                focusableWindowState = false
                isAutoRequestFocus = false
                setContent {
                    MaterialTheme {
                        CompositionLocalProvider(
                            LocalDesktopUiDependencies provides dependencies,
                            LocalReaderNativeWindow provides this@apply,
                        ) {
                            val state by model.state.collectAsState()
                            ReaderViewport(
                                state, model, mockk<Navigator>(relaxed = true), FocusRequester(),
                                scope, "diagnostic fixture", "chapter", owner, null, {}, {},
                            )
                        }
                    }
                }
                addNotify()
                assertTrue(setWindowsWindowCloaked(this, true))
                isVisible = true
            }
        }
        try {
            val readyDeadline = System.nanoTime() + 8_000_000_000L
            while (owner.firstPresentedGeneration.value != session.snapshot.generation &&
                System.nanoTime() < readyDeadline
            ) {
                onEdt { window.renderImmediately() }
                Thread.sleep(25)
            }
            assertEquals(
                session.snapshot.generation,
                owner.firstPresentedGeneration.value,
                "Real comic must draw first",
            )
            onEdt { model.setFullscreen(true) }
            repeat(35) {
                Thread.sleep(35)
                onEdt { window.renderImmediately() }
            }
            onEdt { model.setFullscreen(false) }
            repeat(10) {
                Thread.sleep(35)
                onEdt { window.renderImmediately() }
            }
            assertFalse(window.isActive)
            onEdt { window.dispose() }
            ReaderRenderTracing.closeAll()
            val files = if (Files.exists(directory)) Files.walk(directory, 3).use { it.toList() } else emptyList()
            val frames = files.filter { it.fileName.toString().endsWith(".png") }
            assertTrue(
                frames.size >= if (partial) 1 else 3,
                "Actual reader fullscreen transitions must save PNG frames",
            )
            assertTrue(frames.any { frame -> ImageIO.read(frame.toFile()).let { it.width > 400 && it.height > 400 } })
            if (!partial) {
                assertTrue(
                    frames.any { frame ->
                        val image = ImageIO.read(frame.toFile())
                        (0 until image.width).any { x -> image.getRGB(x, image.height / 2) == java.awt.Color.RED.rgb }
                    },
                    "Captured frames must contain the actual decoded comic",
                )
            }
            val events = files.filter { it.fileName.toString() == "events.jsonl" }.flatMap {
                Files.readAllLines(it).map { line -> Json.parseToJsonElement(line).jsonObject }
            }
            val kinds = events.map { it.getValue("type").jsonPrimitive.content }.toSet()
            assertTrue("image-draw" in kinds, "Single and automatic layouts both need actual image draw records")
            assertTrue("fullscreen-request" in kinds)
            assertTrue("frame-written" in kinds)
            assertTrue(events.any { it["captureStage"]?.jsonPrimitive?.content == "completed-layer-picture" })
            val captured = events.filter { it.getValue("type").jsonPrimitive.content == "frame-captured" }
                .associateBy { it.getValue("renderId").jsonPrimitive.content }
            events.filter { it.getValue("type").jsonPrimitive.content == "frame-written" }.forEach { record ->
                val frame = captured.getValue(record.getValue("renderId").jsonPrimitive.content)
                assertEquals(frame["canvasWidth"], frame["pngWidth"], "Captured width must belong to its render ID")
                assertEquals(frame["canvasHeight"], frame["pngHeight"], "Captured height must belong to its render ID")
                assertTrue(
                    frame.containsKey("renderStartedNanos"),
                    "Frame must carry render start, not PNG completion time",
                )
                assertTrue(
                    frame.getValue("renderStartedNanos").jsonPrimitive.content.toLong() <=
                        frame.getValue("captureStartedNanos").jsonPrimitive.content.toLong(),
                )
            }
            val manifests = files.filter { it.fileName.toString() == "manifest.json" }.map {
                Json.parseToJsonElement(Files.readString(it)).jsonObject
            }
            assertTrue(manifests.isNotEmpty())
            manifests.forEach { manifest ->
                if (partial) {
                    assertEquals("PARTIAL", manifest.getValue("status").jsonPrimitive.content)
                    assertTrue(manifest.getValue("missing").jsonPrimitive.content.toInt() > 0)
                } else {
                    assertEquals("0", manifest.getValue("missing").jsonPrimitive.content)
                    assertEquals(
                        manifest["requested"],
                        manifest["written"],
                        "No requested frame may silently disappear",
                    )
                }
                assertEquals("false", manifest.getValue("presentationObserved").jsonPrimitive.content)
            }
        } finally {
            onEdt { if (window.isDisplayable) window.dispose() }
            ReaderRenderTracing.closeAll()
            owner.close()
            pipeline.close()
            content.close()
            model.onDispose()
            scope.cancel()
            preferences.removeNode()
            if (previous == null) {
                System.clearProperty("mihon.reader.renderTraceDir")
            } else {
                System.setProperty("mihon.reader.renderTraceDir", previous)
            }
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
