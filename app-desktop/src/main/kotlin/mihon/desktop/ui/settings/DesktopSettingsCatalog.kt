package mihon.desktop.ui.settings

import cafe.adriel.voyager.core.screen.Screen
import dev.icerock.moko.resources.StringResource
import mihon.desktop.ui.extension.extensionListDestination
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import mihon.domain.settings.SearchablePreference
import mihon.domain.settings.SearchableSettingsScreen
import mihon.domain.settings.SettingsLayoutDirection
import mihon.domain.settings.SettingsSearchPolicy
import mihon.domain.settings.SettingsSearchResult
import tachiyomi.i18n.MR

internal object DesktopSettingsAnchorResources {
    val downloadNew = MR.strings.pref_download_new
    val downloadAsCbz = MR.strings.save_chapter_as_cbz
    val createBackup = MR.strings.pref_create_backup
    val restoreBackup = MR.strings.pref_restore_backup
}

object DesktopSettingsCatalog {
    fun screens(): List<SearchableSettingsScreen<Screen>> = listOf(
        screen(AppearanceSettingsScreen(), MR.strings.pref_category_appearance, MR.strings.pref_category_theme, MR.strings.desktop_appearance_library_grid),
        screen(LibrarySettingsScreen(), MR.strings.pref_category_library, MR.strings.pref_category_display, MR.strings.pref_category_library_update),
        screen(ReaderSettingsScreen(), MR.strings.pref_category_reader, MR.strings.pref_viewer_type, MR.strings.desktop_reader_pager_mode),
        screen(DownloadSettingsScreen(), MR.strings.pref_category_downloads, DesktopSettingsAnchorResources.downloadNew, DesktopSettingsAnchorResources.downloadAsCbz),
        screen(TrackingSettingsScreen(), MR.strings.pref_category_tracking, MR.strings.pref_auto_update_manga_sync, MR.strings.login),
        screen(extensionListDestination(), MR.strings.label_extensions, MR.strings.label_extensions, MR.strings.label_extension_repos),
        screen(BackupSettingsScreen(), MR.strings.label_backup, DesktopSettingsAnchorResources.createBackup, DesktopSettingsAnchorResources.restoreBackup),
        screen(SecuritySettingsScreen(), MR.strings.pref_category_security, MR.strings.desktop_security_lock_enabled, MR.strings.desktop_secure_screen_title),
        screen(AdvancedSettingsScreen(), MR.strings.pref_category_advanced, MR.strings.pref_clear_cookies, MR.strings.desktop_advanced_clear_network_cache),
        screen(GeneralSettingsScreen(), MR.strings.pref_category_general, MR.strings.pref_incognito_mode, MR.strings.pref_dns_over_https),
        screen(ExtensionRepoScreen(), MR.strings.label_extension_repos, MR.strings.action_add_repo, MR.strings.action_delete_repo),
        screen(AboutScreen(), MR.strings.pref_category_about, MR.strings.check_for_updates, MR.strings.desktop_about_app_data_directory),
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
