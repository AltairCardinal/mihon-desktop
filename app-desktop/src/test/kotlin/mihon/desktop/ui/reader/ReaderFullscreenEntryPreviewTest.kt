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
import mihon.desktop.platform.DesktopFullscreenAdapter
import mihon.desktop.platform.DesktopWindowTransitions
import mihon.desktop.platform.WindowsBorderlessFullscreenAdapter
import mihon.desktop.platform.WindowsWindowTransitions
import mihon.desktop.platform.setWindowsWindowCloaked
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
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
class ReaderFullscreenEntryPreviewTest {
    @Test
    fun `entry retains the rendered comic above native resize until the fullscreen frame is ready`() {
        val window = onEdt {
            ComposeWindow().apply {
                setBounds(100, 100, 640, 480)
                focusableWindowState = false
                isAutoRequestFocus = false
                setContent {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(180.dp).background(Color.Red))
                    }
                }
                addNotify()
                assertTrue(setWindowsWindowCloaked(this, true))
                isVisible = true
            }
        }
        val completed = CountDownLatch(1)
        val native = WindowsBorderlessFullscreenAdapter()
        var beforeResize: Pair<Int, Int>? = null
        var afterResize: Pair<Int, Int>? = null
        val adapter = object : DesktopFullscreenAdapter {
            override fun enter(owner: Window): Boolean {
                beforeResize = previewColors(owner)
                val entered = native.enter(owner)
                window.setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                window.renderImmediately()
                afterResize = previewColors(owner)
                return entered
            }
            override fun exit(owner: Window) = native.exit(owner)
        }
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            fullscreenAdapter = adapter,
            transitions = DesktopWindowTransitions { frame, disabled ->
                val result = WindowsWindowTransitions.setDisabled(frame, disabled)
                if (!disabled) completed.countDown()
                result
            },
        )
        try {
            Thread.sleep(100)
            onEdt {
                window.renderImmediately()
                controller.syncPlacement(true)
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val expected = java.awt.Color.RED.rgb to java.awt.Color.BLACK.rgb
            assertEquals(expected, beforeResize, "The last comic frame must cover the resize before it starts")
            assertEquals(expected, afterResize, "The old comic must survive replacement of the native canvas")
            onEdt {
                assertFalse(window.isActive)
                assertTrue(window.ownedWindows.none { it.name == PREVIEW_NAME && it.isDisplayable })
            }
        } finally {
            onEdt {
                controller.onDispose(wasFullscreen = true)
                window.dispose()
            }
        }
    }

    private fun previewColors(owner: Window): Pair<Int, Int>? {
        val preview = owner.ownedWindows.singleOrNull { it.name == PREVIEW_NAME && it.isShowing } as? JWindow
            ?: return null
        assertFalse(preview.isFocusableWindow)
        val image = BufferedImage(preview.width, preview.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        return try {
            preview.contentPane.paint(graphics)
            image.getRGB(image.width / 2, image.height / 2) to image.getRGB(0, 0)
        } finally {
            graphics.dispose()
            image.flush()
        }
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
