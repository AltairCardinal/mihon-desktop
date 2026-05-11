package mihon.desktop.settings

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class ReaderDefaultMode { PAGER, WEBTOON }

/** DNS over HTTPS provider. [OFF] uses system DNS. */
enum class DohProvider { OFF, GOOGLE, CLOUDFLARE, ADGUARD }

/** Interval for automatic library updates. [OFF] disables automatic updates. */
enum class LibraryUpdateInterval(val hours: Long) {
    OFF(0),
    EVERY_6H(6),
    EVERY_12H(12),
    EVERY_24H(24),
    WEEKLY(168),
    ;

    fun toMillis(): Long = hours * 3_600_000L
}

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

    /** When true, reading history is not recorded. */
    val incognitoMode: Preference<Boolean> by lazy {
        store.getBoolean(key = "incognito_mode", defaultValue = false)
    }

    /** When true, the pager shows a page-flip animation between pages. */
    val pageTurnAnimation: Preference<Boolean> by lazy {
        store.getBoolean(key = "page_turn_animation", defaultValue = true)
    }

    /** Automatic library update interval. */
    val libraryUpdateInterval: Preference<LibraryUpdateInterval> by lazy {
        store.getObjectFromString(
            key = "library_update_interval",
            defaultValue = LibraryUpdateInterval.OFF,
            serializer = { it.name },
            deserializer = { LibraryUpdateInterval.valueOf(it) },
        )
    }

    /** DNS over HTTPS provider selection. */
    val dohProvider: Preference<DohProvider> by lazy {
        store.getObjectFromString(
            key = "doh_provider",
            defaultValue = DohProvider.OFF,
            serializer = { it.name },
            deserializer = { DohProvider.valueOf(it) },
        )
    }

    /** Category IDs to include in library updates (comma-separated). Empty = all. */
    val updateCategoryIncludes: Preference<String> by lazy {
        store.getString(key = "update_category_includes", defaultValue = "")
    }

    /** Category IDs to exclude from library updates (comma-separated). */
    val updateCategoryExcludes: Preference<String> by lazy {
        store.getString(key = "update_category_excludes", defaultValue = "")
    }

    /** Last directory selected in the local manga source browser. Empty = none selected. */
    val localSourceRootDir: Preference<String> by lazy {
        store.getString(key = "local_source_root_dir", defaultValue = "")
    }

    /** Maximum directory depth for recursive local source scanning. */
    val localSourceMaxDepth: Preference<Int> by lazy {
        store.getInt(key = "local_source_max_depth", defaultValue = 3)
    }

    /** Auto-backup interval (stored as enum name). */
    val autoBackupInterval: Preference<String> by lazy {
        store.getString(key = "auto_backup_interval", defaultValue = "OFF")
    }

    /** Maximum number of auto-backup files to keep. */
    val autoBackupMaxFiles: Preference<Int> by lazy {
        store.getInt(key = "auto_backup_max_files", defaultValue = 2)
    }

    /** Directory for auto-backup files. Empty = ~/MihonDesktopBackups. */
    val autoBackupDir: Preference<String> by lazy {
        store.getString(key = "auto_backup_dir", defaultValue = "")
    }
}
