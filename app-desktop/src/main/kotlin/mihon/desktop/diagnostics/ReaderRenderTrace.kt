package mihon.desktop.diagnostics

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.desktop.APP_VERSION
import mihon.desktop.platform.DesktopPlatformPaths
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.PaintEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

private const val MAX_TRACE_FRAMES = 900

/** Opt-in local diagnostics, independent of Test Mode and with no desktop/input access. */
internal object ReaderRenderTracing {
    const val PROPERTY = "mihon.reader.renderTraceDir"
    private val sessions = CopyOnWriteArrayList<ReaderRenderTrace>()

    fun configure(args: Array<String>) {
        args.firstOrNull { it.startsWith("--reader-render-trace=") }?.substringAfter('=')?.let {
            System.setProperty(PROPERTY, it)
        }
        if ("--reader-render-trace" in args && System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, DesktopPlatformPaths.current().logsDir.resolve("reader-render-traces").path)
        }
    }

    fun create(window: ComposeWindow?): ReaderRenderTrace? {
        val requested = System.getProperty(PROPERTY)?.takeIf(String::isNotBlank) ?: return null
        if (window == null) return null
        return runCatching {
            val root = Path.of(requested)
            require(root.isAbsolute) { "Reader render trace output must be absolute" }
            val maxFrames = System.getProperty("mihon.reader.renderTraceMaxFrames")?.toInt() ?: MAX_TRACE_FRAMES
            require(maxFrames in 1..MAX_TRACE_FRAMES) { "Reader trace max frames must be in 1..$MAX_TRACE_FRAMES" }
            ReaderRenderTrace(window, root, maxFrames).also(sessions::add)
        }.onFailure { System.err.println("Reader render trace unavailable: ${it.message}") }.getOrNull()
    }

    fun closeAll() {
        sessions.forEach { session -> session.guard("shutdown") { session.close() } }
        sessions.forEach { session -> session.guard("writer-drain") { session.awaitWrites() } }
        sessions.clear()
    }
}

/**
 * Captures completed SkiaLayer Pictures, never a screenshot of the desktop or GPU presentation.
 * Each render's old holder is retained only as an identity token. Capture occurs after replacement,
 * either before the next delegate call or in an EDT completion callback guarded by render identity.
 */
