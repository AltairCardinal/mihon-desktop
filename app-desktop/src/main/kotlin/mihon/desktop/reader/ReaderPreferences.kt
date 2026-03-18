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

    private companion object {
        const val KEY_READING_MODE = "readingMode"
        const val KEY_DUAL_PAGE = "isDualPage"
    }
}
