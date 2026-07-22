package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.backup.AutoBackupInterval
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.backup.DesktopBackupRestorer
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DownloadItem
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.OperatingSystem
import mihon.desktop.platform.PlatformCredentialUnavailableException
import mihon.desktop.privacy.DesktopCapabilitySupport
import mihon.desktop.privacy.DesktopPrivacyCapabilities
import mihon.desktop.privacy.DesktopWindowPrivacy
import mihon.desktop.privacy.DesktopWindowPrivacyBridge
import mihon.desktop.privacy.DesktopWindowPrivacyController
import mihon.desktop.privacy.NativeAffinityCall
import mihon.desktop.privacy.NativeAffinityQuery
import mihon.desktop.privacy.WDA_NONE
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.LibraryUpdateInterval
import mihon.desktop.ui.settings.AppearanceSettingsScreen
import mihon.desktop.ui.settings.AboutScreen
import mihon.desktop.ui.settings.AdvancedSettingsScreen
import mihon.desktop.ui.settings.AdvancedSettingsPlatformActions
import mihon.desktop.ui.settings.BackupPresentationText
import mihon.desktop.ui.settings.BackupRestoreFailureReason
import mihon.desktop.ui.settings.BackupRestoreScreenModel
import mihon.desktop.ui.settings.BackupRestoreUiState
import mihon.desktop.ui.settings.BackupSettingsScreen
import mihon.desktop.ui.settings.DownloadSettingsScreen
import mihon.desktop.ui.settings.GeneralSettingsScreen
import mihon.desktop.ui.settings.LibrarySettingsScreen
import mihon.desktop.ui.settings.LocalAdvancedSettingsPlatformActions
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.ReaderSettingsScreen
import mihon.desktop.ui.settings.SecuritySettingsScreen
import mihon.desktop.ui.settings.DesktopUpdateScreenModel
import mihon.desktop.ui.settings.presentation
import mihon.desktop.ui.settings.backupPartialFailurePresentation
import mihon.desktop.ui.settings.backupPresentationText
import mihon.desktop.update.CheckFailure
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.DesktopUpdateState
import mihon.desktop.update.DownloadProgress
import mihon.desktop.update.InstallCancelled
import mihon.desktop.update.InstallStage
import mihon.desktop.update.ReadyToInstall
import mihon.desktop.update.UpdateOperation
import mihon.desktop.update.VerifiedDownload
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.i18n.MR
import java.io.File
import java.nio.file.Files
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@org.junit.jupiter.api.parallel.Isolated
class DesktopSettingsResourceIdentityTest {
    private val english = Locale.US
    private val chinese = Locale.forLanguageTag("zh-CN")

    @Test
    fun `desktop settings resources provide base and simplified Chinese copy`() {
        desktopResources.forEach { resource ->
            assertNotEquals(
                resource.localized(english),
                resource.localized(chinese),
                "Missing zh-CN copy for $resource",
            )
        }
    }

