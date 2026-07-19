package mihon.desktop.extension

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.APP_VERSION
import mihon.desktop.di.initDesktopDIForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import java.nio.file.Files
import java.nio.file.Path

@Isolated
class RealExtensionBuildCompatTest {

    @Test
    fun `real MangaDex headers use host version and Android release ABI`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val previousInjekt = Injekt
        val previousHttpAgent = System.getProperty(HTTP_AGENT_PROPERTY)
        System.setProperty(HTTP_AGENT_PROPERTY, TEST_HTTP_AGENT)
        try {
            val provenance = Json.parseToJsonElement(
                Files.readString(repositoryRoot().resolve(PROVENANCE_PATH)),
            ).jsonObject
            val apkPath = repositoryRoot().resolve(provenance.getValue("fixturePath").jsonPrimitive.content)
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable MangaDex fixture: $apkPath")

            val diContext = initDesktopDIForTest(
                appDir = tempDir.resolve("app").toFile(),
                preferenceStore = DesktopPreferenceStore(),
            )
            try {
                val jar = requireNotNull(ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile()))
                writeExtensionMeta(
                    jar,
                    ExtensionMeta(
                        pkgName = provenance.getValue("packageName").jsonPrimitive.content,
                        versionCode = provenance.getValue("versionCode").jsonPrimitive.content.toLong(),
                        versionName = provenance.getValue("versionName").jsonPrimitive.content,
                        artifactSha256 = provenance.getValue("sha256").jsonPrimitive.content,
                        source = ExtensionOrigin.CONVERTED_APK,
                        name = "MangaDex",
                        language = "all",
                        extensionClass = provenance.getValue("extensionClass").jsonPrimitive.content,
                    ),
                )

                val loader = DesktopExtensionLoader(tempDir.toFile())
                val loaded = loader.loadFromSingleJar(jar)
                try {
                    assertEquals(61, loaded.size, "Manifest SourceFactory must contribute every MangaDex source")
                    assertTrue(loader.diagnostics.isEmpty(), "MangaDex loader diagnostics: ${loader.diagnostics}")
                    val english = loaded.single { it.source.lang == "en" }.source as HttpSource

                    assertEquals("Tachiyomi $TEST_HTTP_AGENT", english.headers["User-Agent"])
                    assertEquals("https://mangadex.org/", english.headers["Referer"])
                    assertEquals("https://mangadex.org", english.headers["Origin"])
                    assertEquals(
                        "Android/9 Tachiyomi/$APP_VERSION MangaDex/1.4.211 Keiyoushi",
                        english.headers["Extra"],
                    )
                } finally {
                    loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                }
            } finally {
                diContext.closeAndJoin()
            }
        } finally {
            if (previousHttpAgent == null) {
                System.clearProperty(HTTP_AGENT_PROPERTY)
            } else {
                System.setProperty(HTTP_AGENT_PROPERTY, previousHttpAgent)
            }
            Injekt = previousInjekt
        }
    }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val HTTP_AGENT_PROPERTY = "http.agent"
        const val TEST_HTTP_AGENT = "CodexTestAgent"
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.4.211.provenance.json"
    }
}
