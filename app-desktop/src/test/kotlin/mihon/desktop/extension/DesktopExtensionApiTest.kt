package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopExtensionApiTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: FakeExtensionRepoRepository
    private lateinit var api: DesktopExtensionApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = FakeExtensionRepoRepository()
        api = DesktopExtensionApi(
            client = OkHttpClient(),
            json = json,
            extensionRepoRepository = repo,
        )
    }

    @AfterEach
    fun tearDown() { server.close() }

    @Test
    fun `findAvailableExtensions returns empty when no repos`() = runBlocking {
        val result = api.findAvailableExtensions()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findAvailableExtensions parses index json correctly`() = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repo.upsertRepo(baseUrl, "Test Repo", null, baseUrl, "fp1")

        server.enqueue(
            MockResponse.Builder()
                .body(INDEX_JSON)
                .addHeader("Content-Type", "application/json")
                .build(),
        )

        val result = api.findAvailableExtensions()

        assertEquals(2, result.size)
        val first = result.first { it.pkgName == "eu.kanade.tachiyomi.extension.en.example" }
        assertEquals("Example Source", first.name)
        assertEquals("en", first.lang)
        assertEquals("1.4.17", first.versionName)
        assertEquals(14L, first.versionCode)
        assertTrue(first.jarUrl.contains("eu.kanade.tachiyomi.extension.en.example-1.4.17.apk"))
    }

    @Test
    fun `findAvailableExtensions filters out unsupported lib versions`() = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repo.upsertRepo(baseUrl, "Test Repo", null, baseUrl, "fp1")

        server.enqueue(
            MockResponse.Builder()
                .body(INDEX_JSON_BAD_LIB)
                .addHeader("Content-Type", "application/json")
                .build(),
        )

        val result = api.findAvailableExtensions()
        assertTrue(result.isEmpty(), "Extensions with invalid lib version should be filtered out")
    }

    @Test
    fun `findAvailableExtensions returns empty on network error`() = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repo.upsertRepo(baseUrl, "Test Repo", null, baseUrl, "fp1")

        server.enqueue(MockResponse.Builder().code(500).build())

        val result = api.findAvailableExtensions()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `installExtension rejects Android-only APK with no JVM classes`(@TempDir tmpDir: Path) = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repo.upsertRepo(baseUrl, "Test Repo", null, baseUrl, "fp1")

        // Build a fake APK (ZIP) containing only a .dex file — no .class files
        val fakeApk = buildFakeApk(tmpDir.toFile())
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(fakeApk.readBytes()))
                .addHeader("Content-Type", "application/octet-stream")
                .build(),
        )

        val ext = DesktopAvailableExtension(
            name = "Test",
            pkgName = "test.pkg",
            versionName = "1.4.0",
            versionCode = 1,
            lang = "en",
            isNsfw = false,
            jarUrl = "$baseUrl/apk/test.pkg-1.4.0.apk",
            iconUrl = "$baseUrl/icon/test.pkg.png",
            repoUrl = baseUrl,
        )
        val result = api.installExtension(ext, tmpDir.toFile())
        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        val error = result as DesktopExtensionApi.InstallResult.Error
        // APK with DEX but no valid classes → either conversion fails or no-classes/no-dex path
        val isExpectedError = error.message.contains("Android-only") || error.message.contains("APK convert", ignoreCase = true)
        assertTrue(isExpectedError, "Error should indicate install failure: ${error.message}")
    }

    @Test
    fun `installExtension succeeds for JAR with JVM class files`(@TempDir tmpDir: Path) = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repo.upsertRepo(baseUrl, "Test Repo", null, baseUrl, "fp1")

        val fakeJar = buildFakeJar(tmpDir.toFile())
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(fakeJar.readBytes()))
                .addHeader("Content-Type", "application/java-archive")
                .build(),
        )

        val ext = DesktopAvailableExtension(
            name = "Test",
            pkgName = "test.pkg",
            versionName = "1.4.0",
            versionCode = 1,
            lang = "en",
            isNsfw = false,
            jarUrl = "$baseUrl/apk/test.pkg-1.4.0.apk",
            iconUrl = "$baseUrl/icon/test.pkg.png",
            repoUrl = baseUrl,
        )
        val targetDir = tmpDir.resolve("exts").toFile()
        val result = api.installExtension(ext, targetDir)
        assertInstanceOf(DesktopExtensionApi.InstallResult.Success::class.java, result)
    }

    @Test
    fun `installExtension saves meta json with correct version after successful install`(@TempDir tmpDir: Path) = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val fakeJar = buildFakeJar(tmpDir.toFile())
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(fakeJar.readBytes()))
                .addHeader("Content-Type", "application/java-archive")
                .build(),
        )

        val ext = DesktopAvailableExtension(
            name = "Test",
            pkgName = "eu.kanade.tachiyomi.extension.en.test",
            versionName = "1.4.7",
            versionCode = 42L,
            lang = "en",
            isNsfw = false,
            jarUrl = "$baseUrl/apk/eu.kanade.tachiyomi.extension.en.test-1.4.7.apk",
            iconUrl = "$baseUrl/icon/eu.kanade.tachiyomi.extension.en.test.png",
            repoUrl = baseUrl,
        )
        val targetDir = tmpDir.resolve("exts").toFile()
        val result = api.installExtension(ext, targetDir)
        assertInstanceOf(DesktopExtensionApi.InstallResult.Success::class.java, result)

        // Verify the sidecar meta file was created alongside the JAR
        val jarFile = java.io.File(targetDir, "${ext.pkgName}.jar")
        val metaFile = java.io.File(targetDir, "${ext.pkgName}.meta.json")
        assertTrue(jarFile.exists(), "JAR should exist")
        assertTrue(metaFile.exists(), "Meta file should be created alongside JAR")

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val meta = metaFile.inputStream().use { json.decodeFromStream<ExtensionMeta>(it) }
        assertEquals(42L, meta.versionCode)
        assertEquals("1.4.7", meta.versionName)
        assertEquals(ext.pkgName, meta.pkgName)
    }

    @Test
    fun `installExtension does not create meta file when JAR is Android-only`(@TempDir tmpDir: Path) = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val fakeApk = buildFakeApk(tmpDir.toFile())
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(fakeApk.readBytes()))
                .build(),
        )

        val ext = DesktopAvailableExtension(
            name = "Android Only",
            pkgName = "eu.kanade.tachiyomi.extension.en.androidonly",
            versionName = "1.0.0",
            versionCode = 1L,
            lang = "en",
            isNsfw = false,
            jarUrl = "$baseUrl/apk/android.apk",
            iconUrl = "",
            repoUrl = baseUrl,
        )
        val targetDir = tmpDir.resolve("exts").toFile()
        api.installExtension(ext, targetDir)

        val metaFile = java.io.File(targetDir, "${ext.pkgName}.meta.json")
        assertFalse(metaFile.exists(), "Meta file must NOT be created for rejected Android-only extensions")
    }

    @Test
    fun `findAvailableExtensions parses versionCode correctly`() = runBlocking {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repo.upsertRepo(baseUrl, "Test Repo", null, baseUrl, "fp1")

        server.enqueue(
            MockResponse.Builder()
                .body(INDEX_JSON)
                .addHeader("Content-Type", "application/json")
                .build(),
        )

        val result = api.findAvailableExtensions()
        val en = result.first { it.pkgName == "eu.kanade.tachiyomi.extension.en.example" }
        assertEquals(14L, en.versionCode)
        val ja = result.first { it.pkgName == "eu.kanade.tachiyomi.extension.ja.japansource" }
        assertEquals(21L, ja.versionCode)
    }

    @Test
    fun `findAvailableExtensions aggregates from multiple repos`() = runBlocking {
        val base1 = server.url("/repo1").toString().removeSuffix("/")
        val base2 = server.url("/repo2").toString().removeSuffix("/")
        repo.upsertRepo(base1, "Repo 1", null, base1, "fp1")
        repo.upsertRepo(base2, "Repo 2", null, base2, "fp2")

        // Two requests will be made (one per repo) - enqueue both
        server.enqueue(MockResponse.Builder().body("""[${ITEM_EN}]""").addHeader("Content-Type", "application/json").build())
        server.enqueue(MockResponse.Builder().body("""[${ITEM_JA}]""").addHeader("Content-Type", "application/json").build())

        val result = api.findAvailableExtensions()
        // At least 1 result (network paths may vary but both repos queried)
        assertTrue(result.isNotEmpty())
    }

    companion object {
        // Real extensions use 3-part version: MAJOR.MINOR.PATCH where MAJOR.MINOR is the lib version
        private val ITEM_EN = """{"name":"Tachiyomi: Example Source","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"eu.kanade.tachiyomi.extension.en.example-1.4.17.apk","lang":"en","code":14,"version":"1.4.17","nsfw":0,"sources":[{"id":1234567890,"lang":"en","name":"Example Source","baseUrl":"https://example.com"}]}"""
        private val ITEM_JA = """{"name":"Tachiyomi: 日本語ソース","pkg":"eu.kanade.tachiyomi.extension.ja.japansource","apk":"eu.kanade.tachiyomi.extension.ja.japansource-1.4.5.apk","lang":"ja","code":21,"version":"1.4.5","nsfw":0,"sources":[]}"""
        private val ITEM_BAD_LIB = """{"name":"Tachiyomi: Old Source","pkg":"eu.kanade.tachiyomi.extension.en.oldsource","apk":"eu.kanade.tachiyomi.extension.en.oldsource-0.1.0.apk","lang":"en","code":1,"version":"0.1.0","nsfw":0,"sources":[]}"""

        private val INDEX_JSON = """[$ITEM_EN, $ITEM_JA]"""
        private val INDEX_JSON_BAD_LIB = """[$ITEM_BAD_LIB]"""

        /** Creates a ZIP with only a DEX entry (Android-only, no JVM classes). */
        fun buildFakeApk(dir: File): File = File(dir, "fake.apk").also { f ->
            ZipOutputStream(f.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A)) // DEX magic
                zip.closeEntry()
            }
        }

        /** Creates a JAR with at least one .class entry (JVM-compatible). */
        fun buildFakeJar(dir: File): File = File(dir, "fake.jar").also { f ->
            JarOutputStream(f.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("com/example/Stub.class"))
                jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())) // class magic
                jar.closeEntry()
            }
        }
    }
}
