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

interface DesktopOpenUriEnvironment {
    val operatingSystem: OperatingSystem
    val isHeadless: Boolean
}

interface DesktopOpenUriPlatform {
    val isDesktopSupported: Boolean

    fun isOpenUriSupported(): Boolean

    fun setOpenUriHandler(consumer: (String) -> Unit)

    fun clearOpenUriHandler()
}

class AwtDesktopOpenUriEventPort(
    private val environment: DesktopOpenUriEnvironment = AwtDesktopOpenUriEnvironment,
    private val platform: DesktopOpenUriPlatform = AwtDesktopOpenUriPlatform,
) : DesktopOpenUriEventPort {
    override fun install(consumer: (String) -> Unit): DesktopOpenUriInstallResult {
        if (environment.operatingSystem != OperatingSystem.MACOS || environment.isHeadless || !platform.isDesktopSupported) return DesktopOpenUriInstallResult.Unsupported
        return runCatching {
            if (!platform.isOpenUriSupported()) return DesktopOpenUriInstallResult.Unsupported
            platform.setOpenUriHandler(consumer)
            DesktopOpenUriInstallResult.Installed(IdempotentOpenUriRegistration(platform))
        }.getOrElse(DesktopOpenUriInstallResult::Failed)
    }
}

private object AwtDesktopOpenUriEnvironment : DesktopOpenUriEnvironment {
    override val operatingSystem = OperatingSystem.detect()
    override val isHeadless = GraphicsEnvironment.isHeadless()
}

private object AwtDesktopOpenUriPlatform : DesktopOpenUriPlatform {
    override val isDesktopSupported get() = Desktop.isDesktopSupported()

    override fun isOpenUriSupported() = Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)

    override fun setOpenUriHandler(consumer: (String) -> Unit) {
        Desktop.getDesktop().setOpenURIHandler { event -> consumer(event.uri.toString()) }
    }

    override fun clearOpenUriHandler() {
        Desktop.getDesktop().setOpenURIHandler(null)
    }
}

private class IdempotentOpenUriRegistration(
    private val platform: DesktopOpenUriPlatform,
) : DesktopOpenUriRegistration {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        platform.clearOpenUriHandler()
        closed = true
    }
}