internal class ReaderRenderTrace(
    private val window: ComposeWindow,
    root: Path,
    private val maxFrames: Int = MAX_TRACE_FRAMES,
) : AutoCloseable {
    val directory: Path = root.resolve("${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID()}")
    private val lock = Any()
    private val metadata = directory.resolve("events.jsonl")
    private val log by lazy {
        Files.createDirectories(directory)
        Files.newBufferedWriter(metadata, Charsets.UTF_8)
    }
    private val pictureField = SkiaLayer::class.java.getDeclaredField("picture").apply { isAccessible = true }
    private val writer = ThreadPoolExecutor(
        4,
        4,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        { task -> Thread(task, "reader-frame-png").apply { isDaemon = true } },
    )
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "reader-trace-deadline").apply { isDaemon = true }
    }
    private val queuedBytes = AtomicLong()
    private val mountIds = AtomicLong()
    private val pixelsLock = Any()
    private var lastPixels: CachedPixels? = null
    private data class StoredPng(val file: String, val size: Long)
    private data class CachedPixels(
        val width: Int,
        val height: Int,
        val checksum: Long,
        val rgba: ByteArray,
        val stored: java.util.concurrent.CompletableFuture<StoredPng> = java.util.concurrent.CompletableFuture(),
    )
    private var eventSequence = 0L
    private var renderSequence = 0L
    private var requested = 0
    private var written = 0
    private var missing = 0
    private var diskBytes = 0L

    @Volatile private var failed = false

    @Volatile private var closed = false

    @Volatile private var deadline = System.nanoTime() + CAPTURE_NANOS

    @Volatile private var currentRender = 0L
    private var lastReaderState: JsonObject? = null
    private var layer: SkiaLayer? = null
    private var original: SkikoRenderDelegate? = null
    private var installed: SkikoRenderDelegate? = null
    private var pending: Render? = null
    private val captured = mutableSetOf<Long>()
    private var renderDepth = 0
    private val finished = java.util.concurrent.CountDownLatch(1)
    private var finalizeStarted = false
    private var reentered = false
    private val eventQueue = ArrayBlockingQueue<JsonObject>(4096)
    private val droppedEvents = AtomicLong()
    private val loggerStarted = java.util.concurrent.atomic.AtomicBoolean()

    @Volatile private var eventsClosed = false
    private val logger = Thread({
        try {
            while (!eventsClosed || eventQueue.isNotEmpty()) {
                val record = eventQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                log.append(Json.encodeToString(JsonObject.serializer(), record)).append('\n')
                if (eventQueue.isEmpty()) log.flush()
            }
            log.flush()
        } catch (failure: Throwable) {
            failed = true
            System.err.println("Reader render trace event writer failed: ${failure.message}")
        } finally {
            runCatching { log.close() }
        }
    }, "reader-trace-events").apply { isDaemon = true }
    private val listener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = windowEvent("window-moved")
        override fun componentResized(event: ComponentEvent) = windowEvent("window-resized")
    }
    private val paintListener = AWTEventListener { observed ->
        val target = layer?.canvas
        if (observed is PaintEvent && observed.source === target && isCapturing && target != null) {
            guard("awt-paint-observer") {
                windowEvent(
                    "awt-canvas-paint",
                    buildJsonObject {
                        put("paintId", observed.id)
                        put(
                            "awtUpdateRect",
                            buildJsonArray {
                                add(JsonPrimitive(observed.updateRect.x))
                                add(JsonPrimitive(observed.updateRect.y))
                                add(JsonPrimitive(observed.updateRect.width))
                                add(JsonPrimitive(observed.updateRect.height))
                            },
                        )
                        put("canvasIgnoreRepaint", target.ignoreRepaint)
                        put("canvasBackgroundArgb", target.background?.rgb)
                        put("noEraseBackgroundProperty", System.getProperty("sun.awt.noerasebackground", "unset"))
                    },
                )
            }
        }
    }

    private data class Render(val id: Long, val oldHolder: Any?, val fields: JsonObject, val wanted: Boolean)

    val isCapturing: Boolean get() = !closed && !failed && System.nanoTime() <= deadline
    fun nextMountId(): Long = mountIds.incrementAndGet()
    fun guard(stage: String, action: () -> Unit) {
        runCatching(action).onFailure { gap(currentRender, "$stage-failed:${it.javaClass.simpleName}") }
    }

    fun attach() {
        check(SwingUtilities.isEventDispatchThread())
        window.addComponentListener(listener)
        val target = descendants(window).filterIsInstance<SkiaLayer>().firstOrNull()
        val delegate = target?.renderDelegate
        if (target == null || delegate == null) {
            gap(0, "skia-layer-or-delegate-unavailable")
            return
        }
        runCatching { pictureField.get(target) }.onFailure {
            gap(0, "picture-identity-adapter-unavailable")
            summary()
            return
        }
        layer = target
        original = delegate
        val wrapper = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                if (closed || failed) return delegate.onRender(canvas, width, height, nanoTime)
                if (!SwingUtilities.isEventDispatchThread()) {
                    gap(0, "render-not-on-edt")
                    return delegate.onRender(canvas, width, height, nanoTime)
                }
                val id = ++renderSequence
                if (renderDepth != 0) {
                    reentered = true
                    gap(id, "reentrant-render-unassociated")
                    return delegate.onRender(canvas, width, height, nanoTime)
                }
                reentered = false
                val setup = runCatching {
                    captureCompleted()
                    val fields = buildJsonObject {
                        put("renderId", id)
                        put("renderStartedNanos", System.nanoTime())
                        put("renderClockNanos", nanoTime)
                        put("canvasWidth", width)
                        put("canvasHeight", height)
                        put("window", geometry())
                    }
                    pictureField.get(target) to fields
                }.getOrElse {
                    pending = null
                    gap(id, "render-observer-setup-failed:${it.javaClass.simpleName}")
                    return delegate.onRender(canvas, width, height, nanoTime)
                }
                val (before, fields) = setup
                renderDepth += 1
                val previousRender = currentRender
                currentRender = id
                val wanted = isCapturing
                if (wanted) event("render-begin", fields)
                try {
                    // Preserve the exact original canvas, clip, delegate, scale and invocation count.
                    delegate.onRender(canvas, width, height, nanoTime)
                } catch (failure: Throwable) {
                    gap(id, "delegate-failed:${failure.javaClass.simpleName}")
                    throw failure
                } finally {
                    currentRender = previousRender
                    renderDepth -= 1
                }
                if (reentered) {
                    pending = null
                    gap(id, "outer-render-reentered-unassociated")
                } else {
                    pending = Render(id, before, fields, wanted || isCapturing)
                    runCatching {
                        SwingUtilities.invokeLater {
                            if (!closed && pending?.id == id) captureCompleted()
                        }
                    }.onFailure { gap(id, "completion-dispatch-failed") }
                }
            }
        }
        installed = wrapper
        target.renderDelegate = wrapper
        Toolkit.getDefaultToolkit().addAWTEventListener(paintListener, AWTEvent.PAINT_EVENT_MASK)
        event(
            "session-start",
            buildJsonObject {
                put("appVersion", APP_VERSION)
                put("pid", ProcessHandle.current().pid())
                put("captureStage", "completed-layer-picture")
                put("presentationObserved", false)
                put("privateAdapter", "SkiaLayer.picture identity; inspected Skiko 0.9.37.4")
                put("limits", "$maxFrames frame records, 256 MiB queued pixels, 512 MiB PNG files per reader session")
            },
        )
        windowEvent("window-attached")
        summary()
    }

    /** Called before native placement changes, including its last completed frame as a baseline. */
    fun fullscreenRequest(fullscreen: Boolean) {
        if (closed || failed) return
        runCatching {
            deadline = System.nanoTime() + CAPTURE_NANOS
            captureRevision.value += 1
            captureCompleted(force = true)
            event(
                "fullscreen-request",
                buildJsonObject {
                    put("fullscreen", fullscreen)
                    put("window", geometry())
                },
            )
            val expectedDeadline = deadline
            timer.schedule({
                if (!closed && deadline == expectedDeadline && !isCapturing) {
                    event("capture-window-end")
                    summary()
                }
            }, 1900, TimeUnit.MILLISECONDS)
        }.onFailure { gap(0, "fullscreen-observer-failed:${it.javaClass.simpleName}") }
    }

    fun windowEvent(type: String, fields: JsonObject = buildJsonObject {}) {
        runCatching {
            if (!closed && isCapturing) {
                event(
                    type,
                    buildJsonObject {
                        put("window", geometry())
                        fields.forEach { (key, value) -> put(key, value) }
                    },
                )
            }
        }.onFailure { gap(0, "window-observer-failed:${it.javaClass.simpleName}") }
    }

    fun readerState(state: JsonObject) {
        if (state == lastReaderState || closed) return
        lastReaderState = state
        event("reader-state", state)
    }

    fun imageDraw(fields: JsonObject) {
        if (isCapturing) event("image-draw", fields)
    }

    fun event(type: String, fields: JsonObject = buildJsonObject {}) {
        synchronized(lock) {
            if (failed) return
            runCatching {
                val record = buildJsonObject {
                    put("type", type)
                    put("sequence", ++eventSequence)
                    put("timeNanos", System.nanoTime())
                    put("renderId", currentRender)
                    put("thread", Thread.currentThread().name)
                    fields.forEach { (key, value) -> put(key, value) }
                }
                if (loggerStarted.compareAndSet(false, true)) logger.start()
                if (!eventQueue.offer(record)) droppedEvents.incrementAndGet()
            }.onFailure {
                failed = true
                System.err.println("Reader render trace write failed: ${it.message}")
            }
        }
    }

    private fun captureCompleted(force: Boolean = false) {
        if (!SwingUtilities.isEventDispatchThread()) {
            gap(currentRender, "capture-not-on-edt")
            return
        }
        val target = layer ?: return
        val frame = pending ?: return
        if (renderDepth != 0) return
        if ((!frame.wanted && !force) || frame.id in captured) return
        captured += frame.id
        synchronized(lock) { requested += 1 }
        var reserved = 0L
        var owned: Bitmap? = null
        try {
            val holder = pictureField.get(target)
            if (holder == null || holder === frame.oldHolder || renderDepth != 0) {
                gap(frame.id, "outer-picture-not-completed")
                return
            }
            val width = holder.javaClass.getMethod("getWidth").invoke(holder) as Int
            val height = holder.javaClass.getMethod("getHeight").invoke(holder) as Int
            val bytes = width.toLong() * height * 4
            val allowed = synchronized(lock) { requested <= maxFrames && diskBytes < MAX_DISK_BYTES }
            if (!allowed || bytes <= 0 || bytes > MAX_QUEUED_BYTES) {
                gap(frame.id, "capture-budget-exceeded")
                return
            }
            if (queuedBytes.addAndGet(bytes) > MAX_QUEUED_BYTES) {
                queuedBytes.addAndGet(-bytes)
                gap(frame.id, "capture-budget-exceeded")
                return
            }
            reserved = bytes
            val captureStarted = System.nanoTime()
            val bitmap = target.screenshot()
            if (bitmap == null) {
                gap(frame.id, "layer-screenshot-unavailable")
                return
            }
            owned = bitmap
            if (pictureField.get(target) !== holder) {
                gap(frame.id, "picture-replaced-during-capture")
                return
            }
            event(
                "frame-captured",
                buildJsonObject {
                    frame.fields.forEach { (key, value) -> put(key, value) }
                    put("captureStage", "completed-layer-picture")
                    put("captureStartedNanos", captureStarted)
                    put("captureFinishedNanos", System.nanoTime())
                    put("pngWidth", bitmap.width)
                    put("pngHeight", bitmap.height)
                },
            )
            try {
                writer.execute { saveFrame(frame.id, bitmap, bytes) }
                owned = null
                reserved = 0
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                gap(frame.id, "png-writer-queue-full")
            }
        } catch (failure: Throwable) {
            gap(frame.id, "capture-failed:${failure.javaClass.simpleName}")
        } finally {
            owned?.close()
            if (reserved != 0L) queuedBytes.addAndGet(-reserved)
        }
    }

    private fun saveFrame(id: Long, bitmap: Bitmap, bytes: Long) {
        var ownedPromise: java.util.concurrent.CompletableFuture<StoredPng>? = null
        try {
            val rgba = requireNotNull(
                bitmap.readPixels(
                    ImageInfo(bitmap.width, bitmap.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
                    bitmap.width * 4,
                    0,
                    0,
                ),
            )
            val checksum = java.util.zip.CRC32().apply { update(rgba) }.value
            val (pixels, fresh) = synchronized(pixelsLock) {
                val previous = lastPixels
                if (previous != null && previous.width == bitmap.width && previous.height == bitmap.height &&
                    previous.checksum == checksum && previous.rgba.contentEquals(rgba)
                ) {
                    previous to false
                } else {
                    CachedPixels(bitmap.width, bitmap.height, checksum, rgba).also { lastPixels = it } to true
                }
            }
            if (fresh) {
                ownedPromise = pixels.stored
                val file = directory.resolve("frame-${id.toString().padStart(6, '0')}.png")
                val png = encodeFastPng(bitmap.width, bitmap.height, rgba)
                val accepted = synchronized(lock) {
                    if (diskBytes + png.size > MAX_DISK_BYTES) {
                        false
                    } else {
                        diskBytes += png.size
                        true
                    }
                }
                if (!accepted) {
                    error("png-disk-budget-exceeded")
                }
                Files.write(file, png)
                pixels.stored.complete(StoredPng(file.fileName.toString(), Files.size(file)))
            } else {
                // Identical pixels need no second PNG or bitmap retention while its encoder runs.
                pixels.stored.whenComplete { stored, failure ->
                    if (failure != null) {
                        gap(id, "deduplicated-png-write-failed")
                    } else {
                        recordStoredFrame(id, stored, true)
                    }
                }
                return
            }
            val stored = pixels.stored.get(5, TimeUnit.SECONDS)
            recordStoredFrame(id, stored, false)
        } catch (failure: Throwable) {
            ownedPromise?.completeExceptionally(failure)
            gap(id, "png-write-failed:${failure.javaClass.simpleName}")
        } finally {
            bitmap.close()
            queuedBytes.addAndGet(-bytes)
            summary()
        }
    }

    private fun recordStoredFrame(id: Long, stored: StoredPng, reused: Boolean) {
        synchronized(lock) { written += 1 }
        event(
            "frame-written",
            buildJsonObject {
                put("renderId", id)
                put("file", stored.file)
                put("bytes", stored.size)
                put("reusedIdenticalPixels", reused)
                put("captureStage", "completed-layer-picture")
            },
        )
    }

    private fun gap(id: Long, reason: String) {
        synchronized(lock) { missing += 1 }
        event(
            "capture-gap",
            buildJsonObject {
                put("renderId", id)
                put("reason", reason)
            },
        )
    }

    private fun summary() {
        if (SwingUtilities.isEventDispatchThread()) {
            if (!timer.isShutdown) runCatching { timer.execute { summary() } }
            return
        }
        synchronized(lock) {
            runCatching {
                val result = buildJsonObject {
                    put("schemaVersion", 1)
                    put("appVersion", APP_VERSION)
                    put("captureStage", "completed-layer-picture")
                    put("presentationObserved", false)
                    put("closed", closed)
                    put("captureActive", isCapturing)
                    put("requested", requested)
                    put("written", written)
                    put("missing", missing)
                    put("droppedEvents", droppedEvents.get())
                    put("pngBytes", diskBytes)
                    put("queuedPixelBytes", queuedBytes.get())
                    put(
                        "status",
                        if (failed || missing > 0 ||
                            droppedEvents.get() > 0
                        ) {
                            "PARTIAL"
                        } else if (written < requested) {
                            "WRITING"
                        } else {
                            "RECORDED"
                        },
                    )
                }
                val temporary = directory.resolve("manifest.json.tmp")
                Files.createDirectories(directory)
                Files.writeString(temporary, Json.encodeToString(JsonObject.serializer(), result))
                Files.move(temporary, directory.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure {
                failed = true
                System.err.println("Reader render trace summary failed: ${it.message}")
            }
        }
    }

    private fun geometry(): JsonObject = buildJsonObject {
        put("awtBounds", rectangle(window.bounds))
        put(
            "awtInsets",
            buildJsonArray {
                add(JsonPrimitive(window.insets.left))
                add(JsonPrimitive(window.insets.top))
                add(JsonPrimitive(window.insets.right))
                add(JsonPrimitive(window.insets.bottom))
            },
        )
        put("densityX", window.graphicsConfiguration.defaultTransform.scaleX)
        put("densityY", window.graphicsConfiguration.defaultTransform.scaleY)
        layer?.let {
            put("layerBounds", rectangle(it.bounds))
            put("contentScale", it.contentScale)
        }
        if (Platform.isWindows() && window.isDisplayable) {
            runCatching {
                val hwnd = WinDef.HWND(Native.getComponentPointer(window))
                val outer = WinDef.RECT()
                val client = WinDef.RECT()
                val origin = WinDef.POINT()
                if (User32.INSTANCE.GetWindowRect(hwnd, outer)) put("nativeWindow", nativeRectangle(outer))
                if (User32.INSTANCE.GetClientRect(hwnd, client) &&
                    NativeLibrary.getInstance("user32").getFunction("ClientToScreen", Function.ALT_CONVENTION)
                        .invokeInt(arrayOf(hwnd, origin)) != 0
                ) {
                    put("nativeClient", rectangle(Rectangle(origin.x, origin.y, client.right, client.bottom)))
                }
                descendants(window).filterIsInstance<java.awt.Canvas>().firstOrNull()?.let { canvas ->
                    val bounds = WinDef.RECT()
                    if (canvas.isDisplayable &&
                        User32.INSTANCE.GetWindowRect(WinDef.HWND(Native.getComponentPointer(canvas)), bounds)
                    ) {
                        put("nativeCanvas", nativeRectangle(bounds))
                    }
                }
            }.onFailure { put("nativeGeometryError", it.javaClass.simpleName) }
        }
    }

    override fun close() {
        if (closed) return
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeAndWait { close() }
            return
        }
        if (SwingUtilities.isEventDispatchThread()) {
            captureCompleted()
            layer?.let { if (it.renderDelegate === installed) it.renderDelegate = original }
            window.removeComponentListener(listener)
            Toolkit.getDefaultToolkit().removeAWTEventListener(paintListener)
        }
        closed = true
        event("session-close")
        timer.shutdownNow()
        writer.shutdown()
        summary()
        synchronized(lock) {
            if (!finalizeStarted) {
                finalizeStarted = true
                Thread({
                    if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                        gap(0, "writer-shutdown-timeout-still-draining")
                        while (!writer.awaitTermination(1, TimeUnit.SECONDS)) summary()
                    }
                    event("session-drained")
                    synchronized(pixelsLock) { lastPixels = null }
                    eventsClosed = true
                    if (loggerStarted.get()) logger.join()
                    summary()
                    finished.countDown()
                }, "reader-trace-finalize").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    fun awaitWrites() {
        if (!finished.await(6, TimeUnit.SECONDS)) {
            System.err.println("Reader render trace still draining; evidence is not complete: $directory")
        }
    }

    private fun rectangle(value: Rectangle) = buildJsonArray {
        add(JsonPrimitive(value.x))
        add(JsonPrimitive(value.y))
        add(JsonPrimitive(value.width))
        add(JsonPrimitive(value.height))
    }
    private fun nativeRectangle(value: WinDef.RECT) = rectangle(
        Rectangle(
            value.left,
            value.top,
            value.right - value.left,
            value.bottom - value.top,
        ),
    )
    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }

    companion object {
        private const val CAPTURE_NANOS = 1_800_000_000L
        private const val MAX_QUEUED_BYTES = 256L * 1024 * 1024
        private const val MAX_DISK_BYTES = 512L * 1024 * 1024
    }

    val captureRevision = kotlinx.coroutines.flow.MutableStateFlow(1L)

    private fun encodeFastPng(width: Int, height: Int, rgba: ByteArray): ByteArray {
        val compressed = java.io.ByteArrayOutputStream()
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
        try {
            java.util.zip.DeflaterOutputStream(compressed, deflater).use { stream ->
                repeat(height) { row ->
                    stream.write(0) // PNG filter None avoids expensive adaptive filter scans on the capture path.
                    stream.write(rgba, row * width * 4, width * 4)
                }
            }
        } finally {
            deflater.end()
        }
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { stream ->
            stream.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, bytes: ByteArray) {
                val tag = type.toByteArray(Charsets.US_ASCII)
                stream.writeInt(bytes.size)
                stream.write(tag)
                stream.write(bytes)
                val crc = java.util.zip.CRC32().apply {
                    update(tag)
                    update(bytes)
                }
                stream.writeInt(crc.value.toInt())
            }
            val header = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put(8).put(6).put(0).put(0).put(0).array()
            chunk("IHDR", header)
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
        return output.toByteArray()
    }
}
