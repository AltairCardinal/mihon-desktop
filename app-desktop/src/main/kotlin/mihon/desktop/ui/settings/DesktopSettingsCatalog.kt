package mihon.desktop.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.reflect.KClass
import cafe.adriel.voyager.core.screen.Screen
import dev.icerock.moko.resources.StringResource
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import mihon.domain.settings.SearchablePreference
import mihon.domain.settings.SearchableSettingsScreen
import mihon.domain.settings.SettingsLayoutDirection
import mihon.domain.settings.SettingsSearchPolicy
import mihon.domain.settings.SettingsSearchResult
import tachiyomi.i18n.MR

internal object DesktopSettingsAnchorResources {
    val downloadDirectory = MR.strings.desktop_download_directory
    val downloadNew = MR.strings.pref_download_new
    val downloadAsCbz = MR.strings.save_chapter_as_cbz
    val createBackup = MR.strings.pref_create_backup
    val restoreBackup = MR.strings.pref_restore_backup
    val advancedCrashLog = MR.strings.desktop_advanced_crash_log_open
    val securitySecureScreen = MR.strings.desktop_secure_screen_title
    val aboutAppData = MR.strings.desktop_about_app_data_directory
    val extensionRepoAdd = MR.strings.action_add_repo
    val extensionRepoDelete = MR.strings.action_delete_repo
    val trackingAutoSync = MR.strings.pref_auto_update_manga_sync
    val trackingLogin = MR.strings.login
}

object DesktopSettingsCatalog {
    data class DirectoryItem(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val route: Screen,
    )

    fun directoryItems() = listOf(
        DirectoryItem(
            SettingsDirectoryIcons.palette,
            MR.strings.pref_category_appearance.localized(),
            MR.strings.pref_appearance_summary.localized(),
            AppearanceSettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.library,
            MR.strings.pref_category_library.localized(),
            MR.strings.pref_library_summary.localized(),
            LibrarySettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.reader,
            MR.strings.pref_category_reader.localized(),
            MR.strings.pref_reader_summary.localized(),
            ReaderSettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.download,
            MR.strings.pref_category_downloads.localized(),
            MR.strings.pref_downloads_summary.localized(),
            DownloadSettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.tracking,
            MR.strings.pref_category_tracking.localized(),
            MR.strings.pref_tracking_summary.localized(),
            TrackingSettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.browse,
            MR.strings.browse.localized(),
            MR.strings.pref_browse_summary.localized(),
            ExtensionRepoScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.storage,
            MR.strings.label_data_storage.localized(),
            MR.strings.pref_backup_summary.localized(),
            BackupSettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.security,
            MR.strings.pref_category_security.localized(),
            MR.strings.pref_security_summary.localized(),
            SecuritySettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.advanced,
            MR.strings.pref_category_advanced.localized(),
            MR.strings.pref_advanced_summary.localized(),
            AdvancedSettingsScreen(),
        ),
        DirectoryItem(
            SettingsDirectoryIcons.about,
            MR.strings.pref_category_about.localized(),
            MR.strings.desktop_more_about_summary.localized(),
            AboutScreen(),
        ),
        DirectoryItem(
            Icons.Default.Settings,
            MR.strings.pref_category_general.localized(),
            MR.strings.desktop_more_general_summary.localized(),
            GeneralSettingsScreen(),
        ),
    )

    private fun route(type: KClass<out Screen>): Screen = directoryItems().single { it.route::class == type }.route

    fun screens(): List<SearchableSettingsScreen<Screen>> = listOf(
        screen(
            route(AppearanceSettingsScreen::class),
            MR.strings.pref_category_appearance,
            MR.strings.pref_category_theme,
            MR.strings.pref_app_theme,
            MR.strings.pref_dark_theme_pure_black,
            MR.strings.pref_category_display,
            MR.strings.pref_app_language,
            MR.strings.pref_tablet_ui_mode,
            MR.strings.pref_date_format,
            MR.strings.pref_relative_format,
            MR.strings.pref_display_images_description,
        ),
        screen(
            route(LibrarySettingsScreen::class),
            MR.strings.pref_category_library,
            MR.strings.pref_category_display,
            MR.strings.pref_category_library_update,
            MR.strings.desktop_appearance_library_grid,
            MR.strings.pref_library_columns,
        ),
        screen(
            route(ReaderSettingsScreen::class),
            MR.strings.pref_category_reader,
            MR.strings.pref_viewer_type,
            MR.strings.label_default,
            MR.strings.left_to_right_viewer,
            MR.strings.right_to_left_viewer,
            MR.strings.webtoon_viewer,
            MR.strings.pref_page_transitions,
            MR.strings.desktop_reader_prefetch_next_chapter,
        ),
        SearchableSettingsScreen(
            route = route(DownloadSettingsScreen::class),
            title = MR.strings.pref_category_downloads.localized(),
            preferences = listOf(
                SearchablePreference.Entry(
                    title = DesktopSettingsAnchorResources.downloadDirectory.localized(),
                    summary = MR.strings.pref_storage_location.localized(),
                ),
                SearchablePreference.Entry(DesktopSettingsAnchorResources.downloadNew.localized()),
                SearchablePreference.Entry(DesktopSettingsAnchorResources.downloadAsCbz.localized()),
            ),
        ),
        screen(route(TrackingSettingsScreen::class), MR.strings.pref_category_tracking, DesktopSettingsAnchorResources.trackingAutoSync, DesktopSettingsAnchorResources.trackingLogin),
        screen(route(BackupSettingsScreen::class), MR.strings.label_backup, DesktopSettingsAnchorResources.createBackup, DesktopSettingsAnchorResources.restoreBackup),
        screen(route(SecuritySettingsScreen::class), MR.strings.pref_category_security, MR.strings.desktop_security_lock_enabled, DesktopSettingsAnchorResources.securitySecureScreen),
        screen(route(AdvancedSettingsScreen::class), MR.strings.pref_category_advanced, MR.strings.pref_clear_cookies, MR.strings.desktop_advanced_clear_network_cache, DesktopSettingsAnchorResources.advancedCrashLog),
        screen(route(GeneralSettingsScreen::class), MR.strings.pref_category_general, MR.strings.pref_incognito_mode, MR.strings.pref_dns_over_https),
        screen(route(ExtensionRepoScreen::class), MR.strings.browse, DesktopSettingsAnchorResources.extensionRepoAdd, DesktopSettingsAnchorResources.extensionRepoDelete),
        screen(route(AboutScreen::class), MR.strings.pref_category_about, MR.strings.check_for_updates, DesktopSettingsAnchorResources.aboutAppData),
    )

    fun search(
        query: String,
        layoutDirection: SettingsLayoutDirection = SettingsLayoutDirection.Ltr,
    ): List<SettingsSearchResult<Screen>> = SettingsSearchPolicy.search(screens(), query, layoutDirection)

    private fun screen(route: Screen, title: StringResource, vararg entries: StringResource) =
        SearchableSettingsScreen(
            route = route,
            title = title.localized(),
            preferences = entries.map { SearchablePreference.Entry(it.localized()) },
        )
}
