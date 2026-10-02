package mihon.desktop.platform

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.WString
import java.io.File
import java.io.IOException

/** Same-parent native rename that atomically refuses an occupied destination. */
object DesktopMigrationFileMove {
    fun move(source: File, target: File) {
        require(source.absoluteFile.parentFile == target.absoluteFile.parentFile) {
            "Migration staging must remain in the original filesystem directory"
        }
        val success = when (OperatingSystem.detect()) {
            OperatingSystem.WINDOWS -> NativeLibrary.getInstance("kernel32")
                .getFunction("MoveFileW", com.sun.jna.Function.ALT_CONVENTION)
                .invokeInt(arrayOf(WString(source.absolutePath), WString(target.absolutePath))) != 0
            OperatingSystem.MACOS -> NativeLibrary.getInstance("c")
                .getFunction("renamex_np")
                .invokeInt(arrayOf(source.absolutePath, target.absolutePath, 0x00000004)) == 0
            else -> throw IOException("Atomic migration staging is unavailable on this platform")
        }
        if (!success) {
            throw IOException(
                "Migration file could not be moved without replacement (${Native.getLastError()})",
            )
        }
    }
}
