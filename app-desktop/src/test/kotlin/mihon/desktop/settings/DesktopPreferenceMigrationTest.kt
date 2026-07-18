package mihon.desktop.settings

import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.ui.reader.NavigationMode
import mihon.desktop.ui.reader.WebtoonAutoScrollSpeed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import java.util.Locale
import java.util.prefs.Preferences

class DesktopPreferenceMigrationTest {
    private val root = Preferences.userRoot().node("/mihon/task1a/${System.nanoTime()}")

    @AfterEach
    fun tearDown() = root.removeNode()

    private data class MigrationCase(
        val oldKey: String,
        val newKey: String,
        val oldValue: String,
        val newValue: String,
        val defaultValue: Any,
        val read: (DesktopPreferenceStore, Preferences) -> Any,
    )

    private val readerCases = listOf(
        MigrationCase("readingMode", "reader_reading_mode", "WEBTOON", "RTL", ReadingMode.LTR) { s, l -> ReaderPreferences(s, l).readingMode },
        MigrationCase("navigationMode", "reader_navigation_mode", "L", "RightAndLeft", NavigationMode.RightAndLeft) { s, l -> ReaderPreferences(s, l).navigationMode },
        MigrationCase("isDualPage", "reader_dual_page", "false", "true", true) { s, l -> ReaderPreferences(s, l).isDualPage },
        MigrationCase("autoSplitPages", "reader_auto_split_pages", "true", "false", false) { s, l -> ReaderPreferences(s, l).autoSplitPages },
        MigrationCase("autoSpreadMatching", "reader_auto_spread_matching", "true", "false", false) { s, l -> ReaderPreferences(s, l).isAutoSpreadMatching },
        MigrationCase("backgroundTheme", "reader_background_theme", "BLACK", "WHITE", ReaderBackgroundTheme.DEFAULT) { s, l -> ReaderPreferences(s, l).backgroundTheme },
        MigrationCase("cropBordersPager", "reader_crop_borders_pager", "true", "false", false) { s, l -> ReaderPreferences(s, l).cropBordersPager },
        MigrationCase("webtoonSidePadding", "reader_webtoon_side_padding", "EXTRA_LARGE", "SMALL", WebtoonSidePadding.DEFAULT) { s, l -> ReaderPreferences(s, l).webtoonSidePadding },
        MigrationCase("webtoonAutoScroll", "reader_webtoon_auto_scroll", "true", "false", false) { s, l -> ReaderPreferences(s, l).webtoonAutoScroll },
        MigrationCase("webtoonAutoScrollSpeed", "reader_webtoon_auto_scroll_speed", "Fast", "Slow", WebtoonAutoScrollSpeed.Normal) { s, l -> ReaderPreferences(s, l).webtoonAutoScrollSpeed },
        MigrationCase("cropBordersWebtoon", "reader_crop_borders_webtoon", "true", "false", false) { s, l -> ReaderPreferences(s, l).cropBordersWebtoon },
        MigrationCase("skipReadChapters", "reader_skip_read_chapters", "true", "false", false) { s, l -> ReaderPreferences(s, l).skipReadChapters },
        MigrationCase("scaleType", "reader_scale_type", "FIT_WIDTH", "FIT_HEIGHT", ScaleType.DEFAULT) { s, l -> ReaderPreferences(s, l).scaleType },
        MigrationCase("colorFilterEnabled", "reader_color_filter_enabled", "true", "false", false) { s, l -> ReaderPreferences(s, l).colorFilterEnabled },
        MigrationCase("colorFilterBrightness", "reader_color_filter_brightness", "0.5", "0.25", 0f) { s, l -> ReaderPreferences(s, l).colorFilterBrightness },
        MigrationCase("colorFilterR", "reader_color_filter_red", "10", "20", 0) { s, l -> ReaderPreferences(s, l).colorFilterR },
        MigrationCase("colorFilterG", "reader_color_filter_green", "11", "21", 0) { s, l -> ReaderPreferences(s, l).colorFilterG },
        MigrationCase("colorFilterB", "reader_color_filter_blue", "12", "22", 0) { s, l -> ReaderPreferences(s, l).colorFilterB },
        MigrationCase("colorFilterAlpha", "reader_color_filter_alpha", "100", "200", 128) { s, l -> ReaderPreferences(s, l).colorFilterAlpha },
    )

