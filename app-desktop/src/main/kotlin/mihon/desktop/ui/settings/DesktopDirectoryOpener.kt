package mihon.desktop.ui.settings

import mihon.desktop.platform.DesktopExternalActionPolicy
import java.awt.Desktop
import java.io.File

object DesktopDirectoryOpener {

    fun open(
        directory: File,
        launcher: (File) -> Unit = ::openWithSystemFileManager,
    ): Boolean {
        directory.mkdirs()
        return runCatching {
            launcher(directory)
            true
        }.getOrDefault(false)
    }

    private fun openWithSystemFileManager(directory: File) {
        DesktopExternalActionPolicy.requireAllowed("System file manager")
        check(Desktop.isDesktopSupported()) { "Desktop API is not supported" }
        Desktop.getDesktop().open(directory)
    }
}
