package mihon.desktop.sync

import java.io.File
import java.awt.Desktop
import mihon.desktop.platform.DesktopExternalActionPolicy

internal object DesktopSyncFailureLogOpener {
    fun open(path: String,
        directory: String? = mihon.desktop.platform.DesktopPlatformPaths.current(false).configDir.resolve("sync-failures").path,
        launcher: (File) -> Unit = ::openWithSystem): Boolean = try {
        val file = File(path).canonicalFile
        if (directory == null || file.parentFile != File(directory).canonicalFile ||
            !file.isFile || file.length() > 256 * 1024 || !file.extension.equals("txt", ignoreCase = true)) {
            false
        } else {
            launcher(file)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun openWithSystem(file: File) {
        DesktopExternalActionPolicy.requireAllowed("Sync failure log viewer")
        check(Desktop.isDesktopSupported())
        Desktop.getDesktop().open(file)
    }
}