    private val appCases = listOf(
        MigrationCase("theme_mode", "theme_mode", "DARK", "LIGHT", ThemeMode.SYSTEM) { s, l -> DesktopAppPreferences(s, l).themeMode.get() },
        MigrationCase("default_reader_mode", "default_reader_mode", "WEBTOON", "PAGER", ReaderDefaultMode.PAGER) { s, l -> DesktopAppPreferences(s, l).defaultReaderMode.get() },
        MigrationCase("library_grid_columns", "library_grid_columns", "5", "6", 3) { s, l -> DesktopAppPreferences(s, l).libraryGridColumns.get() },
        MigrationCase("default_rtl", "default_rtl", "true", "false", false) { s, l -> DesktopAppPreferences(s, l).defaultRtl.get() },
        MigrationCase("incognito_mode", "incognito_mode", "true", "false", false) { s, l -> DesktopAppPreferences(s, l).incognitoMode.get() },
        MigrationCase("disabled_source_ids", "disabled_source_ids", "1,2", "3", "") { s, l -> DesktopAppPreferences(s, l).disabledSourceIds.get() },
        MigrationCase("page_turn_animation", "page_turn_animation", "false", "true", true) { s, l -> DesktopAppPreferences(s, l).pageTurnAnimation.get() },
        MigrationCase("library_update_interval", "library_update_interval", "EVERY_6H", "WEEKLY", LibraryUpdateInterval.OFF) { s, l -> DesktopAppPreferences(s, l).libraryUpdateInterval.get() },
        MigrationCase("pref_hide_missing_chapter_indicators", "pref_hide_missing_chapter_indicators", "true", "false", false) { s, l -> DesktopAppPreferences(s, l).hideMissingChapterIndicators.get() },
        MigrationCase("doh_provider", "doh_provider", "GOOGLE", "CLOUDFLARE", DohProvider.OFF) { s, l -> DesktopAppPreferences(s, l).dohProvider.get() },
        MigrationCase("update_category_includes", "update_category_includes", "1", "2", "") { s, l -> DesktopAppPreferences(s, l).updateCategoryIncludes.get() },
        MigrationCase("update_category_excludes", "update_category_excludes", "1", "2", "") { s, l -> DesktopAppPreferences(s, l).updateCategoryExcludes.get() },
        MigrationCase("local_source_root_dir", "local_source_root_dir", "old", "new", "") { s, l -> DesktopAppPreferences(s, l).localSourceRootDir.get() },
        MigrationCase("local_source_max_depth", "local_source_max_depth", "4", "5", 3) { s, l -> DesktopAppPreferences(s, l).localSourceMaxDepth.get() },
        MigrationCase("auto_backup_interval", "auto_backup_interval", "DAILY", "OFF", "OFF") { s, l -> DesktopAppPreferences(s, l).autoBackupInterval.get() },
        MigrationCase("auto_backup_max_files", "auto_backup_max_files", "3", "4", 2) { s, l -> DesktopAppPreferences(s, l).autoBackupMaxFiles.get() },
        MigrationCase("auto_backup_dir", "auto_backup_dir", "old", "new", "") { s, l -> DesktopAppPreferences(s, l).autoBackupDir.get() },
    )

    private val enumCases = listOf(
        readerCases[0],
        readerCases[1],
        readerCases[5],
        readerCases[7],
        readerCases[9],
        readerCases[12],
        appCases[0],
        appCases[1],
        appCases[7],
        appCases[9],
    )

    @TestFactory
    fun `每项偏好迁移旧值 默认值且新值优先`() = (readerCases + appCases).flatMapIndexed { index, case ->
        listOf(
            DynamicTest.dynamicTest("${case.newKey} 旧值迁移") { assertScenario(index, case, "legacy") },
            DynamicTest.dynamicTest("${case.newKey} 无值默认") { assertScenario(index, case, "default") },
            DynamicTest.dynamicTest("${case.newKey} 新值优先") { assertScenario(index, case, "new") },
        )
    }

