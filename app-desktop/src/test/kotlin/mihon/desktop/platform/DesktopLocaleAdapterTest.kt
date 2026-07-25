package mihon.desktop.platform

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import java.util.Locale

@org.junit.jupiter.api.parallel.Isolated
class DesktopLocaleAdapterTest {
    private val originalLocale = Locale.getDefault()

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `empty application language follows the captured system locale`() {
        val preference = DesktopAppPreferences(InMemoryPreferenceStore()).appLanguage
        val applied = mutableListOf<Locale>()
        val adapter = DesktopLocaleAdapter(preference, Locale.CANADA_FRENCH, applied::add)

        val result = adapter.applyPersisted()

        assertInstanceOf(DesktopLocaleApplyResult.Applied::class.java, result)
        assertEquals("", adapter.activeLanguageTag.value)
        assertEquals(listOf(Locale.CANADA_FRENCH), applied)
        assertFalse(preference.isSet())
    }

    @Test
    fun `valid BCP47 selection is canonicalized persisted and applied`() {
        val preference = DesktopAppPreferences(InMemoryPreferenceStore()).appLanguage
        val applied = mutableListOf<Locale>()
        val adapter = DesktopLocaleAdapter(preference, Locale.US, applied::add)

        val result = adapter.select("ZH-cn")

        assertInstanceOf(DesktopLocaleApplyResult.Applied::class.java, result)
        assertEquals("zh-CN", preference.get())
        assertEquals("zh-CN", adapter.activeLanguageTag.value)
        assertEquals(listOf(Locale.forLanguageTag("zh-CN")), applied)
    }

    @Test
    fun `Chinese options keep fixed main script display semantics and resources`() {
        val preference = DesktopAppPreferences(InMemoryPreferenceStore()).appLanguage
        val adapter = DesktopLocaleAdapter(preference, Locale.US, Locale::setDefault)
        val options = adapter.availableLanguages(Locale.US)
        val simplified = options.single { it.languageTag == "zh-CN" }
        val traditional = options.single { it.languageTag == "zh-TW" }

        assertEquals(fixedMainDisplayName("zh-Hans", Locale.forLanguageTag("zh-Hans")), simplified.displayName)
        assertEquals(fixedMainDisplayName("zh-Hant", Locale.forLanguageTag("zh-Hant")), traditional.displayName)
        assertEquals(fixedMainDisplayName("zh-Hans", Locale.US), simplified.localizedDisplayName)
        assertEquals(fixedMainDisplayName("zh-Hant", Locale.US), traditional.localizedDisplayName)

        adapter.select("zh-CN")
        assertEquals(
            MR.strings.pref_app_language.localized(Locale.forLanguageTag("zh-CN")),
            MR.strings.pref_app_language.localized(),
        )
        adapter.select("zh-TW")
        assertEquals(
            MR.strings.pref_app_language.localized(Locale.forLanguageTag("zh-TW")),
            MR.strings.pref_app_language.localized(),
        )
    }

    @Test
    fun `invalid or unavailable persisted tag safely falls back to system`() {
        listOf("not_a_tag", "en-US").forEach { stored ->
            val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
            preferences.appLanguage.set(stored)
            val applied = mutableListOf<Locale>()
            val adapter = DesktopLocaleAdapter(preferences.appLanguage, Locale.JAPAN, applied::add)

            val result = adapter.applyPersisted()

            assertInstanceOf(DesktopLocaleApplyResult.Fallback::class.java, result)
            assertEquals("", preferences.appLanguage.get())
            assertEquals("", adapter.activeLanguageTag.value)
            assertEquals(Locale.JAPAN, applied.last())
        }
    }

    @Test
    fun `failed persisted canonical or invalid repair never reports success`() {
        listOf(
            "ZH-cn" to "zh-CN",
            "not_a_tag" to "",
        ).forEach { (stored, expectedActive) ->
            val delegate = DesktopAppPreferences(InMemoryPreferenceStore()).appLanguage
            delegate.set(stored)
            val preference = FailingSetPreference(delegate).also { it.failWrites = true }
            var jvmLocale = Locale.US
            val adapter = DesktopLocaleAdapter(preference, Locale.US) { jvmLocale = it }

            val result = adapter.applyPersisted()

            assertInstanceOf(DesktopLocaleApplyResult.Failed::class.java, result)
            assertEquals(stored, delegate.get())
            assertEquals(expectedActive, adapter.activeLanguageTag.value)
            assertEquals(
                if (expectedActive.isEmpty()) Locale.US else Locale.forLanguageTag(expectedActive),
                jvmLocale,
            )
        }
    }

    @Test
    fun `failed preference write preserves the prior selection and JVM locale`() {
        val delegate = DesktopAppPreferences(InMemoryPreferenceStore()).appLanguage
        delegate.set("en")
        val preference = FailingSetPreference(delegate)
        val applied = mutableListOf<Locale>()
        val adapter = DesktopLocaleAdapter(preference, Locale.US, applied::add)
        adapter.applyPersisted()
        preference.failWrites = true

        val result = adapter.select("zh-CN")

        assertInstanceOf(DesktopLocaleApplyResult.Failed::class.java, result)
        assertEquals("en", delegate.get())
        assertEquals("en", adapter.activeLanguageTag.value)
        assertTrue(applied.isNotEmpty())
        assertTrue(applied.all { it == Locale.ENGLISH })
    }

