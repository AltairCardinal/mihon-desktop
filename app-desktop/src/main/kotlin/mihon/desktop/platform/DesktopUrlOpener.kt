package mihon.desktop.platform

import java.awt.Desktop
import java.net.URI

object DesktopUrlOpener {

    fun open(
        url: String,
        launcher: (URI) -> Unit = ::openWithSystemBrowser,
    ): Result<Unit> = runCatching {
        val uri = URI(url)
        launcher(uri)
    }

    private fun openWithSystemBrowser(uri: URI) {
        DesktopExternalActionPolicy.requireAllowed("System browser")
        check(Desktop.isDesktopSupported()) { "Desktop API is not supported" }
        val desktop = Desktop.getDesktop()
        check(desktop.isSupported(Desktop.Action.BROWSE)) { "Desktop browse action is not supported" }
        desktop.browse(uri)
    }
}