    private fun assertScenario(index: Int, case: MigrationCase, scenario: String) {
        val node = root.node("$index-$scenario")
        val legacy = node.node("legacy")
        val current = node.node("current")
        if (scenario != "default") legacy.put(case.oldKey, case.oldValue)
        if (scenario == "new") current.put(case.newKey, case.newValue)
        val actual = case.read(DesktopPreferenceStore(current), legacy)
        val expectedRaw = if (scenario == "new") case.newValue else case.oldValue
        val expected = if (scenario == "default") case.defaultValue else parseLike(actual, expectedRaw)
        assertEquals(expected, actual, case.newKey)
        if (scenario == "legacy") assertEquals(case.oldValue, current.get(case.newKey, null), case.newKey)
    }

    private fun parseLike(sample: Any, raw: String): Any = when (sample) {
        is Boolean -> raw.toBoolean()
        is Int -> raw.toInt()
        is Float -> raw.toFloat()
        is Enum<*> -> sample::class.java.enumConstants.first { (it as Enum<*>).name == raw }
        else -> raw
    }

    @TestFactory
    fun `所有枚举的非法旧值与非法当前值均回退默认值`() = enumCases.flatMapIndexed { index, case ->
        listOf(
            DynamicTest.dynamicTest("${case.newKey} 非法旧值") {
                val node = root.node("invalid-$index-legacy")
                val legacy = node.node("legacy")
                val current = node.node("current")
                legacy.put(case.oldKey, "BROKEN")

                assertEquals(case.defaultValue, case.read(DesktopPreferenceStore(current), legacy))
                assertEquals(null, current.get(case.newKey, null), "非法旧值不得迁入 current")
            },
            DynamicTest.dynamicTest("${case.newKey} 非法当前值") {
                val node = root.node("invalid-$index-current")
                val legacy = node.node("legacy")
                val current = node.node("current")
                current.put(case.newKey, "BROKEN")

                assertEquals(case.defaultValue, case.read(DesktopPreferenceStore(current), legacy))
                assertEquals("BROKEN", current.get(case.newKey, null), "读取回退不应改写 current")
            },
        )
    }

    @Test
    fun `source preferences use fixed main keys types and defaults`() {
        val preferences = DesktopAppPreferences(DesktopPreferenceStore(root.node("source-defaults")))

        assertEquals(setOf("all", "en", Locale.getDefault().language), preferences.enabledLanguages.get())
        assertEquals("source_languages", preferences.enabledLanguages.key())
        assertEquals(emptySet<String>(), preferences.disabledSources.get())
        assertEquals("hidden_catalogues", preferences.disabledSources.key())
        assertEquals(emptySet<String>(), preferences.pinnedSources.get())
        assertEquals("pinned_catalogues", preferences.pinnedSources.key())
        assertEquals(false, preferences.globalSearchFilterState.get())
        assertEquals(Preference.appStateKey("has_filters_toggle_state"), preferences.globalSearchFilterState.key())
    }

    @Test
    fun `legacy disabled source ids migrate once when fixed main key is absent`() {
        val store = DesktopPreferenceStore(root.node("source-legacy"))
        val legacy = store.getString("disabled_source_ids", "")
        legacy.set("42, 7,broken")

        val preferences = DesktopAppPreferences(store)

        assertEquals(setOf("42", "7"), preferences.disabledSources.get())
        legacy.set("99")
        assertEquals(setOf("42", "7"), DesktopAppPreferences(store).disabledSources.get())
    }

    @Test
    fun `fixed main disabled source key wins over legacy desktop value including empty`() {
        val store = DesktopPreferenceStore(root.node("source-current"))
        store.getString("disabled_source_ids", "").set("42")
        store.getStringSet("hidden_catalogues", emptySet()).set(emptySet())

        assertEquals(emptySet<String>(), DesktopAppPreferences(store).disabledSources.get())
    }
}
