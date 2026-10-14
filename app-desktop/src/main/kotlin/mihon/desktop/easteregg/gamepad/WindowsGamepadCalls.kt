package mihon.desktop.easteregg.gamepad

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/** XINPUT_GAMEPAD.wButtons masks from the Win32 XInput gaming structures. */
internal object XInputGamepadButtons {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val LEFT_THUMB = 0x0020
    const val RIGHT_SHOULDER = 0x0100
    const val A = 0x1000
    const val B = 0x2000

    const val ERROR_SUCCESS = 0
    const val ERROR_DEVICE_NOT_CONNECTED = 1167
}

internal fun interface WindowsGamepadCalls {
    fun snapshot(userIndex: Int): GamepadSnapshot?
}

/**
 * Reads the first XInput controller. A missing or disconnected pad is not an error here, it simply
 * reports nothing. The native buffer is allocated once because this is polled while the page is
 * open, and the gamepad block is bound to it explicitly instead of through a nested structure.
 */
internal class WindowsNativeGamepadCalls(
    private val api: () -> WindowsXInputApi = ::loadWindowsXInputApi,
    buffer: Pointer = Memory(XInputState().size().toLong()),
) : WindowsGamepadCalls {
    private val buffer = buffer
    private val gamepad = Structure.newInstance(
        XInputGamepad::class.java,
        buffer.share(Int.SIZE_BYTES.toLong()),
    )

    override fun snapshot(userIndex: Int): GamepadSnapshot? {
        if (api().XInputGetState(userIndex, buffer) != XInputGamepadButtons.ERROR_SUCCESS) return null
        gamepad.autoRead()
        return GamepadSnapshot(
            buttons = gamepad.buttons.toInt() and 0xFFFF,
            leftX = gamepad.thumbLX.toInt(),
            leftY = gamepad.thumbLY.toInt(),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
internal fun interface WindowsXInputApi : StdCallLibrary {
    fun XInputGetState(userIndex: Int, state: Pointer): Int
}

@Structure.FieldOrder("buttons", "leftTrigger", "rightTrigger", "thumbLX", "thumbLY", "thumbRX", "thumbRY")
internal class XInputGamepad : Structure() {
    @JvmField var buttons: Short = 0

    @JvmField var leftTrigger: Byte = 0

    @JvmField var rightTrigger: Byte = 0

    @JvmField var thumbLX: Short = 0

    @JvmField var thumbLY: Short = 0

    @JvmField var thumbRX: Short = 0

    @JvmField var thumbRY: Short = 0
}

@Structure.FieldOrder("packetNumber", "gamepad")
internal class XInputState : Structure() {
    @JvmField var packetNumber: Int = 0

    @JvmField var gamepad: XInputGamepad = XInputGamepad()
}

private val XINPUT_LIBRARIES = listOf("XInput1_4", "XInput1_3", "XInput9_1_0")

private fun loadWindowsXInputApi(): WindowsXInputApi {
    var lastFailure: String? = null
    for (library in XINPUT_LIBRARIES) {
        val loaded = try {
            Native.load(library, WindowsXInputApi::class.java)
        } catch (failure: Throwable) {
            lastFailure = failure.toString()
            null
        }
        if (loaded != null) return loaded
    }
    throw UnsatisfiedLinkError("No XInput runtime found; tried ${XINPUT_LIBRARIES.joinToString()}: $lastFailure")
}
