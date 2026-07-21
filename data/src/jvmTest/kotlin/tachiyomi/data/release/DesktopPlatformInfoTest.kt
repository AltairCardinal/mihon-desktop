package tachiyomi.data.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant

class DesktopPlatformInfoTest {
    @Test
    fun `desktop OS and architecture map to honest release targets`() {
        listOf(
            Triple(
                "Windows 11",
                "amd64",
                ReleaseTarget(ReleaseOs.WINDOWS, "x86_64", ReleasePackageType.MSI, ReleaseVariant.STANDARD),
            ),
            Triple(
                "Mac OS X",
                "amd64",
                ReleaseTarget(ReleaseOs.MACOS, "x86_64", ReleasePackageType.DMG, ReleaseVariant.STANDARD),
            ),
            Triple(
                "Mac OS X",
                "aarch64",
                ReleaseTarget(ReleaseOs.MACOS, "arm64", ReleasePackageType.DMG, ReleaseVariant.STANDARD),
            ),
            Triple(
                "Darwin",
                "aarch64",
                ReleaseTarget(ReleaseOs.MACOS, "arm64", ReleasePackageType.DMG, ReleaseVariant.STANDARD),
            ),
            Triple(
                "Linux",
                "amd64",
                ReleaseTarget(ReleaseOs.LINUX, "x86_64", ReleasePackageType.UNKNOWN, ReleaseVariant.STANDARD),
            ),
        ).forEach { (osName, osArch, expected) ->
            val platformInfo = DesktopPlatformInfo(
                osNameProvider = { osName },
                osArchProvider = { osArch },
            )
            assertEquals(expected, platformInfo.releaseTarget(isFoss = false))
        }
    }

    @Test
    fun `default JVM reports the target matching its actual platform`() {
        val platformInfo = DesktopPlatformInfo()
        val expected = when {
            System.getProperty("os.name").contains("win", ignoreCase = true) ->
                ReleaseOs.WINDOWS to
                    ReleasePackageType.MSI
            System.getProperty("os.name").contains("mac", ignoreCase = true) ->
                ReleaseOs.MACOS to
                    ReleasePackageType.DMG
            System.getProperty("os.name").contains("linux", ignoreCase = true) ->
                ReleaseOs.LINUX to
                    ReleasePackageType.UNKNOWN
            else -> ReleaseOs.UNKNOWN to ReleasePackageType.UNKNOWN
        }

        assertEquals(expected.first, platformInfo.releaseOs)
        assertEquals(expected.second, platformInfo.releasePackageType)
    }
}
