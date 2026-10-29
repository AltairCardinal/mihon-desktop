package mihon.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private const val WS_EX_NOACTIVATE = 0x08000000
private const val WS_EX_TOOLWINDOW = 0x00000080

/** One immutable bitmap, independent of the source HWND and of Swing's repaint/resize machinery. */
internal fun retainNativeReaderPixels(
    owner: WinDef.HWND,
    cover: Rectangle,
    origin: WinDef.RECT,
    image: BufferedImage,
    onScreen: Boolean,
): AutoCloseable? {
    val stop = AtomicBoolean()
    val ready = CompletableFuture<Unit>()
    val worker = Thread({
        val native = NativeLibrary.getInstance("user32")
        val dpi = native.getFunction("SetThreadDpiAwarenessContext", Function.ALT_CONVENTION)
        val previousDpi = dpi.invokePointer(arrayOf(Pointer.createConstant(-4L)))
        var surface: WinDef.HWND? = null
        try {
            NativeReaderFrameWindow.registered
            surface = requireNotNull(
                User32.INSTANCE.CreateWindowEx(
                    WinUser.WS_EX_LAYERED or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW or
                        WinUser.WS_EX_TRANSPARENT,
                    NativeReaderFrameWindow.className,
                    "MihonReaderTransitionFrame:${Pointer.nativeValue(owner.pointer)}",
                    WinUser.WS_POPUP,
                    cover.x, cover.y, cover.width, cover.height,
                    null, null, NativeReaderFrameWindow.instance, null,
                ),
            ) { "CreateWindowEx failed: ${Native.getLastError()}" }
            uploadRetainedPixels(surface, cover, origin, image)
            if (stop.get() || !User32.INSTANCE.IsWindow(owner)) return@Thread
            check(!onScreen || User32.INSTANCE.GetForegroundWindow() == owner) { "Reader is no longer foreground" }
            // An owned popup inherits the source's composition absence. This unowned,
            // nonactivating surface remains above it only for this bounded transition.
            check(
                User32.INSTANCE.SetWindowPos(
                    surface,
                    WinDef.HWND(Pointer.createConstant(-1L)),
                    cover.x,
                    cover.y,
                    cover.width,
                    cover.height,
                    WinUser.SWP_NOACTIVATE or WinUser.SWP_SHOWWINDOW or WinUser.SWP_NOOWNERZORDER or
                        WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE,
                ),
            )
            NativeLibrary.getInstance("dwmapi").getFunction("DwmFlush", Function.ALT_CONVENTION).invokeInt(emptyArray())
            ready.complete(Unit)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            val message = WinUser.MSG()
            val affinity = IntByReference()
            val queryAffinity = native.getFunction("GetWindowDisplayAffinity", Function.ALT_CONVENTION)
            val redrawProperty = native.getFunction("GetPropW", Function.ALT_CONVENTION)
            while (!stop.get() && System.nanoTime() < deadline && User32.INSTANCE.IsWindow(owner)) {
                while (User32.INSTANCE.PeekMessage(message, null, 0, 0, 1)) {
                    User32.INSTANCE.TranslateMessage(message)
                    User32.INSTANCE.DispatchMessage(message)
                }
                val active = User32.INSTANCE.GetForegroundWindow()
                if (onScreen && active != null && active != owner) break
                if (queryAffinity.invokeInt(arrayOf(owner, affinity)) == 0 || affinity.value != 0) break
                val held = redrawProperty.invokePointer(arrayOf(owner, WString("SysSetRedraw"))) != null
                if (!User32.INSTANCE.IsWindowVisible(owner) && !held) break
                Thread.sleep(5)
            }
        } catch (failure: Throwable) {
            ready.completeExceptionally(failure)
        } finally {
            surface?.let { User32.INSTANCE.DestroyWindow(it) }
            image.flush()
            if (previousDpi != null) dpi.invokePointer(arrayOf(previousDpi))
            ready.completeExceptionally(IllegalStateException("Reader frame closed before presentation"))
        }
    }, "reader-transition-frame").apply { isDaemon = true }
    worker.start()
    return try {
        ready.get(2, TimeUnit.SECONDS)
        AutoCloseable {
            stop.set(true)
            worker.join(2500)
            check(!worker.isAlive) { "Reader transition frame did not close" }
        }
    } catch (failure: Throwable) {
        stop.set(true)
        worker.join(2500)
        val cause = failure.cause ?: failure
        System.err.println("Reader transition frame unavailable: ${cause.javaClass.simpleName}: ${cause.message}")
        null
    }
}