    @Test
    fun `JVM apply failure rolls back the persisted and active locale atomically`() {
        val preference = DesktopAppPreferences(InMemoryPreferenceStore()).appLanguage
        preference.set("en")
        var jvmLocale = Locale.ENGLISH
        var failChinese = true
        val adapter = DesktopLocaleAdapter(preference, Locale.US) { locale ->
            jvmLocale = locale
            if (failChinese && locale.toLanguageTag() == "zh-CN") {
                failChinese = false
                error("JVM locale apply failed after mutation")
            }
        }
        adapter.applyPersisted()

        val result = adapter.select("zh-CN")

        assertInstanceOf(DesktopLocaleApplyResult.Failed::class.java, result)
        assertEquals("en", preference.get())
        assertEquals("en", adapter.activeLanguageTag.value)
        assertEquals(Locale.ENGLISH, jvmLocale)
    }

    @Test
    fun `rollback failure reconciles JVM and active state to the final persisted legal tag`() {
        val delegate = DesktopAppPreferences(InMemoryPreferenceStore()).appLanguage
        delegate.set("en")
        val preference = RollbackFailingPreference(delegate)
        var jvmLocale = Locale.ENGLISH
        var failFirstChineseApply = true
        val adapter = DesktopLocaleAdapter(preference, Locale.US) { locale ->
            jvmLocale = locale
            if (failFirstChineseApply && locale.toLanguageTag() == "zh-CN") {
                failFirstChineseApply = false
                error("JVM locale apply failed after mutation")
            }
        }
        adapter.applyPersisted()

        val result = adapter.select("zh-CN")

        assertInstanceOf(DesktopLocaleApplyResult.Failed::class.java, result)
        assertEquals("zh-CN", delegate.get())
        assertEquals("zh-CN", adapter.activeLanguageTag.value)
        assertEquals(Locale.forLanguageTag("zh-CN"), jvmLocale)
        assertInstanceOf(
            DesktopLocaleApplyResult.Failed::class.java,
            requireNotNull(adapter.pendingFeedback.value).result,
        )
    }

    @Test
    fun `language list is the fixed main app locale list and keeps default separate`() {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val adapter = DesktopLocaleAdapter(preferences.appLanguage, Locale.US, Locale::setDefault)
        val expected = listOf(
            "am", "ar", "as", "be", "bg", "bn", "ca", "ceb", "cs", "cv", "da", "de", "el", "en",
            "eo", "es", "eu", "fa", "fi", "fil", "fr", "gl", "he", "hi", "hr", "hu", "in", "it",
            "ja", "jv", "ka-GE", "kk", "km", "kn", "ko", "lt", "lv", "ml", "mr", "ms", "my",
            "nb-NO", "ne", "nl", "nn", "pl", "pt", "pt-BR", "ro", "ru", "sa", "sah", "sc", "sdh",
            "sk", "sq", "sr", "sv", "ta", "te", "th", "tr", "uk", "uz", "vi", "zh-CN", "zh-TW",
        )

        assertEquals(67, expected.size)
        assertEquals(expected, adapter.authoritativeLanguageTags)
        assertFalse(adapter.availableLanguages().any { it.languageTag.isEmpty() })
        assertTrue(adapter.availableLanguages().any { it.languageTag == "en" })
        assertTrue(adapter.availableLanguages().any { it.languageTag == "zh-CN" })
        assertTrue(adapter.availableLanguages().any { it.languageTag == "zh-TW" })
        assertFalse(adapter.availableLanguages().any { it.languageTag == "all" })
        assertFalse(adapter.availableLanguages().any { it.languageTag == "other" })
        assertTrue(adapter.availableLanguages().any { it.languageTag == "in" })
        assertFalse(adapter.availableLanguages().any { it.languageTag == "id" })
        assertEquals(
            adapter.availableLanguages().map { it.displayName }.sorted(),
            adapter.availableLanguages().map { it.displayName },
        )

        adapter.select("id")
        assertEquals("in", preferences.appLanguage.get())
        assertEquals("in", adapter.activeLanguageTag.value)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `localized desktop root recreates remembered resources immediately after selection`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val adapter = DesktopLocaleAdapter(preferences.appLanguage, Locale.US, Locale::setDefault)
        adapter.applyPersisted()
        var rememberedCopy = ""
        val scene = ImageComposeScene(200, 200, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                adapter.Provide {
                    rememberedCopy = remember { MR.strings.pref_app_language.localized() }
                }
            }
            repeat(2) { scene.render(); yield() }
            assertEquals(MR.strings.pref_app_language.localized(Locale.US), rememberedCopy)

            adapter.select("zh-CN")
            repeat(3) { scene.render(); yield() }

            assertEquals(MR.strings.pref_app_language.localized(Locale.forLanguageTag("zh-CN")), rememberedCopy)
        } finally {
            scene.close()
        }
    }

    private fun fixedMainDisplayName(languageTag: String, displayLocale: Locale): String {
        val locale = Locale.forLanguageTag(languageTag)
        return locale.getDisplayName(displayLocale).replaceFirstChar { it.uppercase(locale) }
    }

    private class FailingSetPreference(
        private val delegate: Preference<String>,
    ) : Preference<String> by delegate {
        var failWrites = false

        override fun set(value: String) {
            if (failWrites) error("language preference write failed")
            delegate.set(value)
        }
    }

    private class RollbackFailingPreference(
        private val delegate: Preference<String>,
    ) : Preference<String> by delegate {
        override fun set(value: String) {
            if (delegate.get() == "zh-CN" && value == "en") error("preference rollback failed")
            delegate.set(value)
        }
    }
}
