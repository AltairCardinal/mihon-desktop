package mihon.desktop.settings

import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeDefaults
import eu.kanade.domain.ui.model.ThemePreferenceCodec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.net.URI
import java.util.Locale
import java.util.prefs.Preferences

typealias ThemeMode = eu.kanade.domain.ui.model.ThemeMode

enum class ReaderDefaultMode { PAGER, WEBTOON }

/** DNS over HTTPS provider. [OFF] uses system DNS. */
enum class DohProvider { OFF, GOOGLE, CLOUDFLARE, ADGUARD }

data class FlareSolverrRuntimeConfig(val baseUrl: HttpUrl)

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

/** App preferences migrate eagerly when each lazy public preference is first accessed. */
class DesktopAppPreferences(
    private val store: PreferenceStore,
    private val legacy: Preferences = Preferences.userRoot().node("mihon/desktop/app"),
) {

    private fun <T> Preference<T>.migrate(key: String, parser: (String) -> T?): Preference<T> =
        migrateFrom(legacy, key) { parser(legacy.get(it, null)) }

    private fun string(key: String, default: String) = store.getString(key, default)
        .migrate(key) { it }
    private fun int(key: String, default: Int) = store.getInt(key, default)
        .migrate(key) { it.toIntOrNull() }
    private fun boolean(key: String, default: Boolean) = store.getBoolean(key, default)
        .migrate(key) { it.toBooleanStrictOrNull() }

    // Lazy vals so repeated accesses return the same mutable Preference instance.

    val themeMode: Preference<ThemeMode> by lazy {
        store.getObjectFromString(
            key = ThemeDefaults.THEME_MODE_KEY,
            defaultValue = ThemeDefaults.themeMode,
            serializer = ThemePreferenceCodec::encode,
            deserializer = ThemePreferenceCodec::decodeThemeMode,
        ).migrate("theme_mode") { value -> ThemeMode.entries.firstOrNull { it.name == value } }
    }

    val appTheme: Preference<AppTheme> by lazy {
        store.getObjectFromString(
            key = ThemeDefaults.APP_THEME_KEY,
            defaultValue = ThemeDefaults.appTheme(dynamicColorAvailable = false),
            serializer = ThemePreferenceCodec::encode,
            deserializer = { ThemePreferenceCodec.decodeAppTheme(it, dynamicColorAvailable = false) },
        )
    }

    val themeDarkAmoled: Preference<Boolean> by lazy {
        store.getBoolean(key = "pref_theme_dark_amoled_key", defaultValue = false)
    }

    val defaultReaderMode: Preference<ReaderDefaultMode> by lazy {
        store.getObjectFromString(
            key = "default_reader_mode",
            defaultValue = ReaderDefaultMode.PAGER,
            serializer = { it.name },
            deserializer = { ReaderDefaultMode.valueOf(it) },
        ).migrate("default_reader_mode") { runCatching { ReaderDefaultMode.valueOf(it) }.getOrNull() }
    }

    val libraryGridColumns: Preference<Int> by lazy {
        int(key = "library_grid_columns", default = 3)
    }

    val defaultRtl: Preference<Boolean> by lazy {
        boolean(key = "default_rtl", default = false)
    }

    /** When true, reading history is not recorded. */
    val incognitoMode: Preference<Boolean> by lazy {
        boolean(key = "incognito_mode", default = false)
    }

    /** Extension packages whose sources should suppress history and tracker updates. */
    val incognitoExtensions: Preference<Set<String>> by lazy {
        store.getStringSet(key = "incognito_extensions", defaultValue = emptySet())
    }

    /** When true, completing a chapter updates configured tracking services. */
    val autoUpdateTrack: Preference<Boolean> by lazy {
        boolean(key = "pref_auto_update_manga_sync_key", default = true)
    }

    /** Comma-separated source IDs disabled by the user. Empty keeps legacy behavior. */
    val disabledSourceIds: Preference<String> by lazy {
        string(key = "disabled_source_ids", default = "")
    }

    /** Languages whose sources participate in source discovery and global search. */
    val enabledLanguages: Preference<Set<String>> by lazy {
        store.getStringSet(
            key = "source_languages",
            defaultValue = setOf("all", "en", Locale.getDefault().language),
        )
    }

    /** Source IDs excluded from discovery while remaining resolvable for existing manga. */
    val disabledSources: Preference<Set<String>> by lazy {
        store.getStringSet(key = "hidden_catalogues", defaultValue = emptySet()).also { current ->
            if (!current.isSet() && disabledSourceIds.isSet()) {
                current.set(
                    disabledSourceIds.get()
                        .split(',')
                        .mapNotNull { it.trim().toLongOrNull()?.toString() }
                        .toSet(),
                )
            }
        }
    }

    /** Source IDs preferred by source discovery and global search. */
    val pinnedSources: Preference<Set<String>> by lazy {
        store.getStringSet(key = "pinned_catalogues", defaultValue = emptySet())
    }

    val lastUsedSource: Preference<Long> by lazy {
        store.getLong(Preference.appStateKey("last_catalogue_source"), -1L)
    }

    /** Whether global search only displays sources with results. */
    val globalSearchFilterState: Preference<Boolean> by lazy {
        store.getBoolean(Preference.appStateKey("has_filters_toggle_state"), false)
    }

    /** When true, the pager shows a page-flip animation between pages. */
    val pageTurnAnimation: Preference<Boolean> by lazy {
        boolean(key = "page_turn_animation", default = true)
    }

    /** Automatic library update interval. */
    val libraryUpdateInterval: Preference<LibraryUpdateInterval> by lazy {
        store.getObjectFromString(
            key = "library_update_interval",
            defaultValue = LibraryUpdateInterval.OFF,
            serializer = { it.name },
            deserializer = { LibraryUpdateInterval.valueOf(it) },
        ).migrate("library_update_interval") { runCatching { LibraryUpdateInterval.valueOf(it) }.getOrNull() }
    }

    /** When true, manga detail lists hide missing chapter indicator rows. */
    val hideMissingChapterIndicators: Preference<Boolean> by lazy {
        boolean(key = "pref_hide_missing_chapter_indicators", default = false)
    }

    /** DNS over HTTPS provider selection. */
    val dohProvider: Preference<DohProvider> by lazy {
        store.getObjectFromString(
            key = "doh_provider",
            defaultValue = DohProvider.OFF,
            serializer = { it.name },
            deserializer = { DohProvider.valueOf(it) },
        ).migrate("doh_provider") { runCatching { DohProvider.valueOf(it) }.getOrNull() }
    }

    val flareSolverrEnabled: Preference<Boolean> by lazy {
        boolean(key = "flare_solverr_enabled", default = false)
    }

    val flareSolverrUrl: Preference<String> by lazy {
        string(key = "flare_solverr_url", default = "")
    }

    fun flareSolverrRuntimeConfig(): FlareSolverrRuntimeConfig? {
        if (!flareSolverrEnabled.get()) return null
        val rawUrl = flareSolverrUrl.get().trim()
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!uri.isAbsolute || uri.rawAuthority.isNullOrBlank()) return null
        val baseUrl = rawUrl.toHttpUrlOrNull() ?: return null
        return FlareSolverrRuntimeConfig(baseUrl)
    }

    /** Category IDs to include in library updates (comma-separated). Empty = all. */
    val updateCategoryIncludes: Preference<String> by lazy {
        string(key = "update_category_includes", default = "")
    }

    /** Category IDs to exclude from library updates (comma-separated). */
    val updateCategoryExcludes: Preference<String> by lazy {
        string(key = "update_category_excludes", default = "")
    }

    /** Last directory selected in the local manga source browser. Empty = none selected. */
    val localSourceRootDir: Preference<String> by lazy {
        string(key = "local_source_root_dir", default = "")
    }

    /** Maximum directory depth for recursive local source scanning. */
    val localSourceMaxDepth: Preference<Int> by lazy {
        int(key = "local_source_max_depth", default = 3)
    }

    /** Auto-backup interval (stored as enum name). */
    val autoBackupInterval: Preference<String> by lazy {
        string(key = "auto_backup_interval", default = "OFF")
    }

    /** Maximum number of auto-backup files to keep. */
    val autoBackupMaxFiles: Preference<Int> by lazy {
        int(key = "auto_backup_max_files", default = 2)
    }

    /** Directory for auto-backup files. Empty = ~/MihonDesktopBackups. */
    val autoBackupDir: Preference<String> by lazy {
        string(key = "auto_backup_dir", default = "")
    }
}
