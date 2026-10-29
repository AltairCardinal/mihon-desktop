package mihon.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.SkiaLayer
import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JComponent
import javax.swing.JWindow
import kotlin.math.roundToInt

/** Retains the old physical pixels; neither the image nor its pan is projected into the target viewport. */
internal fun retainWindowsReaderFrame(window: Window, canvas: Canvas, cover: Rectangle): AutoCloseable? {
    if (!Platform.isWindows() || !window.isShowing || !canvas.isDisplayable) return null
    if (cover.width <= 0 || cover.height <= 0 || cover.width.toLong() * cover.height * 4 > 64 * 1024 * 1024) return null
    val user32 = NativeLibrary.getInstance("user32")
    val hwnd = WinDef.HWND(Native.getComponentPointer(window))
    val affinity = IntByReference()
    // Do not copy protected reader content into another window, or reveal a background test window.
    if (user32.getFunction("GetWindowDisplayAffinity", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(hwnd, affinity)) == 0 || affinity.value != 0
    ) {
        return null
    }
    val cloaked = IntByReference()
    if (NativeLibrary.getInstance("dwmapi").getFunction("DwmGetWindowAttribute", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(hwnd, 14, cloaked, Int.SIZE_BYTES)) < 0 || cloaked.value != 0
    ) {
        return null
    }
    val nativeCover = WinDef.RECT().apply {
        left = cover.x
        top = cover.y
        right = cover.x + cover.width
        bottom = cover.y + cover.height
    }
    if (!window.isActive && user32.getFunction("MonitorFromRect", Function.ALT_CONVENTION)
            .invokePointer(arrayOf(nativeCover, 0)) != null
    ) {
        return null
    }
    val layer = generateSequence(canvas.parent) { it.parent }.filterIsInstance<SkiaLayer>().firstOrNull() ?: return null
    val image = layer.screenshot()?.use { bitmap ->
        if (bitmap.width <= 0 || bitmap.height <= 0 ||
            bitmap.width.toLong() * bitmap.height * 4 > 64 * 1024 * 1024
        ) {
            return null
        }
        val info = ImageInfo(bitmap.width, bitmap.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bytes = bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: return null
        BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB_PRE).apply {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                .get((raster.dataBuffer as DataBufferInt).data)
        }
    } ?: return null
    val frameRect = WinDef.RECT()
    val canvasRect = WinDef.RECT()
    if (!User32.INSTANCE.GetWindowRect(hwnd, frameRect) ||
        !User32.INSTANCE.GetWindowRect(WinDef.HWND(Native.getComponentPointer(canvas)), canvasRect)
    ) {
        image.flush()
        return null
    }
    val scale = window.graphicsConfiguration.defaultTransform
    val panel = RetainedReaderPixels(image, canvasRect.left, canvasRect.top)
    var retained: JWindow? = null
    return try {
        val surface = JWindow(window, window.graphicsConfiguration).apply {
            name = "MihonReaderTransitionFrame"
            focusableWindowState = false
            isAutoRequestFocus = false
            // A layered backing bitmap survives the owner's redraw hold; a normal GDI
            // window can have no retained pixels when its drawing region is clipped.
            background = Color(0, 0, 0, 0)
            contentPane = panel
            setBounds(
                window.x + ((cover.x - frameRect.left) / scale.scaleX).roundToInt(),
                window.y + ((cover.y - frameRect.top) / scale.scaleY).roundToInt(),
                (cover.width / scale.scaleX).roundToInt(),
                (cover.height / scale.scaleY).roundToInt(),
            )
            addNotify()
        }
        retained = surface
        check(setWindowsWindowCloaked(surface, true))
        WindowsWindowTransitions.setDisabled(surface, true)
        surface.isVisible = true
        surface.validate()
        val surfaceHwnd = WinDef.HWND(Native.getComponentPointer(surface))
        val actual = WinDef.RECT().also { check(User32.INSTANCE.GetWindowRect(surfaceHwnd, it)) }
        if (actual.left != cover.x || actual.top != cover.y ||
            actual.right - actual.left != cover.width || actual.bottom - actual.top != cover.height
        ) {
            check(
                User32.INSTANCE.SetWindowPos(
                    surfaceHwnd,
                    null,
                    cover.x,
                    cover.y,
                    cover.width,
                    cover.height,
                    WinUser.SWP_NOACTIVATE or WinUser.SWP_NOZORDER or WinUser.SWP_NOOWNERZORDER,
                ),
            )
        }
        panel.paintImmediately(0, 0, panel.width, panel.height)
        Toolkit.getDefaultToolkit().sync()
        check(setWindowsWindowCloaked(surface, false))
        // Ensure this independent surface has presented before the owner's GPU resize starts.
        WindowsWindowTransitions.setDisabled(surface, false)
        AutoCloseable {
            surface.dispose()
            panel.release()
        }
    } catch (failure: Throwable) {
        retained?.dispose()
        panel.release()
        System.err.println("Reader transition frame unavailable: ${failure.javaClass.simpleName}")
        null
    }
}

private class RetainedReaderPixels(
    private var image: BufferedImage?,
    private val screenX: Int,
    private val screenY: Int,
) : JComponent() {
    init {
        isOpaque = true
    }

    override fun paintComponent(graphics: Graphics) {
        val frame = image ?: return
        val surface = javax.swing.SwingUtilities.getWindowAncestor(this) ?: return
        val origin = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(WinDef.HWND(Native.getComponentPointer(surface)), origin)) return
        val draw = graphics.create() as Graphics2D
        try {
            draw.color = Color(frame.getRGB(0, 0))
            draw.fillRect(0, 0, width, height)
            val scale = draw.transform
            draw.translate((screenX - origin.left) / scale.scaleX, (screenY - origin.top) / scale.scaleY)
            draw.scale(1 / scale.scaleX, 1 / scale.scaleY)
            draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            draw.drawImage(frame, 0, 0, null)
        } finally {
            draw.dispose()
        }
    }

    fun release() {
        image?.flush()
        image = null
    }
}
