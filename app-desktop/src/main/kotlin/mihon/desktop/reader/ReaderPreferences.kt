package mihon.desktop.reader

/**
 * Persists reader settings across sessions using java.util.prefs.Preferences.
 * Data is stored per-user in the OS preference store (~/.java/.userPrefs on Linux,
 * ~/Library/Preferences on macOS, registry on Windows).
 */
class ReaderPreferences {

    private val prefs = java.util.prefs.Preferences
        .userRoot()
        .node("mihon/desktop/reader")

    /** Last used reading mode (LTR/RTL/WEBTOON). Does not override chapter-specific flags. */
    var readingMode: ReadingMode
        get() = try {
            ReadingMode.valueOf(prefs.get(KEY_READING_MODE, ReadingMode.LTR.name))
        } catch (_: Exception) {
            ReadingMode.LTR
        }
        set(value) {
            prefs.put(KEY_READING_MODE, value.name)
            prefs.flush()
        }

    /** Whether dual-page mode was last enabled. */
    var isDualPage: Boolean
        get() = prefs.getBoolean(KEY_DUAL_PAGE, false)
        set(value) {
            prefs.putBoolean(KEY_DUAL_PAGE, value)
            prefs.flush()
        }

    /** Whether automatic edge-pixel spread matching is enabled. */
    var isAutoSpreadMatching: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPREAD, false)
        set(value) {
            prefs.putBoolean(KEY_AUTO_SPREAD, value)
            prefs.flush()
        }

    /** Background colour theme for the reader. */
    var backgroundTheme: ReaderBackgroundTheme
        get() = try {
            ReaderBackgroundTheme.valueOf(prefs.get(KEY_BG_THEME, ReaderBackgroundTheme.DEFAULT.name))
        } catch (_: Exception) {
            ReaderBackgroundTheme.DEFAULT
        }
        set(value) {
            prefs.put(KEY_BG_THEME, value.name)
            prefs.flush()
        }

    /** Whether to automatically crop white borders from page images (pager modes). */
    var cropBordersPager: Boolean
        get() = prefs.getBoolean(KEY_CROP_BORDERS_PAGER, false)
        set(value) {
            prefs.putBoolean(KEY_CROP_BORDERS_PAGER, value)
            prefs.flush()
        }

    /** Webtoon side padding — limits page width on wide screens. */
    var webtoonSidePadding: WebtoonSidePadding
        get() = try {
            WebtoonSidePadding.valueOf(prefs.get(KEY_WEBTOON_SIDE_PADDING, WebtoonSidePadding.DEFAULT.name))
        } catch (_: Exception) {
            WebtoonSidePadding.DEFAULT
        }
        set(value) {
            prefs.put(KEY_WEBTOON_SIDE_PADDING, value.name)
            prefs.flush()
        }

    /** Whether to automatically crop white borders from page images (webtoon mode). */
    var cropBordersWebtoon: Boolean
        get() = prefs.getBoolean(KEY_CROP_BORDERS_WEBTOON, false)
        set(value) {
            prefs.putBoolean(KEY_CROP_BORDERS_WEBTOON, value)
            prefs.flush()
        }

    /** Whether the colour filter overlay is active. */
    var colorFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_COLOR_FILTER_ENABLED, false)
        set(value) {
            prefs.putBoolean(KEY_COLOR_FILTER_ENABLED, value)
            prefs.flush()
        }

    /** Brightness offset: -0.75 (dark) … 0 (neutral) … 1.0 (bright). */
    var colorFilterBrightness: Float
        get() = prefs.getFloat(KEY_COLOR_FILTER_BRIGHTNESS, 0f)
        set(value) {
            prefs.putFloat(KEY_COLOR_FILTER_BRIGHTNESS, value)
            prefs.flush()
        }

    /** Red channel of the colour tint overlay (0–255). */
    var colorFilterR: Int
        get() = prefs.getInt(KEY_COLOR_FILTER_R, 0)
        set(value) {
            prefs.putInt(KEY_COLOR_FILTER_R, value)
            prefs.flush()
        }

    /** Green channel of the colour tint overlay (0–255). */
    var colorFilterG: Int
        get() = prefs.getInt(KEY_COLOR_FILTER_G, 0)
        set(value) {
            prefs.putInt(KEY_COLOR_FILTER_G, value)
            prefs.flush()
        }

    /** Blue channel of the colour tint overlay (0–255). */
    var colorFilterB: Int
        get() = prefs.getInt(KEY_COLOR_FILTER_B, 0)
        set(value) {
            prefs.putInt(KEY_COLOR_FILTER_B, value)
            prefs.flush()
        }

    /** Alpha (opacity) of the colour tint overlay (0–255). */
    var colorFilterAlpha: Int
        get() = prefs.getInt(KEY_COLOR_FILTER_ALPHA, 128)
        set(value) {
            prefs.putInt(KEY_COLOR_FILTER_ALPHA, value)
            prefs.flush()
        }

    /** Loads and returns a [ReaderColorFilter] reflecting the persisted state. */
    fun loadColorFilter(): ReaderColorFilter = ReaderColorFilter(
        enabled = colorFilterEnabled,
        brightness = colorFilterBrightness,
        r = colorFilterR,
        g = colorFilterG,
        b = colorFilterB,
        alpha = colorFilterAlpha,
    )

    /** Persists a [ReaderColorFilter] in a single call. */
    fun saveColorFilter(filter: ReaderColorFilter) {
        colorFilterEnabled = filter.enabled
        colorFilterBrightness = filter.brightness
        colorFilterR = filter.r
        colorFilterG = filter.g
        colorFilterB = filter.b
        colorFilterAlpha = filter.alpha
    }

    private companion object {
        const val KEY_READING_MODE = "readingMode"
        const val KEY_DUAL_PAGE = "isDualPage"
        const val KEY_AUTO_SPREAD = "autoSpreadMatching"
        const val KEY_BG_THEME = "backgroundTheme"
        const val KEY_WEBTOON_SIDE_PADDING = "webtoonSidePadding"
        const val KEY_CROP_BORDERS_PAGER = "cropBordersPager"
        const val KEY_CROP_BORDERS_WEBTOON = "cropBordersWebtoon"
        const val KEY_COLOR_FILTER_ENABLED = "colorFilterEnabled"
        const val KEY_COLOR_FILTER_BRIGHTNESS = "colorFilterBrightness"
        const val KEY_COLOR_FILTER_R = "colorFilterR"
        const val KEY_COLOR_FILTER_G = "colorFilterG"
        const val KEY_COLOR_FILTER_B = "colorFilterB"
        const val KEY_COLOR_FILTER_ALPHA = "colorFilterAlpha"
    }
}
