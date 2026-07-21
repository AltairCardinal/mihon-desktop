package mihon.desktop.platform

import java.awt.Desktop
import java.awt.GraphicsEnvironment

fun interface DesktopOpenUriEventPort {
    fun install(consumer: (String) -> Unit): DesktopOpenUriInstallResult
}

fun interface DesktopOpenUriRegistration : AutoCloseable {
    override fun close()
}

sealed interface DesktopOpenUriInstallResult {
    data class Installed(val registration: DesktopOpenUriRegistration) : DesktopOpenUriInstallResult

    data object Unsupported : DesktopOpenUriInstallResult

    data class Failed(val error: Throwable) : DesktopOpenUriInstallResult
}

object AwtDesktopOpenUriEventPort : DesktopOpenUriEventPort {
    override fun install(consumer: (String) -> Unit): DesktopOpenUriInstallResult {
        if (OperatingSystem.detect() != OperatingSystem.MACOS || GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return DesktopOpenUriInstallResult.Unsupported
        return runCatching {
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return DesktopOpenUriInstallResult.Unsupported
            desktop.setOpenURIHandler { event -> consumer(event.uri.toString()) }
            DesktopOpenUriInstallResult.Installed(DesktopOpenUriRegistration { desktop.setOpenURIHandler(null) })
        }.getOrElse(DesktopOpenUriInstallResult::Failed)
    }
}
