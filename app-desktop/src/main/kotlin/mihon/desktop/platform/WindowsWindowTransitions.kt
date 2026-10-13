package mihon.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
import java.awt.Window

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
