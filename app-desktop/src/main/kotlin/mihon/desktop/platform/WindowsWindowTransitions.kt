package mihon.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
import java.awt.Window

private const val DWMWA_CLOAK = 13

internal fun interface DesktopWindowTransitions {
    fun setDisabled(window: Window, disabled: Boolean): Boolean
}

internal object WindowsWindowTransitions : DesktopWindowTransitions {
    private const val DWMWA_TRANSITIONS_FORCEDISABLED = 3

    override fun setDisabled(window: Window, disabled: Boolean): Boolean = runCatching {
        val dwm = NativeLibrary.getInstance("dwmapi")
        // Finish presenting the initial window before restoring ordinary user-triggered transitions.
        if (!disabled) dwm.getFunction("DwmFlush", Function.ALT_CONVENTION).invokeInt(emptyArray())
        dwm.getFunction("DwmSetWindowAttribute", Function.ALT_CONVENTION).invokeInt(
            arrayOf(
                Native.getComponentPointer(window),
                DWMWA_TRANSITIONS_FORCEDISABLED,
                IntByReference(if (disabled) 1 else 0),
                Int.SIZE_BYTES,
            ),
        ) >= 0
    }.getOrDefault(false)
}

/** Keep the native window rendering, but do not expose its provisional first frame to the user. */
internal fun setWindowsWindowCloaked(window: Window, cloaked: Boolean): Boolean = runCatching {
    val dwm = NativeLibrary.getInstance("dwmapi")
    if (!cloaked) dwm.getFunction("DwmFlush", Function.ALT_CONVENTION).invokeInt(emptyArray())
    dwm.getFunction("DwmSetWindowAttribute", Function.ALT_CONVENTION).invokeInt(
        arrayOf(
            Native.getComponentPointer(window),
            DWMWA_CLOAK,
            IntByReference(if (cloaked) 1 else 0),
            Int.SIZE_BYTES,
        ),
    ) >= 0
}.getOrDefault(false)
