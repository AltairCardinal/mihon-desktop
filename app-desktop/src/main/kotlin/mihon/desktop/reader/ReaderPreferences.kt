package mihon.desktop.reader

import mihon.desktop.settings.migrateFrom
import mihon.desktop.ui.reader.NavigationMode
import mihon.desktop.ui.reader.WebtoonAutoScrollSpeed
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsCodec
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.util.prefs.Preferences
import kotlin.reflect.KProperty

private operator fun <T> Preference<T>.getValue(thisRef: Any?, property: KProperty<*>): T = get()
private operator fun <T> Preference<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)

enum class NextChapterPrefetchMode {
    OFF,
    FIRST_VIEWPORT,
    FULL_NEXT_CHAPTER,
}

/** Reader preferences eagerly migrate every public setting during construction. */
class ReaderPreferences(
    private val store: PreferenceStore = DesktopPreferenceStore(),
    private val legacy: Preferences = Preferences.userRoot().node("mihon/desktop/reader"),
) {
    private fun <T> Preference<T>.legacy(key: String, read: () -> T?): Preference<T> =
        migrateFrom(legacy, key) { read() }

    private val readingModePref = store.getEnum("reader_reading_mode", ReadingMode.AUTO)
        .legacy("readingMode") {
            legacy.get("readingMode", null)?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
        }
        .apply { if (get() == ReadingMode.DEFAULT) set(ReadingMode.AUTO) }
    private val navigationModePref = store.getEnum("reader_navigation_mode", NavigationMode.RightAndLeft)
        .legacy("navigationMode") {
            legacy.get("navigationMode", null)?.let { runCatching { NavigationMode.valueOf(it) }.getOrNull() }
        }
    private val dualPagePref = store.getBoolean("reader_dual_page", false)
        .legacy("isDualPage") { legacy.getBoolean("isDualPage", false) }
        .apply {
            // Before adaptive mode, changing only dual-page did not save a direction.
            // Preserve that explicit manual layout, but never replace a saved DEFAULT.
            if (isSet() && !readingModePref.isSet()) readingModePref.set(ReadingMode.RTL)
        }
    private val autoSplitPref = store.getBoolean("reader_auto_split_pages", false).legacy("autoSplitPages") {
        legacy.getBoolean("autoSplitPages", false)
    }
    private val autoSpreadPref = store.getBoolean("reader_auto_spread_matching", false).legacy("autoSpreadMatching") {
        legacy.getBoolean("autoSpreadMatching", false)
    }
    private val backgroundPref = store.getEnum("reader_background_theme", ReaderBackgroundTheme.DEFAULT)
        .legacy("backgroundTheme") {
            legacy.get("backgroundTheme", null)?.let { runCatching { ReaderBackgroundTheme.valueOf(it) }.getOrNull() }
        }
    private val cropPagerPref = store.getBoolean("reader_crop_borders_pager", false).legacy("cropBordersPager") {
        legacy.getBoolean("cropBordersPager", false)
    }
    private val paddingPref = store.getEnum("reader_webtoon_side_padding", WebtoonSidePadding.DEFAULT)
        .legacy("webtoonSidePadding") {
            legacy.get("webtoonSidePadding", null)?.let { runCatching { WebtoonSidePadding.valueOf(it) }.getOrNull() }
        }
    private val autoScrollPref = store.getBoolean("reader_webtoon_auto_scroll", false).legacy("webtoonAutoScroll") {
        legacy.getBoolean("webtoonAutoScroll", false)
    }
    private val autoScrollSpeedPref = store.getEnum("reader_webtoon_auto_scroll_speed", WebtoonAutoScrollSpeed.Normal)
        .legacy("webtoonAutoScrollSpeed") {
            legacy.get("webtoonAutoScrollSpeed", null)?.let {
                runCatching { WebtoonAutoScrollSpeed.valueOf(it) }.getOrNull()
            }
        }
    private val cropWebtoonPref = store.getBoolean("reader_crop_borders_webtoon", false).legacy("cropBordersWebtoon") {
        legacy.getBoolean("cropBordersWebtoon", false)
    }
    private val skipReadPref = store.getBoolean("reader_skip_read_chapters", false).legacy("skipReadChapters") {
        legacy.getBoolean("skipReadChapters", false)
    }
    private val skipFilteredPref = store.getBoolean(
        "reader_skip_filtered_chapters",
        false,
    ).legacy("skipFilteredChapters") {
        legacy.getBoolean("skipFilteredChapters", false)
    }
    private val skipDuplicatePref = store.getBoolean(
        "reader_skip_duplicate_chapters",
        false,
    ).legacy("skipDuplicateChapters") {
        legacy.getBoolean("skipDuplicateChapters", false)
    }
    val nextChapterPrefetchPreference: Preference<NextChapterPrefetchMode> = store
        .getEnum("reader_next_chapter_prefetch", NextChapterPrefetchMode.OFF)
        .legacy("nextChapterPrefetchMode") {
            legacy.get("nextChapterPrefetchMode", null)?.let {
                runCatching { NextChapterPrefetchMode.valueOf(it) }.getOrNull()
            }
        }
    private val scaleTypePref = store.getEnum("reader_scale_type", ScaleType.DEFAULT)
        .legacy("scaleType") {
            legacy.get("scaleType", null)?.let { runCatching { ScaleType.valueOf(it) }.getOrNull() }
        }
    private val filterEnabledPref = store.getBoolean(
        "reader_color_filter_enabled",
        false,
    ).legacy("colorFilterEnabled") {
        legacy.getBoolean("colorFilterEnabled", false)
    }
    private val brightnessPref = store.getFloat("reader_color_filter_brightness", 0f).legacy("colorFilterBrightness") {
        legacy.getFloat("colorFilterBrightness", 0f)
    }
    private val brightnessEnabledPref = store.getBoolean("reader_color_filter_brightness_enabled", false).apply {
        if (!isSet() && filterEnabledPref.get() && brightnessPref.get() != 0f) set(true)
    }
    private val redPref = store.getInt("reader_color_filter_red", 0).legacy("colorFilterR") {
        legacy.getInt("colorFilterR", 0)
    }
    private val greenPref = store.getInt("reader_color_filter_green", 0).legacy("colorFilterG") {
        legacy.getInt("colorFilterG", 0)
    }
    private val bluePref = store.getInt("reader_color_filter_blue", 0).legacy("colorFilterB") {
        legacy.getInt("colorFilterB", 0)
    }
    private val alphaPref = store.getInt("reader_color_filter_alpha", 128).legacy("colorFilterAlpha") {
        legacy.getInt("colorFilterAlpha", 128)
    }
    private val grayscalePref = store.getBoolean(
        "reader_color_filter_grayscale",
        false,
    ).legacy("colorFilterGrayscale") {
        legacy.getBoolean("colorFilterGrayscale", false)
    }
    private val invertPref = store.getBoolean("reader_color_filter_invert", false).legacy("colorFilterInvert") {
        legacy.getBoolean("colorFilterInvert", false)
    }
    private val displayWebtoonNavigationPref = store.getEnum("reader_display_webtoonNavigation", NavigationMode.Default)
    private val displayZoomStartPref = store.getEnum("reader_display_zoomStart", ReaderZoomStart.AUTO)
    private val displayLandscapeZoomPref = store.getBoolean("reader_display_landscapeZoom", true)
    private val displayNavigateToPanPref = store.getBoolean("reader_display_navigateToPan", true)
    private val displaySplitInvertPref = store.getBoolean("reader_display_splitInvert", false)
    private val displayRotateWidePref = store.getBoolean("reader_display_rotateWide", false)
    private val displayRotateInvertPref = store.getBoolean("reader_display_rotateInvert", false)
    private val displayWebtoonPaddingPref = store.getInt(
        "reader_display_webtoonPadding",
        (paddingPref.get().ratio * 100).toInt(),
    )
    private val displayWebtoonDoubleTapZoomPref = store.getBoolean("reader_display_webtoonDoubleTapZoom", true)
    private val displayWebtoonDisableZoomOutPref = store.getBoolean("reader_display_webtoonDisableZoomOut", false)
    private val displayShowPageNumberPref = store.getBoolean("reader_display_showPageNumber", true)
    private val displayFullscreenPref = store.getBoolean("reader_display_fullscreen", false)
    private val displayKeepScreenOnPref = store.getBoolean("reader_display_keepScreenOn", true)
    private val displayLongTapPref = store.getBoolean("reader_display_longTap", true)
    private val displayAlwaysShowChapterTransitionPref = store.getBoolean(
        "reader_display_alwaysShowChapterTransition",
        true,
    )
    private val displayPageTransitionsPref = store.getBoolean("page_turn_animation", true)
    private val displayFlashPref = store.getBoolean("reader_display_flash", false)
    private val displayFlashMillisPref = store.getInt("reader_display_flashMillis", 100)
    private val displayFlashIntervalPref = store.getInt("reader_display_flashInterval", 1)
    private val displayFlashColorPref = store.getEnum("reader_display_flashColor", ReaderFlashColor.BLACK)
    private val displayRipplePref = store.getBoolean("reader_display_ripple", false)
    private val displayRippleStylePref = store.getEnum("reader_display_rippleStyle", ReaderRippleStyle.SHADOW_LINE)

    private val displayPagerInversionPref = store.getEnum(
        "reader_display_pagerInversion",
        mihon.domain.reader.NavigationInversion.NONE,
    )
    private val displayWebtoonInversionPref = store.getEnum(
        "reader_display_webtoonInversion",
        mihon.domain.reader.NavigationInversion.NONE,
    )
    private val settingsSnapshotPref = store.getLong("reader_settings_snapshot", 0L)

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
    var nextChapterPrefetchMode by nextChapterPrefetchPreference
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

    fun loadDisplayOptions(): ReaderDisplayOptions = ReaderDisplayOptions(
        pagerInversion = displayPagerInversionPref.get(),
        webtoonNavigation = displayWebtoonNavigationPref.get(),
        webtoonInversion = displayWebtoonInversionPref.get(),
        zoomStart = displayZoomStartPref.get(),
        landscapeZoom = displayLandscapeZoomPref.get(),
        navigateToPan = displayNavigateToPanPref.get(),
        splitInvert = displaySplitInvertPref.get(),
        rotateWide = displayRotateWidePref.get(),
        rotateInvert = displayRotateInvertPref.get(),
        webtoonPadding = displayWebtoonPaddingPref.get(),
        webtoonDoubleTapZoom = displayWebtoonDoubleTapZoomPref.get(),
        webtoonDisableZoomOut = displayWebtoonDisableZoomOutPref.get(),
        showPageNumber = displayShowPageNumberPref.get(),
        fullscreen = displayFullscreenPref.get(),
        keepScreenOn = displayKeepScreenOnPref.get(),
        longTap = displayLongTapPref.get(),
        alwaysShowChapterTransition = displayAlwaysShowChapterTransitionPref.get(),
        pageTransitions = displayPageTransitionsPref.get(),
        flash = displayFlashPref.get(),
        flashMillis = displayFlashMillisPref.get(),
        flashInterval = displayFlashIntervalPref.get(),
        flashColor = displayFlashColorPref.get(),
        ripple = displayRipplePref.get(),
        rippleStyle = displayRippleStylePref.get(),
    )

    fun saveDisplayOptions(options: ReaderDisplayOptions) {
        displayPagerInversionPref.set(options.pagerInversion)
        displayWebtoonNavigationPref.set(options.webtoonNavigation)
        displayWebtoonInversionPref.set(options.webtoonInversion)
        displayZoomStartPref.set(options.zoomStart)
        displayLandscapeZoomPref.set(options.landscapeZoom)
        displayNavigateToPanPref.set(options.navigateToPan)
        displaySplitInvertPref.set(options.splitInvert)
        displayRotateWidePref.set(options.rotateWide)
        displayRotateInvertPref.set(options.rotateInvert)
        displayWebtoonPaddingPref.set(options.webtoonPadding)
        displayWebtoonDoubleTapZoomPref.set(options.webtoonDoubleTapZoom)
        displayWebtoonDisableZoomOutPref.set(options.webtoonDisableZoomOut)
        displayShowPageNumberPref.set(options.showPageNumber)
        displayFullscreenPref.set(options.fullscreen)
        displayKeepScreenOnPref.set(options.keepScreenOn)
        displayLongTapPref.set(options.longTap)
        displayAlwaysShowChapterTransitionPref.set(options.alwaysShowChapterTransition)
        displayPageTransitionsPref.set(options.pageTransitions)
        displayFlashPref.set(options.flash)
        displayFlashMillisPref.set(options.flashMillis)
        displayFlashIntervalPref.set(options.flashInterval)
        displayFlashColorPref.set(options.flashColor)
        displayRipplePref.set(options.ripple)
        displayRippleStylePref.set(options.rippleStyle)
    }

    fun loadReaderSettings(): ReaderSettings {
        val saved = ReaderSettingsCodec.custom(settingsSnapshotPref.get())
        val mode = when (readingMode) {
            ReadingMode.DEFAULT, ReadingMode.AUTO -> if (saved.direction == ReaderDirection.LTR) 9L else 7L
            ReadingMode.LTR -> if (isDualPage) 8L else 1L
            ReadingMode.RTL -> if (isDualPage) 6L else 2L
            ReadingMode.VERTICAL -> 3L
            ReadingMode.WEBTOON -> if (saved.gap) 5L else 4L
        }
        return ReaderSettingsCodec.fromMode(mode, saved).copy(match = isAutoSpreadMatching)
    }

    fun saveReaderSettings(settings: ReaderSettings) {
        settingsSnapshotPref.set(ReaderSettingsCodec.setCustom(0L, settings))
        readingMode =
            if (settings.mode == ReaderSettingsMode.HORIZONTAL && settings.layout == ReaderSettingsLayout.AUTO) {
                ReadingMode.AUTO
            } else {
                settings.desktopReadingMode()
            }
        isDualPage = settings.layout == ReaderSettingsLayout.DOUBLE
        isAutoSpreadMatching = settings.match
    }

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
