package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.backup.AutoBackupInterval
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.backup.DesktopBackupRestorer
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DownloadItem
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.LibraryUpdateInterval
import mihon.desktop.ui.settings.AppearanceSettingsScreen
import mihon.desktop.ui.settings.BackupPresentationText
import mihon.desktop.ui.settings.BackupRestoreFailureReason
import mihon.desktop.ui.settings.BackupRestoreScreenModel
import mihon.desktop.ui.settings.BackupRestoreUiState
import mihon.desktop.ui.settings.BackupSettingsScreen
import mihon.desktop.ui.settings.DownloadSettingsScreen
import mihon.desktop.ui.settings.GeneralSettingsScreen
import mihon.desktop.ui.settings.LibrarySettingsScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.ReaderSettingsScreen
import mihon.desktop.ui.settings.backupPartialFailurePresentation
import mihon.desktop.ui.settings.backupPresentationText
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
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
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

    private fun assertCopy(actual: Set<String>, vararg expected: String) {
        expected.forEach { assertTrue(it in actual, "Missing '$it': $actual") }
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

    private val desktopResources: List<StringResource> = listOf(
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
