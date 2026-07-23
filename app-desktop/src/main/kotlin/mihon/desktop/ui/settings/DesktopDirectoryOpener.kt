package mihon.desktop.ui.settings

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
        check(System.getProperty("org.gradle.test.worker") == null) {
            "System file manager is disabled in Gradle test workers"
        }
        check(Desktop.isDesktopSupported()) { "Desktop API is not supported" }
        Desktop.getDesktop().open(directory)
    }
}
