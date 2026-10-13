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
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.UpdateChannel

class ForkReleaseServiceContractTest {
    @Test
    fun `production parser selects own channel platform and highest Android versionCode`() = runTest {
        withServer { server, service ->
            server.enqueue(
                index(entry(code = 101), entry(channel = "stable", code = 103), entry(arch = "universal", code = 102)),
            )
            val release = service.latest(arguments())!!
            assertEquals(103L, release.versionCode)
            assertEquals("arm64-v8a", release.asset.target.arch)
            assertEquals(HASH, release.asset.checksum?.value)
            assertEquals(3L, release.sizeBytes)
            assertEquals(APP_ID, release.applicationId)
            assertEquals(CERTIFICATE, release.certificateSha256)
            assertEquals("/updates/v1.json", server.takeRequest().url.encodedPath)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `stable excludes testing and newest universal can supersede older architecture package`() = runTest {
        withServer { server, service ->
            server.enqueue(index(entry(channel = "stable", code = 99), entry(code = 101)))
            assertEquals(99L, service.latest(arguments(channel = UpdateChannel.STABLE))!!.versionCode)
            server.enqueue(index(entry(code = 101), entry(arch = "universal", code = 102)))
            assertNull(service.latest(arguments())!!.asset.target.arch)
        }
    }

    @Test
    fun `Desktop ZIP matches platform architecture and uses complete four part numeric order`() = runTest {
        withServer(ReleaseOs.WINDOWS, "x86_64") { server, service ->
            server.enqueue(
                index(
                    desktopEntry("0.12.0.1.abcdef0"),
                    desktopEntry("0.11.99.999.abcdef0"),
                    desktopEntry("0.12.0.2.abcdef0", arch = "arm64", platform = "macos"),
                ),
            )
            val release = service.latest(arguments(ReleaseOs.WINDOWS))!!
            assertEquals("0.12.0.1.abcdef0", release.version)
            assertEquals(ReleasePackageType.ARCHIVE, release.asset.target.packageType)
        }
    }

    @Test
    fun `empty and unmatched index are explicit no compatible package`() = runTest {
        withServer { server, service ->
            server.enqueue(index())
            assertNull(service.latest(arguments()))
            server.enqueue(index(entry(arch = "x86")))
            assertNull(service.latest(arguments()))
        }
    }

    @Test
    fun `invalid identity hash size URLs duplicate and schema reject without upstream fallback`() = runTest {
        val valid = entry()
        val invalid = listOf(
            valid.replace(APP_ID, "app.mihon"),
            valid.replace(CERTIFICATE, "c".repeat(64)),
            valid.replace(HASH, "z".repeat(64)),
            valid.replace("\"sizeBytes\":3", "\"sizeBytes\":0"),
            valid.replace("https://github.com/", "http://github.com/"),
            valid.replace("github.com/AltairCardinal", "github.com/attacker"),
            valid.replace("https://github.com/", "https://user@github.com/"),
            valid.replace("https://github.com/", "https://github.com:8443/"),
            valid.replace("/download/v101/", "/download/other/"),
            valid.replace("/app.apk", "/%2e%2e%2fapp.apk"),
            valid.replace("/app.apk", "/app.apk?raw=1"),
            valid.replace("/app.apk", "/app.apk#fragment"),
        ).map { indexBody(it) } +
            listOf(indexBody(valid, valid), "{\"schemaVersion\":2,\"releases\":[]}", "{}", "{bad-json")
        withServer { server, service ->
            invalid.forEach { body ->
                server.enqueue(MockResponse(body = body))
                assertTrue(runCatching { service.latest(arguments()) }.isFailure, body)
            }
            assertEquals(invalid.size, server.requestCount)
        }
    }

    @Test
    fun `403 429 500 and redirect fail closed with no fallback request`() = runTest {
        withServer { server, service ->
            listOf(403, 429, 500).forEach { status ->
                server.enqueue(MockResponse(code = status, body = "failure"))
                assertTrue(runCatching { service.latest(arguments()) }.isFailure, "HTTP $status")
            }
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = okhttp3.Headers.headersOf("Location", server.url("/unexpected").toString()),
                ),
            )
            server.enqueue(MockResponse(code = 500))
            assertTrue(runCatching { service.latest(arguments()) }.isFailure)
            assertEquals(4, server.requestCount)
        }
    }

    private suspend fun withServer(
        os: ReleaseOs = ReleaseOs.ANDROID,
        arch: String = "arm64-v8a",
        block: suspend (MockWebServer, ReleaseServiceImpl) -> Unit,
    ) {
        MockWebServer().also { it.start() }.use { server ->
            val target = object : PlatformInfo {
                override val preferredAbi = arch
                override val releaseOs = os
                override val releasePackageType = if (os ==
                    ReleaseOs.ANDROID
                ) {
                    ReleasePackageType.APK
                } else {
                    ReleasePackageType.MSI
                }
            }
            block(
                server,
                ReleaseServiceImpl(
                    OkHttpClient(),
                    Json { ignoreUnknownKeys = true },
                    target,
                    apiBaseUrl = server.url("/upstream").toString(),
                    forkIndexUrl = server.url("/updates/v1.json").toString(),
                ),
            )
        }
    }

    private fun arguments(os: ReleaseOs = ReleaseOs.ANDROID, channel: UpdateChannel = UpdateChannel.TESTING) =
        GetApplicationRelease.Arguments(
            false,
            false,
            0,
            if (os == ReleaseOs.ANDROID) "0.19.4-aex.72" else "0.11.19.91.abcdef0",
            "AltairCardinal/mihon-desktop",
            true,
            ForkUpdateRequest(channel, os, if (os == ReleaseOs.ANDROID) 90 else null, APP_ID, CERTIFICATE),
        )

    private fun index(vararg entries: String) = MockResponse(body = indexBody(*entries))
    private fun indexBody(vararg entries: String) = """{"schemaVersion":1,"releases":[${entries.joinToString(",")}]}"""
    private fun entry(channel: String = "testing", arch: String = "arm64-v8a", code: Long = 101) = """{
        "channel":"$channel","platform":"android","arch":"$arch","packageType":"apk",
        "version":"0.19.4-aex.$code","versionCode":$code,"applicationId":"$APP_ID","certificateSha256":"$CERTIFICATE",
        "releaseUrl":"$ROOT/tag/v$code","downloadUrl":"$ROOT/download/v$code/app.apk",
        "sha256":"$HASH","sizeBytes":3,"notes":"test release"
    }"""
    private fun desktopEntry(version: String, arch: String = "x86_64", platform: String = "windows") = """{
        "channel":"testing","platform":"$platform","arch":"$arch","packageType":"zip","version":"$version",
        "releaseUrl":"$ROOT/tag/v$version","downloadUrl":"$ROOT/download/v$version/app.zip",
        "sha256":"$HASH","sizeBytes":3,"notes":"test release"
    }"""

    private companion object {
        const val ROOT = "https://github.com/AltairCardinal/mihon-desktop/releases"
        const val APP_ID = "app.mihon.desktop.fork"
        val CERTIFICATE = "b".repeat(64)
        val HASH = "a".repeat(64)
    }
}
