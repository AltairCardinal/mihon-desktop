package mihon.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.SkiaLayer
import java.awt.Canvas
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    val onScreen = user32.getFunction("MonitorFromRect", Function.ALT_CONVENTION)
        .invokePointer(arrayOf(nativeCover, 0)) != null
    if (onScreen && !window.isActive) return null
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
    return try {
        retainNativeReaderPixels(hwnd, cover, canvasRect, image, onScreen)
    } catch (failure: Throwable) {
        image.flush()
        System.err.println("Reader transition frame unavailable: ${failure.javaClass.simpleName}")
        null
    }
}
