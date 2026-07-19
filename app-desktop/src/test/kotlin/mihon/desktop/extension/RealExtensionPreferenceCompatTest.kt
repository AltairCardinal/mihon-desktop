package mihon.desktop.extension

import eu.kanade.tachiyomi.source.preference.EditTextPreference
import eu.kanade.tachiyomi.source.preference.MultiSelectListPreference
import eu.kanade.tachiyomi.source.preference.SwitchPreference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.ui.extension.SourcePreferencesState
import mihon.desktop.ui.extension.resolveSourcePreferencesState
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
class RealExtensionPreferenceCompatTest {

    @Test
    fun `EditTextPreference keeps legacy JVM constructor descriptors`() {
        val type = EditTextPreference::class.java
        assertNotNull(type.getConstructor(String::class.java, String::class.java, String::class.java))
        assertNotNull(
            type.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            ),
        )
    }

    @Test
    fun `real Comix conversion loads after the WebView verifier closure`(
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
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable Comix fixture: $apkPath")
            assertEquals(provenance.string("sizeBytes").toLong(), Files.size(apkPath))
            assertEquals(provenance.string("sha256"), sha256(apkPath))
            assertEquals(EXTENSION_CLASS, ManifestClassExtractor.extractFromApk(apkPath.toFile()))
            assertEquals(PACKAGE_NAME, provenance.string("packageName"))
            assertEquals(VERSION_CODE, provenance.string("versionCode").toLong())
            assertEquals(VERSION_NAME, provenance.string("versionName"))
            assertEquals(EXTENSION_CLASS, provenance.string("extensionClass"))
            assertEquals("success", provenance.string("expectedOutcome"))

            val diContext = initDesktopDIForTest(
                appDir = tempDir.resolve("app").toFile(),
                preferenceStore = DesktopPreferenceStore(),
            )
            try {
                val convertedJar = ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile())
                assertNotNull(convertedJar, "Production converter rejected the immutable Comix APK")
                val jar = requireNotNull(convertedJar)
                writeExtensionMeta(
                    jar,
                    ExtensionMeta(
                        pkgName = PACKAGE_NAME,
                        versionCode = VERSION_CODE,
                        versionName = VERSION_NAME,
                        artifactSha256 = provenance.string("sha256"),
                        source = ExtensionOrigin.CONVERTED_APK,
                        name = "Comix",
                        language = "en",
                        extensionClass = EXTENSION_CLASS,
                    ),
                )
                val loader = DesktopExtensionLoader(tempDir.toFile())
                val loaded = loader.loadFromSingleJar(jar)
                try {
                    assertEquals(1, loaded.size, "Comix did not load through production Desktop DI")
                    assertTrue(
                        loader.diagnostics.isEmpty(),
                        "Comix failed in outer loader wiring: ${loader.diagnostics}",
                    )
                    val source = loaded.single().source
                    assertEquals(EXTENSION_CLASS, source.javaClass.name)
                    val codeSource = java.io.File(source.javaClass.protectionDomain.codeSource.location.toURI())
                    assertEquals(jar.canonicalFile, codeSource.canonicalFile)

                    val state = resolveSourcePreferencesState(source)
                    assertTrue(state is SourcePreferencesState.Content, "Comix preferences were not resolved: $state")
                    val items = (state as SourcePreferencesState.Content).items
                    val multiSelects = items.filterIsInstance<MultiSelectListPreference>()
                    val switches = items.filterIsInstance<SwitchPreference>()
                    val editTexts = items.filterIsInstance<EditTextPreference>()
                    assertEquals(3, multiSelects.size)
                    assertEquals(4, switches.size)
                    assertEquals(1, editTexts.size)
                    val defaultTypes = multiSelects.single { it.key == "pref_default_types" }
                    assertEquals("Default types", defaultTypes.title)
                    assertEquals(listOf("Manga", "Manhwa", "Manhua", "Other"), defaultTypes.entries)
                    assertEquals(listOf("manga", "manhwa", "manhua", "other"), defaultTypes.entryValues)
                    assertEquals(setOf("manga", "manhwa", "manhua", "other"), defaultTypes.defaultValue)

                    val defaultDemographics = multiSelects.single { it.key == "pref_default_demographics" }
                    assertEquals("Default demographics", defaultDemographics.title)
                    assertEquals(listOf("Josei", "Seinen", "Shoujo", "Shounen"), defaultDemographics.entries)
                    assertEquals(listOf("3", "4", "1", "2"), defaultDemographics.entryValues)
                    assertEquals(setOf("3", "4", "1", "2"), defaultDemographics.defaultValue)

                    val blockedGenres = multiSelects.single { it.key == "pref_blocked_genres" }
                    assertEquals("Blocked genres", blockedGenres.title)
                    assertTrue(blockedGenres.entries.isNotEmpty())
                    assertEquals(blockedGenres.entries.size, blockedGenres.entryValues.size)
                    assertEquals(emptySet<String>(), blockedGenres.defaultValue)

                    assertEquals(
                        listOf(
                            Triple("pref_deduplicate_chapters", "Deduplicate Chapters", false),
                            Triple("pref_alt_names_in_description", "Show Alternative Names in Description", false),
                            Triple("pref_show_extra_info", "Show extra info in description", true),
                            Triple("pref_show_tags_in_genres", "Show tags in genre chips", false),
                        ),
                        switches.map { Triple(it.key, it.title, it.defaultValue) },
                    )

                    val scanlatorBlacklist = editTexts.single()
                    assertEquals("pref_scanlator_blacklist", scanlatorBlacklist.key)
                    assertEquals("Scanlator Blacklist", scanlatorBlacklist.title)
                    assertEquals(
                        "Filter out chapters from specific groups. Comma-separated list of group names or group IDs " +
                            "(e.g., 'Violet Scans, 307').",
                        scanlatorBlacklist.summary,
                    )
                    assertEquals("", scanlatorBlacklist.defaultValue)
                    assertEquals("Exclude groups", scanlatorBlacklist.dialogTitle)
                } finally {
                    loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                }
            } finally {
                diContext.closeAndJoin()
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
            "app-desktop/src/test/resources/extensions/real/keiyoushi-comix-1.4.34.provenance.json"
        const val AUTHORITY_REF = "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
        const val REPOSITORY_COMMIT = "7d5052fb895d086ae2ec6e3cca861146ee3ea0ec"
        const val GIT_BLOB = "ebade6b9ed19d1ba02ac67c377cef31caa0bb0c7"
        const val RETRIEVED_AT = "2026-07-19"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.en.comix"
        const val VERSION_CODE = 34L
        const val VERSION_NAME = "1.4.34"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.en.comix.ExtensionGenerated"
        const val RAW_URL =
            "https://raw.githubusercontent.com/keiyoushi/extensions/$REPOSITORY_COMMIT/" +
                "apk/tachiyomi-en.comix-v1.4.34.apk"
        val PROVENANCE_FIELDS = setOf(
            "authorityRef", "repository", "repositoryCommit", "gitBlob", "license", "fixturePath", "sha256",
            "sizeBytes", "packageName", "versionCode", "versionName", "extensionClass", "expectedOutcome", "rawUrl",
            "retrievedAt",
        )
    }
}