    @Test
    fun `More General and Appearance render their shared MR identities`() = runBlocking {
        val prefs = DesktopAppPreferences(InMemoryPreferenceStore())
        val downloads = mockk<DesktopDownloadManager> {
            every { queue } returns MutableStateFlow(listOf(mockk<DownloadItem>(), mockk<DownloadItem>()))
        }
        val emptyDownloads = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns prefs
            every { downloadManager } returns downloads
        }
        val emptyDependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns prefs
            every { downloadManager } returns emptyDownloads
        }
        val previousLocale = Locale.getDefault()
        try {
            listOf(english, chinese).forEach { locale ->
                val more = render(MoreRootScreen(), dependencies, locale, height = 2_000)
                assertCopy(
                    more.text,
                    MR.strings.label_more.localized(locale),
                    MR.strings.pref_category_tracking.localized(locale),
                    MR.strings.desktop_more_tracking_summary.localized(locale),
                    MR.strings.pref_category_general.localized(locale),
                    MR.strings.desktop_more_general_summary.localized(locale),
                    MR.strings.pref_category_downloads.localized(locale),
                    MR.strings.desktop_more_download_settings_summary.localized(locale),
                    MR.strings.label_backup.localized(locale),
                    MR.strings.desktop_more_backup_summary.localized(locale),
                    MR.strings.label_download_queue.localized(locale),
                    MR.strings.desktop_more_download_queue_count.localized(locale, 2),
                    MR.strings.pref_category_appearance.localized(locale),
                    MR.strings.desktop_more_appearance_summary.localized(locale),
                    MR.strings.pref_category_reader.localized(locale),
                    MR.strings.desktop_more_reader_summary.localized(locale),
                    MR.strings.pref_category_library.localized(locale),
                    MR.strings.desktop_more_library_summary.localized(locale),
                    MR.strings.label_migration.localized(locale),
                    MR.strings.desktop_more_migration_summary.localized(locale),
                    MR.strings.label_stats.localized(locale),
                    MR.strings.desktop_more_stats_summary.localized(locale),
                    MR.strings.pref_category_advanced.localized(locale),
                    MR.strings.desktop_more_advanced_summary.localized(locale),
                    MR.strings.pref_category_about.localized(locale),
                    MR.strings.desktop_more_about_summary.localized(locale),
                )
                assertEntry(
                    more,
                    MR.strings.label_download_queue.localized(locale),
                    MR.strings.desktop_more_download_queue_count.localized(locale, 2),
                )
                if (locale == chinese) {
                    val emptyMore = render(MoreRootScreen(), emptyDependencies, locale, height = 2_000)
                    val queue = MR.strings.label_download_queue.localized(locale)
                    assertEntry(emptyMore, queue, queue)
                }

                val general = render(GeneralSettingsScreen(), dependencies, locale)
                assertCopy(
                    general.text,
                    MR.strings.pref_category_general.localized(locale),
                    MR.strings.pref_incognito_mode.localized(locale),
                    MR.strings.pref_incognito_mode_summary.localized(locale),
                    MR.strings.pref_page_transitions.localized(locale),
                    MR.strings.desktop_general_page_transition_summary.localized(locale),
                    MR.strings.pref_dns_over_https.localized(locale),
                    MR.strings.desktop_general_doh_restart_summary.localized(locale),
                    MR.strings.desktop_general_system_dns.localized(locale),
                    MR.strings.desktop_general_google_dns.localized(locale),
                    MR.strings.desktop_general_cloudflare_dns.localized(locale),
                    MR.strings.desktop_general_adguard_dns.localized(locale),
                )
                assertCopy(general.descriptions, MR.strings.action_bar_up_description.localized(locale))

                val appearance = render(AppearanceSettingsScreen(), dependencies, locale)
                assertCopy(
                    appearance.text,
                    MR.strings.pref_category_appearance.localized(locale),
                    MR.strings.pref_category_theme.localized(locale),
                    MR.strings.theme_system.localized(locale),
                    MR.strings.theme_light.localized(locale),
                    MR.strings.theme_dark.localized(locale),
                    MR.strings.desktop_appearance_library_grid.localized(locale),
                    MR.strings.desktop_appearance_grid_columns.localized(locale, 3),
                )
                assertCopy(appearance.descriptions, MR.strings.action_bar_up_description.localized(locale))
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `About renders shared identities through production dependencies`() = runBlocking {
        val home = Files.createTempDirectory("mihon-about-red").toFile()
        val paths = DesktopPlatformPaths.resolve("Linux", home.absolutePath, emptyMap())
        val release = Release("v9.8.7", "", "https://example/release", "https://example/download")
        val verified = mockk<VerifiedDownload>()
        val ready = mockk<ReadyToInstall>()
        val controller = DesktopUpdateController(
            { GetApplicationRelease.Result.NewUpdate(release) },
            { _, _ -> verified },
            { _, _ -> ready },
            { _, _ -> InstallCancelled },
        )
        val model = DesktopUpdateScreenModel(controller, this)
        val extensionManager = DesktopExtensionManager()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { this@mockk.extensionManager } returns extensionManager
            every { updateScreenModel } returns model
        }
        val previousLocale = Locale.getDefault()
        try {
            paths.databaseFile.parentFile.mkdirs()
            paths.databaseFile.writeBytes(ByteArray(2_048))
            listOf(english, chinese).forEach { locale ->
                paths.networkCacheDir.mkdirs()
                paths.networkCacheDir.resolve("response.bin").writeBytes(ByteArray(1_536))
                val copy = render(AboutScreen(paths), dependencies, locale, height = 2_400)
                assertCopy(
                    copy.text,
                    MR.strings.pref_category_about.localized(locale),
                    MR.strings.desktop_about_version_value.localized(locale, MR.strings.version.localized(locale), mihon.desktop.APP_VERSION),
                    MR.strings.check_for_updates.localized(locale),
                    MR.strings.label_extensions.localized(locale),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_app_data_directory.localized(locale), paths.configDir.absolutePath),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_database.localized(locale), "${paths.databaseFile.absolutePath} (2.0 KB)"),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_network_cache.localized(locale), "1.5 KB"),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_installed_extensions.localized(locale), "0"),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_extensions_directory.localized(locale), paths.extensionsDir.absolutePath),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_java_version.localized(locale), "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_operating_system.localized(locale), "${System.getProperty("os.name")} ${System.getProperty("os.version")}"),
                )
                assertCopy(copy.descriptions, MR.strings.action_bar_up_description.localized(locale))
                val cleared = renderAfterClicks(
                    AboutScreen(paths), dependencies, locale,
                    MR.strings.desktop_advanced_clear_network_cache.localized(locale),
                )
                assertCopy(
                    cleared.text,
                    MR.strings.desktop_about_network_cache_cleared.localized(locale),
                    MR.strings.desktop_about_info_row.localized(locale, MR.strings.desktop_about_network_cache.localized(locale), "0 B"),
                )
            }

            assertTrue(controller.check(DesktopUpdateScreenModel.releaseArguments()))
            listOf(english, chinese).forEach { locale ->
                assertCopy(render(AboutScreen(paths), dependencies, locale, height = 2_400).text, MR.strings.desktop_update_available.localized(locale, release.version))
            }
            assertTrue(controller.download())
            listOf(english, chinese).forEach { locale ->
                assertCopy(
                    render(AboutScreen(paths), dependencies, locale, height = 2_400).text,
                    MR.strings.desktop_update_ready.localized(locale),
                    MR.strings.desktop_update_install_prompt.localized(locale),
                    MR.strings.action_install.localized(locale),
                    MR.strings.action_not_now.localized(locale),
                    MR.strings.update_check_open.localized(locale),
                )
            }

            listOf(english, chinese).forEach { locale ->
                Locale.setDefault(locale)
                listOf(
                    DesktopUpdateState.Idle to MR.strings.desktop_update_idle.localized(locale),
                    DesktopUpdateState.Checking to MR.strings.desktop_update_checking.localized(locale),
                    DesktopUpdateState.UpToDate to MR.strings.update_check_no_new_updates.localized(locale),
                    DesktopUpdateState.UpdateAvailable(release) to MR.strings.desktop_update_available.localized(locale, release.version),
                    DesktopUpdateState.NoCompatiblePackage to MR.strings.desktop_update_no_compatible_package.localized(locale),
                    DesktopUpdateState.CheckFailed(CheckFailure.REQUEST_FAILED, true) to MR.strings.desktop_update_check_failed.localized(locale, MR.strings.desktop_update_failure_request_failed.localized(locale)),
                    DesktopUpdateState.CheckFailed(CheckFailure.OS_TOO_OLD, false) to MR.strings.desktop_update_check_failed.localized(locale, MR.strings.desktop_update_failure_os_too_old.localized(locale)),
                    DesktopUpdateState.Downloading(release, DownloadProgress(1, 2)) to MR.strings.desktop_update_downloading.localized(locale, release.version),
                    DesktopUpdateState.Verifying(release, verified) to MR.strings.desktop_update_verifying.localized(locale, release.version),
                    DesktopUpdateState.ReadyToInstall(release.releaseLink, ready) to MR.strings.desktop_update_ready.localized(locale),
                    DesktopUpdateState.HandingOff(release.releaseLink) to MR.strings.desktop_update_handing_off.localized(locale),
                    DesktopUpdateState.HandedOff(release.releaseLink) to MR.strings.desktop_update_handed_off.localized(locale),
                    DesktopUpdateState.InstallFailed(InstallStage.VERIFY, release.releaseLink) to MR.strings.desktop_update_install_failed_verify.localized(locale),
                    DesktopUpdateState.InstallFailed(InstallStage.HANDOFF, release.releaseLink) to MR.strings.desktop_update_install_failed_handoff.localized(locale),
                    DesktopUpdateState.RetryableFailure(UpdateOperation.DOWNLOAD, release.releaseLink) to MR.strings.desktop_update_download_retryable.localized(locale),
                    DesktopUpdateState.Cancelled(null) to MR.strings.desktop_update_cancelled.localized(locale),
                    DesktopUpdateState.ManualOnly(release.releaseLink) to MR.strings.desktop_update_manual.localized(locale),
                ).forEach { (state, expected) -> assertEquals(expected, state.presentation().message) }

                var openMode = 0
                val manualController = DesktopUpdateController(
                    { GetApplicationRelease.Result.NewUpdate(release) },
                    { _, _ -> mihon.desktop.update.ManualOnly(release.releaseLink) },
                    { _, _ -> error("unused") },
                    { _, _ -> error("unused") },
                )
                val manualModel = DesktopUpdateScreenModel(
                    manualController,
                    this,
                    openUrl = {
                        when (openMode) {
                            0 -> false
                            1 -> error("browser failed")
                            else -> true
                        }
                    },
                )
                try {
                    assertTrue(manualModel.intent(mihon.desktop.ui.settings.DesktopUpdateIntent.CHECK))
                    manualModel.operationJob!!.join()
                    assertInstanceOf(DesktopUpdateState.UpdateAvailable::class.java, manualModel.state.value)
                    assertTrue(manualModel.intent(mihon.desktop.ui.settings.DesktopUpdateIntent.DOWNLOAD))
                    manualModel.operationJob!!.join()
                    assertEquals(DesktopUpdateState.ManualOnly(release.releaseLink), manualModel.state.value)

                    val expected = MR.strings.desktop_update_open_failed.localized(locale, release.releaseLink)
                    assertTrue(manualModel.intent(mihon.desktop.ui.settings.DesktopUpdateIntent.MANUAL))
                    assertEquals(expected, manualModel.feedback.value)
                    openMode = 1
                    assertTrue(manualModel.intent(mihon.desktop.ui.settings.DesktopUpdateIntent.MANUAL))
                    assertEquals(expected, manualModel.feedback.value)
                    openMode = 2
                    assertTrue(manualModel.intent(mihon.desktop.ui.settings.DesktopUpdateIntent.MANUAL))
                    assertEquals(null, manualModel.feedback.value)
                } finally {
                    manualModel.closeAndJoin()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
            model.close()
            extensionManager.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun `Reader Library and Download render their shared MR identities`() = runBlocking {
        val prefs = DesktopAppPreferences(InMemoryPreferenceStore())
        val downloadPrefs = DesktopDownloadPreferences(InMemoryPreferenceStore())
        val categoryLoader = mockk<GetCategories> {
            coEvery { await() } returns listOf(Category(1, "Favorites", 0, 0))
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns prefs
            every { downloadPreferences } returns downloadPrefs
            every { getCategories } returns categoryLoader
        }
        val previousLocale = Locale.getDefault()
        try {
            listOf(english, chinese).forEach { locale ->
                val reader = render(ReaderSettingsScreen(), dependencies, locale)
                assertCopy(
                    reader.text,
                    MR.strings.pref_category_reader.localized(locale),
                    MR.strings.pref_viewer_type.localized(locale),
                    MR.strings.desktop_reader_pager_mode.localized(locale),
                    MR.strings.desktop_reader_webtoon_mode.localized(locale),
                    MR.strings.desktop_reader_rtl.localized(locale),
                    MR.strings.desktop_reader_rtl_summary.localized(locale),
                )
                assertEntry(
                    reader,
                    MR.strings.desktop_reader_rtl.localized(locale),
                    MR.strings.desktop_reader_rtl_summary.localized(locale),
                )
                assertCopy(reader.descriptions, MR.strings.action_bar_up_description.localized(locale))

                val library = render(LibrarySettingsScreen(), dependencies, locale)
                assertCopy(
                    library.text,
                    MR.strings.pref_category_library.localized(locale),
                    MR.strings.pref_category_library_update.localized(locale),
                    MR.strings.update_never.localized(locale),
                    MR.strings.pref_behavior.localized(locale),
                    MR.strings.desktop_library_manual_refresh_summary.localized(locale),
                    MR.strings.pref_category_display.localized(locale),
                    MR.strings.pref_hide_missing_chapter_indicators.localized(locale),
                    MR.strings.desktop_library_excluded_categories.localized(locale),
                    MR.strings.desktop_library_excluded_categories_summary.localized(locale),
                )
                assertCopy(library.descriptions, MR.strings.action_bar_up_description.localized(locale))
                listOf(
                    LibraryUpdateInterval.EVERY_6H to MR.strings.update_6hour,
                    LibraryUpdateInterval.EVERY_12H to MR.strings.update_12hour,
                    LibraryUpdateInterval.EVERY_24H to MR.strings.update_24hour,
                    LibraryUpdateInterval.WEEKLY to MR.strings.update_weekly,
                ).forEach { (interval, resource) ->
                    prefs.libraryUpdateInterval.set(interval)
                    val selectedLibrary = render(LibrarySettingsScreen(), dependencies, locale)
                    assertSelectedEntry(selectedLibrary, resource.localized(locale))
                }

                val download = render(DownloadSettingsScreen(), dependencies, locale)
                assertCopy(
                    download.text,
                    MR.strings.pref_category_downloads.localized(locale),
                    MR.strings.save_chapter_as_cbz.localized(locale),
                    MR.strings.desktop_download_cbz_summary.localized(locale),
                    MR.strings.pref_download_new.localized(locale),
                    MR.strings.desktop_download_new_chapters_summary.localized(locale),
                    MR.strings.pref_remove_after_read.localized(locale),
                    MR.strings.desktop_download_delete_after_read_summary.localized(locale),
                    MR.strings.desktop_download_parallel.localized(locale),
                    MR.strings.desktop_download_sequential.localized(locale),
                )
                assertEntry(
                    download,
                    MR.strings.save_chapter_as_cbz.localized(locale),
                    MR.strings.desktop_download_cbz_summary.localized(locale),
                )
                assertEntry(
                    download,
                    MR.strings.pref_download_new.localized(locale),
                    MR.strings.desktop_download_new_chapters_summary.localized(locale),
                )
                assertEntry(
                    download,
                    MR.strings.pref_remove_after_read.localized(locale),
                    MR.strings.desktop_download_delete_after_read_summary.localized(locale),
                )
                assertCopy(download.descriptions, MR.strings.action_bar_up_description.localized(locale))
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Advanced renders localized production states and shared identities`() = runBlocking {
        val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { this@mockk.appPreferences } returns appPreferences
        }
        val previousLocale = Locale.getDefault()
        try {
            listOf(chinese, english).forEach { locale ->
                appPreferences.flareSolverrEnabled.set(true)
                appPreferences.flareSolverrUrl.set("")
                val cacheSize = CompletableDeferred<String>()
                val successScene = advancedScene(
                    dependencies,
                    locale,
                    ControlledAdvancedSettingsPlatformActions(cacheSize, crashFolderOpened = true),
                )
                try {
                    assertCopy(snapshot(successScene).text, MR.strings.desktop_advanced_calculating.localized(locale))
                    cacheSize.complete("42 KB")
                    assertCopy(snapshot(successScene).text, "42 KB")
                    click(successScene, MR.strings.desktop_advanced_crash_log_open.localized(locale))
                    assertCopy(snapshot(successScene).text, MR.strings.desktop_advanced_crash_log_opened.localized(locale))
                } finally {
                    successScene.close()
                }
                val failureScene = advancedScene(
                    dependencies,
                    locale,
                    ControlledAdvancedSettingsPlatformActions(CompletableDeferred("42 KB"), crashFolderOpened = false),
                )
                try {
                    snapshot(failureScene)
                    click(failureScene, MR.strings.desktop_advanced_crash_log_open.localized(locale))
                    assertCopy(snapshot(failureScene).text, MR.strings.desktop_advanced_crash_log_open_failed.localized(locale))
                } finally {
                    failureScene.close()
                }
                val advanced = render(AdvancedSettingsScreen(), dependencies, locale, height = 2_400)
                assertCopy(
                    advanced.text,
                    MR.strings.pref_category_advanced.localized(locale),
                    MR.strings.label_network.localized(locale),
                    MR.strings.pref_clear_cookies.localized(locale),
                    MR.strings.desktop_settings_cloudflare_title.localized(locale),
                    MR.strings.desktop_settings_cloudflare_solver_title.localized(locale),
                    MR.strings.desktop_settings_cloudflare_solver_url.localized(locale),
                    MR.strings.desktop_settings_cloudflare_domain.localized(locale),
                    MR.strings.desktop_challenge_manual_cookie.localized(locale),
                    MR.strings.desktop_challenge_manual_submit.localized(locale),
                    MR.strings.desktop_settings_cloudflare_solver_url_required.localized(locale),
                    MR.strings.desktop_advanced_network_cache_size.localized(locale),
                    MR.strings.desktop_advanced_clear_network_cache.localized(locale),
                    MR.strings.desktop_advanced_crash_log_folder.localized(locale),
                    MR.strings.desktop_advanced_crash_log_open.localized(locale),
                )
                assertCopy(advanced.descriptions, MR.strings.action_bar_up_description.localized(locale))
                appPreferences.flareSolverrUrl.set("not-a-url")
                assertCopy(
                    render(AdvancedSettingsScreen(), dependencies, locale, height = 2_400).text,
                    MR.strings.desktop_settings_cloudflare_solver_url_invalid.localized(locale),
                )
                assertCopy(
                    renderAfterClicks(
                        AdvancedSettingsScreen(),
                        dependencies,
                        locale,
                        MR.strings.desktop_challenge_manual_submit.localized(locale),
                    ).text,
                    MR.strings.desktop_settings_cloudflare_cookie_required.localized(locale),
                )
                assertCopy(
                    renderAfterClicks(
                        AdvancedSettingsScreen(),
                        dependencies,
                        locale,
                        MR.strings.desktop_advanced_clear_network_cache.localized(locale),
                    ).text,
                    MR.strings.desktop_advanced_clear_network_cache_warning.localized(locale),
                    MR.strings.desktop_advanced_clear_confirm.localized(locale),
                    MR.strings.action_cancel.localized(locale),
                )
                assertCopy(
                    renderAfterClicks(
                        AdvancedSettingsScreen(),
                        dependencies,
                        locale,
                        MR.strings.pref_clear_cookies.localized(locale),
                        MR.strings.desktop_settings_clear_cookies_confirm.localized(locale),
                    ).text,
                    MR.strings.cookies_cleared.localized(locale),
                )
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Security renders localized credential privacy and window states`() = runBlocking {
        val previousLocale = Locale.getDefault()
        try {
            listOf(chinese, english).forEach { locale ->
                val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
                val securityPreferences = SecurityPreferences(InMemoryPreferenceStore())
                val dependencies = securityDependencies(
                    securityPreferences,
                    appPreferences,
                    DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend())),
                    windowController(securityPreferences, appPreferences, supported = false),
                    DesktopPrivacyCapabilities.production.copy(
                        sharedUpdatesData = DesktopPrivacyCapabilities.production.sharedUpdatesData.copy(
                            support = DesktopCapabilitySupport.Unsupported,
                        ),
                    ),
                )
                val scene = securityScene(dependencies, locale)
                try {
                    val main = snapshot(scene)
                    assertCopy(
                        main.text,
                        MR.strings.pref_category_security.localized(locale),
                        MR.strings.desktop_security_lock_enabled.localized(locale),
                        MR.strings.desktop_security_authentication_failed.localized(locale),
                        MR.strings.desktop_secure_screen_unsupported.localized(locale),
                        MR.strings.desktop_privacy_native_notifications_unavailable.localized(locale),
                        MR.strings.desktop_privacy_telemetry_unavailable.localized(locale),
                        MR.strings.desktop_privacy_widget_unavailable.localized(locale),
                    )
                    assertNoCopy(main.text, MR.strings.desktop_privacy_widget_unavailable_updates_available.localized(locale))
                    assertCopy(main.descriptions, MR.strings.action_bar_up_description.localized(locale))
                    clickToggle(scene)
                    assertCopy(
                        snapshot(scene).text,
                        MR.strings.desktop_security_enable.localized(locale),
                        MR.strings.desktop_security_new_passphrase.localized(locale),
                        MR.strings.desktop_security_confirm_passphrase.localized(locale),
                        MR.strings.action_save.localized(locale),
                        MR.strings.action_cancel.localized(locale),
                    )
                    setText(scene, 0, MR.strings.desktop_security_new_passphrase.localized(locale), "one")
                    setText(scene, 1, MR.strings.desktop_security_confirm_passphrase.localized(locale), "two")
                    click(scene, MR.strings.action_save.localized(locale))
                    assertCopy(snapshot(scene).text, MR.strings.desktop_security_passphrase_mismatch.localized(locale))

                    clickToggle(scene)
                    snapshot(scene)
                    setText(scene, 0, MR.strings.desktop_security_new_passphrase.localized(locale), "secret")
                    setText(scene, 1, MR.strings.desktop_security_confirm_passphrase.localized(locale), "secret")
                    click(scene, MR.strings.action_save.localized(locale))
                    assertCopy(snapshot(scene).text, MR.strings.desktop_security_saved.localized(locale))

                } finally {
                    scene.close()
                }

                val enabled = InMemoryPreferenceStore.InMemoryPreference("use_biometric_lock", true, false)
                val authenticationPreferences = SecurityPreferences(InMemoryPreferenceStore(sequenceOf(enabled)))
                val authentication = securityScene(
                    securityDependencies(
                        authenticationPreferences,
                        appPreferences,
                        DesktopPassphraseVerifier(
                            DesktopCredentialStore(MemoryCredentialBackend("secret".toCharArray())),
                        ),
                        windowController(authenticationPreferences, appPreferences, supported = false),
                        DesktopPrivacyCapabilities.production,
                    ),
                    locale,
                )
                try {
                    snapshot(authentication)
                    clickToggle(authentication)
                    snapshot(authentication)
                    setText(authentication, 0, MR.strings.desktop_security_current_passphrase.localized(locale), "wrong")
                    click(authentication, MR.strings.action_save.localized(locale))
                    assertCopy(
                        snapshot(authentication).text,
                        MR.strings.desktop_security_authentication_failed.localized(locale),
                    )
                } finally {
                    authentication.close()
                }

                val unavailable = securityScene(
                    securityDependencies(
                        SecurityPreferences(InMemoryPreferenceStore()),
                        appPreferences,
                        DesktopPassphraseVerifier(DesktopCredentialStore(UnavailableCredentialBackend())),
                        windowController(securityPreferences, appPreferences, supported = false),
                        DesktopPrivacyCapabilities.production,
                    ),
                    locale,
                )
                try {
                    assertCopy(snapshot(unavailable).text, MR.strings.desktop_security_backend_unavailable.localized(locale))
                } finally {
                    unavailable.close()
                }

                val telemetrySupported = securityScene(
                    securityDependencies(
                        securityPreferences,
                        appPreferences,
                        DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend())),
                        windowController(securityPreferences, appPreferences, supported = false),
                        DesktopPrivacyCapabilities.production.copy(
                            telemetryRuntime = DesktopPrivacyCapabilities.production.telemetryRuntime.copy(
                                support = DesktopCapabilitySupport.Supported,
                            ),
                            systemWidgetProvider = DesktopPrivacyCapabilities.production.systemWidgetProvider.copy(
                                support = DesktopCapabilitySupport.Supported,
                            ),
                        ),
                    ),
                    locale,
                )
                try {
                    val copy = snapshot(telemetrySupported).text
                    assertCopy(copy, MR.strings.desktop_privacy_native_notifications_unavailable.localized(locale))
                    assertNoCopy(
                        copy,
                        MR.strings.desktop_privacy_telemetry_unavailable.localized(locale),
                        MR.strings.hide_notification_content.localized(locale),
                        MR.strings.desktop_privacy_widget_unavailable.localized(locale),
                        MR.strings.desktop_privacy_widget_unavailable_updates_available.localized(locale),
                    )
                } finally {
                    telemetrySupported.close()
                }

                val supported = securityScene(
                    securityDependencies(
                        securityPreferences,
                        appPreferences,
                        DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend())),
                        windowController(securityPreferences, appPreferences, supported = true),
                        DesktopPrivacyCapabilities.production.copy(
                            nativeSystemNotifications = DesktopPrivacyCapabilities.production.nativeSystemNotifications.copy(
                                support = DesktopCapabilitySupport.Supported,
                            ),
                        ),
                    ),
                    locale,
                )
                try {
                    val copy = snapshot(supported).text
                    assertCopy(
                        copy,
                        MR.strings.desktop_secure_screen_supported.localized(locale),
                        MR.strings.hide_notification_content.localized(locale),
                        MR.strings.desktop_privacy_telemetry_unavailable.localized(locale),
                        MR.strings.desktop_privacy_widget_unavailable_updates_available.localized(locale),
                    )
                    assertNoCopy(
                        copy,
                        MR.strings.desktop_privacy_native_notifications_unavailable.localized(locale),
                        MR.strings.desktop_privacy_widget_unavailable.localized(locale),
                    )
                } finally {
                    supported.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Backup renders localized main preview and restore states`() = runBlocking {
        val preview = BackupPreview(2, 8, 1, 1, 3, 2, 1)
        val file = File("library.tachibk")
        val previousLocale = Locale.getDefault()
        try {
            listOf(english, chinese).forEach { locale ->
                Locale.setDefault(locale)
                val previewGate = CompletableDeferred<BackupPreview>()
                var restoreGate = CompletableDeferred<TaskState<DesktopBackupRestorer.RestoreResult>>()
                val model = BackupRestoreScreenModel(
                    loadPreview = { previewGate.await() },
                    restore = { _, _ -> restoreGate.await() },
                    scope = this,
                )
                val factory = mockk<BackupRestoreScreenModelFactory> { every { create() } returns model }
                val appPrefs = DesktopAppPreferences(InMemoryPreferenceStore())
                val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
                    every { appPreferences } returns appPrefs
                    every { backupRestoreScreenModelFactory } returns factory
                }
                val scene = ImageComposeScene(900, 2_000, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
                try {
                    scene.setContent {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                            Navigator(BackupSettingsScreen()) { CurrentScreen() }
                        }
                    }
                    suspend fun snapshot(): RenderedCopy {
                        repeat(3) {
                            scene.render()
                            yield()
                        }
                        return RenderedCopy(textCopy(scene), descriptionCopy(scene), entryCopy(scene), selectedEntryCopy(scene))
                    }

                    val main = snapshot()
                    assertCopy(
                        main.text,
                        MR.strings.label_backup.localized(locale),
                        MR.strings.pref_create_backup.localized(locale),
                        MR.strings.desktop_backup_create_summary.localized(locale),
                        MR.strings.pref_restore_backup.localized(locale),
                        MR.strings.desktop_backup_restore_summary.localized(locale),
                        MR.strings.file_select_backup.localized(locale),
                        MR.strings.desktop_backup_automatic.localized(locale),
                        MR.strings.desktop_backup_automatic_summary.localized(locale),
                        MR.strings.pref_backup_interval.localized(locale),
                        MR.strings.desktop_backup_max_files.localized(locale),
                    )
                    assertCopy(main.descriptions, MR.strings.action_bar_up_description.localized(locale))
                    assertSelectedEntry(main, MR.strings.off.localized(locale))
                    listOf(
                        AutoBackupInterval.EVERY_6H to MR.strings.update_6hour,
                        AutoBackupInterval.EVERY_12H to MR.strings.update_12hour,
                        AutoBackupInterval.EVERY_24H to MR.strings.update_24hour,
                        AutoBackupInterval.EVERY_48H to MR.strings.update_48hour,
                        AutoBackupInterval.WEEKLY to MR.strings.update_weekly,
                    ).forEach { (interval, resource) ->
                        appPrefs.autoBackupInterval.set(interval.name)
                        assertSelectedEntry(snapshot(), resource.localized(locale))
                    }

                    model.select(file)
                    assertCopy(snapshot().text, MR.strings.desktop_backup_reading_file.localized(locale, file.name))
                    previewGate.complete(preview)
                    val confirmation = snapshot()
                    assertCopy(
                        confirmation.text,
                        MR.strings.desktop_backup_restore_confirm_title.localized(locale),
                        MR.strings.desktop_backup_restore_confirm_summary.localized(locale),
                        MR.strings.action_restore.localized(locale),
                        MR.strings.action_cancel.localized(locale),
                        MR.strings.desktop_backup_preview_library.localized(locale, 2, 8, 1),
                        MR.strings.desktop_backup_preview_services.localized(locale, 1, 3, 2),
                        MR.strings.desktop_backup_preview_repositories.localized(locale, 1),
                    )

                    model.confirmRestore()
                    assertCopy(
                        snapshot().text,
                        MR.strings.restoring_backup.localized(locale),
                        file.name,
                        MR.strings.desktop_backup_progress.localized(locale, 0, 3, 0),
                        MR.strings.action_cancel.localized(locale),
                    )
                    restoreGate.complete(
                        TaskState.Success(DesktopBackupRestorer.RestoreResult().apply { repeat(7) { incrementSuccess() } }),
                    )
                    assertCopy(snapshot().text, MR.strings.desktop_backup_completed_count.localized(locale, 7))

                    restoreGate = CompletableDeferred()
                    model.retryRestore()
                    restoreGate.complete(
                        TaskState.Failure(
                            AppError.PartialFailure(
                                failures = listOf(AppError.MalformedData()),
                                failedUnits = listOf(AppError.FailedUnit("manga:/broken", AppError.MalformedData())),
                            ),
                        ),
                    )
                    assertCopy(snapshot().text, MR.strings.desktop_backup_partial.localized(locale, 1))

                    restoreGate = CompletableDeferred()
                    model.retryRestore()
                    restoreGate.complete(TaskState.Failure(AppError.Storage()))
                    assertCopy(
                        snapshot().text,
                        MR.strings.restoring_backup_error.localized(locale),
                        MR.strings.desktop_backup_restore_storage_error.localized(locale),
                        MR.strings.action_retry.localized(locale),
                    )
                    model.cancel()
                    assertCopy(snapshot().text, MR.strings.restoring_backup_canceled.localized(locale))
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Backup system feedback formatter preserves typed errors and MR identity`() = runBlocking {
        val file = File("library.tachibk")
        val partial = AppError.PartialFailure(
            failures = listOf(AppError.MalformedData(), AppError.Permission()),
            failedUnits = listOf(
                AppError.FailedUnit("manga:/broken", AppError.MalformedData()),
                AppError.FailedUnit("category:/locked", AppError.Permission()),
            ),
        )
        listOf(english, chinese).forEach { locale ->
            assertEquals(
                MR.strings.onboarding_storage_action_select.localized(locale),
                backupPresentationText(BackupPresentationText.DirectoryChooserTitle, locale),
            )
            assertEquals(
                MR.strings.file_select_backup.localized(locale),
                backupPresentationText(BackupPresentationText.FileChooserTitle, locale),
            )
            assertEquals(
                MR.strings.desktop_backup_file_filter.localized(locale),
                backupPresentationText(BackupPresentationText.FileFilter, locale),
            )
            assertEquals(
                MR.strings.desktop_backup_create_cancelled.localized(locale),
                backupPresentationText(BackupPresentationText.CreateCancelled, locale),
            )
            assertEquals(
                MR.strings.desktop_backup_restore_selection_cancelled.localized(locale),
                backupPresentationText(BackupPresentationText.RestoreSelectionCancelled, locale),
            )
            assertEquals(
                MR.strings.desktop_backup_saved.localized(locale, file.name),
                backupPresentationText(BackupPresentationText.Created(file.name), locale),
            )
            assertEquals(
                MR.strings.desktop_backup_failed.localized(locale, "disk full"),
                backupPresentationText(BackupPresentationText.CreationFailed("disk full"), locale),
            )
            assertEquals(
                MR.strings.desktop_backup_restore_error_count.localized(locale, 12),
                backupPresentationText(BackupPresentationText.RestoreErrorCount(12), locale),
            )
            assertEquals(
                MR.strings.desktop_backup_error_item.localized(locale, "manga:/broken"),
                backupPresentationText(BackupPresentationText.RestoreErrorItem("manga:/broken"), locale),
            )
            assertEquals(
                MR.strings.desktop_backup_more_errors.localized(locale, 2),
                backupPresentationText(BackupPresentationText.MoreErrors(2), locale),
            )

            listOf(
                AppError.Storage() to MR.strings.desktop_backup_restore_storage_error.localized(locale),
                AppError.Permission() to MR.strings.desktop_backup_restore_permission_error.localized(locale),
                AppError.MalformedData() to MR.strings.desktop_backup_restore_malformed_error.localized(locale),
            ).forEach { (error, expected) ->
                val reason = BackupRestoreFailureReason.Restore(error)
                assertEquals(expected, backupPresentationText(BackupPresentationText.RestoreFailure(reason), locale))
            }

            val presentedPartial = backupPartialFailurePresentation(partial, locale)
            assertEquals(MR.strings.desktop_backup_partial.localized(locale, 2), presentedPartial.summary)
            assertEquals(2, presentedPartial.details.size)
            assertEquals(
                MR.strings.desktop_backup_failed_unit.localized(
                    locale,
                    "manga:/broken",
                    MR.strings.desktop_backup_restore_malformed_error.localized(locale),
                ),
                presentedPartial.details.first(),
            )
        }

        val storageGate = CompletableDeferred<TaskState<DesktopBackupRestorer.RestoreResult>>()
        val model = BackupRestoreScreenModel({ BackupPreview(1, 0, 0, 0, 0, 0, 0) }, { _, _ -> storageGate.await() }, this)
        model.select(file)
        yield()
        model.confirmRestore()
        storageGate.complete(TaskState.Failure(AppError.Storage()))
        yield()
        val storage = model.state.value as BackupRestoreUiState.Failure
        val typedError = (storage.reason as BackupRestoreFailureReason.Restore).error
        assertInstanceOf(AppError.Storage::class.java, typedError)
        assertFalse(
            backupPresentationText(BackupPresentationText.RestoreFailure(storage.reason), english).contains("磁盘"),
        )
    }

    private suspend fun render(
        screen: Screen,
        dependencies: DesktopUiDependencies,
        locale: Locale,
        height: Int = 900,
    ): RenderedCopy {
        Locale.setDefault(locale)
        val scene = ImageComposeScene(900, height, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        return try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
            scene.render()
            RenderedCopy(textCopy(scene), descriptionCopy(scene), entryCopy(scene), selectedEntryCopy(scene))
        } finally {
            scene.close()
        }
    }

    private suspend fun renderAfterClicks(
        screen: Screen,
        dependencies: DesktopUiDependencies,
        locale: Locale,
        vararg labels: String,
    ): RenderedCopy {
        Locale.setDefault(locale)
        val scene = ImageComposeScene(900, 2_400, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        return try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
            repeat(3) { scene.render(); yield() }
            labels.forEach { label ->
                val node = nodes(scene).last {
                    it.config.contains(SemanticsActions.OnClick) && flatten(it).flatMap(::textCopy).contains(label)
                }
                assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
                repeat(3) { scene.render(); yield() }
            }
            RenderedCopy(textCopy(scene), descriptionCopy(scene), entryCopy(scene), selectedEntryCopy(scene))
        } finally {
            scene.close()
        }
    }

    private suspend fun advancedScene(
        dependencies: DesktopUiDependencies,
        locale: Locale,
        actions: AdvancedSettingsPlatformActions,
    ): ImageComposeScene {
        Locale.setDefault(locale)
        return ImageComposeScene(900, 2_400, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalAdvancedSettingsPlatformActions provides actions,
                ) {
                    Navigator(AdvancedSettingsScreen()) { CurrentScreen() }
                }
            }
        }
    }

    private suspend fun securityScene(
        dependencies: DesktopUiDependencies,
        locale: Locale,
    ): ImageComposeScene {
        Locale.setDefault(locale)
        return ImageComposeScene(900, 2_000, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(SecuritySettingsScreen()) { CurrentScreen() }
                }
            }
        }
    }

    private fun securityDependencies(
        securityPreferences: SecurityPreferences,
        appPreferences: DesktopAppPreferences,
        verifier: DesktopPassphraseVerifier,
        windowController: DesktopWindowPrivacyController,
        capabilities: DesktopPrivacyCapabilities,
    ) = mockk<DesktopUiDependencies>(relaxed = true) {
        every { this@mockk.securityPreferences } returns securityPreferences
        every { this@mockk.appPreferences } returns appPreferences
        every { passphraseVerifier } returns verifier
        every { windowPrivacyController } returns windowController
        every { privacyCapabilities } returns capabilities
    }

    private fun windowController(
        securityPreferences: SecurityPreferences,
        appPreferences: DesktopAppPreferences,
        supported: Boolean,
    ): DesktopWindowPrivacyController {
        val controller = DesktopWindowPrivacyController(
            securityPreferences,
            appPreferences,
            DesktopWindowPrivacy(ControlledWindowPrivacyBridge(supported)),
        )
        if (supported) {
            controller.attach(null)
            controller.applyPolicy(SecurityPreferences.SecureScreenMode.ALWAYS, incognito = false)
        }
        return controller
    }

    private suspend fun snapshot(scene: ImageComposeScene): RenderedCopy {
        repeat(3) { scene.render(); yield() }
        return RenderedCopy(textCopy(scene), descriptionCopy(scene), entryCopy(scene), selectedEntryCopy(scene))
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).last {
            it.config.contains(SemanticsActions.OnClick) && flatten(it).flatMap(::textCopy).contains(label)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun clickToggle(scene: ImageComposeScene) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsProperties.ToggleableState) && it.config.contains(SemanticsActions.OnClick)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun setText(scene: ImageComposeScene, fieldIndex: Int, label: String, value: String) {
        val editable = nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }
        val node = editable[fieldIndex]
        assertEquals(listOf(label), textCopy(node))
        assertTrue(requireNotNull(node.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
    }

    private fun assertCopy(actual: Set<String>, vararg expected: String) {
        expected.forEach { assertTrue(it in actual, "Missing '$it': $actual") }
    }

    private fun assertNoCopy(actual: Set<String>, vararg unexpected: String) {
        unexpected.forEach { assertFalse(it in actual, "Unexpected '$it': $actual") }
    }

    private fun assertEntry(actual: RenderedCopy, title: String, subtitle: String) {
        assertTrue(listOf(title, subtitle) in actual.entries, "Missing entry '$title' -> '$subtitle': ${actual.entries}")
    }

    private fun assertSelectedEntry(actual: RenderedCopy, title: String) {
        assertTrue(listOf(title) in actual.selectedEntries, "Missing selected entry '$title': ${actual.selectedEntries}")
    }

    private fun textCopy(scene: ImageComposeScene): Set<String> =
        nodes(scene).flatMap { node ->
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].map { it.text }
            } else {
                emptyList()
            }
        }.toSet()

    private fun descriptionCopy(scene: ImageComposeScene): Set<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.ContentDescription)) {
            node.config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
    }.toSet()

    private fun entryCopy(scene: ImageComposeScene): Set<List<String>> = nodes(scene)
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .map { node -> flatten(node).flatMap { textCopy(it) } }
        .filter { it.size >= 2 }
        .toSet()

    private fun selectedEntryCopy(scene: ImageComposeScene): Set<List<String>> = nodes(scene)
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .map { node -> flatten(node) }
        .filter { nodes ->
            nodes.any { node ->
                node.config.contains(SemanticsProperties.Selected) && node.config[SemanticsProperties.Selected]
            }
        }
        .map { nodes -> nodes.flatMap { textCopy(it) } }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun textCopy(node: SemanticsNode): List<String> =
        if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private data class RenderedCopy(
        val text: Set<String>,
        val descriptions: Set<String>,
        val entries: Set<List<String>>,
        val selectedEntries: Set<List<String>>,
    )

    private class ControlledAdvancedSettingsPlatformActions(
        private val cacheSize: CompletableDeferred<String>,
        private val crashFolderOpened: Boolean,
    ) : AdvancedSettingsPlatformActions {
        override suspend fun loadNetworkCacheSize(): String = cacheSize.await()
        override suspend fun openCrashLogFolder(): Boolean = crashFolderOpened
    }

    private class MemoryCredentialBackend(initial: CharArray? = null) : CredentialBackend {
        private var secret: CharArray? = initial?.copyOf()
        override fun save(account: String, secret: CharArray) {
            this.secret = secret.copyOf()
        }
        override fun load(account: String): CharArray? = secret?.copyOf()
        override fun delete(account: String) {
            secret = null
        }
    }

    private class UnavailableCredentialBackend : CredentialBackend {
        override fun save(account: String, secret: CharArray) = unavailable()
        override fun load(account: String): CharArray? = unavailable()
        override fun delete(account: String) = unavailable()
        private fun unavailable(): Nothing = throw PlatformCredentialUnavailableException(OperatingSystem.UNSUPPORTED)
    }

    private class ControlledWindowPrivacyBridge(supported: Boolean) : DesktopWindowPrivacyBridge {
        override val unsupportedReasonSlug = if (supported) null else "test_platform"
        private var affinity = WDA_NONE
        override fun windowHandle(window: java.awt.Window?) = 1L
        override fun setAffinity(handle: Long, affinity: Int) =
            NativeAffinityCall.success().also { this.affinity = affinity }
        override fun queryAffinity(handle: Long) = NativeAffinityQuery.success(affinity)
    }

    private val desktopResources: List<StringResource> = listOf(
        MR.strings.desktop_about_description,
        MR.strings.desktop_about_based_on,
        MR.strings.desktop_about_storage,
        MR.strings.desktop_about_app_data_directory,
        MR.strings.desktop_about_database,
        MR.strings.desktop_about_network_cache,
        MR.strings.desktop_about_network_cache_cleared,
        MR.strings.desktop_about_installed_extensions,
        MR.strings.desktop_about_extensions_directory,
        MR.strings.desktop_about_environment,
        MR.strings.desktop_about_java_version,
        MR.strings.desktop_about_operating_system,
        MR.strings.desktop_about_unknown,
        MR.strings.desktop_about_info_row,
        MR.strings.desktop_about_version_value,
        MR.strings.desktop_update_idle,
        MR.strings.desktop_update_checking,
        MR.strings.desktop_update_available,
        MR.strings.desktop_update_no_compatible_package,
        MR.strings.desktop_update_check_failed,
        MR.strings.desktop_update_failure_request_failed,
        MR.strings.desktop_update_failure_os_too_old,
        MR.strings.desktop_update_downloading,
        MR.strings.desktop_update_verifying,
        MR.strings.desktop_update_ready,
        MR.strings.desktop_update_install_prompt,
        MR.strings.desktop_update_handing_off,
        MR.strings.desktop_update_handed_off,
        MR.strings.desktop_update_install_failed_verify,
        MR.strings.desktop_update_install_failed_handoff,
        MR.strings.desktop_update_download_retryable,
        MR.strings.desktop_update_cancelled,
        MR.strings.desktop_update_manual,
        MR.strings.desktop_update_open_failed,
        MR.strings.desktop_more_tracking_summary,
        MR.strings.desktop_more_general_summary,
        MR.strings.desktop_more_download_settings_summary,
        MR.strings.desktop_more_backup_summary,
        MR.strings.desktop_more_download_queue_count,
        MR.strings.desktop_more_appearance_summary,
        MR.strings.desktop_more_reader_summary,
        MR.strings.desktop_more_library_summary,
        MR.strings.desktop_more_migration_summary,
        MR.strings.desktop_more_stats_summary,
        MR.strings.desktop_more_advanced_summary,
        MR.strings.desktop_more_about_summary,
        MR.strings.desktop_general_page_transition_summary,
        MR.strings.desktop_general_doh_restart_summary,
        MR.strings.desktop_general_system_dns,
        MR.strings.desktop_general_google_dns,
        MR.strings.desktop_general_cloudflare_dns,
        MR.strings.desktop_general_adguard_dns,
        MR.strings.desktop_appearance_library_grid,
        MR.strings.desktop_appearance_grid_columns,
        MR.strings.desktop_reader_pager_mode,
        MR.strings.desktop_reader_webtoon_mode,
        MR.strings.desktop_reader_rtl,
        MR.strings.desktop_reader_rtl_summary,
        MR.strings.desktop_download_cbz_summary,
        MR.strings.desktop_download_parallel,
        MR.strings.desktop_download_new_chapters_summary,
        MR.strings.desktop_download_delete_after_read_summary,
        MR.strings.desktop_download_sequential,
        MR.strings.desktop_library_excluded_categories,
        MR.strings.desktop_library_manual_refresh_summary,
        MR.strings.desktop_library_excluded_categories_summary,
        MR.strings.desktop_challenge_manual_cookie,
        MR.strings.desktop_challenge_manual_submit,
        MR.strings.desktop_settings_cloudflare_title,
        MR.strings.desktop_settings_cloudflare_description,
        MR.strings.desktop_settings_cloudflare_solver_title,
        MR.strings.desktop_settings_cloudflare_solver_url,
        MR.strings.desktop_settings_cloudflare_solver_explicit_only,
        MR.strings.desktop_settings_cloudflare_solver_url_required,
        MR.strings.desktop_settings_cloudflare_solver_url_invalid,
        MR.strings.desktop_settings_cloudflare_domain,
        MR.strings.desktop_settings_cloudflare_invalid_domain,
        MR.strings.desktop_settings_cloudflare_cookie_required,
        MR.strings.desktop_settings_cloudflare_domain_parse_failed,
        MR.strings.desktop_settings_cloudflare_cookie_imported,
        MR.strings.desktop_settings_clear_cookies_summary,
        MR.strings.desktop_settings_clear_cookies_warning,
        MR.strings.desktop_settings_clear_cookies_confirm,
        MR.strings.desktop_advanced_network_cache_size,
        MR.strings.desktop_advanced_calculating,
        MR.strings.desktop_advanced_clear_network_cache,
        MR.strings.desktop_advanced_clear_network_cache_warning,
        MR.strings.desktop_advanced_clear_confirm,
        MR.strings.desktop_advanced_crash_log_folder,
        MR.strings.desktop_advanced_crash_log_open,
        MR.strings.desktop_advanced_crash_log_opened,
        MR.strings.desktop_advanced_crash_log_open_failed,
        MR.strings.desktop_security_lock_enabled,
        MR.strings.desktop_security_lock_enabled_summary,
        MR.strings.desktop_security_backend_unavailable,
        MR.strings.desktop_security_backend_error,
        MR.strings.desktop_security_current_passphrase,
        MR.strings.desktop_security_new_passphrase,
        MR.strings.desktop_security_confirm_passphrase,
        MR.strings.desktop_security_enable,
        MR.strings.desktop_security_disable,
        MR.strings.desktop_security_change_passphrase,
        MR.strings.desktop_security_lock_delay,
        MR.strings.desktop_security_delay_never,
        MR.strings.desktop_security_delay_immediately,
        MR.strings.desktop_security_delay_five_minutes,
        MR.strings.desktop_security_saved,
        MR.strings.desktop_security_passphrase_mismatch,
        MR.strings.desktop_security_authentication_failed,
        MR.strings.desktop_security_cancelled,
        MR.strings.desktop_privacy_capabilities_title,
        MR.strings.desktop_privacy_native_notifications_unavailable,
        MR.strings.desktop_privacy_telemetry_unavailable,
        MR.strings.desktop_privacy_widget_unavailable,
        MR.strings.desktop_privacy_widget_unavailable_updates_available,
        MR.strings.desktop_secure_screen_title,
        MR.strings.desktop_secure_screen_supported,
        MR.strings.desktop_secure_screen_not_protected,
        MR.strings.desktop_secure_screen_limited,
        MR.strings.desktop_secure_screen_unsupported,
        MR.strings.desktop_secure_screen_window_not_ready,
        MR.strings.desktop_secure_screen_failed,
        MR.strings.desktop_backup_create_summary,
        MR.strings.desktop_backup_restore_summary,
        MR.strings.desktop_backup_saved,
        MR.strings.desktop_backup_failed,
        MR.strings.desktop_backup_automatic,
        MR.strings.desktop_backup_automatic_summary,
        MR.strings.desktop_backup_max_files,
        MR.strings.desktop_backup_file_filter,
        MR.strings.desktop_backup_restore_confirm_title,
        MR.strings.desktop_backup_restore_confirm_summary,
        MR.strings.desktop_backup_preview_library,
        MR.strings.desktop_backup_preview_services,
        MR.strings.desktop_backup_preview_repositories,
        MR.strings.desktop_backup_reading_file,
        MR.strings.desktop_backup_progress,
        MR.strings.desktop_backup_completed_count,
        MR.strings.desktop_backup_partial,
        MR.strings.desktop_backup_more_errors,
        MR.strings.desktop_backup_create_cancelled,
        MR.strings.desktop_backup_restore_selection_cancelled,
        MR.strings.desktop_backup_restore_error_count,
        MR.strings.desktop_backup_error_item,
        MR.strings.desktop_backup_restore_storage_error,
        MR.strings.desktop_backup_restore_permission_error,
        MR.strings.desktop_backup_restore_malformed_error,
        MR.strings.desktop_backup_failed_unit,
        MR.strings.desktop_backup_unsupported_version,
        MR.strings.desktop_backup_empty_file,
        MR.strings.desktop_backup_missing_data,
        MR.strings.desktop_backup_restore_not_started,
        MR.strings.desktop_backup_restore_unknown_error,
    )
}
