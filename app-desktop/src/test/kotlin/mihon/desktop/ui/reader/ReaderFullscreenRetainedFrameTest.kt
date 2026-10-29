package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import mihon.desktop.platform.WindowsBorderlessFullscreenAdapter
import mihon.desktop.platform.WindowsFullscreenBounds
import mihon.desktop.platform.retainNativeReaderPixels
import mihon.desktop.platform.setWindowsWindowCloaked
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Actual reader controller, owned offscreen windows and window-only WGC. No desktop or input. */
@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReaderFullscreenRetainedFrameTest {
    @Test
    fun `an independent retained frame closes on timeout hide or capture protection`() {
        val window = onEdt {
            ComposeWindow().apply {
                setBounds(-20000, -20000, 320, 240)
                focusableWindowState = false
                isAutoRequestFocus = false
                isVisible = true
            }
        }
        val owner = WinDef.HWND(Native.getComponentPointer(window))
        val affinity = NativeLibrary.getInstance(
            "user32",
        ).getFunction("SetWindowDisplayAffinity", Function.ALT_CONVENTION)
        try {
            for (stop in listOf("timeout", "hide", "protect")) {
                val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB_PRE)
                (image.raster.dataBuffer as java.awt.image.DataBufferInt).data.fill(0xffff0000.toInt())
                val cover = Rectangle(-30000, -30000, 32, 32)
                val origin = WinDef.RECT().apply {
                    left = cover.x
                    top = cover.y
                }
                val frame = requireNotNull(retainNativeReaderPixels(owner, cover, origin, image, false))
                try {
                    val hwnd =
                        requireNotNull(
                            User32.INSTANCE.FindWindow(
                                null,
                                "MihonReaderTransitionFrame:${Pointer.nativeValue(owner.pointer)}",
                            ),
                        )
                    assertTrue(User32.INSTANCE.IsWindowVisible(hwnd))
                    assertFalse(hwnd == User32.INSTANCE.GetForegroundWindow(), "The frame must never receive focus")
                    Thread.sleep(100)
                    assertTrue(User32.INSTANCE.IsWindow(hwnd), "The frame must remain alive before a stop condition")
                    when (stop) {
                        "hide" -> onEdt { window.isVisible = false }
                        "protect" -> assertEquals(1, affinity.invokeInt(arrayOf(owner, 0x11)))
                    }
                    val deadline =
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (stop == "timeout") 2500 else 750)
                    while (User32.INSTANCE.IsWindow(hwnd) && System.nanoTime() < deadline) Thread.sleep(5)
                    assertFalse(User32.INSTANCE.IsWindow(hwnd), "The independent frame must close after $stop")
                } finally {
                    frame.close()
                    onEdt {
                        affinity.invokeInt(arrayOf(owner, 0))
                        window.isVisible = true
                    }
                }
            }
        } finally {
            onEdt { window.dispose() }
        }
    }

    @Test
    fun `both transitions keep the old pixels at their screen position until the new frame is presented`() {
        val target = Rectangle(-20000, -20000, 960, 720)
        val window = onEdt {
            ComposeWindow().apply {
                setBounds(target.x, target.y, 800, 600)
                focusableWindowState = false
                isAutoRequestFocus = false
                setContent {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Box(Modifier.offset(y = (-65).dp).size(240.dp, 120.dp).background(Color.Red))
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
        val pending = mutableListOf<Runnable>()
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { pending += it },
            fullscreenAdapter = WindowsBorderlessFullscreenAdapter(
                bounds = { _, _ -> WindowsFullscreenBounds(physical) { target } },
            ),
        )
        try {
            repeat(4) {
                onEdt { window.renderImmediately() }
                Thread.sleep(30)
            }
            for (fullscreen in listOf(true, false)) {
                val source = capture(Pointer.nativeValue(Native.getComponentPointer(window)))
                val sourceRect = onEdt { captureRect(WinDef.HWND(Native.getComponentPointer(window))) }
                val sourceRed = source.redRows()
                val screenRedTop = sourceRect.top + sourceRed.first()
                onEdt {
                    controller.syncPlacement(fullscreen)
                    pending.removeAt(0).run()
                }
                val hwnd = onEdt { retainedHandle(window) }
                assertNotNull(hwnd, "The actual resize hold must retain a separate visible reader frame")
                requireNotNull(hwnd)
                assertTrue(User32.INSTANCE.IsWindowVisible(hwnd))
                assertFalse(
                    hwnd == User32.INSTANCE.GetForegroundWindow(),
                    "The retained frame must not take keyboard focus",
                )
                assertFalse(User32.INSTANCE.IsWindowVisible(WinDef.HWND(Native.getComponentPointer(window))))
                // The source window can leave composition while held. Its retained frame
                // must not inherit that absence, even though its source WGC picture is valid.
                onEdt { assertTrue(setWindowsWindowCloaked(window, true)) }
                val hidden = com.sun.jna.ptr.IntByReference()
                assertEquals(
                    0,
                    NativeLibrary.getInstance("dwmapi").getFunction("DwmGetWindowAttribute", Function.ALT_CONVENTION)
                        .invokeInt(arrayOf(hwnd, 14, hidden, Int.SIZE_BYTES)),
                )
                assertEquals(
                    0,
                    hidden.value,
                    "The retained frame must remain composed when the source is temporarily absent",
                )
                val rect = captureRect(hwnd)
                val frozen = capture(Pointer.nativeValue(hwnd.pointer), checkEveryFrame = true)
                onEdt { assertTrue(setWindowsWindowCloaked(window, false)) }
                val output = Path.of("build/reports/reader-retained-frame")
                Files.createDirectories(output)
                writePixels(source, output.resolve("source-$fullscreen.png"))
                writePixels(frozen, output.resolve("retained-$fullscreen.png"))
                val red = frozen.redRows()
                println(
                    "Retained $fullscreen: sourceTop=$screenRedTop, retainedTop=${rect.top + red.first()}, physicalHeight=${red.size}",
                )
                // Native inset rasterization can round by one physical pixel at 150% DPI.
                // Preserve physical comic height exactly, without scaling its pan or center.
                assertTrue(
                    kotlin.math.abs(screenRedTop - rect.top - red.first()) < kotlin.math.ceil(scale.scaleY),
                    "Retaining a frame must preserve its screen position within native DPI rounding",
                )
                assertEquals(sourceRed.size, red.size, "The retained comic must preserve its physical pixel height")
                assertEquals(0, frozen.whiteSamples(), "The retained black reader background must not be white")
                onEdt { pending.removeAt(0).run() }
                // WWindowPeer destroys the native peer on the toolkit thread after dispose returns.
                val disposedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (User32.INSTANCE.IsWindow(hwnd) && System.nanoTime() < disposedDeadline) Thread.sleep(5)
                assertFalse(
                    User32.INSTANCE.IsWindow(hwnd),
                    "Only the final presented frame may release the retained surface",
                )
                assertFalse(window.isActive)
            }
            for (protected in listOf(true, false)) {
                onEdt {
                    val owner = WinDef.HWND(Native.getComponentPointer(window))
                    if (protected) {
                        assertEquals(
                            1,
                            NativeLibrary.getInstance(
                                "user32",
                            ).getFunction("SetWindowDisplayAffinity", Function.ALT_CONVENTION)
                                .invokeInt(arrayOf(owner, 0x11)),
                        )
                    } else {
                        assertTrue(setWindowsWindowCloaked(window, true))
                    }
                    try {
                        for (fullscreen in listOf(true, false)) {
                            controller.syncPlacement(fullscreen)
                            pending.removeAt(0).run()
                            assertFalse(
                                window.ownedWindows.any {
                                    it.isDisplayable
                                },
                                "Protected or cloaked content must not be copied to an unprotected surface",
                            )
                            assertEquals(null, retainedHandle(window))
                            pending.removeAt(0).run()
                        }
                    } finally {
                        if (protected) {
                            assertEquals(
                                1,
                                NativeLibrary.getInstance(
                                    "user32",
                                ).getFunction("SetWindowDisplayAffinity", Function.ALT_CONVENTION)
                                    .invokeInt(arrayOf(owner, 0)),
                            )
                        } else {
                            assertTrue(setWindowsWindowCloaked(window, false))
                        }
                    }
                }
            }
        } finally {
            onEdt {
                pending.toList().forEach(Runnable::run)
                window.ownedWindows.forEach { it.dispose() }
                window.dispose()
            }
        }
    }

    private fun captureRect(hwnd: WinDef.HWND): WinDef.RECT {
        val rect = WinDef.RECT()
        val result = NativeLibrary.getInstance("dwmapi").getFunction("DwmGetWindowAttribute", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(hwnd, 9, rect, rect.size()))
        assertEquals(0, result)
        rect.read()
        return rect
    }

    private fun retainedHandle(window: ComposeWindow): WinDef.HWND? {
        val root = Pointer.nativeValue(Native.getComponentPointer(window))
        return User32.INSTANCE.FindWindow(null, "MihonReaderTransitionFrame:$root")
            ?: window.ownedWindows.firstOrNull { it.name == "MihonReaderTransitionFrame" && it.isDisplayable }
                ?.let { WinDef.HWND(Native.getComponentPointer(it)) }
    }

    private data class Pixels(val width: Int, val height: Int, val bgra: ByteArray) {
        fun redRows(): List<Int> = (0 until height).filter { y ->
            val i = (y * width + width / 2) * 4
            (bgra[i + 2].toInt() and 255) > 200 && bgra[i].toInt() == 0 && bgra[i + 1].toInt() == 0
        }
        fun whiteSamples(): Int = (0 until width * height step 8).count { i ->
            (0..2).all { channel -> (bgra[i * 4 + channel].toInt() and 255) > 235 }
        }
    }

    private fun writePixels(pixels: Pixels, path: Path) {
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        val rgba = IntArray(pixels.width * pixels.height) { i ->
            ((pixels.bgra[i * 4 + 3].toInt() and 255) shl 24) or
                ((pixels.bgra[i * 4 + 2].toInt() and 255) shl 16) or
                ((pixels.bgra[i * 4 + 1].toInt() and 255) shl 8) or (pixels.bgra[i * 4].toInt() and 255)
        }
        image.setRGB(0, 0, pixels.width, pixels.height, rgba, 0, pixels.width)
        ImageIO.write(image, "png", path.toFile())
    }

    private fun capture(hwnd: Long, checkEveryFrame: Boolean = false): Pixels {
        val helper = Path.of("artifacts/diagnostics/compositor-native/reader-compositor.exe").toAbsolutePath()
        val process = ProcessBuilder(
            helper.toString(),
            "0x${hwnd.toString(16)}",
            ProcessHandle.current().pid().toString(),
            "0.2",
        )
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val input = DataInputStream(process.inputStream)
            var first: Pixels? = null
            while (true) {
                val magic = try {
                    Integer.reverseBytes(input.readInt())
                } catch (_: java.io.EOFException) {
                    break
                }
                assertEquals(0x4d574743, magic)
                val width = Integer.reverseBytes(input.readInt())
                val height = Integer.reverseBytes(input.readInt())
                assertEquals(width, Integer.reverseBytes(input.readInt()))
                assertEquals(height, Integer.reverseBytes(input.readInt()))
                input.readLong()
                check(width in 1..4096 && height in 1..4096)
                val pixels = ByteArray(width * height * 4)
                input.readFully(pixels)
                val actual = Pixels(width, height, pixels)
                if (checkEveryFrame) {
                    assertTrue(actual.redRows().isNotEmpty(), "Every retained frame must contain the comic")
                    assertEquals(0, actual.whiteSamples(), "No retained frame may have a white block")
                }
                if (first == null) first = actual
            }
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return requireNotNull(first) { "The real owned window must provide a compositor frame" }
        } finally {
            process.inputStream.close()
            if (process.isAlive) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
