package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.domain.ui.model.selectableAppThemes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.DesktopLocalizedNavigatorContent
import mihon.desktop.DesktopOwnerIngressDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.OwnerUiDependencies
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.backup.AutoBackupInterval
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.backup.DesktopBackupRestorer
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DownloadItem
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.DesktopLocaleAdapter
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.OperatingSystem
import mihon.desktop.platform.PlatformCredentialUnavailableException
import mihon.desktop.network.DesktopCloudflareCookieImportResult
import mihon.desktop.network.DesktopNetworkMaintenancePort
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
import mihon.desktop.ui.settings.ExtensionRepoScreen
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
import mihon.desktop.tracking.DesktopAuthenticatingTrackerService
import mihon.desktop.ui.tracking.TrackingMessage
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import mihon.desktop.ui.tracking.trackingMessageText
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
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.i18n.MR
import java.io.File
import java.nio.file.Files
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@org.junit.jupiter.api.parallel.Isolated
class DesktopSettingsResourceIdentityTest {
    private val originalLocale = Locale.getDefault()
    private val english = Locale.US
    private val chinese = Locale.forLanguageTag("zh-CN")

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

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
    fun `Tracking messages preserve typed identity and render load failure through production screen`() = runBlocking {
        val previousLocale = Locale.getDefault()
        try {
            fun validationDependencies(status: Long, score: Double): DesktopUiDependencies {
                val track = mockk<Track>(relaxed = true) {
                    every { trackerId } returns 7L
                    every { this@mockk.status } returns status
                    every { this@mockk.score } returns score
                }
                val repository = mockk<TrackRepository>(relaxed = true) {
                    coEvery { getTracksByMangaId(42L) } returns listOf(track)
                }
                val service = mockk<TrackerService>(relaxed = true) {
                    every { profile } returns MutableStateFlow(TrackerProfile(7L, "Test Service", TrackerAuthentication.OAUTH, true))
                    every { statuses } returns listOf(1L to "Reading")
                    every { scores } returns listOf(10.0)
                }
                return mockk(relaxed = true) {
                    every { getTracks } returns tachiyomi.domain.track.interactor.GetTracks(repository)
                    every { insertTrack } returns tachiyomi.domain.track.interactor.InsertTrack(repository)
                    every { deleteTrack } returns tachiyomi.domain.track.interactor.DeleteTrack(repository)
                    every { trackerServiceRegistry } returns
                        object : TrackerServiceRegistry { override val services = listOf(service) }
                }
            }

            listOf(english, chinese).forEach { locale ->
                val expected = listOf(
                    TrackingMessage.LoadFailed to MR.strings.desktop_tracking_load_failed.localized(locale),
                    TrackingMessage.Bound to MR.strings.desktop_tracking_bound.localized(locale),
                    TrackingMessage.Updated to MR.strings.desktop_tracking_updated.localized(locale),
                    TrackingMessage.Removed to MR.strings.desktop_tracking_removed.localized(locale),
                    TrackingMessage.LoggedOut to MR.strings.logout_success.localized(locale),
                    TrackingMessage.SearchTitleEmpty to MR.strings.desktop_tracking_search_title_empty.localized(locale),
                    TrackingMessage.MangaRequired to MR.strings.desktop_tracking_manga_required.localized(locale),
                    TrackingMessage.NotBound to MR.strings.desktop_tracking_not_bound.localized(locale),
                    TrackingMessage.NegativeChapter to MR.strings.desktop_tracking_negative_chapter.localized(locale),
                    TrackingMessage.ChapterOutOfRange(12) to
                        MR.strings.desktop_tracking_chapter_out_of_range.localized(locale, 12L),
                    TrackingMessage.UnknownService to MR.strings.desktop_tracking_unknown_service.localized(locale),
                    TrackingMessage.ServiceUnavailable to MR.strings.desktop_tracking_service_unavailable.localized(locale),
                    TrackingMessage.LoginRequired to MR.strings.desktop_tracking_login_required.localized(locale),
                    TrackingMessage.LoginCancelled to MR.strings.desktop_tracking_login_cancelled.localized(locale),
                    TrackingMessage.LoginFailed to MR.strings.desktop_tracking_login_failed.localized(locale),
                    TrackingMessage.LogoutFailed to MR.strings.desktop_tracking_logout_failed.localized(locale),
                    TrackingMessage.UnbindFailed to MR.strings.desktop_tracking_unbind_failed.localized(locale),
                )
                expected.forEach { (message, copy) -> assertEquals(copy, trackingMessageText(message, locale)) }
                assertEquals("Provider 原始资料 #42", trackingMessageText(TrackingMessage.External("Provider 原始资料 #42"), locale))

                val registry = object : TrackerServiceRegistry {
                    override val services = emptyList<TrackerService>()
                    override fun refresh() = throw IllegalStateException()
                }
                val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
                    every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
                    every { trackerServiceRegistry } returns registry
                }
                assertCopy(
                    renderAfterClicks(TrackingSettingsScreen(), dependencies, locale).text,
                    MR.strings.desktop_tracking_load_failed.localized(locale),
                )
                val validationFailures = listOf(
                    validationDependencies(99L, 10.0) to
                        MR.strings.desktop_tracking_unsupported_status.localized(locale, "Test Service"),
                    validationDependencies(1L, 7.5) to
                        MR.strings.desktop_tracking_unsupported_score.localized(locale, "Test Service"),
                ).mapNotNull { (screenDependencies, message) ->
                    val rendered = renderAfterClicks(
                        TrackingSettingsScreen(42L, "Manga", 12L),
                        screenDependencies,
                        locale,
                        MR.strings.desktop_tracking_manage.localized(locale),
                        MR.strings.desktop_tracking_update.localized(locale),
                    )
                    if (message in rendered.text) null else "Missing '$message': ${rendered.text}"
                }
                assertTrue(validationFailures.isEmpty(), validationFailures.joinToString("\n"))
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Tracking screens preserve shared actions fields parameters and desktop states`() = runBlocking {
        val previousLocale = Locale.getDefault()
        val unavailableReason = "Provider maintenance #42 · https://status.example/tracker"
        val mangaTitle = "Manga parameter #17"
        val serviceName = "Service parameter #29"
        try {
            listOf(english, chinese).forEach { locale ->
                val unavailable = trackingService(1, "Unavailable provider", TrackerAuthentication.OAUTH, false, unavailableReason = unavailableReason)
                val sourceManaged = trackingSourceService(2, "Source provider", loggedIn = true)
                val loggedIn = trackingService(3, serviceName, TrackerAuthentication.OAUTH, true, username = "reader@example.test")
                val loggedOut = trackingService(4, "Logged-out provider", TrackerAuthentication.OAUTH, false)
                val settings = render(
                    TrackingSettingsScreen(),
                    trackingDependencies(listOf(unavailable, sourceManaged, loggedIn, loggedOut)),
                    locale,
                    height = 2_400,
                )
                assertCopy(
                    settings.text,
                    MR.strings.pref_category_tracking.localized(locale),
                    MR.strings.pref_auto_update_manga_sync.localized(locale),
                    MR.strings.desktop_tracking_auto_update_summary.localized(locale),
                    unavailableReason,
                    MR.strings.desktop_tracking_source_available.localized(locale),
                    MR.strings.desktop_tracking_logged_in_as_not_bound.localized(locale, "reader@example.test"),
                    MR.strings.desktop_tracking_not_logged_in.localized(locale),
                    MR.strings.desktop_tracking_source_managed.localized(locale),
                    MR.strings.logout.localized(locale),
                    MR.strings.login.localized(locale),
                )
                assertCopy(settings.descriptions, MR.strings.action_bar_up_description.localized(locale))

                listOf(
                    TrackerAuthentication.USERNAME_PASSWORD to listOf(MR.strings.username, MR.strings.password),
                    TrackerAuthentication.API_KEY to listOf(MR.strings.desktop_tracking_api_key),
                    TrackerAuthentication.OAUTH to listOf(MR.strings.desktop_tracking_oauth_browser),
                ).forEachIndexed { index, (authentication, fields) ->
                    val authName = "Auth provider $index"
                    val dialog = renderAfterClicks(
                        TrackingSettingsScreen(),
                        trackingDependencies(listOf(trackingService(10L + index, authName, authentication, false))),
                        locale,
                        MR.strings.login.localized(locale),
                    )
                    assertCopy(
                        dialog.text,
                        MR.strings.login_title.localized(locale, authName),
                        MR.strings.login.localized(locale),
                        MR.strings.action_cancel.localized(locale),
                        *fields.map { it.localized(locale) }.toTypedArray(),
                    )
                }

                val platformUnavailable = renderAfterClicks(
                    TrackingSettingsScreen(42, mangaTitle, 12),
                    trackingDependencies(listOf(trackingSourceService(5, "Source login", loggedIn = false))),
                    locale,
                    MR.strings.login.localized(locale),
                )
                assertCopy(platformUnavailable.text, MR.strings.desktop_tracking_platform_unavailable.localized(locale))

                val logout = renderAfterClicks(
                    TrackingSettingsScreen(),
                    trackingDependencies(listOf(loggedIn)),
                    locale,
                    MR.strings.logout.localized(locale),
                )
                assertCopy(
                    logout.text,
                    MR.strings.logout_title.localized(locale, serviceName),
                    MR.strings.desktop_tracking_logout_consequence.localized(locale),
                    MR.strings.logout.localized(locale),
                    MR.strings.action_cancel.localized(locale),
                )

                val boundTrack = trackingTrack(trackerId = 3, title = "Remote title #51")
                val boundDependencies = trackingDependencies(listOf(loggedIn), listOf(boundTrack))
                val editor = renderAfterClicks(
                    TrackingSettingsScreen(42, mangaTitle, 12),
                    boundDependencies,
                    locale,
                    MR.strings.desktop_tracking_manage.localized(locale),
                )
                assertCopy(
                    editor.text,
                    MR.strings.manga_tracking_tab.localized(locale),
                    MR.strings.desktop_tracking_dialog_title.localized(locale, mangaTitle, serviceName),
                    MR.strings.desktop_tracking_bound_to.localized(locale, boundTrack.title),
                    "${MR.strings.status.localized(locale)}: Reading status #61",
                    "${MR.strings.score.localized(locale)}: 8.0",
                    MR.strings.chapters.localized(locale),
                    "2.0 / 12",
                    MR.strings.desktop_tracking_update.localized(locale),
                    MR.strings.action_remove.localized(locale),
                    MR.strings.action_close.localized(locale),
                )
                val unbind = renderAfterClicks(
                    TrackingSettingsScreen(42, mangaTitle, 12),
                    boundDependencies,
                    locale,
                    MR.strings.desktop_tracking_manage.localized(locale),
                    MR.strings.action_remove.localized(locale),
                )
                assertCopy(
                    unbind.text,
                    MR.strings.track_delete_title.localized(locale, serviceName),
                    MR.strings.track_delete_text.localized(locale),
                    MR.strings.action_remove.localized(locale),
                    MR.strings.action_cancel.localized(locale),
                )

                val result = TrackSearchResult(77, "External result #77", 20, remoteUrl = "https://tracker.example/item/77")
                val searchService = trackingService(6, "Search provider", TrackerAuthentication.OAUTH, true, searchResults = listOf(result))
                val searchScene = trackingScene(
                    TrackingSettingsScreen(42, mangaTitle, 12),
                    trackingDependencies(listOf(searchService)),
                    locale,
                )
                try {
                    snapshot(searchScene)
                    click(searchScene, MR.strings.desktop_tracking_manage.localized(locale))
                    snapshot(searchScene)
                    click(searchScene, MR.strings.action_search.localized(locale))
                    assertCopy(snapshot(searchScene).text, MR.strings.desktop_tracking_search_title_empty.localized(locale))
                    setText(searchScene, 0, MR.strings.action_search_hint.localized(locale), "needle")
                    click(searchScene, MR.strings.action_search.localized(locale))
                    assertCopy(
                        snapshot(searchScene).text,
                        result.title,
                        MR.strings.action_track.localized(locale),
                        MR.strings.desktop_tracking_dialog_title.localized(locale, mangaTitle, "Search provider"),
                    )
                } finally {
                    searchScene.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Tracking dialog actions bind fields side effects and localized fallbacks`() = runBlocking {
        val previousLocale = Locale.getDefault()
        val mangaTitle = "Action manga #42"
        try {
            listOf(english, chinese).forEach { locale ->
                val passwordService = trackingService(10, "Password provider", TrackerAuthentication.USERNAME_PASSWORD, false)
                withTrackingScene(TrackingSettingsScreen(), trackingDependencies(listOf(passwordService)), locale) { scene ->
                    openTracking(scene, MR.strings.login.localized(locale))
                    clickTracking(scene, MR.strings.action_cancel.localized(locale))
                    coVerify(exactly = 0) { passwordService.login(any(), any()) }
                    openTracking(scene, MR.strings.login.localized(locale))
                    setText(scene, 0, MR.strings.username.localized(locale), "user-10")
                    setText(scene, 1, MR.strings.password.localized(locale), "pass-10")
                    clickTracking(scene, MR.strings.login.localized(locale))
                    coVerify(exactly = 1) { passwordService.login("user-10", "pass-10") }
                }

                val apiService = trackingService(11, "API provider", TrackerAuthentication.API_KEY, false)
                withTrackingScene(TrackingSettingsScreen(), trackingDependencies(listOf(apiService)), locale) { scene ->
                    openTracking(scene, MR.strings.login.localized(locale))
                    setText(scene, 0, MR.strings.desktop_tracking_api_key.localized(locale), "api-key-11")
                    clickTracking(scene, MR.strings.login.localized(locale))
                    coVerify(exactly = 1) { apiService.loginWithApiKey("api-key-11") }
                }

                val logoutService = trackingService(12, "Logout provider", TrackerAuthentication.OAUTH, true)
                withTrackingScene(TrackingSettingsScreen(), trackingDependencies(listOf(logoutService)), locale) { scene ->
                    openTracking(scene, MR.strings.logout.localized(locale))
                    clickTracking(scene, MR.strings.action_cancel.localized(locale))
                    coVerify(exactly = 0) { logoutService.logout() }
                    openTracking(scene, MR.strings.logout.localized(locale))
                    clickTracking(scene, MR.strings.logout.localized(locale))
                    coVerify(exactly = 1) { logoutService.logout() }
                }

                val track = trackingTrack(13, "Bound title")
                val boundService = trackingService(13, "Bound provider", TrackerAuthentication.OAUTH, true)
                coEvery { boundService.update(track, any()) } returns track
                val boundRepository = trackingRepository(listOf(track))
                val boundDependencies = trackingDependencies(listOf(boundService), repository = boundRepository)
                withTrackingScene(TrackingSettingsScreen(42, mangaTitle, 12), boundDependencies, locale) { scene ->
                    openTracking(scene, MR.strings.desktop_tracking_manage.localized(locale))
                    clickTracking(scene, MR.strings.desktop_tracking_update.localized(locale))
                    coVerify(exactly = 1) { boundService.update(track, TrackEdit(1, 8.0, 2.0)) }
                    coVerify(exactly = 1) { boundRepository.insert(track) }
                }
                withTrackingScene(TrackingSettingsScreen(42, mangaTitle, 12), boundDependencies, locale) { scene ->
                    openTracking(scene, MR.strings.desktop_tracking_manage.localized(locale))
                    openTracking(scene, MR.strings.action_remove.localized(locale))
                    clickTracking(scene, MR.strings.action_cancel.localized(locale))
                    coVerify(exactly = 0) { boundRepository.delete(any(), any()) }
                    openTracking(scene, MR.strings.action_remove.localized(locale))
                    clickTracking(scene, MR.strings.action_remove.localized(locale))
                    coVerify(exactly = 1) { boundRepository.delete(42, 13) }
                }

                val result = TrackSearchResult(77, "Search result", 20)
                val resultTrack = trackingTrack(14, result.title)
                val searchService = trackingService(14, "Search provider", TrackerAuthentication.OAUTH, true, searchResults = listOf(result))
                coEvery { searchService.bind(42, result) } returns resultTrack
                val searchRepository = trackingRepository(emptyList())
                val searchDependencies = trackingDependencies(listOf(searchService), repository = searchRepository)
                withTrackingScene(TrackingSettingsScreen(42, mangaTitle, 12), searchDependencies, locale) { scene ->
                    openTracking(scene, MR.strings.desktop_tracking_manage.localized(locale))
                    searchFor(scene, locale, "query")
                    clickTracking(scene, MR.strings.action_close.localized(locale))
                    coVerify(exactly = 0) { searchService.bind(any(), any()) }
                    openTracking(scene, MR.strings.desktop_tracking_manage.localized(locale))
                    searchFor(scene, locale, "query")
                    clickTracking(scene, MR.strings.action_track.localized(locale))
                    coVerify(exactly = 1) { searchService.bind(42, result) }
                    coVerify(exactly = 1) { searchRepository.insert(resultTrack) }
                }

                listOf(
                    MR.strings.desktop_tracking_search_failed,
                    MR.strings.desktop_tracking_bind_failed,
                    MR.strings.desktop_tracking_update_failed,
                ).forEachIndexed { operation, expected ->
                    val failedTrack = trackingTrack(20L + operation, "Failure track")
                    val failedResult = TrackSearchResult(20L + operation, "Failure result", 12)
                    val failedService = trackingService(
                        20L + operation, "Failure provider", TrackerAuthentication.OAUTH, true,
                        searchResults = listOf(failedResult),
                    )
                    val failedRepository = trackingRepository(if (operation == 2) listOf(failedTrack) else emptyList())
                    when (operation) {
                        0 -> coEvery { failedService.search(any()) } throws IllegalStateException()
                        1 -> {
                            coEvery { failedService.bind(any(), any()) } returns failedTrack
                            coEvery { failedRepository.insert(any()) } throws IllegalStateException()
                        }
                        else -> coEvery { failedService.update(any(), any()) } throws IllegalStateException()
                    }
                    val dependencies = trackingDependencies(listOf(failedService), repository = failedRepository)
                    withTrackingScene(TrackingSettingsScreen(42, mangaTitle, 12), dependencies, locale) { scene ->
                        openTracking(scene, MR.strings.desktop_tracking_manage.localized(locale))
                        if (operation < 2) searchFor(scene, locale, "failure")
                        if (operation == 1) clickTracking(scene, MR.strings.action_track.localized(locale))
                        if (operation == 2) clickTracking(scene, MR.strings.desktop_tracking_update.localized(locale))
                        assertCopy(snapshot(scene).text, expected.localized(locale))
                    }
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `More General and Appearance render their shared MR identities`() = runBlocking {
        val prefs = DesktopAppPreferences(InMemoryPreferenceStore())
        val localeAdapter = DesktopLocaleAdapter(prefs.appLanguage, english, Locale::setDefault)
        val downloads = mockk<DesktopDownloadManager> {
            every { queue } returns MutableStateFlow(listOf(mockk<DownloadItem>(), mockk<DownloadItem>()))
        }
        val emptyDownloads = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        val network = mockk<DesktopNetworkHelper> {
            every { routeObservations } returns MutableStateFlow(emptyList())
            every { activeGlobalMode } returns prefs.globalNetworkMode.get()
            every { activeGlobalProxy } returns prefs.proxyRuntimeConfig()
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns prefs
            every { this@mockk.localeAdapter } returns localeAdapter
            every { downloadManager } returns downloads
            every { downloadQueuePort } returns downloads
            every { networkHelper } returns network
            every { networkRoutingPort } returns network
        }
        val emptyDependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns prefs
            every { this@mockk.localeAdapter } returns localeAdapter
            every { downloadManager } returns emptyDownloads
            every { downloadQueuePort } returns emptyDownloads
        }
        val previousLocale = Locale.getDefault()
        try {
            listOf(english, chinese).forEach { locale ->
                val more = render(MoreRootScreen(), dependencies, locale, height = 2_000)
                assertCopy(
                    more.text,
                    MR.strings.label_more.localized(locale),
                    MR.strings.pref_incognito_mode.localized(locale),
                    MR.strings.pref_incognito_mode_summary.localized(locale),
                    MR.strings.label_download_queue.localized(locale),
                    MR.strings.desktop_more_download_queue_count.localized(locale, 2),
                    MR.strings.label_extensions.localized(locale),
                    MR.strings.desktop_more_extensions_summary.localized(locale),
                    MR.strings.label_migration.localized(locale),
                    MR.strings.desktop_more_migration_summary.localized(locale),
                    MR.strings.label_stats.localized(locale),
                    MR.strings.desktop_more_stats_summary.localized(locale),
                    MR.strings.label_settings.localized(locale),
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
                val themeCopy = selectableAppThemes(dynamicColorAvailable = false)
                    .map { requireNotNull(it.titleRes).localized(locale) }
                assertCopy(
                    appearance.text,
                    MR.strings.pref_category_appearance.localized(locale),
                    MR.strings.pref_app_language.localized(locale),
                    MR.strings.desktop_language_follow_system.localized(locale),
                    MR.strings.pref_category_theme.localized(locale),
                    MR.strings.theme_system.localized(locale),
                    MR.strings.theme_light.localized(locale),
                    MR.strings.theme_dark.localized(locale),
                    MR.strings.pref_app_theme.localized(locale),
                    MR.strings.pref_dark_theme_pure_black.localized(locale),
                    MR.strings.desktop_appearance_library_grid.localized(locale),
                    MR.strings.desktop_appearance_grid_columns.localized(locale, 3),
                    *themeCopy.toTypedArray(),
                )
                assertCopy(appearance.descriptions, MR.strings.action_bar_up_description.localized(locale))
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Appearance language entry applies default and reports persistence failure without changing selection`() = runBlocking {
        val preferenceStore = InMemoryPreferenceStore()
        val preferences = DesktopAppPreferences(preferenceStore)
        preferences.appLanguage.set("en")
        val localeAdapter = DesktopLocaleAdapter(preferences.appLanguage, chinese, Locale::setDefault)
        localeAdapter.applyPersisted()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns preferences
            every { this@mockk.localeAdapter } returns localeAdapter
        }
        val firstLanguage = localeAdapter.availableLanguages(english).first()
        val dialog = renderAfterClicks(
            AppearanceSettingsScreen(),
            dependencies,
            english,
            MR.strings.pref_app_language.localized(english),
            useLocalizedHost = true,
        )
        assertCopy(dialog.text, firstLanguage.displayName, requireNotNull(firstLanguage.localizedDisplayName))

        val applied = renderAfterClicks(
            AppearanceSettingsScreen(),
            dependencies,
            english,
            MR.strings.pref_app_language.localized(english),
            MR.strings.desktop_language_follow_system.localized(english),
            expectedTextAfterClicks = "${MR.strings.pref_app_language.localized(chinese)}: ${MR.strings.desktop_language_follow_system.localized(chinese)}",
            useLocalizedHost = true,
        )

        assertEquals("", preferences.appLanguage.get())
        assertEquals("", localeAdapter.activeLanguageTag.value)
        assertEntry(
            applied,
            MR.strings.pref_app_language.localized(chinese),
            MR.strings.desktop_language_follow_system.localized(chinese),
        )
        assertCopy(
            applied.text,
            MR.strings.pref_app_language.localized(chinese),
            MR.strings.desktop_language_follow_system.localized(chinese),
            "${MR.strings.pref_app_language.localized(chinese)}: ${MR.strings.desktop_language_follow_system.localized(chinese)}",
        )

        preferences.appLanguage.set("en")
        val failingPreference = FailingStringPreference(preferences.appLanguage)
        val failing = DesktopLocaleAdapter(failingPreference, chinese, Locale::setDefault)
        failing.applyPersisted()
        failingPreference.failWrites = true
        val failingDependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns preferences
            every { this@mockk.localeAdapter } returns failing
        }
        val failed = renderAfterClicks(
            AppearanceSettingsScreen(),
            failingDependencies,
            english,
            MR.strings.pref_app_language.localized(english),
            MR.strings.desktop_language_follow_system.localized(english),
            useLocalizedHost = true,
        )

        assertEquals("en", preferences.appLanguage.get())
        assertEquals("en", failing.activeLanguageTag.value)
        assertCopy(failed.text, MR.strings.unknown_error.localized(english))
    }

    @Test
    fun `production locale navigation retains Appearance and delivers feedback after localized recreation`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        preferences.appLanguage.set("en")
        val localeAdapter = DesktopLocaleAdapter(preferences.appLanguage, chinese, Locale::setDefault)
        localeAdapter.applyPersisted()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns preferences
            every { this@mockk.localeAdapter } returns localeAdapter
        }
        val owner = DesktopOwnerIngressDependencies(mockk(relaxed = true), dependencies)
        val scene = ImageComposeScene(900, 2_400, coroutineContext = coroutineContext) {}
        var originalNavigator: Navigator? = null
        var latestNavigator: Navigator? = null
        try {
            scene.setContent {
                OwnerUiDependencies(owner) {
                    Navigator(AppearanceSettingsScreen()) { navigator ->
                        if (originalNavigator == null) originalNavigator = navigator
                        latestNavigator = navigator
                        DesktopLocalizedNavigatorContent(localeAdapter, navigator)
                    }
                }
            }
            repeat(3) { scene.render(); yield() }
            click(scene, MR.strings.pref_app_language.localized(english))
            repeat(3) { scene.render(); yield() }
            click(scene, MR.strings.desktop_language_follow_system.localized(english))
            val expectedFeedback =
                "${MR.strings.pref_app_language.localized(chinese)}: ${MR.strings.desktop_language_follow_system.localized(chinese)}"
            awaitText(scene, expectedFeedback)
            val rendered = RenderedCopy(textCopy(scene), descriptionCopy(scene), entryCopy(scene), selectedEntryCopy(scene))

            assertSame(requireNotNull(originalNavigator), requireNotNull(latestNavigator))
            assertInstanceOf(AppearanceSettingsScreen::class.java, requireNotNull(latestNavigator).lastItem)
            dismissSnackbar(scene, localeAdapter)
            assertNull(localeAdapter.pendingFeedback.value)
            assertEntry(
                rendered,
                MR.strings.pref_app_language.localized(chinese),
                MR.strings.desktop_language_follow_system.localized(chinese),
            )
            assertCopy(
                rendered.text,
                MR.strings.pref_category_appearance.localized(chinese),
                expectedFeedback,
            )
        } finally {
            scene.close()
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
            every { extensionPresentationService } returns extensionManager
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
    fun `Extension repositories render shared identities through production repository`() = runBlocking {
        val previousLocale = Locale.getDefault()
        try {
            listOf(english, chinese).forEach { locale ->
                val repository = FakeExtensionRepoRepository()
                val refresh = mockk<UpdateExtensionRepo>(relaxed = true)
                val dependencies = extensionRepoDependencies(repository, update = refresh)
                val copy = render(ExtensionRepoScreen(), dependencies, locale)
                assertCopy(
                    copy.text,
                    MR.strings.label_extension_repos.localized(locale),
                    MR.strings.information_empty_repos.localized(locale),
                    MR.strings.desktop_extension_repo_empty_hint.localized(locale),
                )
                assertCopy(
                    copy.descriptions,
                    MR.strings.action_bar_up_description.localized(locale),
                    MR.strings.action_add_repo.localized(locale),
                    MR.strings.action_webview_refresh.localized(locale),
                )

                val required = render(ExtensionRepoScreen(""), dependencies, locale)
                assertCopy(
                    required.text,
                    MR.strings.action_add_repo.localized(locale),
                    MR.strings.desktop_extension_repo_add_message.localized(locale),
                    MR.strings.label_add_repo_input.localized(locale),
                    MR.strings.information_required_plain.localized(locale),
                    MR.strings.action_add.localized(locale),
                    MR.strings.action_cancel.localized(locale),
                )

                val repo = ExtensionRepo("https://repo.example", "Example Repo", "Example", "https://website.example", "fingerprint")
                repository.insertRepo(repo.baseUrl, repo.name, repo.shortName, repo.website, repo.signingKeyFingerprint)
                val duplicateScene = extensionRepoScene(ExtensionRepoScreen(repo.baseUrl), dependencies, locale)
                try {
                    assertCopy(snapshot(duplicateScene).text, MR.strings.error_repo_exists.localized(locale))
                    val field = nodes(duplicateScene).single { it.config.contains(SemanticsActions.SetText) }
                    assertEquals(AnnotatedString(repo.baseUrl), field.config[SemanticsProperties.EditableText])
                } finally {
                    duplicateScene.close()
                }

                val copied = mutableListOf<AnnotatedString>()
                val clipboardManager = mockk<ClipboardManager>(relaxed = true)
                every { clipboardManager.setText(any()) } answers { copied += arg<AnnotatedString>(0) }
                val scene = extensionRepoScene(ExtensionRepoScreen(), dependencies, locale, clipboardManager)
                try {
                    val listed = snapshot(scene)
                    assertCopy(listed.text, repo.name, requireNotNull(repo.shortName), repo.baseUrl)
                    val expectedActions = listOf(
                        MR.strings.action_open_in_browser.localized(locale),
                        MR.strings.action_copy_link.localized(locale),
                        MR.strings.action_delete_repo.localized(locale),
                    )
                    val repoActions = nodes(scene).filter { node ->
                        node.config.contains(SemanticsActions.OnClick) &&
                            flatten(node).flatMap(::descriptionCopy).any { it in expectedActions }
                    }
                    assertEquals(expectedActions, repoActions.map { flatten(it).flatMap(::descriptionCopy).single() })
                    assertTrue(requireNotNull(repoActions[1].config[SemanticsActions.OnClick].action).invoke())
                    assertEquals(listOf(AnnotatedString("${repo.baseUrl}/index.min.json")), copied)
                    click(scene, MR.strings.action_webview_refresh.localized(locale))
                    snapshot(scene)
                    coVerify(exactly = 1) { refresh.awaitAll() }
                    click(scene, MR.strings.action_delete_repo.localized(locale))
                    val delete = snapshot(scene)
                    assertCopy(
                        delete.text,
                        MR.strings.delete_repo_confirmation.localized(locale, repo.baseUrl),
                        MR.strings.desktop_extension_repo_delete_consequence.localized(locale),
                        MR.strings.action_remove.localized(locale),
                        MR.strings.action_cancel.localized(locale),
                    )
                    click(scene, MR.strings.action_remove.localized(locale))
                    assertCopy(snapshot(scene).text, MR.strings.information_empty_repos.localized(locale))
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `Extension repository create outcomes use production screen wiring`() = runBlocking {
        val previousLocale = Locale.getDefault()
        try {
            listOf(english, chinese).forEach { locale ->
                val outcomes = listOf(
                    CreateExtensionRepo.Result.InvalidUrl to MR.strings.desktop_extension_repo_https_required.localized(locale),
                    CreateExtensionRepo.Result.RepositoryUnavailable to MR.strings.desktop_extension_repo_unavailable.localized(locale),
                    CreateExtensionRepo.Result.InvalidRepository to MR.strings.desktop_extension_repo_invalid_metadata.localized(locale),
                    CreateExtensionRepo.Result.RepoAlreadyExists to MR.strings.error_repo_exists.localized(locale),
                    CreateExtensionRepo.Result.Error to MR.strings.desktop_extension_repo_add_failed.localized(locale),
                )
                outcomes.forEachIndexed { index, (result, expected) ->
                    val create = mockk<CreateExtensionRepo> { coEvery { await(any()) } returns result }
                    val scene = extensionRepoScene(
                        ExtensionRepoScreen("https://repo$index.example"),
                        extensionRepoDependencies(FakeExtensionRepoRepository(), create),
                        locale,
                    )
                    try {
                        snapshot(scene)
                        click(scene, MR.strings.action_add.localized(locale))
                        assertCopy(snapshot(scene).text, expected)
                    } finally {
                        scene.close()
                    }
                }

                val successRepository = FakeExtensionRepoRepository()
                val success = mockk<CreateExtensionRepo> {
                    coEvery { await(any()) } coAnswers {
                        successRepository.insertRepo("https://created.example", "Created", "Short", "https://website.example", "created-fp")
                        CreateExtensionRepo.Result.Success
                    }
                }
                val successScene = extensionRepoScene(
                    ExtensionRepoScreen("https://created.example/index.min.json"),
                    extensionRepoDependencies(successRepository, success),
                    locale,
                )
                try {
                    snapshot(successScene)
                    click(successScene, MR.strings.action_add.localized(locale))
                    assertCopy(snapshot(successScene).text, "Created", "Short", "https://created.example")
                } finally {
                    successScene.close()
                }

                val pending = CompletableDeferred<CreateExtensionRepo.Result>()
                val pendingCreate = mockk<CreateExtensionRepo> { coEvery { await(any()) } coAnswers { pending.await() } }
                val pendingUrl = "https://pending.example"
                val pendingScene = extensionRepoScene(
                    ExtensionRepoScreen(pendingUrl),
                    extensionRepoDependencies(FakeExtensionRepoRepository(), pendingCreate),
                    locale,
                )
                try {
                    snapshot(pendingScene)
                    click(pendingScene, MR.strings.action_add.localized(locale))
                    assertCopy(snapshot(pendingScene).text, MR.strings.desktop_extension_repo_pending.localized(locale), pendingUrl)
                    pending.complete(CreateExtensionRepo.Result.Error)
                    snapshot(pendingScene)
                } finally {
                    pendingScene.close()
                }

                val conflictRepository = FakeExtensionRepoRepository()
                val oldRepo = ExtensionRepo("https://old.example", "Old", null, "https://old.example", "shared-fp")
                val newRepo = ExtensionRepo("https://new.example", "New", null, "https://new.example", "shared-fp")
                conflictRepository.insertRepo(oldRepo.baseUrl, oldRepo.name, oldRepo.shortName, oldRepo.website, oldRepo.signingKeyFingerprint)
                val conflictCreate = mockk<CreateExtensionRepo> {
                    coEvery { await(newRepo.baseUrl) } returns CreateExtensionRepo.Result.DuplicateFingerprint(oldRepo, newRepo)
                }
                val conflictScene = extensionRepoScene(
                    ExtensionRepoScreen(newRepo.baseUrl),
                    extensionRepoDependencies(conflictRepository, conflictCreate),
                    locale,
                )
                try {
                    snapshot(conflictScene)
                    click(conflictScene, MR.strings.action_add.localized(locale))
                    assertCopy(
                        snapshot(conflictScene).text,
                        MR.strings.action_replace_repo_title.localized(locale),
                        MR.strings.action_replace_repo_message.localized(locale, newRepo.name, oldRepo.name),
                        MR.strings.action_replace_repo.localized(locale),
                    )
                    click(conflictScene, MR.strings.action_replace_repo.localized(locale))
                    assertCopy(snapshot(conflictScene).text, newRepo.name, newRepo.baseUrl)
                    assertEquals(listOf(newRepo), conflictRepository.getAll())
                } finally {
                    conflictScene.close()
                }
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
    fun `Advanced renders localized production states and shared identities`() = runBlocking {
        val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val networkMaintenance = mockk<DesktopNetworkMaintenancePort>(relaxed = true) {
            every { importCloudflareCookie("", "") } returns DesktopCloudflareCookieImportResult.InvalidValue
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { this@mockk.appPreferences } returns appPreferences
            every { networkMaintenancePort } returns networkMaintenance
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
                    awaitText(successScene, "42 KB")
                    click(successScene, MR.strings.desktop_advanced_crash_log_open.localized(locale))
                    awaitText(successScene, MR.strings.desktop_advanced_crash_log_opened.localized(locale))
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
                    awaitText(failureScene, MR.strings.desktop_advanced_crash_log_open_failed.localized(locale))
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
        expectedTextAfterClicks: String? = null,
        useLocalizedHost: Boolean = false,
    ): RenderedCopy {
        Locale.setDefault(locale)
        val scene = ImageComposeScene(900, 2_400, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        return try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { navigator ->
                        if (useLocalizedHost) {
                            DesktopLocalizedNavigatorContent(dependencies.localeAdapter, navigator)
                        } else {
                            CurrentScreen()
                        }
                    }
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
            if (expectedTextAfterClicks != null) {
                awaitText(scene, expectedTextAfterClicks)
                RenderedCopy(textCopy(scene), descriptionCopy(scene), entryCopy(scene), selectedEntryCopy(scene)).also {
                    dismissSnackbar(scene, dependencies.localeAdapter)
                    assertNull(dependencies.localeAdapter.pendingFeedback.value)
                }
            } else {
                RenderedCopy(textCopy(scene), descriptionCopy(scene), entryCopy(scene), selectedEntryCopy(scene))
            }
        } finally {
            scene.close()
        }
    }

    private suspend fun awaitText(scene: ImageComposeScene, expected: String) = withTimeout(5_000) {
        while (expected !in textCopy(scene)) {
            scene.render()
            yield()
        }
        repeat(3) {
            scene.render()
            yield()
        }
        assertTrue(expected in textCopy(scene), "Expected '$expected' to remain visible across recomposition")
    }

    private suspend fun dismissSnackbar(scene: ImageComposeScene, localeAdapter: DesktopLocaleAdapter) {
        val dismiss = nodes(scene).first { it.config.contains(SemanticsActions.Dismiss) }
        assertTrue(requireNotNull(dismiss.config[SemanticsActions.Dismiss].action).invoke())
        withTimeout(5_000) {
            while (localeAdapter.pendingFeedback.value != null) {
                scene.render()
                yield()
            }
        }
    }

    private suspend fun trackingScene(
        screen: TrackingSettingsScreen,
        dependencies: DesktopUiDependencies,
        locale: Locale,
    ): ImageComposeScene {
        Locale.setDefault(locale)
        return ImageComposeScene(900, 2_400, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
        }
    }

    private suspend fun withTrackingScene(
        screen: TrackingSettingsScreen,
        dependencies: DesktopUiDependencies,
        locale: Locale,
        block: suspend (ImageComposeScene) -> Unit,
    ) {
        val scene = trackingScene(screen, dependencies, locale)
        try {
            block(scene)
        } finally {
            scene.close()
        }
    }

    private suspend fun openTracking(scene: ImageComposeScene, label: String) {
        snapshot(scene)
        clickTracking(scene, label)
    }

    private suspend fun clickTracking(scene: ImageComposeScene, label: String) {
        click(scene, label)
        snapshot(scene)
    }

    private suspend fun searchFor(scene: ImageComposeScene, locale: Locale, query: String) {
        setText(scene, 0, MR.strings.action_search_hint.localized(locale), query)
        clickTracking(scene, MR.strings.action_search.localized(locale))
    }

    private fun trackingService(
        id: Long,
        name: String,
        authentication: TrackerAuthentication,
        loggedIn: Boolean,
        username: String? = null,
        unavailableReason: String? = null,
        searchResults: List<TrackSearchResult> = emptyList(),
    ) = mockk<DesktopAuthenticatingTrackerService>(relaxed = true) {
        every { profile } returns MutableStateFlow(TrackerProfile(id, name, authentication, loggedIn, username, unavailableReason))
        every { statuses } returns listOf(1L to "Reading status #61")
        every { scores } returns listOf(8.0)
        coEvery { search(any()) } returns searchResults
    }

    private fun trackingSourceService(id: Long, name: String, loggedIn: Boolean) = mockk<TrackerService>(relaxed = true) {
        every { profile } returns MutableStateFlow(TrackerProfile(id, name, TrackerAuthentication.API_KEY, loggedIn))
        every { statuses } returns emptyList()
        every { scores } returns emptyList()
    }

    private fun trackingRepository(tracks: List<Track>) = mockk<TrackRepository>(relaxed = true) {
        coEvery { getTracksByMangaId(any()) } returns tracks
    }

    private fun trackingDependencies(
        services: List<TrackerService>,
        tracks: List<Track> = emptyList(),
        repository: TrackRepository = trackingRepository(tracks),
    ) =
        mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
            every { trackerServiceRegistry } returns object : TrackerServiceRegistry { override val services = services }
            every { getTracks } returns tachiyomi.domain.track.interactor.GetTracks(repository)
            every { insertTrack } returns tachiyomi.domain.track.interactor.InsertTrack(repository)
            every { deleteTrack } returns tachiyomi.domain.track.interactor.DeleteTrack(repository)
        }

    private fun trackingTrack(trackerId: Long, title: String) = Track(
        id = 1,
        mangaId = 42,
        trackerId = trackerId,
        remoteId = 51,
        libraryId = null,
        title = title,
        lastChapterRead = 2.0,
        totalChapters = 12,
        status = 1,
        score = 8.0,
        remoteUrl = "https://tracker.example/item/51",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

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

    private suspend fun extensionRepoScene(
        screen: ExtensionRepoScreen,
        dependencies: DesktopUiDependencies,
        locale: Locale,
        clipboardManager: ClipboardManager = mockk(relaxed = true),
    ): ImageComposeScene {
        Locale.setDefault(locale)
        return ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalClipboardManager provides clipboardManager,
                ) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
        }
    }

    private fun extensionRepoDependencies(
        repository: FakeExtensionRepoRepository,
        create: CreateExtensionRepo = mockk(relaxed = true),
        update: UpdateExtensionRepo = mockk(relaxed = true),
    ) = mockk<DesktopUiDependencies>(relaxed = true) {
        every { getExtensionRepo } returns GetExtensionRepo(repository)
        every { createExtensionRepo } returns create
        every { deleteExtensionRepo } returns DeleteExtensionRepo(repository)
        every { replaceExtensionRepo } returns ReplaceExtensionRepo(repository)
        every { updateExtensionRepo } returns update
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
            it.config.contains(SemanticsActions.OnClick) &&
                flatten(it).any { node -> label in textCopy(node) || label in descriptionCopy(node) }
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

    private fun descriptionCopy(scene: ImageComposeScene): Set<String> = nodes(scene).flatMap(::descriptionCopy).toSet()

    private fun descriptionCopy(node: SemanticsNode): List<String> =
        if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList()

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

    private class FailingStringPreference(
        private val delegate: tachiyomi.core.common.preference.Preference<String>,
    ) : tachiyomi.core.common.preference.Preference<String> by delegate {
        var failWrites = false

        override fun set(value: String) {
            if (failWrites) error("language preference write failed")
            delegate.set(value)
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
        MR.strings.desktop_extension_repo_empty_hint,
        MR.strings.desktop_extension_repo_add_message,
        MR.strings.desktop_extension_repo_pending,
        MR.strings.desktop_extension_repo_https_required,
        MR.strings.desktop_extension_repo_unavailable,
        MR.strings.desktop_extension_repo_invalid_metadata,
        MR.strings.desktop_extension_repo_add_failed,
        MR.strings.desktop_extension_repo_delete_consequence,
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
        MR.strings.selected,
        MR.strings.not_selected,
        MR.strings.on,
        MR.strings.off,
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
