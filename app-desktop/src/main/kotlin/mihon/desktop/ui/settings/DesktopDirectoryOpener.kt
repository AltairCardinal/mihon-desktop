package mihon.desktop.ui.settings

import java.awt.Desktop
import java.io.File

internal object DesktopDirectoryOpener {

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
        check(Desktop.isDesktopSupported()) { "Desktop API is not supported" }
        Desktop.getDesktop().open(directory)
    }
}
