package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class RealExtensionCompatEvidenceTest {

    @Test
    fun `immutable ManHuaGui APK loads through the production converter and loader`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val provenancePath = repositoryRoot().resolve(PROVENANCE_PATH)
        val provenance = Json.parseToJsonElement(Files.readString(provenancePath)).jsonObject
        assertEquals(PROVENANCE_FIELDS, provenance.keys)
        assertEquals(AUTHORITY_REF, provenance.string("authorityRef"))
        assertEquals(REPOSITORY_COMMIT, provenance.string("repositoryCommit"))
        assertEquals(GIT_BLOB, provenance.string("gitBlob"))
        assertEquals("Apache-2.0", provenance.string("license"))
        assertEquals(RETRIEVED_AT, provenance.string("retrievedAt"))
        assertEquals(RAW_URL, provenance.url("rawUrl"))

        val apkPath = repositoryRoot().resolve(provenance.string("fixturePath"))
        assertTrue(Files.isRegularFile(apkPath), "Missing immutable real extension fixture: $apkPath")
        assertEquals(provenance.string("sizeBytes").toLong(), Files.size(apkPath))
        assertEquals(provenance.string("sha256"), sha256(apkPath))
        assertEquals(EXTENSION_CLASS, ManifestClassExtractor.extractFromApk(apkPath.toFile()))
        assertEquals(PACKAGE_NAME, provenance.string("packageName"))
        assertEquals(VERSION_CODE, provenance.string("versionCode").toLong())
        assertEquals(VERSION_NAME, provenance.string("versionName"))
        assertEquals(EXTENSION_CLASS, provenance.string("extensionClass"))

        val diContext = initDesktopDIForTest(
            appDir = tempDir.resolve("app").toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            val convertedJar = ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile())
            assertNotNull(convertedJar, "Production APK converter rejected the pinned real fixture")
            val jar = requireNotNull(convertedJar)
            writeExtensionMeta(
                jar,
                ExtensionMeta(
                    pkgName = PACKAGE_NAME,
                    versionCode = VERSION_CODE,
                    versionName = VERSION_NAME,
                    artifactSha256 = provenance.string("sha256"),
                    source = ExtensionOrigin.CONVERTED_APK,
                    name = "ManHuaGui",
                    language = "zh",
                    extensionClass = EXTENSION_CLASS,
                ),
            )

            val loader = DesktopExtensionLoader(tempDir.toFile())
            val loaded = loader.loadFromSingleJar(jar)
            assertEquals("success", provenance.string("expectedOutcome"))
            assertTrue(loaded.isNotEmpty()) { loaderFailureDiagnostic(loader, jar, EXTENSION_CLASS) }
            try {
                val source = loaded.first().source
                val codeSource = java.io.File(source.javaClass.protectionDomain.codeSource.location.toURI())
                assertEquals(jar.canonicalFile, codeSource.canonicalFile)
                assertTrue(source.id != 0L)
                assertTrue(source.name.isNotBlank())
                assertEquals("zh", source.lang)
            } finally {
                loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
            }
        } finally {
            diContext.closeAndJoin()
        }
    }

    private fun loaderFailureDiagnostic(loader: DesktopExtensionLoader, jar: java.io.File, className: String): String {
        loader.diagnostics.firstOrNull()?.let { diagnostic ->
            return "type=${diagnostic.errorType}, category=${diagnostic.category}, message=${diagnostic.message}"
        }
        val rootCause = loaderFailureRootCause(jar, className)
        return "type=${rootCause.javaClass.name}, category=empty-loader-result, message=${rootCause.message}"
    }

    private fun loaderFailureRootCause(jar: java.io.File, className: String): Throwable {
        val failure = try {
            ExtensionClassLoader(jar.toURI().toURL(), javaClass.classLoader).use { classLoader ->
                classLoader.loadClass(className).getDeclaredConstructor().newInstance()
            }
            null
        } catch (error: Throwable) {
            error
        }
        return generateSequence(requireNotNull(failure) { "Direct instantiation unexpectedly succeeded" }) { it.cause }
            .last()
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.string(name: String) =
        getValue(name).jsonPrimitive.content

    private fun Map<String, kotlinx.serialization.json.JsonElement>.url(name: String) =
        getValue(name).jsonArray.joinToString("") { it.jsonPrimitive.content }

    private fun repositoryRoot() =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-manhuagui-1.4.28.provenance.json"
        const val AUTHORITY_REF = "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
        const val REPOSITORY_COMMIT = "7d5052fb895d086ae2ec6e3cca861146ee3ea0ec"
        const val GIT_BLOB = "4529f7017f762a70d52bc15ff70e6260fae17d98"
        const val RETRIEVED_AT = "2026-07-19"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.zh.manhuagui"
        const val VERSION_CODE = 28L
        const val VERSION_NAME = "1.4.28"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.zh.manhuagui.ExtensionGenerated"
        const val RAW_URL =
            "https://raw.githubusercontent.com/keiyoushi/extensions/$REPOSITORY_COMMIT/" +
                "apk/tachiyomi-zh.manhuagui-v1.4.28.apk"
        val PROVENANCE_FIELDS = setOf(
            "authorityRef", "repository", "repositoryCommit", "gitBlob", "license", "fixturePath", "sha256",
            "sizeBytes", "packageName", "versionCode", "versionName", "extensionClass", "expectedOutcome", "rawUrl",
            "retrievedAt",
        )
    }
}
