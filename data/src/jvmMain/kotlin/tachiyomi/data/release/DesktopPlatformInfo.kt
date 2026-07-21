package tachiyomi.data.release

import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import java.util.Locale

class DesktopPlatformInfo(
    private val osNameProvider: () -> String? = { System.getProperty("os.name") },
    private val osArchProvider: () -> String? = { System.getProperty("os.arch") },
) : PlatformInfo {
    override val preferredAbi: String?
        get() {
            val arch = osArchProvider()?.lowercase(Locale.ROOT) ?: return null
            return when {
                arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
                arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
                else -> null
            }
        }

    override val releaseOs: ReleaseOs
        get() = when (currentOsName()) {
            "windows" -> ReleaseOs.WINDOWS
            "macos" -> ReleaseOs.MACOS
            "linux" -> ReleaseOs.LINUX
            else -> ReleaseOs.UNKNOWN
        }

    override val releasePackageType: ReleasePackageType
        get() = when (currentOsName()) {
            "windows" -> ReleasePackageType.MSI
            "macos" -> ReleasePackageType.DMG
            else -> ReleasePackageType.UNKNOWN
        }

    private fun currentOsName(): String {
        val osName = osNameProvider()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "mac" in osName || "darwin" in osName -> "macos"
            "win" in osName -> "windows"
            "linux" in osName -> "linux"
            else -> "unknown"
        }
    }
}
