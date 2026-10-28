package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import mihon.desktop.platform.DesktopWindowTransitions
import mihon.desktop.platform.WindowsWindowTransitions
import mihon.desktop.platform.setWindowsWindowCloaked
import mihon.desktop.reader.ZoomState
import org.jetbrains.skiko.SkiaLayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Container
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JWindow
import javax.swing.SwingUtilities

@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReaderFullscreenNativeImagePositionTest {
    @Test
    fun `entry keeps a panned comic at its final vertical position without a rescaled intermediate frame`() {
        for (offsetY in listOf(-40f, 0f, 40f)) assertFullscreenPosition(offsetY)
    }

    private fun assertFullscreenPosition(offsetY: Float) {
        val window = onEdt {
            ComposeWindow().apply {
                setBounds(100, 100, 640, 480)
                focusableWindowState = false
                isAutoRequestFocus = false
                setContent {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(180.dp)
                                .readerImageTransform(ZoomState(scale = 1.35f, offsetY = offsetY), null)
                                .background(Color.Red),
                        )
                    }
                }
                addNotify()
                assertTrue(setWindowsWindowCloaked(this, true))
                isVisible = true
            }
        }
        val completed = CountDownLatch(1)
        var presentedY: Double? = null
        var intermediateY: Double? = null
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            transitions = DesktopWindowTransitions { frame, disabled ->
                if (!disabled && presentedY == null) {
                    presentedY = comicCenter(window)
                    intermediateY = previewCenter(window) ?: presentedY
                }
                WindowsWindowTransitions.setDisabled(frame, disabled).also {
                    if (!disabled) completed.countDown()
                }
            },
        )
        try {
            Thread.sleep(100)
            onEdt {
                window.renderImmediately()
                controller.syncPlacement(true)
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val settledY = onEdt {
                window.renderImmediately()
                assertFalse(window.isActive)
                comicCenter(window)
            }
            val expectedY = window.graphicsConfiguration.bounds.height / 2.0 - 0.5 +
                offsetY / window.graphicsConfiguration.defaultTransform.scaleY
            assertEquals(expectedY, settledY, 2.0, "Fullscreen must preserve the actual reader pan")
            assertEquals(
                settledY,
                requireNotNull(presentedY),
                2.0,
                "The first fullscreen frame must use final geometry",
            )
            assertEquals(
                settledY,
                requireNotNull(intermediateY),
                2.0,
                "An independently scaled snapshot must not move the comic up before the live frame returns",
            )
        } finally {
            onEdt {
                controller.onDispose(wasFullscreen = true)
                window.dispose()
            }
        }
    }

    private fun comicCenter(window: ComposeWindow): Double {
        fun findLayer(container: Container): SkiaLayer? {
            if (container is SkiaLayer) return container
            return container.components.filterIsInstance<Container>().firstNotNullOfOrNull(::findLayer)
        }
        val layer = requireNotNull(findLayer(window))
        val bitmap = requireNotNull(layer.screenshot())
        return try {
            val center = redCenter(bitmap.width, bitmap.height, bitmap::getColor)
            layer.locationOnScreen.y - window.graphicsConfiguration.bounds.y + center * layer.height / bitmap.height
        } finally {
            bitmap.close()
        }
    }

    private fun previewCenter(owner: Window): Double? {
        val preview = owner.ownedWindows.singleOrNull { it.name == PREVIEW_NAME && it.isShowing } as? JWindow
            ?: return null
        val image = BufferedImage(preview.width, preview.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        return try {
            preview.contentPane.paint(graphics)
            preview.y - owner.graphicsConfiguration.bounds.y + redCenter(image.width, image.height, image::getRGB)
        } finally {
            graphics.dispose()
            image.flush()
        }
    }

    private fun redCenter(width: Int, height: Int, pixel: (Int, Int) -> Int): Double {
        val rows = (0 until height).filter { pixel(width / 2, it) == java.awt.Color.RED.rgb }
        assertTrue(rows.isNotEmpty(), "The actual comic pixels must remain available")
        return (rows.first() + rows.last()) / 2.0
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }

    private companion object {
        const val PREVIEW_NAME = "MihonReaderFullscreenEntryPreview"
    }
}
