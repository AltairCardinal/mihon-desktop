package mihon.desktop.sync

import java.io.File
import java.awt.Desktop
import mihon.data.sync.runtime.SyncDiagnosticFiles
import mihon.desktop.platform.DesktopExternalActionPolicy

internal object DesktopSyncDiagnosticOpener {
    fun open(path: String, directory: String?, launcher: (File) -> Unit = ::openWithSystem): Boolean = try {
        val file = SyncDiagnosticFiles.exportFile(path, directory)
        if (file == null) false else { launcher(file); true }
    } catch (_: Exception) { false }

    private fun openWithSystem(file: File) {
        DesktopExternalActionPolicy.requireAllowed("Sync diagnostic viewer")
        check(Desktop.isDesktopSupported())
        Desktop.getDesktop().open(file)
    }
}
