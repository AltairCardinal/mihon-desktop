package tachiyomi.data.release

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseVariant

class ReleaseServiceImplTest {
    @Test
    fun `each fixed-main Android ABI selects exact asset then falls back only to universal`() = runTest {
        ANDROID_ABIS.forEach { abi ->
            withServer(abi) { server, service ->
                server.enqueue(MockResponse(body = releaseFixture(canonicalAssets())))
                val exact = service.latest(arguments())!!
                assertEquals("mihon-$abi-v1.2.3.apk", exact.asset.name)
                assertEquals(ReleaseOs.ANDROID, exact.asset.target.os)
                assertEquals(abi, exact.asset.target.arch)
                assertEquals(ReleasePackageType.APK, exact.asset.target.packageType)
                assertEquals(ReleaseVariant.STANDARD, exact.asset.target.variant)
                assertEquals(CHECKSUM, exact.asset.checksum?.value)

                server.enqueue(MockResponse(body = releaseFixture(asset("mihon-v1.2.3.apk"))))
                val fallback = service.latest(arguments())!!
                assertEquals("mihon-v1.2.3.apk", fallback.asset.name)
                assertNull(fallback.asset.target.arch)
            }
        }
    }

    @Test
    fun `FOSS selects only canonical FOSS asset and never falls back to standard`() = runTest {
        withServer { server, service ->
            server.enqueue(MockResponse(body = releaseFixture(canonicalAssets())))
            val foss = service.latest(arguments(isFoss = true))!!
            assertEquals("mihon-v1.2.3-foss.apk", foss.asset.name)
            assertEquals(ReleaseVariant.FOSS, foss.asset.target.variant)
            assertNull(foss.asset.target.arch)

            server.enqueue(MockResponse(body = releaseFixture(asset("mihon-v1.2.3.apk"))))
            assertNull(service.latest(arguments(isFoss = true)))
            assertEquals("/repos/mihonapp/mihon/releases/latest", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `returns null for empty or incompatible assets without substring matches`() = runTest {
        withServer { server, service ->
            server.enqueue(MockResponse(body = releaseFixture("")))
            assertNull(service.latest(arguments()))

            val misleading = asset("notes-foss.zip") + "," + asset("mihon-arm64-v8a-v1.2.3.apk.bak")
            server.enqueue(MockResponse(body = releaseFixture(misleading)))
            assertNull(service.latest(arguments()))
        }
    }

    @Test
    fun `missing checksum remains explicit metadata instead of inventing one`() = runTest {
        withServer { server, service ->
            server.enqueue(MockResponse(body = releaseFixture(asset("mihon-arm64-v8a-v1.2.3.apk", null))))
            val release = service.latest(arguments())!!

            assertNull(release.asset.checksum)
            assertEquals("https://example/arm64.apk", release.downloadLink)
        }
    }

    @Test
    fun `desktop targets select only exact canonical MSI and DMG assets`() = runTest {
        listOf(
            DesktopFixture(
                "Windows 11",
                "amd64",
                "mihon-desktop-windows-x86_64-v1.2.3.msi",
                ReleaseOs.WINDOWS,
                "x86_64",
                ReleasePackageType.MSI,
            ),
            DesktopFixture(
                "Mac OS X",
                "amd64",
                "mihon-desktop-macos-x86_64-v1.2.3.dmg",
                ReleaseOs.MACOS,
                "x86_64",
                ReleasePackageType.DMG,
            ),
            DesktopFixture(
                "Mac OS X",
                "aarch64",
                "mihon-desktop-macos-arm64-v1.2.3.dmg",
                ReleaseOs.MACOS,
                "arm64",
                ReleasePackageType.DMG,
            ),
        ).forEach { fixture ->
            withDesktopServer(fixture.osName, fixture.osArch) { server, service ->
                server.enqueue(MockResponse(body = releaseFixture(asset(fixture.assetName))))

                val release = service.latest(arguments())!!

                assertEquals(fixture.assetName, release.asset.name)
                assertEquals(fixture.releaseOs, release.asset.target.os)
                assertEquals(fixture.releaseArch, release.asset.target.arch)
                assertEquals(fixture.packageType, release.asset.target.packageType)
                assertEquals(CHECKSUM, release.asset.checksum?.value)
            }
        }
    }

    @Test
    fun `desktop discovery rejects disguised or incompatible package names`() = runTest {
        val rejectedNames = listOf(
            "mihon-desktop-macos-x86_64-v1.2.3.dmg",
            "mihon-desktop-windows-arm64-v1.2.3.msi",
            "mihon-desktop-windows-x86_64-v1.2.3.dmg",
            "mihon desktop-windows-x86_64-v1.2.3.msi",
            "mihon-desktop-windows-x86_64-v1.2.3.msi.bak",
            "notes-mihon-desktop-windows-x86_64-v1.2.3.msi",
            "Mihon Desktop-1.2.3.msi",
        )
        withDesktopServer("Windows 11", "amd64") { server, service ->
            rejectedNames.forEach { name ->
                server.enqueue(MockResponse(body = releaseFixture(asset(name))))
                assertNull(service.latest(arguments()), name)
            }
        }
    }

    @Test
    fun `APK-only release has no compatible desktop package`() = runTest {
        withDesktopServer("Windows 11", "amd64") { server, service ->
            server.enqueue(MockResponse(body = releaseFixture(canonicalAssets())))

            assertNull(service.latest(arguments()))
        }
    }

    @Test
    fun `invalid digest algorithm length and characters all produce null checksum`() = runTest {
        withServer { server, service ->
            listOf("md5:$CHECKSUM", "sha256:abcd", "sha256:${"g".repeat(64)}").forEach { digest ->
                server.enqueue(MockResponse(body = releaseFixture(asset("mihon-arm64-v8a-v1.2.3.apk", digest))))
                assertNull(service.latest(arguments())!!.asset.checksum, digest)
            }
        }
    }

    @Test
    fun `403 429 and 500 remain HTTP failures`() = runTest {
        listOf(403, 429, 500).forEach { status ->
            withServer { server, service ->
                server.enqueue(MockResponse(code = status, body = "failure"))
                assertTrue(runCatching { service.latest(arguments()) }.isFailure, "HTTP $status")
            }
        }
    }

    @Test
    fun `malformed JSON remains a parsing failure`() = runTest {
        withServer { server, service ->
            server.enqueue(MockResponse(body = "{not-json"))
            assertTrue(runCatching { service.latest(arguments()) }.isFailure)
        }
    }

    private suspend fun withServer(
        abi: String = ANDROID_ABIS.first(),
        block: suspend (MockWebServer, ReleaseServiceImpl) -> Unit,
    ) = withServer(AndroidTargetInfo(abi), block)

    private suspend fun withServer(
        platformInfo: PlatformInfo,
        block: suspend (MockWebServer, ReleaseServiceImpl) -> Unit,
    ) {
        MockWebServer().also { it.start() }.use { server ->
            val service = ReleaseServiceImpl(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true },
                platformInfo,
                server.url("/").toString(),
            )
            block(server, service)
        }
    }

    private suspend fun withDesktopServer(
        osName: String,
        osArch: String,
        block: suspend (MockWebServer, ReleaseServiceImpl) -> Unit,
    ) {
        val platformInfo = DesktopPlatformInfo(
            osNameProvider = { osName },
            osArchProvider = { osArch },
        )
        withServer(platformInfo, block)
    }

    private fun arguments(isFoss: Boolean = false) = GetApplicationRelease.Arguments(
        isFoss,
        false,
        0,
        "v1.0.0",
        "mihonapp/mihon",
        true,
    )

    private fun releaseFixture(assets: String) =
        """{"tag_name":"v1.2.3","body":"Hello @alice<!-->hidden","html_url":"https://example/release","assets":[$assets]}"""

    private fun canonicalAssets() = listOf(
        asset("mihon-v1.2.3.apk"),
        *ANDROID_ABIS.map { asset("mihon-$it-v1.2.3.apk") }.toTypedArray(),
        asset("mihon-v1.2.3-foss.apk"),
        asset("unrelated-arm64-v8a-v1.2.3.apk"),
    ).joinToString(",")

    private fun asset(name: String, digest: String? = "sha256:$CHECKSUM"): String {
        val url = if ("arm64-v8a" in name) "https://example/arm64.apk" else "https://example/$name"
        val checksum = digest?.let { ",\"digest\":\"$it\"" }.orEmpty()
        return """{"name":"$name","browser_download_url":"$url"$checksum}"""
    }

    private data class AndroidTargetInfo(override val preferredAbi: String) : PlatformInfo {
        override val releaseOs = ReleaseOs.ANDROID
        override val releasePackageType = ReleasePackageType.APK
    }

    private data class DesktopFixture(
        val osName: String,
        val osArch: String,
        val assetName: String,
        val releaseOs: ReleaseOs,
        val releaseArch: String,
        val packageType: ReleasePackageType,
    )

    companion object {
        private val ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        private const val CHECKSUM = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
