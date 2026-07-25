package mihon.desktop.ui.tracking

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.tracking.DesktopAuthenticatingTrackerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerProviderCatalog
import tachiyomi.domain.track.service.TrackerProviderError
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderOperation
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerProviderSession
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class TrackingSettingsKeyboardDialogTest {
    private val keys = listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)

    @Test
    fun `login confirm dispatches once on key down with service specific credentials`() = runBlocking {
        listOf(
            Key.Enter to LoginInvocation(TrackerAuthentication.USERNAME_PASSWORD, "enter-user", "enter-password"),
            Key.NumPadEnter to LoginInvocation(TrackerAuthentication.USERNAME_PASSWORD, "numpad-user", "numpad-password"),
            Key.Spacebar to LoginInvocation(TrackerAuthentication.API_KEY, null, "api-key"),
        ).forEachIndexed { index, (key, credentials) ->
            val (method, username, secret) = credentials
            val fixture = LoginHarness.authenticatingService(index.toLong() + 1, "Confirm service $index", method)
            LoginHarness.scene(listOf(fixture.service)).useScene { scene ->
                LoginHarness.openLogin(scene, fixture.service.profile.value.name)
                LoginHarness.setCredentials(scene, method, username, secret)
                LoginHarness.activateExactlyOnce(
                    scene,
                    MR.strings.login.localized(),
                    key,
                    last = true,
                    calls = fixture.invocations::size,
                )
                assertEquals(listOf(credentials), fixture.invocations)
            }
        }
    }

    @Test
    fun `login cancel dismisses once on key down without authentication`() = runBlocking {
        val fixture = LoginHarness.authenticatingService(10, "Direct cancel", TrackerAuthentication.USERNAME_PASSWORD)
        keys.forEach { key ->
            var dismissCalls = 0
            LoginHarness.dialogScene(fixture.service) { dismissCalls++ }.useScene { scene ->
                LoginHarness.activateExactlyOnce(
                    scene,
                    MR.strings.action_cancel.localized(),
                    key,
                    calls = { dismissCalls },
                )
            }
        }
        LoginHarness.scene(listOf(fixture.service)).useScene { scene ->
            val title = MR.strings.login_title.localized(java.util.Locale.getDefault(), fixture.service.profile.value.name)
            LoginHarness.openLogin(scene, fixture.service.profile.value.name)
            LoginHarness.activateExactlyOnce(scene, MR.strings.action_cancel.localized(), Key.Enter) {
                if (title in LoginHarness.text(scene)) 0 else 1
            }
            assertTrue(fixture.invocations.isEmpty())
        }
    }

    @Test
    fun `invalid service specific credentials keep login disabled without authentication`() = runBlocking {
        listOf(
            Triple(TrackerAuthentication.USERNAME_PASSWORD, null, null),
            Triple(TrackerAuthentication.USERNAME_PASSWORD, "username", null),
            Triple(TrackerAuthentication.USERNAME_PASSWORD, null, "password"),
            Triple(TrackerAuthentication.USERNAME_PASSWORD, " \t", "\n"),
            Triple(TrackerAuthentication.API_KEY, null, null),
            Triple(TrackerAuthentication.API_KEY, null, " \t"),
        ).forEachIndexed { index, (method, username, secret) ->
            val fixture = LoginHarness.authenticatingService(index.toLong() + 20, "Invalid service $index", method)
            LoginHarness.dialogScene(fixture.service) {}.useScene { scene ->
                LoginHarness.setCredentials(scene, method, username, secret)
                LoginHarness.assertDisabledForKeys(scene, MR.strings.login.localized(), keys)
                assertTrue(fixture.invocations.isEmpty())
            }
        }
    }

    @Test
    fun `confirmation buttons dispatch once on key down`() = runBlocking {
        listOf(MR.strings.logout.localized(), MR.strings.action_remove.localized()).forEach { confirmLabel ->
            keys.forEach { key ->
                var confirms = 0
                var dismisses = 0
                LoginHarness.confirmationScene(confirmLabel, { confirms++ }, { dismisses++ }).useScene { scene ->
                    LoginHarness.activateExactlyOnce(scene, confirmLabel, key, calls = { confirms })
                    assertEquals(0, dismisses)
                }
                LoginHarness.confirmationScene(confirmLabel, { confirms++ }, { dismisses++ }).useScene { scene ->
                    LoginHarness.activateExactlyOnce(scene, MR.strings.action_cancel.localized(), key, calls = { dismisses })
                    assertEquals(1, confirms)
                }
            }
        }
    }

    @Test
    fun `remote delete exposes one checkbox action and keyboard toggles exactly once`() = runBlocking {
        listOf(Key.Enter, Key.Spacebar).forEach { key ->
            var toggles = 0
            LoginHarness.remoteDeleteConfirmationScene { toggles++ }.useScene { scene ->
                val label = "Delete remote entry"
                val operableCheckboxes = LoginHarness.operableCheckboxes(scene, label)
                assertEquals(1, operableCheckboxes.size)
                LoginHarness.activateExactlyOnce(scene, operableCheckboxes.single(), key) { toggles }
            }
        }
    }

    @Test
    fun `full settings logout confirms the selected tracker only and cancel has no side effect`() = runBlocking {
        val logoutIds = mutableListOf<Long>()
        val target = LoginHarness.authenticatingService(71, "Logout target", TrackerAuthentication.API_KEY, loggedIn = true) { logoutIds += 71L }
        val other = LoginHarness.authenticatingService(72, "Logout other", TrackerAuthentication.API_KEY, loggedIn = true) { logoutIds += 72L }
        LoginHarness.scene(listOf(target.service, other.service)).useScene { scene ->
            LoginHarness.click(scene, target.service.profile.value.name)
            LoginHarness.activateExactlyOnce(scene, MR.strings.action_cancel.localized(), Key.Enter) {
                if (MR.strings.desktop_tracking_logout_consequence.localized() in LoginHarness.text(scene)) 0 else 1
            }
            assertTrue(logoutIds.isEmpty())

            LoginHarness.click(scene, target.service.profile.value.name)
            LoginHarness.activateExactlyOnce(scene, MR.strings.logout.localized(), Key.NumPadEnter, dialogAnchor = MR.strings.desktop_tracking_logout_consequence.localized(), calls = logoutIds::size)
            LoginHarness.await(scene) { MR.strings.logout_success.localized() in LoginHarness.text(scene) }
            assertEquals(listOf(71L), logoutIds)
        }
    }

    @Test
    fun `full manga tracking unbinds the selected tracker only and cancel has no side effect`() = runBlocking {
        val trackerId = 81L
        val service = LoginHarness.authenticatingService(trackerId, "Unbind target", TrackerAuthentication.API_KEY, loggedIn = true)
        val deletions = mutableListOf<Pair<Long, Long>>()
        val repository = LoginHarness.repository(
            listOf(
                Track(1, 42, trackerId, 2, null, "Bound manga", 3.0, 10, 1, 0.0, "", 0, 0, false),
            ),
        ) { mangaId, deletedTrackerId -> deletions += mangaId to deletedTrackerId }
        LoginHarness.scene(listOf(service.service), repository, TrackingSettingsScreen(42, "Manga", 10)).useScene { scene ->
            LoginHarness.click(scene, service.service.profile.value.name)
            LoginHarness.click(scene, MR.strings.action_remove.localized())
            LoginHarness.activateExactlyOnce(scene, MR.strings.action_cancel.localized(), Key.Spacebar) {
                if (MR.strings.track_delete_text.localized() in LoginHarness.text(scene)) 0 else 1
            }
            assertTrue(deletions.isEmpty())

            LoginHarness.click(scene, MR.strings.action_remove.localized())
            LoginHarness.activateExactlyOnce(scene, MR.strings.action_remove.localized(), Key.Spacebar, dialogAnchor = MR.strings.track_delete_text.localized(), calls = deletions::size)
            LoginHarness.await(scene) { MR.strings.desktop_tracking_removed.localized() in LoginHarness.text(scene) }
            assertEquals(listOf(42L to trackerId), deletions)
        }
    }

    @Test
    fun `remote delete checkbox preserves local default and reports failure after local unbind`() = runBlocking {
        suspend fun exercise(
            removeRemote: Boolean,
            remoteFailure: TrackerProviderError? = null,
        ): Pair<List<TrackerProviderRequest>, List<Pair<Long, Long>>> {
            val fixture = LoginHarness.providerService(remoteFailure)
            val deletions = mutableListOf<Pair<Long, Long>>()
            val repository = LoginHarness.repository(
                listOf(Track(1, 42, 1, 2, 44, "Bound manga", 3.0, 10, 1, 0.0, "", 0, 0, false)),
            ) { mangaId, trackerId -> deletions += mangaId to trackerId }
            LoginHarness.scene(listOf(fixture.service), repository, TrackingSettingsScreen(42, "Manga", 10)).useScene { scene ->
                LoginHarness.click(scene, fixture.service.profile.value.name)
                LoginHarness.click(scene, MR.strings.action_remove.localized())
                val remoteLabel = MR.strings.track_delete_remote_text.localized(
                    java.util.Locale.getDefault(),
                    fixture.service.profile.value.name,
                )
                LoginHarness.await(scene) { remoteLabel in LoginHarness.text(scene) }
                if (removeRemote) LoginHarness.click(scene, remoteLabel)
                LoginHarness.click(scene, MR.strings.action_remove.localized(), last = true)
                LoginHarness.await(scene) { deletions.isNotEmpty() }
                remoteFailure?.let {
                    LoginHarness.await(scene) { TrackerProviderErrorKind.RATE_LIMITED.name in LoginHarness.text(scene) }
                }
            }
            return fixture.requests to deletions
        }

        val (localRequests, localDeletions) = exercise(removeRemote = false)
        assertTrue(localRequests.isEmpty())
        assertEquals(listOf(42L to 1L), localDeletions)

        val (remoteRequests, remoteDeletions) = exercise(removeRemote = true)
        assertTrue(remoteRequests.single() is TrackerProviderRequest.Delete)
        assertEquals(listOf(42L to 1L), remoteDeletions)

        val (failedRequests, failedDeletions) = exercise(
            removeRemote = true,
            remoteFailure = TrackerProviderError(
                TrackerProviderOperation.DELETE,
                TrackerProviderErrorKind.RATE_LIMITED,
                429,
            ),
        )
        assertTrue(failedRequests.single() is TrackerProviderRequest.Delete)
        assertEquals(listOf(42L to 1L), failedDeletions)
    }

    @Test
    fun `bound logged out and unavailable providers remain manageable for local unbind`() = runBlocking {
        listOf(
            LoginHarness.providerService(loggedIn = false, name = "Logged out bound"),
            LoginHarness.providerService(loggedIn = false, unavailableReason = "Provider unavailable", name = "Unavailable bound"),
        ).forEach { fixture ->
            val deletions = mutableListOf<Pair<Long, Long>>()
            val repository = LoginHarness.repository(
                listOf(Track(1, 42, 1, 22, 44, "Bound manga", 3.0, 10, 1, 70.0, "", 0, 0, false)),
            ) { mangaId, trackerId -> deletions += mangaId to trackerId }
            LoginHarness.scene(
                listOf(fixture.service),
                repository,
                TrackingSettingsScreen(42, "Manga", 10),
            ).useScene { scene ->
                LoginHarness.await(scene) { MR.strings.desktop_tracking_manage.localized() in LoginHarness.text(scene) }
                LoginHarness.click(scene, fixture.service.profile.value.name)
                LoginHarness.await(scene) { MR.strings.action_remove.localized() in LoginHarness.text(scene) }
                LoginHarness.click(scene, MR.strings.action_remove.localized())
                LoginHarness.click(scene, MR.strings.action_remove.localized(), last = true)
                LoginHarness.await(scene) { deletions.isNotEmpty() }
            }
            assertEquals(listOf(42L to 1L), deletions)
        }
    }

    @Test
    fun `provider edit uses mounted date pickers and explicit remove sends zero`() = runBlocking {
        val fixture = LoginHarness.providerService(id = 2, name = "AniList")
        val start = LocalDate.parse("2024-01-02").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val finish = LocalDate.parse("2025-03-04").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val repository = LoginHarness.repository(
            listOf(Track(1, 42, 2, 22, 44, "Bound manga", 3.0, 10, 1, 70.0, "", start, finish, false)),
        )
        LoginHarness.scene(listOf(fixture.service), repository, TrackingSettingsScreen(42, "Manga", 10)).useScene { scene ->
            LoginHarness.click(scene, fixture.service.profile.value.name)
            LoginHarness.await(scene) {
                listOf(
                    MR.strings.status.localized(),
                    MR.strings.score.localized(),
                    MR.strings.chapters.localized(),
                    MR.strings.tracked_privately.localized(),
                    MR.strings.track_started_reading_date.localized(),
                    MR.strings.track_finished_reading_date.localized(),
                ).all { it in LoginHarness.text(scene) }
            }

            assertFalse(LoginHarness.hasEditableField(scene, MR.strings.track_started_reading_date.localized()))
            assertFalse(LoginHarness.hasEditableField(scene, MR.strings.track_finished_reading_date.localized()))
            LoginHarness.click(scene, MR.strings.tracked_privately.localized())
            LoginHarness.click(scene, MR.strings.track_started_reading_date.localized())
            LoginHarness.await(scene) { MR.strings.action_remove.localized() in LoginHarness.text(scene) }
            LoginHarness.click(scene, MR.strings.action_remove.localized(), last = true)
            LoginHarness.click(scene, MR.strings.track_finished_reading_date.localized())
            LoginHarness.await(scene) { MR.strings.action_remove.localized() in LoginHarness.text(scene) }
            LoginHarness.click(scene, MR.strings.action_remove.localized(), last = true)
            LoginHarness.click(scene, MR.strings.desktop_tracking_update.localized())
            LoginHarness.await(scene) { fixture.edits.isNotEmpty() }
        }

        assertEquals(
            TrackEdit(
                status = 1,
                score = 70.0,
                lastChapterRead = 3.0,
                startDate = 0,
                finishDate = 0,
                private = true,
            ),
            fixture.edits.single(),
        )
    }

    @Test
    fun `date picker boundaries and timezone conversion preserve calendar days`() {
        val selectedDay = LocalDate.of(2024, 1, 2)
        listOf(ZoneId.of("America/Los_Angeles"), ZoneId.of("Asia/Tokyo")).forEach { zone ->
            val localEpoch = selectedDay.atStartOfDay(zone).toInstant().toEpochMilli()
            val pickerEpoch = selectedDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

            assertEquals(pickerEpoch, trackingDatePickerSelection(localEpoch, zone))
            assertEquals(localEpoch, trackingDateFromPickerSelection(pickerEpoch, zone))
            assertEquals(selectedDay, Instant.ofEpochMilli(trackingDateFromPickerSelection(pickerEpoch, zone)).atZone(zone).toLocalDate())
        }

        val today = LocalDate.of(2025, 3, 4)
        val start = LocalDate.of(2024, 2, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val finish = LocalDate.of(2024, 5, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val startPicker = TrackingSelectableDates(
            selectingStart = true,
            startDate = start,
            finishDate = finish,
            zone = ZoneOffset.UTC,
            today = today,
        )
        val finishPicker = TrackingSelectableDates(
            selectingStart = false,
            startDate = start,
            finishDate = finish,
            zone = ZoneOffset.UTC,
            today = today,
        )
        assertFalse(startPicker.isSelectableDate(LocalDate.of(2024, 5, 7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
        assertFalse(finishPicker.isSelectableDate(LocalDate.of(2024, 2, 2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
        assertFalse(finishPicker.isSelectableDate(LocalDate.of(2025, 3, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
        assertTrue(startPicker.isSelectableDate(LocalDate.of(2024, 5, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
        assertTrue(finishPicker.isSelectableDate(LocalDate.of(2024, 2, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
    }

    @Test
    fun `unsupported provider hides date and private controls and sends null fields`() = runBlocking {
        val fixture = LoginHarness.providerService(id = 4, name = "Shikimori")
        val repository = LoginHarness.repository(
            listOf(Track(1, 42, 4, 22, 44, "Bound manga", 3.0, 10, 1, 0.0, "", 0, 0, false)),
        )
        LoginHarness.scene(listOf(fixture.service), repository, TrackingSettingsScreen(42, "Manga", 10)).useScene { scene ->
            LoginHarness.click(scene, fixture.service.profile.value.name)
            LoginHarness.await(scene) { MR.strings.desktop_tracking_update.localized() in LoginHarness.text(scene) }
            assertFalse(MR.strings.tracked_privately.localized() in LoginHarness.text(scene))
            assertFalse(MR.strings.track_started_reading_date.localized() in LoginHarness.text(scene))
            assertFalse(MR.strings.track_finished_reading_date.localized() in LoginHarness.text(scene))
            LoginHarness.click(scene, MR.strings.desktop_tracking_update.localized())
            LoginHarness.await(scene) { fixture.edits.isNotEmpty() }
        }
        assertEquals(null, fixture.edits.single().private)
        assertEquals(null, fixture.edits.single().startDate)
        assertEquals(null, fixture.edits.single().finishDate)
    }
}

private typealias LoginInvocation = Triple<TrackerAuthentication, String?, String>

@OptIn(ExperimentalComposeUiApi::class)
private object LoginHarness {
    data class Fixture(
        val service: DesktopAuthenticatingTrackerService,
        val invocations: MutableList<LoginInvocation>,
    )

    data class ProviderFixture(
        val service: TrackerProviderService,
        val requests: MutableList<TrackerProviderRequest>,
        val edits: MutableList<TrackEdit>,
    )

    fun authenticatingService(
        id: Long,
        name: String,
        authentication: TrackerAuthentication,
        loggedIn: Boolean = false,
        onLogout: () -> Unit = {},
    ): Fixture {
        val profile = MutableStateFlow(TrackerProfile(id, name, authentication, loggedIn))
        val invocations = mutableListOf<LoginInvocation>()
        val service = mockk<DesktopAuthenticatingTrackerService>(relaxed = true) {
            every { this@mockk.profile } returns profile
            coEvery { login(any(), any()) } coAnswers {
                invocations += LoginInvocation(TrackerAuthentication.USERNAME_PASSWORD, firstArg(), secondArg())
            }
            coEvery { loginWithApiKey(any()) } coAnswers {
                invocations += LoginInvocation(TrackerAuthentication.API_KEY, null, firstArg())
            }
            coEvery { logout() } coAnswers { onLogout() }
        }
        return Fixture(service, invocations)
    }

    fun providerService(
        failure: TrackerProviderError? = null,
        id: Long = 1,
        name: String = "MyAnimeList",
        loggedIn: Boolean = true,
        unavailableReason: String? = null,
    ): ProviderFixture {
        val profile = MutableStateFlow(
            TrackerProfile(
                id,
                name,
                TrackerAuthentication.OAUTH,
                loggedIn,
                unavailableReason = unavailableReason,
            ),
        )
        val requests = mutableListOf<TrackerProviderRequest>()
        val edits = mutableListOf<TrackEdit>()
        val service = mockk<TrackerProviderService>(relaxed = true) {
            every { this@mockk.profile } returns profile
            every { configuration } returns TrackerProviderCatalog.configuration(id)
            every { session } answers { TrackerProviderSession(id, profile.value.loggedIn) }
            every { statuses } returns listOf(1L to "Reading", 2L to "Completed")
            every { scores } returns listOf(0.0, 70.0, 100.0)
            coEvery { update(any(), any()) } coAnswers {
                val track = firstArg<Track>()
                val edit = secondArg<TrackEdit>().also(edits::add)
                track.copy(
                    status = edit.status ?: track.status,
                    score = edit.score ?: track.score,
                    lastChapterRead = edit.lastChapterRead ?: track.lastChapterRead,
                    startDate = edit.startDate ?: track.startDate,
                    finishDate = edit.finishDate ?: track.finishDate,
                    private = edit.private ?: track.private,
                )
            }
            coEvery { execute(any()) } coAnswers {
                val request = firstArg<TrackerProviderRequest>()
                requests += request
                failure?.let { TrackerProviderResult.Failure(it) }
                    ?: TrackerProviderResult.Success(request.track)
            }
        }
        return ProviderFixture(service, requests, edits)
    }

    fun repository(tracks: List<Track> = emptyList(), onDelete: (Long, Long) -> Unit = { _, _ -> }) =
        mockk<TrackRepository>(relaxed = true) {
            every { getTracksAsFlow() } returns flowOf(tracks)
            coEvery { getTracksByMangaId(any()) } returns tracks
            coEvery { delete(any(), any()) } coAnswers { onDelete(firstArg(), secondArg()) }
        }

    suspend fun scene(
        services: List<TrackerService>,
        repository: TrackRepository = repository(),
        screen: TrackingSettingsScreen = TrackingSettingsScreen(),
    ): ImageComposeScene {
        val registry = object : TrackerServiceRegistry { override val services = services }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
            every { trackRepository } returns repository
            every { trackerServiceRegistry } returns registry
        }
        return ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
            await(scene) { services.last().profile.value.name in text(scene) }
        }
    }

    suspend fun openLogin(scene: ImageComposeScene, serviceName: String) {
        assertTrue(requireNotNull(serviceAction(scene, serviceName).config[SemanticsActions.OnClick].action).invoke())
        val title = MR.strings.login_title.localized(java.util.Locale.getDefault(), serviceName)
        await(scene) { title in text(scene) }
    }

    suspend fun dialogScene(service: TrackerService, onDismiss: () -> Unit): ImageComposeScene =
        ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent { LoginDialog(service, onDismiss) { error("Cancel must not run authentication") } }
            await(scene) { MR.strings.action_cancel.localized() in text(scene) }
        }

    suspend fun confirmationScene(
        confirmLabel: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ): ImageComposeScene =
        ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent { TrackingConfirmationDialog("Title", "Message", confirmLabel, onConfirm, onDismiss) }
            await(scene) { confirmLabel in text(scene) }
        }

    suspend fun remoteDeleteConfirmationScene(onToggle: () -> Unit): ImageComposeScene =
        ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                var checked by remember { mutableStateOf(false) }
                TrackingConfirmationDialog(
                    title = "Title",
                    message = "Message",
                    confirmLabel = "Confirm",
                    onConfirm = {},
                    onDismiss = {},
                    remoteDeleteLabel = "Delete remote entry",
                    removeRemoteTrack = checked,
                    onRemoveRemoteChange = {
                        checked = it
                        onToggle()
                    },
                )
            }
            await(scene) { "Delete remote entry" in text(scene) }
        }

    suspend fun setCredentials(
        scene: ImageComposeScene,
        authentication: TrackerAuthentication,
        username: String?,
        secret: String?,
    ) {
        username?.let { setField(scene, MR.strings.username.localized(), it) }
        secret?.let { setField(scene, secretLabel(authentication), it) }
    }

    private fun secretLabel(authentication: TrackerAuthentication) =
        if (authentication == TrackerAuthentication.API_KEY) MR.strings.desktop_tracking_api_key.localized()
        else MR.strings.password.localized()

    suspend fun setField(scene: ImageComposeScene, label: String, value: String) {
        val field = nodes(scene, true)
            .filter { it.config.contains(SemanticsActions.SetText) }
            .single { label in subtreeText(it) }
        assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        render(scene)
    }

    fun hasEditableField(scene: ImageComposeScene, label: String) = nodes(scene, true).any {
        it.config.contains(SemanticsActions.SetText) && label in subtreeText(it)
    }

    suspend fun activateExactlyOnce(
        scene: ImageComposeScene,
        label: String,
        key: Key,
        last: Boolean = false,
        dialogAnchor: String? = null,
        calls: () -> Int,
    ) {
        val node = dialogAnchor?.let { anchor ->
            val root = nodes(scene, true).filter { anchor in subtreeText(it) && label in subtreeText(it) }
                .minBy { flatten(it).size }
            flatten(root).filter { it.config.contains(SemanticsActions.OnClick) && label in subtreeText(it) }.single()
        } ?: action(scene, label, last)
        assertTrue(requireNotNull(node.config[SemanticsActions.RequestFocus].action).invoke())
        send(scene, key, KeyEventType.KeyUp)
        assertEquals(0, calls(), "$key initial KeyUp")
        send(scene, key, KeyEventType.KeyDown)
        assertEquals(1, calls(), "$key KeyDown")
        send(scene, key, KeyEventType.KeyUp)
        assertEquals(1, calls(), "$key final KeyUp")
    }

    suspend fun activateExactlyOnce(
        scene: ImageComposeScene,
        node: SemanticsNode,
        key: Key,
        calls: () -> Int,
    ) {
        assertTrue(requireNotNull(node.config[SemanticsActions.RequestFocus].action).invoke())
        send(scene, key, KeyEventType.KeyUp)
        assertEquals(0, calls(), "$key initial KeyUp")
        send(scene, key, KeyEventType.KeyDown)
        assertEquals(1, calls(), "$key KeyDown")
        send(scene, key, KeyEventType.KeyUp)
        assertEquals(1, calls(), "$key final KeyUp")
    }

    suspend fun assertDisabledForKeys(scene: ImageComposeScene, label: String, keys: List<Key>) {
        val node = nodes(scene, true).single {
            it.config.contains(SemanticsProperties.Disabled) &&
                label in subtreeText(it)
        }
        keys.forEach { key ->
            send(scene, key, KeyEventType.KeyUp)
            send(scene, key, KeyEventType.KeyDown)
            send(scene, key, KeyEventType.KeyUp)
        }
    }

    private fun serviceAction(scene: ImageComposeScene, name: String) = nodes(scene, true)
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .single { name in subtreeText(it) }

    suspend fun click(scene: ImageComposeScene, label: String, last: Boolean = false) {
        assertTrue(requireNotNull(action(scene, label, last).config[SemanticsActions.OnClick].action).invoke())
        render(scene)
    }

    private fun action(scene: ImageComposeScene, label: String, last: Boolean): SemanticsNode {
        val matches = nodes(scene, true).filter { it.config.contains(SemanticsActions.OnClick) && label in subtreeText(it) }
        return if (last) matches.last() else matches.single()
    }

    suspend fun await(scene: ImageComposeScene, condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) render(scene)
    }

    private suspend fun send(scene: ImageComposeScene, key: Key, type: KeyEventType) {
        scene.sendKeyEvent(composeKeyEvent(key, type))
        render(scene)
    }

    private suspend fun render(scene: ImageComposeScene) {
        scene.render()
        yield()
    }

    fun text(scene: ImageComposeScene) = nodes(scene).flatMap(::subtreeText).joinToString()
    fun operableCheckboxes(scene: ImageComposeScene, label: String) = nodes(scene, true).filter {
        it.config.contains(SemanticsActions.OnClick) &&
            it.config.contains(SemanticsProperties.Role) &&
            it.config[SemanticsProperties.Role] == Role.Checkbox &&
            label in subtreeText(it)
    }
    private fun subtreeText(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return androidx.compose.ui.input.key.KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null))
    }
}

private suspend fun ImageComposeScene.useScene(block: suspend (ImageComposeScene) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}
