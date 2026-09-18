package mihon.desktop.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.presentation.DesktopReaderPresentationRegistry
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.desktopReaderPresentationRequest
import mihon.domain.reader.ReaderDirection
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.core.common.preference.DesktopPreferenceStore
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderPageTurnPresentationTest {
    @ParameterizedTest
    @CsvSource("false", "true")
    fun `settled feedback resumes after a blocked page becomes reportable without moving`(dual: Boolean) = runTest {
        val mode = if (dual) ReaderPresentationMode.DUAL_PAGED else ReaderPresentationMode.SINGLE_PAGED
        val presentation = DesktopReaderPresentationRegistry.require(mode).present(
            desktopReaderPresentationRequest(chapter = readerChapterSession(pageCount = 7), direction = ReaderDirection.LTR),
        )
        var reportable by mutableStateOf(false)
        var reports = 0
        val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                if (dual) {
                    DualPageSettledVisiblePageReporter(presentation, false, { 1 }, { reportable }) { reports++ }
                } else {
                    SinglePageSettledVisiblePageReporter(presentation, false, { 1 }, { reportable }) { reports++ }
                }
            }
            repeat(6) { scene.render().close(); runCurrent() }
            assertEquals(0, reports)
            reportable = true
            repeat(6) { scene.render().close(); runCurrent() }
            assertEquals(1, reports, "Releasing the pending-target gate must publish the already settled page")
        } finally {
            scene.close()
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `external page requests animate just like taps`(dual: Boolean, rtl: Boolean) = runTest {
        Fixture(this, dual, rtl).use { fixture ->
            repeat(40) { fixture.frame(); runCurrent() }
            fixture.page = if (dual) 3 else 1
            var sawTransition = false
            repeat(40) {
                val colors = fixture.frame()
                sawTransition = sawTransition || (RED in colors && GREEN in colors)
                runCurrent()
            }
            assertTrue(sawTransition, "An animated turn must draw both departing and arriving pages in a frame")
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `adjacent pages are decoded before an instant turn`(dual: Boolean, rtl: Boolean) = runTest {
        Fixture(this, dual, rtl).use { fixture ->
            repeat(40) { fixture.frame(); runCurrent() }
            val nextPage = if (dual) 3 else 1
            assertTrue(nextPage in fixture.decoded, "Next viewport must already hold a decoded image")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,request", "false,true,request", "true,false,request", "true,true,request",
        "false,false,tap", "false,true,tap", "true,false,tap", "true,true,tap",
        "false,false,key", "false,true,key", "true,false,key", "true,true,key",
        "false,false,wheel", "false,true,wheel", "true,false,wheel", "true,true,wheel",
    )
    fun `live animation preference controls taps and external requests without blank instant frames`(
        dual: Boolean,
        rtl: Boolean,
        input: String,
    ) = runTest {
        Fixture(this, dual, rtl).use { fixture ->
            repeat(40) { fixture.frame(); runCurrent() }
            for (animated in listOf(false, true, false)) {
                fixture.preferences.pageTurnAnimation.set(animated)
                repeat(6) { fixture.frame(); runCurrent() }
                if (!animated && !dual && !rtl && input == "request") {
                    fixture.writeFrame("reader-page-turn-before.png")
                }
                val forward = fixture.page < (if (dual) 3 else 1)
                val target = if (forward) (if (dual) 3 else 1) else (if (dual) 1 else 0)
                when (input) {
                    "tap" -> fixture.tap(forward, rtl)
                    "key" -> fixture.key(forward, rtl)
                    "wheel" -> fixture.wheel(forward)
                    else -> fixture.page = target
                }
                var sawTransition = false
                repeat(40) {
                    val colors = fixture.frame()
                    sawTransition = sawTransition || (RED in colors && GREEN in colors)
                    if (!animated) assertTrue(colors.all { it == RED || it == GREEN }, "Instant frame exposed background: $colors")
                    runCurrent()
                }
                if (!animated && !dual && !rtl && input == "request") {
                    fixture.writeFrame("reader-page-turn-after.png")
                }
                assertEquals(animated, sawTransition, "Animation preference must control the mounted production reader")
                assertEquals(target, fixture.page)
                assertEquals(setOf(if (forward) GREEN else RED), fixture.frame())
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `latest rapid page request wins over settled page feedback`(dual: Boolean, rtl: Boolean) = runTest {
        Fixture(this, dual, rtl).use { fixture ->
            repeat(40) { fixture.frame(); runCurrent() }
            fixture.page = if (dual) 3 else 1
            repeat(3) { fixture.frame(); runCurrent() }
            fixture.page = if (dual) 5 else 3
            repeat(80) { fixture.frame(); runCurrent() }

            assertEquals(if (dual) 5 else 3, fixture.page)
            assertEquals(setOf(GREEN), fixture.frame())
        }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `user turn during external animation clears stale programmatic feedback`(rtl: Boolean) = runTest {
        Fixture(this, dual = false, rtl).use { fixture ->
            repeat(40) { fixture.frame(); runCurrent() }
            fixture.page = 1
            repeat(40) { fixture.frame(); runCurrent() }
            fixture.page = 3
            repeat(3) { fixture.frame(); runCurrent() }
            fixture.tap(forward = false, rtl = rtl)
            repeat(80) { fixture.frame(); runCurrent() }

            assertEquals(0, fixture.page)
            assertEquals(setOf(RED), fixture.frame())
        }
    }

    private class Fixture(val scope: TestScope, val dual: Boolean, rtl: Boolean) : AutoCloseable {
        private val root = Preferences.userRoot().node("/mihon/page-turn-test/${System.nanoTime()}")
        private val store = DesktopPreferenceStore(root.node("store"))
        val preferences = DesktopAppPreferences(store, root.node("app"))
        private val readerPreferences = ReaderPreferences(store, root.node("reader")).apply {
            readingMode = if (rtl) ReadingMode.RTL else ReadingMode.LTR
            autoSplitPages = false
            isAutoSpreadMatching = false
        }
        private val model = ReaderScreenModel(
            prefs = readerPreferences,
            dualPageOverride = dual,
            initialSessionState = desktopReaderSessionState(pageCount = 7, initialPage = if (dual) 1 else 0),
        )
        var page: Int
            get() = model.state.value.currentPage
            set(value) { model.goToPage(value) }
        val decoded = CopyOnWriteArraySet<Int>()
        private val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        private val content = DesktopReaderPageContentOwner(scope, { byteArrayOf(1) }, reporter)
        private val pipeline = DesktopReaderPageImagePipeline(
            scope, content, reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decoded += key.pageIndex
                val bitmap = Bitmap().apply { allocN32Pixels(32, 32) }
                Canvas(bitmap).use { canvas -> canvas.clear(if (key.pageIndex < (if (dual) 3 else 1)) RED else GREEN) }
                DesktopReaderImageAsset(
                    bitmap.asComposeImageBitmap(), 32, 32, 4096L, false, disposer = bitmap::close,
                )
            },
        )
        private val owner = DesktopReaderPresentationImageOwner(scope, pipeline, ReaderPageIoObserver(reporter))
        private val scene = ImageComposeScene(400, 400, coroutineContext = scope.coroutineContext) {}
        private var time = 0L
        init {
            owner.beginGeneration(1L)
            val dependencies = mockk<DesktopUiDependencies> { every { appPreferences } returns preferences }
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    val state by model.state.collectAsState()
                    ReaderContent(
                        state = state, model = model, contextMenuScope = scope,
                        mangaTitle = "", chapterTitle = "", presentationImageOwner = owner,
                        readerNav = null, onPrevChapter = {}, onNextChapter = {},
                    )
                }
            }
        }
        fun tap(forward: Boolean, rtl: Boolean) {
            val position = Offset(if (forward != rtl) 380f else 20f, 200f)
            scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        }
        fun wheel(forward: Boolean) {
            val event = java.awt.event.MouseWheelEvent(
                java.awt.Canvas(), java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 200, 200, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, if (forward) 1 else -1,
            )
            assertTrue(handleReaderWheelEvent(event, model.state.value, model, {}, {}))
        }
        fun key(forward: Boolean, rtl: Boolean) {
            val key = if (forward != rtl) androidx.compose.ui.input.key.Key.DirectionRight else androidx.compose.ui.input.key.Key.DirectionLeft
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val type = Class.forName("androidx.compose.ui.input.key.KeyEventType").getMethod("access\$getKeyDown\$cp").invoke(null)
            val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
            val native = factory.invoke(null, key.keyCode, type, 0, false, false, false, false, null)
            assertTrue(handleReaderKeyEvent(androidx.compose.ui.input.key.KeyEvent(native), model.state.value, model, mockk(), null, {}, {}))
        }
        fun frame(): Set<Int> {
            time += 16_000_000L
            val bitmap = renderBitmap()
            return try {
                (10 until 400 step 10).map { bitmap.getColor(it, 200) }.toSet()
            } finally {
                bitmap.close()
            }
        }
        fun writeFrame(path: String) {
            val bitmap = renderBitmap()
            try {
                val data = SkiaImage.makeFromBitmap(bitmap).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)
                } ?: error("Failed to encode reader frame")
                val outputDirectory = System.getenv("MIHON_READER_SAMPLE_DIR")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?: File(".gradle-coordinator")
                outputDirectory.resolve(path).apply { parentFile?.mkdirs() }.writeBytes(data.bytes)
            } finally {
                bitmap.close()
            }
        }
        private fun renderBitmap(): Bitmap {
            time += 16_000_000L
            return scene.render(time).toComposeImageBitmap().asSkiaBitmap()
        }
        override fun close() {
            scene.close()
            owner.close()
            pipeline.close()
            content.close()
            model.onDispose()
            root.removeNode()
        }
    }
    private companion object {
        const val RED = -65536
        const val GREEN = -16711936
    }
}
