package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class ExtensionLanguageFilterTest {

    private fun fakeSource(lang: String): Source = object : Source {
        override val id: Long = lang.hashCode().toLong()
        override val name: String = "Fake $lang"
        override val lang: String = lang
        override fun toString() = name
    }

    private fun installed(vararg langs: String) = InstalledExtension(
        jarFile = File("fake-${langs.first()}.jar"),
        sources = langs.map { fakeSource(it) },
    )

    private fun available(lang: String) = DesktopAvailableExtension(
        name = "Ext $lang",
        pkgName = "eu.kanade.ext.$lang",
        versionName = "1.0",
        versionCode = 1L,
        lang = lang,
        isNsfw = false,
        jarUrl = "https://example.com/$lang.jar",
        iconUrl = "https://example.com/$lang.png",
        repoUrl = "https://example.com",
    )

    // ── filterAvailableByLangs ──────────────────────────────────────────

    @Test
    fun `filterAvailableByLangs with empty selection returns all`() {
        val extensions = listOf(available("en"), available("zh"), available("ja"))
        val result = filterAvailableByLangs(extensions, emptySet())
        assertEquals(extensions, result)
    }

    @Test
    fun `filterAvailableByLangs with selection returns matching only`() {
        val en = available("en")
        val zh = available("zh")
        val ja = available("ja")
        val result = filterAvailableByLangs(listOf(en, zh, ja), setOf("zh", "ja"))
        assertEquals(listOf(zh, ja), result)
    }

    @Test
    fun `filterAvailableByLangs with no match returns empty list`() {
        val result = filterAvailableByLangs(listOf(available("en")), setOf("zh"))
        assertEquals(emptyList<DesktopAvailableExtension>(), result)
    }

    // ── filterInstalledByLangs ──────────────────────────────────────────

    @Test
    fun `filterInstalledByLangs with empty selection returns all`() {
        val extensions = listOf(installed("en"), installed("zh"))
        val result = filterInstalledByLangs(extensions, emptySet())
        assertEquals(extensions, result)
    }

    @Test
    fun `filterInstalledByLangs keeps extension if any source lang matches`() {
        val multi = installed("en", "zh")
        val jaOnly = installed("ja")
        val result = filterInstalledByLangs(listOf(multi, jaOnly), setOf("zh"))
        assertEquals(listOf(multi), result)
    }

    @Test
    fun `filterInstalledByLangs with no match returns empty list`() {
        val result = filterInstalledByLangs(listOf(installed("en")), setOf("zh"))
        assertEquals(emptyList<InstalledExtension>(), result)
    }

    // ── availableLangs ──────────────────────────────────────────────────

    @Test
    fun `availableLangs returns sorted unique lang codes from list`() {
        val extensions = listOf(available("zh"), available("en"), available("zh"), available("ja"))
        assertEquals(listOf("en", "ja", "zh"), availableLangs(extensions))
    }

    @Test
    fun `availableLangs on empty list returns empty`() {
        assertEquals(emptyList<String>(), availableLangs(emptyList()))
    }

    // ── installedLangs ──────────────────────────────────────────────────

    @Test
    fun `installedLangs returns sorted unique lang codes from all sources`() {
        val extensions = listOf(installed("zh", "en"), installed("ja"))
        assertEquals(listOf("en", "ja", "zh"), installedLangs(extensions))
    }
}
