package mihon.desktop.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.net.Proxy

/** RED — DesktopAppPreferences and ThemeMode do not exist yet. */
class DesktopAppPreferencesTest {

    private fun prefs() = DesktopAppPreferences(InMemoryPreferenceStore())

    @Test
    fun `default theme is SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, prefs().themeMode.get())
    }

    @Test
    fun `default reader mode is PAGER`() {
        assertEquals(ReaderDefaultMode.PAGER, prefs().defaultReaderMode.get())
    }

    @Test
    fun `default grid columns is 3`() {
        assertEquals(3, prefs().libraryGridColumns.get())
    }

    @Test
    fun `default rtl is false`() {
        assertFalse(prefs().defaultRtl.get())
    }

    @Test
    fun `theme preference round-trips DARK`() {
        val p = prefs()
        p.themeMode.set(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, p.themeMode.get())
    }

    @Test
    fun `reader mode preference round-trips WEBTOON`() {
        val p = prefs()
        p.defaultReaderMode.set(ReaderDefaultMode.WEBTOON)
        assertEquals(ReaderDefaultMode.WEBTOON, p.defaultReaderMode.get())
    }

    @Test
    fun `grid columns preference round-trips 4`() {
        val p = prefs()
        p.libraryGridColumns.set(4)
        assertEquals(4, p.libraryGridColumns.get())
    }

    @Test
    fun `rtl preference round-trips true`() {
        val p = prefs()
        p.defaultRtl.set(true)
        assertEquals(true, p.defaultRtl.get())
    }

    // ─── local source max depth ──────────────────────────────────────────────────

    @Test
    fun `default local source max depth is 3`() {
        assertEquals(3, prefs().localSourceMaxDepth.get())
    }

    @Test
    fun `local source max depth round-trips 5`() {
        val p = prefs()
        p.localSourceMaxDepth.set(5)
        assertEquals(5, p.localSourceMaxDepth.get())
    }

    @Test
    fun `hide missing chapter indicators defaults to false`() {
        assertFalse(prefs().hideMissingChapterIndicators.get())
    }

    @Test
    fun `hide missing chapter indicators round-trips true`() {
        val p = prefs()
        p.hideMissingChapterIndicators.set(true)
        assertEquals(true, p.hideMissingChapterIndicators.get())
    }

    @Test
    fun `hide missing chapter indicators uses original Mihon preference key`() {
        assertEquals("pref_hide_missing_chapter_indicators", prefs().hideMissingChapterIndicators.key())
    }

    @Test
    fun `global network follows system by default`() {
        val preferences = prefs()

        assertEquals(GlobalNetworkMode.SYSTEM, preferences.globalNetworkMode.get())
        assertEquals("", preferences.proxyUrl.get())
        assertNull(preferences.proxyRuntimeConfig())
    }

    @Test
    fun `legacy enabled proxy migrates to manual mode without losing its URL`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("network_proxy_enabled", true, false),
                InMemoryPreferenceStore.InMemoryPreference(
                    "network_proxy_url",
                    "http://127.0.0.1:10808",
                    "",
                ),
            ),
        )

        val preferences = DesktopAppPreferences(store)

        assertEquals(GlobalNetworkMode.MANUAL, preferences.globalNetworkMode.get())
        assertEquals("http://127.0.0.1:10808", preferences.proxyUrl.get())
        assertEquals(
            DesktopProxyRuntimeConfig(Proxy.Type.HTTP, "127.0.0.1", 10808),
            preferences.proxyRuntimeConfig(),
        )
    }

    @Test
    fun `manual HTTP proxy URL produces runtime config`() {
        val preferences = prefs()
        preferences.globalNetworkMode.set(GlobalNetworkMode.MANUAL)
        preferences.proxyUrl.set(" http://127.0.0.1:10808 ")

        assertEquals(
            DesktopProxyRuntimeConfig(Proxy.Type.HTTP, "127.0.0.1", 10808),
            preferences.proxyRuntimeConfig(),
        )
    }

    @Test
    fun `manual SOCKS5 proxy URL produces runtime config`() {
        val preferences = prefs()
        preferences.globalNetworkMode.set(GlobalNetworkMode.MANUAL)
        preferences.proxyUrl.set("socks5://localhost:7891")

        assertEquals(
            DesktopProxyRuntimeConfig(Proxy.Type.SOCKS, "localhost", 7891),
            preferences.proxyRuntimeConfig(),
        )
    }

    @Test
    fun `invalid or authenticated proxy URL is rejected`() {
        val preferences = prefs()
        preferences.globalNetworkMode.set(GlobalNetworkMode.MANUAL)

        listOf(
            "127.0.0.1:10808",
            "ftp://127.0.0.1:10808",
            "http://user:password@127.0.0.1:10808",
            "http://127.0.0.1:70000",
        ).forEach { value ->
            preferences.proxyUrl.set(value)
            assertNull(preferences.proxyRuntimeConfig(), value)
        }
    }

    @Test
    fun `direct and system modes never expose manual runtime proxy`() {
        val preferences = prefs()
        preferences.proxyUrl.set("http://127.0.0.1:10808")

        preferences.globalNetworkMode.set(GlobalNetworkMode.DIRECT)
        assertNull(preferences.proxyRuntimeConfig())
        preferences.globalNetworkMode.set(GlobalNetworkMode.SYSTEM)
        assertNull(preferences.proxyRuntimeConfig())
    }

    @Test
    fun `plugin network policy defaults to inherit and round trips all overrides`() {
        val preferences = prefs()
        val packageName = "eu.kanade.tachiyomi.extension.en.example"

        assertEquals(PluginNetworkMode.INHERIT_GLOBAL, preferences.pluginNetworkMode(packageName).get())
        PluginNetworkMode.entries.forEach { mode ->
            preferences.pluginNetworkMode(packageName).set(mode)
            assertEquals(mode, preferences.pluginNetworkMode(packageName).get())
        }
        preferences.pluginProxyUrl(packageName).set("socks5://127.0.0.1:7890")
        assertEquals(
            DesktopProxyRuntimeConfig(Proxy.Type.SOCKS, "127.0.0.1", 7890),
            preferences.pluginProxyRuntimeConfig(packageName),
        )
    }

    @Test
    fun `plugin manual proxy is ignored outside manual mode`() {
        val preferences = prefs()
        val packageName = "pkg.example"
        preferences.pluginProxyUrl(packageName).set("http://127.0.0.1:8080")

        preferences.pluginNetworkMode(packageName).set(PluginNetworkMode.DIRECT)
        assertNull(preferences.pluginProxyRuntimeConfig(packageName))
        preferences.pluginNetworkMode(packageName).set(PluginNetworkMode.SYSTEM)
        assertNull(preferences.pluginProxyRuntimeConfig(packageName))
    }

    @Test
    fun `plugin domain export target is remembered per plugin`() {
        val preferences = prefs()

        assertEquals("PROXY", preferences.pluginDomainExportTarget("pkg.one").get())
        preferences.pluginDomainExportTarget("pkg.one").set("漫画源")

        assertEquals("漫画源", preferences.pluginDomainExportTarget("pkg.one").get())
        assertEquals("PROXY", preferences.pluginDomainExportTarget("pkg.two").get())
    }
}
