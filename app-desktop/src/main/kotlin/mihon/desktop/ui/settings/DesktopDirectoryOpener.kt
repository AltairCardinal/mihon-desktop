package mihon.desktop.ui.settings

import mihon.desktop.platform.DesktopExternalActionPolicy
import java.awt.Desktop
import java.io.File

fun interface DesktopDirectoryOpenPort {
    fun open(directory: File): DesktopDirectoryOpenResult
}

sealed interface DesktopDirectoryOpenResult {
    data object Opened : DesktopDirectoryOpenResult
    data object Rejected : DesktopDirectoryOpenResult
    data class Failed(val error: Throwable) : DesktopDirectoryOpenResult
}

object DesktopDirectoryOpener {

    fun open(
        directory: File,
        launcher: (File) -> Unit = ::openWithSystemFileManager,
    ): Boolean = openResult(directory, launcher) is DesktopDirectoryOpenResult.Opened

    fun openResult(
        directory: File,
        launcher: (File) -> Unit = ::openWithSystemFileManager,
    ): DesktopDirectoryOpenResult = try {
        if (!directory.isDirectory && !directory.mkdirs()) {
            DesktopDirectoryOpenResult.Rejected
        } else if (!directory.isDirectory) {
            DesktopDirectoryOpenResult.Rejected
        } else {
            launcher(directory)
            DesktopDirectoryOpenResult.Opened
        }
    } catch (error: Exception) {
        DesktopDirectoryOpenResult.Failed(error)
    }

    private fun openWithSystemFileManager(directory: File) {
        DesktopExternalActionPolicy.requireAllowed("System file manager")
        check(Desktop.isDesktopSupported()) { "Desktop API is not supported" }
        Desktop.getDesktop().open(directory)
    }
}
