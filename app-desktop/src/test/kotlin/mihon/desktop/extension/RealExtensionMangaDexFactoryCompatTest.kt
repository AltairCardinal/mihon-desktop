package mihon.desktop.extension

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.preference.EditTextPreference
import eu.kanade.tachiyomi.source.preference.JvmPreferenceItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.ui.extension.SourcePreferencesState
import mihon.desktop.ui.extension.resolveSourcePreferencesState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
                    listOf("blockedGroups_en", "blockedUploader_en").forEach { key ->
                        val validator = descriptorValidator(preferences.single { it.key == key })
                        assertNotNull(validator, "$key must retain its real OnBindEditTextListener")
                        requireNotNull(validator)
                        assertNull(validator(""), "$key must accept an empty value")
                        assertNull(validator(UUID_ONE), "$key must accept one UUID")
                        assertNull(validator("$UUID_ONE, $UUID_TWO"), "$key must accept comma-separated UUIDs")
                        assertEquals(INVALID_UUID_ERROR, validator("not-a-uuid"))
                    }

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
                    assertTextWatcherDispatchOrder()
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

    private fun assertTextWatcherDispatchOrder() {
        val events = mutableListOf<String>()
        EditText(Context()).apply {
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {
                        events += "before:$text:$start:$count:$after"
                    }

                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                        events += "on:$text:$start:$before:$count"
                    }

                    override fun afterTextChanged(editable: Editable?) {
                        events += "after:$editable"
                    }
                },
            )
            setText("value")
        }
        assertEquals(
            listOf("before::0:0:5", "on:value:0:0:5", "after:value"),
            events,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun descriptorValidator(preference: JvmPreferenceItem): ((String) -> String?)? {
        val getter = preference.javaClass.methods.singleOrNull { it.name == "getValidator" } ?: return null
        return getter.invoke(preference) as? (String) -> String?
    }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.4.211.provenance.json"
        const val UUID_ONE = "51d83883-4103-437c-b4b1-731cb73d786c"
        const val UUID_TWO = "0234a31e-a729-4e28-9d6a-3f87c4966b9e"
        const val INVALID_UUID_ERROR = "The text contains invalid UUIDs"
    }
}
