package mihon.desktop.extension

import android.widget.EditText
import androidx.preference.EditTextPreference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.ui.extension.SourcePreferencesState
import mihon.desktop.ui.extension.resolveSourcePreferencesState
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
class RealExtensionMangaDexFactoryCompatTest {

    @Test
    fun `real MangaDex factory verifier links Android text callback descriptors`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val previousInjekt = Injekt
        try {
            val provenance = Json.parseToJsonElement(
                Files.readString(repositoryRoot().resolve(PROVENANCE_PATH)),
            ).jsonObject
            val apkPath = repositoryRoot().resolve(provenance.getValue("fixturePath").jsonPrimitive.content)
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
                    val english = loaded.single { it.source.lang == "en" }
                    assertEquals("MangaDex", english.source.name)

                    val preferenceState = resolveSourcePreferencesState(english.source)
                    assertTrue(preferenceState is SourcePreferencesState.Content) {
                        val error = (preferenceState as? SourcePreferencesState.SetupFailure)?.error
                        "MangaDex preferences failed: ${error?.javaClass?.name}: ${error?.message}"
                    }
                    val preferences = (preferenceState as SourcePreferencesState.Content).items
                    assertEquals(12, preferences.size)
                    assertEquals("Cover quality", preferences.single { it.key == "thumbnailQuality_en" }.title)
                    assertEquals("Block groups by UUID", preferences.single { it.key == "blockedGroups_en" }.title)

                    val listenerType = EditTextPreference.OnBindEditTextListener::class.java
                    assertEquals(
                        Void.TYPE,
                        listenerType.getDeclaredMethod("onBindEditText", EditText::class.java).returnType,
                    )
                    listOf("j2", "l2").forEach { className ->
                        val implementation = english.classLoader.loadClass(className)
                        assertTrue(listenerType.isAssignableFrom(implementation))
                        assertEquals(
                            Void.TYPE,
                            implementation.getDeclaredMethod("onBindEditText", EditText::class.java).returnType,
                        )
                    }

                    assertTextWatcherDescriptors()
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

    private fun assertTextWatcherDescriptors() {
        val editable = Class.forName("android.text.Editable")
        val watcher = Class.forName("android.text.TextWatcher")
        assertTrue(CharSequence::class.java.isAssignableFrom(editable))
        assertEquals(
            Void.TYPE,
            watcher.getDeclaredMethod(
                "beforeTextChanged",
                CharSequence::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).returnType,
        )
        assertEquals(
            Void.TYPE,
            watcher.getDeclaredMethod(
                "onTextChanged",
                CharSequence::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).returnType,
        )
        assertEquals(Void.TYPE, watcher.getDeclaredMethod("afterTextChanged", editable).returnType)
    }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.4.211.provenance.json"
    }
}
