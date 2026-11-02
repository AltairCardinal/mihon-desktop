package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import cafe.adriel.voyager.navigator.Navigator
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import org.jetbrains.skiko.SkiaLayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.PaintEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    fun `fullscreen entry never renders the old comic canvas at the new native client origin`() {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }
            .distinctBy { it.defaultTransform.scaleY }
            .take(2)
            .forEachIndexed { index, configuration ->
                capture(ReadingMode.RTL, checkEntryLayout = true, configuration = configuration, screenIndex = index)
            }
    }

    @Test
    fun `diagnostics retain more than 180 real frames across repeated fullscreen transitions`() {
        capture(ReadingMode.RTL, entryCount = 3)
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

    @Test
    fun `diagnostics record AWT hardware canvas paint events in the actual reader`() {
        capture(ReadingMode.LTR, checkAwtPaint = true)
    }

    @Test
    fun `F11 captures the completed comic picture before changing reader fullscreen state`() {
        capture(ReadingMode.LTR, checkPreKeyBaseline = true)
    }

    @Test
    fun `real reader bitmap and title survive idle and metadata in window capture`() {
        capture(ReadingMode.LTR, checkCompositorPixels = true)
    }

    private fun capture(
        mode: ReadingMode,
        partial: Boolean = false,
        checkEntryLayout: Boolean = false,
        entryCount: Int = 1,
        configuration: GraphicsConfiguration? = null,
        screenIndex: Int = 0,
        checkAwtPaint: Boolean = false,
        checkPreKeyBaseline: Boolean = false,
        checkCompositorPixels: Boolean = false,
    ) {
        val root = System.getenv("MIHON_READER_TRACE_TEST_OUTPUT")?.let(Path::of) ?: output
        val suffix = when {
            checkCompositorPixels -> "-compositor-idle"
            partial -> "-bounded"
            entryCount > 1 -> "-repeated"
            checkEntryLayout -> "-entry-$screenIndex"
            checkAwtPaint -> "-awt-paint"
            checkPreKeyBaseline -> "-pre-key"
            else -> ""
        }
        val directory = root.resolve(mode.name + suffix)
        val previous = System.getProperty("mihon.reader.renderTraceDir")
        if (checkCompositorPixels) {
            System.clearProperty("mihon.reader.renderTraceDir")
        } else {
            ReaderRenderTracing.configure(arrayOf("--reader-render-trace=$directory"))
        }
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
        if (checkCompositorPixels) model.toggleUI()
        val pages = session.snapshot.activeChapter.pages.associate { page ->
            val image = if (checkEntryLayout) {
                BufferedImage(1960, 862, BufferedImage.TYPE_INT_RGB)
            } else {
                BufferedImage(160, 240, BufferedImage.TYPE_INT_RGB)
            }
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
        val keyHandler = slot<(KeyEvent) -> Boolean>()
        val toggleEnteredAt = AtomicLong()
        var keyCutoffNanos = 0L
        var keyCutoffSequence = 0L
        if (checkPreKeyBaseline) {
            // Capture the real ReaderViewport closure while preserving its focus modifier.
            // The cloaked window never receives operating-system input or focus.
            mockkStatic("mihon.desktop.ui.reader.DesktopReaderScreenKt")
            every { any<Modifier>().readerKeyboardFocus(any(), capture(keyHandler)) } answers { callOriginal() }
            mockkObject(model)
            every { model.toggleFullscreen() } answers {
                toggleEnteredAt.compareAndSet(0, System.nanoTime())
                callOriginal()
            }
        }
        val window = onEdt {
            ComposeWindow(configuration).apply {
                if (checkCompositorPixels) {
                    setBounds(-20000, -20000, 2575, 1407)
                } else if (checkEntryLayout && configuration != null) {
                    val bounds = configuration.bounds
                    setBounds(bounds.x, bounds.y, bounds.width, bounds.height - 48)
                } else {
                    setBounds(40, 40, 360, 640)
                }
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
                                scope, "diagnostic fixture",
                                if (checkCompositorPixels) "chapter diagnostic glyph" else "chapter",
                                owner, null, {}, {},
                            )
                        }
                    }
                }
                addNotify()
                if (!checkCompositorPixels) assertTrue(setWindowsWindowCloaked(this, true))
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
            if (checkCompositorPixels) {
                captureIdleCompositor(window, model, directory)
                return
            }
            if (checkPreKeyBaseline) {
                val endDeadline = System.nanoTime() + 8_000_000_000L
                while (readTraceEvents(directory).none { it["type"]?.jsonPrimitive?.content == "capture-window-end" } &&
                    System.nanoTime() < endDeadline
                ) {
                    Thread.sleep(25)
                }
                val captureEnded = readTraceEvents(directory)
                    .any { it["type"]?.jsonPrimitive?.content == "capture-window-end" }
                assertTrue(captureEnded)
                // Record a fresh completed Picture outside the initial capture window. A forced
                // pre-key baseline must not be satisfied by an already-captured startup render.
                onEdt { window.renderImmediately() }
                onEdt { }
                keyCutoffSequence = readTraceEvents(directory)
                    .maxOf { it.getValue("sequence").jsonPrimitive.content.toLong() }
            }
            repeat(entryCount) {
                onEdt {
                    if (checkPreKeyBaseline) {
                        assertTrue(keyHandler.isCaptured, "The real viewport must install its keyboard handler")
                        keyCutoffNanos = System.nanoTime()
                        assertTrue(keyHandler.captured(f11KeyEvent()))
                        assertTrue(model.state.value.isFullscreen)
                    } else {
                        model.setFullscreen(true)
                    }
                }
                repeat(35) { frame ->
                    Thread.sleep(35)
                    onEdt {
                        window.renderImmediately()
                        if (checkAwtPaint && frame == 2) {
                            fun layer(component: Component): SkiaLayer? = when (component) {
                                is SkiaLayer -> component
                                is Container -> component.components.firstNotNullOfOrNull(::layer)
                                else -> null
                            }
                            val canvas = requireNotNull(layer(window)).canvas
                            canvas.dispatchEvent(PaintEvent(canvas, PaintEvent.PAINT, Rectangle(0, 0, 16, 16)))
                        }
                    }
                }
                onEdt { model.setFullscreen(false) }
                repeat(10) {
                    Thread.sleep(35)
                    onEdt { window.renderImmediately() }
                }
            }
            assertFalse(window.isActive)
            onEdt {
                // The last render's completion callback can be queued behind this teardown.
                // Drain it while Skiko still owns the completed Picture; disposal releases that Picture.
                ReaderRenderTracing.closeAll()
                window.dispose()
            }
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
            if (checkPreKeyBaseline) {
                val afterKey = events.filter {
                    it.getValue("timeNanos").jsonPrimitive.content.toLong() >= keyCutoffNanos &&
                        it.getValue("sequence").jsonPrimitive.content.toLong() > keyCutoffSequence
                }
                val request = afterKey.first {
                    it["type"]?.jsonPrimitive?.content == "fullscreen-request" &&
                        it["fullscreen"]?.jsonPrimitive?.content == "true"
                }
                val requestSequence = request.getValue("sequence").jsonPrimitive.content.toLong()
                assertTrue(toggleEnteredAt.get() > keyCutoffNanos, "The actual model toggle must pass the observer")
                assertTrue(
                    request.getValue("timeNanos").jsonPrimitive.content.toLong() < toggleEnteredAt.get(),
                    "The real F11 trace request must precede the model toggle, not merely native placement",
                )
                val stateBeforeRequest = events.last {
                    it["type"]?.jsonPrimitive?.content == "reader-state" &&
                        it.getValue("sequence").jsonPrimitive.content.toLong() < requestSequence
                }
                assertEquals("false", stateBeforeRequest.getValue("fullscreen").jsonPrimitive.content)
                val baseline = afterKey.last {
                    it["type"]?.jsonPrimitive?.content == "frame-captured" &&
                        it.getValue("sequence").jsonPrimitive.content.toLong() < requestSequence
                }
                val capturedAt = baseline.getValue("captureStartedNanos").jsonPrimitive.content.toLong()
                val renderedAt = baseline.getValue("renderStartedNanos").jsonPrimitive.content.toLong()
                assertTrue(capturedAt < toggleEnteredAt.get())
                assertTrue(renderedAt < toggleEnteredAt.get())
                assertTrue(
                    comicCenterY(baseline, events, files).isFinite(),
                    "The pre-key PNG must contain real red comic pixels",
                )
                assertTrue(
                    afterKey.any {
                        it["type"]?.jsonPrimitive?.content == "reader-state" &&
                            it["fullscreen"]?.jsonPrimitive?.content == "true"
                    },
                )
                println(
                    "PRE_KEY_BASELINE directory=$directory cutoff=$keyCutoffNanos toggle=${toggleEnteredAt.get()} " +
                        "baseline=$baseline request=$request",
                )
            }
            if (checkAwtPaint) {
                val paints = events.filter { it["type"]?.jsonPrimitive?.content == "awt-canvas-paint" }
                assertTrue(paints.isNotEmpty(), "Real AWT canvas dispatch must reach the reader trace")
                assertTrue(paints.any { it["paintId"]?.jsonPrimitive?.content == PaintEvent.PAINT.toString() })
            }
            if (checkEntryLayout) {
                val request = events.first {
                    it["type"]?.jsonPrimitive?.content == "fullscreen-request" &&
                        it["fullscreen"]?.jsonPrimitive?.content == "true"
                }.getValue("timeNanos").jsonPrimitive.content.toLong()
                val exit = events.first {
                    it["type"]?.jsonPrimitive?.content == "fullscreen-request" &&
                        it["fullscreen"]?.jsonPrimitive?.content == "false" &&
                        it.getValue("timeNanos").jsonPrimitive.content.toLong() > request
                }.getValue("timeNanos").jsonPrimitive.content.toLong()
                val enteringFrames = events.filter {
                    it["type"]?.jsonPrimitive?.content == "render-begin" &&
                        it.getValue("timeNanos").jsonPrimitive.content.toLong() in request until exit
                }
                assertTrue(enteringFrames.isNotEmpty(), "Actual reader entry frames must be observed")
                enteringFrames.forEach { frame ->
                    val client = frame.getValue("window").jsonObject.getValue("nativeClient")
                        .jsonArray
                    assertEquals(
                        client[2].jsonPrimitive.content.toDouble(),
                        frame.getValue("canvasWidth").jsonPrimitive.content.toDouble(),
                        2.0,
                        "Render ${frame["renderId"]} must not draw the previous comic width " +
                            "after the native client moves",
                    )
                    assertEquals(
                        client[3].jsonPrimitive.content.toDouble(),
                        frame.getValue("canvasHeight").jsonPrimitive.content.toDouble(),
                        2.0,
                        "Render ${frame["renderId"]} must not draw the previous comic height " +
                            "after the native client moves",
                    )
                }
                val fullscreenClient = enteringFrames.last().getValue("window").jsonObject.getValue("nativeClient")
                val fullscreenFrames = enteringFrames.filter {
                    it.getValue("window").jsonObject.getValue("nativeClient") == fullscreenClient
                }
                assertEquals(
                    comicCenterY(fullscreenFrames.last(), events, files),
                    comicCenterY(fullscreenFrames.first(), events, files),
                    2.0,
                    "The first full-size PNG must place actual decoded comic pixels at their settled screen position",
                )
            }
            assertTrue("image-draw" in kinds, "Single and automatic layouts both need actual image draw records")
            assertTrue("fullscreen-request" in kinds)
            assertTrue("frame-written" in kinds)
            assertTrue(events.any { it["captureStage"]?.jsonPrimitive?.content == "completed-layer-picture" })
            val captured = events.filter { it.getValue("type").jsonPrimitive.content == "frame-captured" }
                .associateBy { it.getValue("renderId").jsonPrimitive.content }
            if (entryCount > 1) {
                assertTrue(
                    captured.size > 180,
                    "Repeated real fullscreen transitions must not exhaust the former frame limit",
                )
            }
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
            if (checkPreKeyBaseline) {
                unmockkObject(model)
                unmockkStatic("mihon.desktop.ui.reader.DesktopReaderScreenKt")
            }
            if (previous == null) {
                System.clearProperty("mihon.reader.renderTraceDir")
            } else {
                System.setProperty("mihon.reader.renderTraceDir", previous)
            }
        }
    }

    private fun captureIdleCompositor(window: ComposeWindow, model: ReaderScreenModel, directory: Path) {
        Files.createDirectories(directory)
        val executable = Path.of("artifacts/diagnostics/compositor-native/reader-compositor.exe").toAbsolutePath()
        assertTrue(Files.isRegularFile(executable), "The existing compositor helper must be built first")
        val hwnd = onEdt { Pointer.nativeValue(Native.getComponentPointer(window)) }
        val scale = onEdt { window.graphicsConfiguration.defaultTransform }
        val titleLeft = (60 * scale.scaleX).toInt()
        val titleRight = (460 * scale.scaleX).toInt()
        val titleTop = (28 * scale.scaleY).toInt()
        val titleBottom = (110 * scale.scaleY).toInt()
        val frames = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)
        val post = CountDownLatch(1)
        val metadataAt = AtomicLong(Long.MAX_VALUE)
        val firstTimestamp = AtomicLong()
        val failure = AtomicReference<Throwable>()
        // Match the existing compositor test's console-free Gradle-worker process topology.
        val process = ProcessBuilder(
            executable.toString(),
            "0x${hwnd.toString(16)}",
            ProcessHandle.current().pid().toString(),
            "16",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val reader = Thread {
            try {
                DataInputStream(process.inputStream).use { input ->
                    while (true) {
                        val magic = try {
                            Integer.reverseBytes(input.readInt())
                        } catch (_: EOFException) {
                            break
                        }
                        check(magic == 0x4d574743)
                        val width = Integer.reverseBytes(input.readInt())
                        val height = Integer.reverseBytes(input.readInt())
                        val contentWidth = Integer.reverseBytes(input.readInt())
                        val contentHeight = Integer.reverseBytes(input.readInt())
                        val timestamp = java.lang.Long.reverseBytes(input.readLong()) * 100
                        check(width in 1..4096 && height in 1..4096)
                        check(width == contentWidth && height == contentHeight)
                        val pixels = ByteArray(width * height * 4)
                        input.readFully(pixels)
                        var red = 0
                        var glyph = 0
                        for (y in 0 until height step 2) {
                            for (x in 0 until width step 2) {
                                val offset = (y * width + x) * 4
                                val blue = pixels[offset].toInt() and 255
                                val green = pixels[offset + 1].toInt() and 255
                                val r = pixels[offset + 2].toInt() and 255
                                if (r > 200 && green < 20 && blue < 20) red++
                                // Real TopAppBar chapter title; exclude navigation and right-side icons.
                                if (x in titleLeft..titleRight && y in titleTop..titleBottom &&
                                    r > 200 && blue > 200 && green > 200
                                ) {
                                    glyph++
                                }
                            }
                        }
                        frames += "timestamp=$timestamp size=${width}x$height red=$red titleGlyph=$glyph"
                        val healthy = red > 1000 && glyph > 8
                        if (firstTimestamp.get() != 0L) {
                            check(healthy) { "Bitmap or actual title glyph disappeared: ${frames.last()}" }
                        }
                        val initialHealthy = healthy && firstTimestamp.compareAndSet(0, timestamp)
                        val name = when {
                            frames.size == 1 -> "first.png"
                            timestamp >= metadataAt.get() && post.count > 0 -> "after-metadata.png"
                            else -> null
                        }
                        if (name != null) {
                            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                            val row = IntArray(width)
                            for (y in 0 until height) {
                                for (x in 0 until width) {
                                    val i = (y * width + x) * 4
                                    row[x] = ((pixels[i + 2].toInt() and 255) shl 16) or
                                        ((pixels[i + 1].toInt() and 255) shl 8) or (pixels[i].toInt() and 255)
                                }
                                image.setRGB(0, y, width, 1, row, 0, width)
                            }
                            ImageIO.write(image, "png", directory.resolve(name).toFile())
                            image.flush()
                            if (name == "after-metadata.png") post.countDown()
                        }
                        if (initialHealthy) first.countDown()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
                first.countDown()
                post.countDown()
            }
        }.apply {
            name = "reader-compositor-diagnostic"
            isDaemon = true
            start()
        }
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS), "WGC must deliver the actual decoded comic and title")
            failure.get()?.let { throw it }
            assertTrue(firstTimestamp.get() != 0L, "WGC must observe initial real bitmap and title glyph")
            Thread.sleep(8_100)
            onEdt {
                metadataAt.set(System.nanoTime())
                model.setSpreadPages(setOf(2))
            }
            assertTrue(post.await(5, TimeUnit.SECONDS), "Metadata must produce a fresh WGC frame after idle")
            failure.get()?.let { throw it }
            assertTrue(
                frames.any {
                    val timestamp = it.substringAfter("timestamp=").substringBefore(' ').toLong()
                    timestamp - firstTimestamp.get() >= 8_000_000_000L
                },
                "An old startup PNG cannot satisfy the idle observation",
            )
            assertFalse(window.isActive)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            reader.join(2_000)
            val captureInfo = "scale=${scale.scaleX},${scale.scaleY} " +
                "titleROI=$titleLeft,$titleTop,$titleRight,$titleBottom"
            Files.write(directory.resolve("frames.txt"), listOf(captureInfo) + frames)
            println("READER_WGC_IDLE directory=$directory metadataAt=${metadataAt.get()} frames=${frames.size}")
        }
    }

    private fun readTraceEvents(directory: Path): List<JsonObject> =
        if (!Files.exists(directory)) {
            emptyList()
        } else {
            Files.walk(directory, 3).use { paths ->
                paths.filter { it.fileName.toString() == "events.jsonl" }.toList().flatMap { path ->
                    // The writer may currently be appending its final record; final validation below
                    // still parses every complete file after the trace has drained.
                    Files.readAllLines(path).mapNotNull { line ->
                        runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                    }
                }
            }
        }

    private fun f11KeyEvent(): KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod("access\$getKeyDown\$cp").invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
        }
        return KeyEvent(factory.invoke(null, Key.F11.keyCode, type, 0, false, false, false, false, null))
    }

    private fun comicCenterY(frame: JsonObject, events: List<JsonObject>, files: List<Path>): Double {
        val record = events.single {
            it["type"]?.jsonPrimitive?.content == "frame-written" && it["renderId"] == frame["renderId"]
        }
        val file = files.single { it.fileName.toString() == record.getValue("file").jsonPrimitive.content }
        val image = ImageIO.read(file.toFile())
        try {
            val row = IntArray(image.width)
            val rows = (0 until image.height).filter { y ->
                image.getRGB(0, y, image.width, 1, row, 0, image.width)
                row.any { it == java.awt.Color.RED.rgb }
            }
            assertTrue(rows.isNotEmpty(), "The actual decoded comic must be visible in the entry PNG")
            val canvas = frame.getValue("window").jsonObject.getValue("nativeCanvas").jsonArray
            return canvas[1].jsonPrimitive.content.toDouble() + (rows.first() + rows.last()) / 2.0
        } finally {
            image.flush()
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
