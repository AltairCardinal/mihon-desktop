package mihon.desktop.settings

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class ReaderDefaultMode { PAGER, WEBTOON }

class DesktopAppPreferences(private val store: PreferenceStore) {

    // Lazy vals so repeated accesses return the same mutable Preference instance.

    val themeMode: Preference<ThemeMode> by lazy {
        store.getObjectFromString(
            key = "theme_mode",
            defaultValue = ThemeMode.SYSTEM,
            serializer = { it.name },
            deserializer = { ThemeMode.valueOf(it) },
        )
    }

    val defaultReaderMode: Preference<ReaderDefaultMode> by lazy {
        store.getObjectFromString(
            key = "default_reader_mode",
            defaultValue = ReaderDefaultMode.PAGER,
            serializer = { it.name },
            deserializer = { ReaderDefaultMode.valueOf(it) },
        )
    }

    val libraryGridColumns: Preference<Int> by lazy {
        store.getInt(key = "library_grid_columns", defaultValue = 3)
    }

    val defaultRtl: Preference<Boolean> by lazy {
        store.getBoolean(key = "default_rtl", defaultValue = false)
    }
}
