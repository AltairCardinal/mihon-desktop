package mihon.desktop.platform

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Platform
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.SkiaLayer
import java.awt.Color
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JComponent
import javax.swing.JWindow
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the last rendered reader frame visible while its GPU canvas is resized. */
internal fun showReaderFullscreenEntryPreview(window: ComposeWindow): AutoCloseable? {
    if (!Platform.isWindows() || GraphicsEnvironment.isHeadless() || !window.isShowing) return null
    val image = runCatching { captureReaderFrame(window) }.getOrNull() ?: return null
    val panel = FullscreenEntryImage(image)
    var preview: JWindow? = null
    return try {
        val surface = JWindow(window, window.graphicsConfiguration).apply {
            name = "MihonReaderFullscreenEntryPreview"
            focusableWindowState = false
            isAutoRequestFocus = false
            contentPane = panel
            bounds = window.graphicsConfiguration.bounds
            addNotify()
        }
        preview = surface
        check(setWindowsWindowCloaked(surface, true)) { "Entry preview could not be prepared invisibly" }
        WindowsWindowTransitions.setDisabled(surface, true)
        surface.isVisible = true
        surface.validate()
        panel.paintImmediately(0, 0, panel.width, panel.height)
        Toolkit.getDefaultToolkit().sync()
        // Background/native geometry tests keep their owner inactive and the preview cloaked.
        // A foreground reader reveals only the already-painted snapshot, without taking focus.
        if (window.isActive) {
            check(setWindowsWindowCloaked(surface, false)) { "Entry preview could not be revealed" }
            WindowsWindowTransitions.setDisabled(surface, false)
        }
        AutoCloseable {
            surface.dispose()
            panel.release()
        }
    } catch (_: Throwable) {
        preview?.dispose()
        panel.release()
        null
    }
}

private fun captureReaderFrame(window: ComposeWindow): BufferedImage? {
    fun findLayer(container: Container): SkiaLayer? {
        if (container is SkiaLayer) return container
        return container.components.filterIsInstance<Container>().firstNotNullOfOrNull(::findLayer)
    }
    val bitmap = findLayer(window)?.screenshot() ?: return null
    return try {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val info = ImageInfo(bitmap.width, bitmap.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bytes = bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: return null
        BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB_PRE).apply {
            val pixels = (raster.dataBuffer as DataBufferInt).data
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
        }
    } finally {
        bitmap.close()
    }
}

private class FullscreenEntryImage(private var image: BufferedImage?) : JComponent() {
    init {
        isOpaque = true
    }

    override fun paintComponent(graphics: Graphics) {
        val frame = image ?: return
        val draw = graphics.create() as Graphics2D
        try {
            draw.color = Color(frame.getRGB(0, 0))
            draw.fillRect(0, 0, width, height)
            val scale = min(width.toDouble() / frame.width, height.toDouble() / frame.height)
            val targetWidth = (frame.width * scale).roundToInt()
            val targetHeight = (frame.height * scale).roundToInt()
            draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            draw.drawImage(
                frame,
                (width - targetWidth) / 2,
                (height - targetHeight) / 2,
                targetWidth,
                targetHeight,
                null,
            )
        } finally {
            draw.dispose()
        }
    }

    fun release() {
        image?.flush()
        image = null
    }
}
