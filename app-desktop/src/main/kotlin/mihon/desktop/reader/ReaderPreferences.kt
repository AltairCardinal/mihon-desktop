package mihon.desktop.reader

import mihon.desktop.settings.migrateFrom
import mihon.desktop.ui.reader.NavigationMode
import mihon.desktop.ui.reader.WebtoonAutoScrollSpeed
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.util.prefs.Preferences
import kotlin.reflect.KProperty

private operator fun <T> Preference<T>.getValue(thisRef: Any?, property: KProperty<*>): T = get()
private operator fun <T> Preference<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)

/** Reader preferences eagerly migrate every public setting during construction. */
class ReaderPreferences(
    store: PreferenceStore = DesktopPreferenceStore(),
    private val legacy: Preferences = Preferences.userRoot().node("mihon/desktop/reader"),
) {
    private fun <T> Preference<T>.legacy(key: String, read: () -> T?): Preference<T> =
        migrateFrom(legacy, key) { read() }

    private val readingModePref = store.getEnum("reader_reading_mode", ReadingMode.RTL)
        .legacy("readingMode") { legacy.get("readingMode", null)?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() } }
    private val navigationModePref = store.getEnum("reader_navigation_mode", NavigationMode.RightAndLeft)
        .legacy("navigationMode") { legacy.get("navigationMode", null)?.let { runCatching { NavigationMode.valueOf(it) }.getOrNull() } }
    private val dualPagePref = store.getBoolean("reader_dual_page", false).legacy("isDualPage") { legacy.getBoolean("isDualPage", false) }
    private val autoSplitPref = store.getBoolean("reader_auto_split_pages", false).legacy("autoSplitPages") { legacy.getBoolean("autoSplitPages", false) }
    private val autoSpreadPref = store.getBoolean("reader_auto_spread_matching", false).legacy("autoSpreadMatching") { legacy.getBoolean("autoSpreadMatching", false) }
    private val backgroundPref = store.getEnum("reader_background_theme", ReaderBackgroundTheme.DEFAULT)
        .legacy("backgroundTheme") { legacy.get("backgroundTheme", null)?.let { runCatching { ReaderBackgroundTheme.valueOf(it) }.getOrNull() } }
    private val cropPagerPref = store.getBoolean("reader_crop_borders_pager", false).legacy("cropBordersPager") { legacy.getBoolean("cropBordersPager", false) }
    private val paddingPref = store.getEnum("reader_webtoon_side_padding", WebtoonSidePadding.DEFAULT)
        .legacy("webtoonSidePadding") { legacy.get("webtoonSidePadding", null)?.let { runCatching { WebtoonSidePadding.valueOf(it) }.getOrNull() } }
    private val autoScrollPref = store.getBoolean("reader_webtoon_auto_scroll", false).legacy("webtoonAutoScroll") { legacy.getBoolean("webtoonAutoScroll", false) }
    private val autoScrollSpeedPref = store.getEnum("reader_webtoon_auto_scroll_speed", WebtoonAutoScrollSpeed.Normal)
        .legacy("webtoonAutoScrollSpeed") { legacy.get("webtoonAutoScrollSpeed", null)?.let { runCatching { WebtoonAutoScrollSpeed.valueOf(it) }.getOrNull() } }
    private val cropWebtoonPref = store.getBoolean("reader_crop_borders_webtoon", false).legacy("cropBordersWebtoon") { legacy.getBoolean("cropBordersWebtoon", false) }
    private val skipReadPref = store.getBoolean("reader_skip_read_chapters", false).legacy("skipReadChapters") { legacy.getBoolean("skipReadChapters", false) }
    private val skipFilteredPref = store.getBoolean("reader_skip_filtered_chapters", false).legacy("skipFilteredChapters") { legacy.getBoolean("skipFilteredChapters", false) }
    private val skipDuplicatePref = store.getBoolean("reader_skip_duplicate_chapters", false).legacy("skipDuplicateChapters") { legacy.getBoolean("skipDuplicateChapters", false) }
    private val scaleTypePref = store.getEnum("reader_scale_type", ScaleType.DEFAULT)
        .legacy("scaleType") { legacy.get("scaleType", null)?.let { runCatching { ScaleType.valueOf(it) }.getOrNull() } }
    private val filterEnabledPref = store.getBoolean("reader_color_filter_enabled", false).legacy("colorFilterEnabled") { legacy.getBoolean("colorFilterEnabled", false) }
    private val brightnessPref = store.getFloat("reader_color_filter_brightness", 0f).legacy("colorFilterBrightness") { legacy.getFloat("colorFilterBrightness", 0f) }
    private val brightnessEnabledPref = store.getBoolean("reader_color_filter_brightness_enabled", false).apply {
        if (!isSet() && filterEnabledPref.get() && brightnessPref.get() != 0f) set(true)
    }
    private val redPref = store.getInt("reader_color_filter_red", 0).legacy("colorFilterR") { legacy.getInt("colorFilterR", 0) }
    private val greenPref = store.getInt("reader_color_filter_green", 0).legacy("colorFilterG") { legacy.getInt("colorFilterG", 0) }
    private val bluePref = store.getInt("reader_color_filter_blue", 0).legacy("colorFilterB") { legacy.getInt("colorFilterB", 0) }
    private val alphaPref = store.getInt("reader_color_filter_alpha", 128).legacy("colorFilterAlpha") { legacy.getInt("colorFilterAlpha", 128) }
    private val grayscalePref = store.getBoolean("reader_color_filter_grayscale", false).legacy("colorFilterGrayscale") { legacy.getBoolean("colorFilterGrayscale", false) }
    private val invertPref = store.getBoolean("reader_color_filter_invert", false).legacy("colorFilterInvert") { legacy.getBoolean("colorFilterInvert", false) }

    var readingMode by readingModePref
    var navigationMode by navigationModePref
    var isDualPage by dualPagePref
    var autoSplitPages by autoSplitPref
    var isAutoSpreadMatching by autoSpreadPref
    var backgroundTheme by backgroundPref
    var cropBordersPager by cropPagerPref
    var webtoonSidePadding by paddingPref
    var webtoonAutoScroll by autoScrollPref
    var webtoonAutoScrollSpeed by autoScrollSpeedPref
    var cropBordersWebtoon by cropWebtoonPref
    var skipReadChapters by skipReadPref
    var skipFilteredChapters by skipFilteredPref
    var skipDuplicateChapters by skipDuplicatePref
    var scaleType by scaleTypePref
    var colorFilterEnabled by filterEnabledPref
    var colorFilterBrightnessEnabled by brightnessEnabledPref
    var colorFilterBrightness by brightnessPref
    var colorFilterR by redPref
    var colorFilterG by greenPref
    var colorFilterB by bluePref
    var colorFilterAlpha by alphaPref
    var colorFilterGrayscale by grayscalePref
    var colorFilterInvert by invertPref

    fun loadColorFilter() = ReaderColorFilter(
        tintEnabled = colorFilterEnabled,
        brightnessEnabled = colorFilterBrightnessEnabled,
        brightness = colorFilterBrightness,
        r = colorFilterR,
        g = colorFilterG,
        b = colorFilterB,
        alpha = colorFilterAlpha,
        grayscaleEnabled = colorFilterGrayscale,
        invertEnabled = colorFilterInvert,
    ).normalized()
    fun saveColorFilter(filter: ReaderColorFilter) {
        colorFilterEnabled = filter.tintEnabled
        colorFilterBrightnessEnabled = filter.brightnessEnabled
        colorFilterBrightness = filter.brightness
        colorFilterR = filter.r
        colorFilterG = filter.g
        colorFilterB = filter.b
        colorFilterAlpha = filter.alpha
        colorFilterGrayscale = filter.grayscaleEnabled
        colorFilterInvert = filter.invertEnabled
    }

    internal fun clearDualPageForTests() {
        dualPagePref.delete()
        legacy.remove("isDualPage")
    }
}
