package mihon.desktop.extension

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Isolated
class RealExtensionBase64CompatTest {

    @Test
    fun `real FavComic client decrypts an encrypted image after production loading`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val previousInjekt = Injekt
        try {
            val provenance = Json.parseToJsonElement(
                Files.readString(repositoryRoot().resolve(PROVENANCE_PATH)),
            ).jsonObject
            assertEquals(PROVENANCE_FIELDS, provenance.keys)
            assertEquals(AUTHORITY_REF, provenance.string("authorityRef"))
            assertEquals(REPOSITORY_COMMIT, provenance.string("repositoryCommit"))
            assertEquals(GIT_BLOB, provenance.string("gitBlob"))
            assertEquals("Apache-2.0", provenance.string("license"))
            assertEquals(RETRIEVED_AT, provenance.string("retrievedAt"))
            assertEquals(RAW_URL, provenance.url("rawUrl"))

            val apkPath = repositoryRoot().resolve(provenance.string("fixturePath"))
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable FavComic fixture: $apkPath")
            assertEquals(provenance.string("sizeBytes").toLong(), Files.size(apkPath))
            assertEquals(provenance.string("sha256"), sha256(apkPath))
            assertEquals(EXTENSION_CLASS, ManifestClassExtractor.extractFromApk(apkPath.toFile()))
            assertEquals(PACKAGE_NAME, provenance.string("packageName"))
            assertEquals(VERSION_CODE, provenance.string("versionCode").toLong())
            assertEquals(VERSION_NAME, provenance.string("versionName"))
            assertEquals(EXTENSION_CLASS, provenance.string("extensionClass"))
            assertEquals("success", provenance.string("expectedOutcome"))

            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body(Buffer().write(ENCRYPTED_IMAGE)).build())
                val diContext = initDesktopDIForTest(
                    appDir = tempDir.resolve("app").toFile(),
                    preferenceStore = DesktopPreferenceStore(),
                )
                try {
                    val convertedJar = ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile())
                    assertNotNull(convertedJar, "Production converter rejected the immutable FavComic APK")
                    val jar = requireNotNull(convertedJar)
                    writeExtensionMeta(
                        jar,
                        ExtensionMeta(
                            pkgName = PACKAGE_NAME,
                            versionCode = VERSION_CODE,
                            versionName = VERSION_NAME,
                            artifactSha256 = provenance.string("sha256"),
                            source = ExtensionOrigin.CONVERTED_APK,
                            name = "FavComic",
                            language = "zh",
                            extensionClass = EXTENSION_CLASS,
                        ),
                    )

                    val loader = DesktopExtensionLoader(tempDir.toFile())
                    val loaded = loader.loadFromSingleJar(jar)
                    try {
                        assertEquals(1, loaded.size, "FavComic did not load through production Desktop DI")
                        assertTrue(loader.diagnostics.isEmpty(), "FavComic loader diagnostics: ${loader.diagnostics}")
                        val source = loaded.single().source as HttpSource
                        val codeSource = Path.of(source.javaClass.protectionDomain.codeSource.location.toURI())
                        assertEquals(jar.canonicalFile.toPath(), codeSource.toFile().canonicalFile.toPath())

                        val url = server.url("/cover.jpg").newBuilder().fragment("true").build()
                        source.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            assertTrue(response.isSuccessful)
                            // FavComic includes #true in its suffix check; this fallback is APK behavior, not a Desktop gap.
                            assertEquals(EXPECTED_CONTENT_TYPE, response.body.contentType().toString())
                            assertArrayEquals(PLAINTEXT_IMAGE, response.body.bytes())
                        }

                        assertEquals(1, server.requestCount)
                        assertEquals("/cover.jpg", server.takeRequest().url.encodedPath)
                    } finally {
                        loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                    }
                } finally {
                    diContext.closeAndJoin()
                }
            }
        } finally {
            Injekt = previousInjekt
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun Map<String, JsonElement>.string(name: String) = getValue(name).jsonPrimitive.content

    private fun Map<String, JsonElement>.url(name: String) =
        getValue(name).jsonArray.joinToString("") { it.jsonPrimitive.content }

    private fun repositoryRoot() =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-favcomic-1.4.1.provenance.json"
        const val AUTHORITY_REF = "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
        const val REPOSITORY_COMMIT = "7d5052fb895d086ae2ec6e3cca861146ee3ea0ec"
        const val GIT_BLOB = "a3937a5f16f2a6c7c1f58d4bddff1e28695ed4a9"
        const val RETRIEVED_AT = "2026-07-19"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.zh.favcomic"
        const val VERSION_CODE = 1L
        const val VERSION_NAME = "1.4.1"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.zh.favcomic.ExtensionGenerated"
        const val EXPECTED_CONTENT_TYPE = "application/octet-stream"
        const val RAW_URL =
            "https://raw.githubusercontent.com/keiyoushi/extensions/$REPOSITORY_COMMIT/" +
                "apk/tachiyomi-zh.favcomic-v1.4.1.apk"
        val ENCRYPTED_IMAGE = hex(
            "000102030405060708090a0b0c0d0e0f" +
                "ba7209b41d2d82d8e0fa995afb9b5f0f8c7f832ccb4fe225aa424c90a2c222f6",
        )
        val PLAINTEXT_IMAGE = hex("89504e470d0a1a0a466176436f6d6963")
        val PROVENANCE_FIELDS = setOf(
            "authorityRef", "repository", "repositoryCommit", "gitBlob", "license", "fixturePath", "sha256",
            "sizeBytes", "packageName", "versionCode", "versionName", "extensionClass", "expectedOutcome", "rawUrl",
            "retrievedAt",
        )

        private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
