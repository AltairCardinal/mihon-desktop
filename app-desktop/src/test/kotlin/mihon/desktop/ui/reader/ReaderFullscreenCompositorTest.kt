package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import mihon.desktop.platform.DesktopWindowTransitions
import mihon.desktop.platform.WindowsBorderlessFullscreenAdapter
import mihon.desktop.platform.WindowsFullscreenBounds
import mihon.desktop.platform.WindowsWindowTransitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Observes the owned window's GPU/compositor content, never the desktop or input. */
@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReaderFullscreenCompositorTest {
    @Test
    fun `entry never presents the old comic stretched to the new window`() {
        val target = Rectangle(-20000, -20000, 1280, 720)
        val delay = AtomicBoolean(false)
        val window = onEdt {
            ComposeWindow().apply {
                setBounds(target.x, target.y, 1278, 688)
                focusableWindowState = false
                isAutoRequestFocus = false
                setContent {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1960f / 862f)
                                .drawBehind {
                                    // A real frame may miss a refresh deadline; it must not expose stale geometry.
                                    if (delay.get()) Thread.sleep(45)
                                }
                                .background(Color.Red),
                        )
                    }
                }
                isVisible = true
            }
        }
        val scale = window.graphicsConfiguration.defaultTransform
        val physical = Rectangle(
            (target.x * scale.scaleX).toInt(),
            (target.y * scale.scaleY).toInt(),
            (target.width * scale.scaleX).toInt(),
            (target.height * scale.scaleY).toInt(),
        )
        val adapter = WindowsBorderlessFullscreenAdapter(
            bounds = { _, _ -> WindowsFullscreenBounds(physical) { target } },
        )
        val completion = AtomicReference<CountDownLatch>()
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            fullscreenAdapter = adapter,
            transitions = DesktopWindowTransitions { frame, disabled ->
                WindowsWindowTransitions.setDisabled(frame, disabled).also {
                    if (!disabled) completion.get()?.countDown()
                }
            },
        )
        val helper = Path.of("artifacts/diagnostics/compositor-native/reader-compositor.exe").toAbsolutePath()
        val frames = CopyOnWriteArrayList<CapturedFrame>()
        val output = Path.of("build/reports/reader-fullscreen-compositor", System.currentTimeMillis().toString())
        Files.createDirectories(output)
        println("Compositor evidence: ${output.toAbsolutePath()}")
        var failure: Throwable? = null
        var process: Process? = null
        var reader: Thread? = null
        try {
            assertTrue(
                Files.isRegularFile(helper),
                "Build scripts/build-reader-compositor.ps1 before this test: $helper",
            )
            repeat(5) {
                onEdt { window.renderImmediately() }
                Thread.sleep(40)
            }
            val hwnd = onEdt { Pointer.nativeValue(Native.getComponentPointer(window)) }
            process = ProcessBuilder(
                helper.toString(),
                "0x${hwnd.toString(16)}",
                ProcessHandle.current().pid().toString(),
                "7",
            )
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val capture = process
            reader = Thread {
                try {
                    val input = DataInputStream(capture.inputStream)
                    val written = mutableSetOf<List<Int>>()
                    while (true) {
                        val magic = try {
                            Integer.reverseBytes(input.readInt())
                        } catch (_: java.io.EOFException) {
                            break
                        }
                        check(magic == 0x4d574743)
                        val width = Integer.reverseBytes(input.readInt())
                        val height = Integer.reverseBytes(input.readInt())
                        val contentWidth = Integer.reverseBytes(input.readInt())
                        val contentHeight = Integer.reverseBytes(input.readInt())
                        val timestamp = java.lang.Long.reverseBytes(input.readLong()) * 100
                        check(width in 1..4096 && height in 1..4096 && contentWidth == width && contentHeight == height)
                        val pixels = ByteArray(width * height * 4)
                        input.readFully(pixels)
                        val redRows = (0 until height).filter { y ->
                            val i = (y * width + width / 2) * 4
                            (pixels[i + 2].toInt() and 255) > 200 &&
                                (pixels[i + 1].toInt() and 255) < 4 && (pixels[i].toInt() and 255) < 4
                        }
                        val sample = CapturedFrame(
                            width,
                            height,
                            redRows.firstOrNull() ?: -1,
                            redRows.lastOrNull() ?: -1,
                            timestamp,
                        )
                        frames += sample
                        // Keep metadata for every delivered frame, but only one PNG per geometry.
                        // Holding all full-resolution frames would let a longer capture exhaust the test JVM.
                        if (written.add(listOf(width, height, sample.top, sample.bottom))) {
                            check(written.size <= 32)
                            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                            val rgb = IntArray(width * height) { i ->
                                ((pixels[i * 4 + 2].toInt() and 255) shl 16) or
                                    ((pixels[i * 4 + 1].toInt() and 255) shl 8) or (pixels[i * 4].toInt() and 255)
                            }
                            image.setRGB(0, 0, width, height, rgb, 0, width)
                            ImageIO.write(image, "png", output.resolve("frame-${written.size}.png").toFile())
                        }
                    }
                } catch (problem: Throwable) {
                    failure = problem
                }
            }.apply { start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (frames.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(frames.isNotEmpty(), "Capture must observe the initial real window before entry")
            val entries = mutableListOf<LongRange>()
            repeat(3) {
                val begin = System.nanoTime()
                delay.set(true)
                switch(controller, completion, true)
                Thread.sleep(180)
                entries += begin..System.nanoTime()
                assertTrue(onEdt { User32.INSTANCE.IsWindowVisible(WinDef.HWND(Native.getComponentPointer(window))) })
                delay.set(false)
                switch(controller, completion, false)
                Thread.sleep(180)
                assertTrue(onEdt { User32.INSTANCE.IsWindowVisible(WinDef.HWND(Native.getComponentPointer(window))) })
            }
            assertTrue(capture.waitFor(10, TimeUnit.SECONDS))
            reader.join(2000)
            assertFalse(reader.isAlive, "The capture reader must drain before checking frames")
            failure?.let { throw it }
            assertEquals(0, capture.exitValue())
            assertFalse(window.isActive)
            Files.writeString(output.resolve("frames.txt"), frames.joinToString("\n"))
            assertTrue(frames.all { it.top >= 0 }, "Comic must not disappear or turn black: $frames")
            entries.forEachIndexed { index, interval ->
                val fullscreen = frames.filter {
                    it.timestamp in interval && it.width == physical.width && it.height == physical.height
                }
                assertTrue(fullscreen.isNotEmpty(), "Cycle $index must actually capture fullscreen pixels: $frames")
                val expectedTop = (physical.height - physical.width * 862.0 / 1960) / 2
                assertTrue(
                    fullscreen.all { kotlin.math.abs(it.top - expectedTop) <= 2 },
                    "Cycle $index stale comic positions: ${fullscreen.map { it.top }}; expected=$expectedTop",
                )
            }
        } finally {
            if (process?.isAlive == true) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            reader?.join(2000)
            onEdt {
                controller.onDispose(wasFullscreen = true)
                window.dispose()
            }
        }
    }

    private data class CapturedFrame(
        val width: Int,
        val height: Int,
        val top: Int,
        val bottom: Int,
        val timestamp: Long,
    )

    private fun switch(
        controller: DesktopReaderFullscreenController,
        completion: AtomicReference<CountDownLatch>,
        fullscreen: Boolean,
    ) {
        val done = CountDownLatch(1)
        completion.set(done)
        onEdt { controller.syncPlacement(fullscreen) }
        assertTrue(done.await(3, TimeUnit.SECONDS), "The real transition must complete")
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