private fun uploadRetainedPixels(surface: WinDef.HWND, cover: Rectangle, origin: WinDef.RECT, image: BufferedImage) {
    val gdi = GDI32.INSTANCE
    val dc = requireNotNull(gdi.CreateCompatibleDC(null))
    var bitmap: WinDef.HBITMAP? = null
    var previous: WinNT.HANDLE? = null
    try {
        val info = WinGDI.BITMAPINFO().apply {
            bmiHeader.biSize = bmiHeader.size()
            bmiHeader.biWidth = cover.width
            bmiHeader.biHeight = -cover.height
            bmiHeader.biPlanes = 1
            bmiHeader.biBitCount = 32
            bmiHeader.biCompression = WinGDI.BI_RGB
        }
        val pointer = PointerByReference()
        bitmap = requireNotNull(gdi.CreateDIBSection(dc, info, WinGDI.DIB_RGB_COLORS, pointer, null, 0))
        previous = requireNotNull(gdi.SelectObject(dc, bitmap))
        val destination = requireNotNull(pointer.value)
        val row = IntArray(cover.width) { image.getRGB(0, 0) or (0xff shl 24) }
        repeat(cover.height) { y -> destination.write(y.toLong() * cover.width * 4, row, 0, row.size) }
        val dx = origin.left - cover.x
        val dy = origin.top - cover.y
        val sourceX = max(0, -dx)
        val sourceY = max(0, -dy)
        val destinationX = max(0, dx)
        val destinationY = max(0, dy)
        val width = min(image.width - sourceX, cover.width - destinationX)
        val height = min(image.height - sourceY, cover.height - destinationY)
        val source = (image.raster.dataBuffer as DataBufferInt).data
        if (width > 0 && height > 0) {
            repeat(height) { y ->
                destination.write(
                    ((destinationY + y).toLong() * cover.width + destinationX) * 4,
                    source,
                    (sourceY + y) * image.width + sourceX,
                    width,
                )
            }
        }
        val blend = WinUser.BLENDFUNCTION().apply {
            SourceConstantAlpha = 255.toByte()
            AlphaFormat = 1
        }
        check(
            User32.INSTANCE.UpdateLayeredWindow(
                surface, null, WinDef.POINT(cover.x, cover.y), WinUser.SIZE(cover.width, cover.height),
                dc, WinDef.POINT(), 0, blend, 2,
            ),
        )
    } finally {
        previous?.let { gdi.SelectObject(dc, it) }
        bitmap?.let { gdi.DeleteObject(it) }
        gdi.DeleteDC(dc)
    }
}

/** Its own message thread never clears the bitmap on source-window resize, erase or paint events. */
private object NativeReaderFrameWindow {
    val className = "MihonReaderTransitionPixels"
    val instance = Kernel32.INSTANCE.GetModuleHandle(null)
    private val procedure = WinUser.WindowProc { hwnd, message, wParam, lParam ->
        when (message) {
            0x0014 -> WinDef.LRESULT(1) // WM_ERASEBKGND: the immutable bitmap already covers it.
            0x000F -> { // WM_PAINT: validate damage without overwriting the retained pixels.
                NativeLibrary.getInstance("user32").getFunction("ValidateRect", Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(hwnd, null))
                WinDef.LRESULT(0)
            }
            0x0084 -> WinDef.LRESULT(-1) // HTTRANSPARENT: never intercept reader input.
            else -> User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
        }
    }
    val registered = User32.INSTANCE.RegisterClassEx(
        WinUser.WNDCLASSEX().apply {
            cbSize = size()
            lpszClassName = className
            hInstance = instance
            lpfnWndProc = procedure
        },
    ).also { check(it.toInt() != 0) { "RegisterClassEx failed: ${Native.getLastError()}" } }
}
