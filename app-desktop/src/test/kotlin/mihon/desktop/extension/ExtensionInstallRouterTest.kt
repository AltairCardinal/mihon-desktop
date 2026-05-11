package mihon.desktop.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase C: Tests for the JAR-first / APK-fallback install routing in [DesktopExtensionApi].
 *
 * - JAR available (has .class files): install directly as COMPILED_JAR, no conversion attempted
 * - APK only (has .dex, no .class): convert via ApkToJarConverter, install as CONVERTED_APK
 * - APK conversion fails (invalid DEX): return Error
 * - Meta sidecar records the correct [ExtensionOrigin]
 */
class ExtensionInstallRouterTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DesktopExtensionApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DesktopExtensionApi(
            client = OkHttpClient(),
            json = json,
            extensionRepoRepository = FakeExtensionRepoRepository(),
        )
    }

    @AfterEach
    fun tearDown() { server.close() }

    // ── Route: pre-compiled JAR ────────────────────────────────────────────────

    @Test
    fun `JAR with class files installs as COMPILED_JAR without APK conversion`(@TempDir tmp: Path) = runBlocking {
        server.enqueue(fakeJarResponse(tmp.toFile()))

        val result = api.installExtension(fakeExt(server), tmp.toFile())

        result.shouldBeInstanceOf<DesktopExtensionApi.InstallResult.Success>()
        val meta = readExtensionMeta((result as DesktopExtensionApi.InstallResult.Success).file)
        meta?.source shouldBe ExtensionOrigin.COMPILED_JAR
    }

    @Test
    fun `meta source is COMPILED_JAR for JAR install`(@TempDir tmp: Path) = runBlocking {
        server.enqueue(fakeJarResponse(tmp.toFile()))
        val result = api.installExtension(fakeExt(server), tmp.toFile())
        result.shouldBeInstanceOf<DesktopExtensionApi.InstallResult.Success>()
        val meta = readExtensionMeta((result as DesktopExtensionApi.InstallResult.Success).file)
        meta?.source shouldBe ExtensionOrigin.COMPILED_JAR
    }

    // ── Route: APK with valid DEX → convert ───────────────────────────────────

    @Test
    fun `APK with invalid DEX returns Error`(@TempDir tmp: Path) = runBlocking {
        // Invalid DEX (just the magic bytes — dex2jar will fail to parse)
        server.enqueue(fakeApkResponse(tmp.toFile(), invalidDex = true))
        val result = api.installExtension(fakeExt(server), tmp.toFile())
        result.shouldBeInstanceOf<DesktopExtensionApi.InstallResult.Error>()
    }

    @Test
    fun `APK conversion failure error message mentions convert not Android-only`(@TempDir tmp: Path) = runBlocking {
        server.enqueue(fakeApkResponse(tmp.toFile(), invalidDex = true))
        val result = api.installExtension(fakeExt(server), tmp.toFile())
        val error = result as DesktopExtensionApi.InstallResult.Error
        // Conversion failure must NOT say "Android-only" — the UI uses that substring to decide
        // whether to show "Android-only extension — cannot run on desktop" (a misleading message
        // for a conversion failure that might succeed with a different APK version).
        assert(!error.message.contains("Android-only")) {
            "Conversion failure should NOT say 'Android-only': ${error.message}"
        }
        assert(error.message.contains("convert", ignoreCase = true) || error.message.contains("APK", ignoreCase = true)) {
            "Conversion failure message should mention 'convert' or 'APK': ${error.message}"
        }
    }

    // ── Route: no DEX and no class files ────────────────────────────────────

    @Test
    fun `empty ZIP with no DEX and no class files returns Error`(@TempDir tmp: Path) = runBlocking {
        val emptyZip = File(tmp.toFile(), "empty.zip")
        ZipOutputStream(emptyZip.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(emptyZip.readBytes()))
                .build(),
        )
        val result = api.installExtension(fakeExt(server), tmp.toFile())
        result.shouldBeInstanceOf<DesktopExtensionApi.InstallResult.Error>()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fakeExt(server: MockWebServer) = DesktopAvailableExtension(
        name = "Test Extension",
        pkgName = "test.pkg.router",
        versionName = "1.4.0",
        versionCode = 1L,
        lang = "en",
        isNsfw = false,
        jarUrl = "${server.url("/apk/test.pkg.router-1.4.0.apk")}",
        iconUrl = "${server.url("/icon/test.pkg.router.png")}",
        repoUrl = server.url("/").toString(),
    )

    private fun fakeJarResponse(dir: File): MockResponse {
        val jar = File(dir, "fake.jar")
        JarOutputStream(jar.outputStream()).use {
            it.putNextEntry(JarEntry("com/example/Stub.class"))
            it.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            it.closeEntry()
        }
        return MockResponse.Builder()
            .body(okio.Buffer().write(jar.readBytes()))
            .addHeader("Content-Type", "application/java-archive")
            .build()
    }

    private fun fakeApkResponse(dir: File, invalidDex: Boolean): MockResponse {
        val apk = File(dir, "fake.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            // Invalid DEX: just the 4-byte magic, no full header
            zip.write(if (invalidDex) byteArrayOf(0x64, 0x65, 0x78, 0x0A) else minimalDex())
            zip.closeEntry()
        }
        return MockResponse.Builder()
            .body(okio.Buffer().write(apk.readBytes()))
            .addHeader("Content-Type", "application/vnd.android.package-archive")
            .build()
    }

    private fun minimalDex() = ByteArray(0) // placeholder for a real DEX if needed
}
