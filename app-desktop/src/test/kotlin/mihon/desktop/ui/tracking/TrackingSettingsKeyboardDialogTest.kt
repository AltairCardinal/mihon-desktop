package mihon.desktop.ui.tracking

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.i18n.MR

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
    fun `full settings logout confirms the selected tracker only and cancel has no side effect`() = runBlocking {
        val target = LoginHarness.authenticatingService(71, "Logout target", TrackerAuthentication.API_KEY, loggedIn = true)
        val other = LoginHarness.authenticatingService(72, "Logout other", TrackerAuthentication.API_KEY, loggedIn = true)
        LoginHarness.scene(listOf(target.service, other.service)).useScene { scene ->
            LoginHarness.click(scene, target.service.profile.value.name)
            LoginHarness.activateExactlyOnce(scene, MR.strings.action_cancel.localized(), Key.Enter) {
                if (MR.strings.desktop_tracking_logout_consequence.localized() in LoginHarness.text(scene)) 0 else 1
            }
            assertTrue(target.logoutIds.isEmpty() && other.logoutIds.isEmpty())

            LoginHarness.click(scene, target.service.profile.value.name)
            LoginHarness.activateExactlyOnce(scene, MR.strings.logout.localized(), Key.NumPadEnter, last = true, calls = target.logoutIds::size)
            LoginHarness.await(scene) { MR.strings.logout_success.localized() in LoginHarness.text(scene) }
            assertEquals(listOf(71L), target.logoutIds)
            assertTrue(other.logoutIds.isEmpty())
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
            LoginHarness.activateExactlyOnce(scene, MR.strings.action_remove.localized(), Key.Spacebar, last = true, calls = deletions::size)
            LoginHarness.await(scene) { MR.strings.desktop_tracking_removed.localized() in LoginHarness.text(scene) }
            assertEquals(listOf(42L to trackerId), deletions)
        }
    }
}

private typealias LoginInvocation = Triple<TrackerAuthentication, String?, String>

@OptIn(ExperimentalComposeUiApi::class)
private object LoginHarness {
    data class Fixture(
        val service: DesktopAuthenticatingTrackerService,
        val invocations: MutableList<LoginInvocation>,
        val logoutIds: MutableList<Long>,
    )

    fun authenticatingService(
        id: Long,
        name: String,
        authentication: TrackerAuthentication,
        loggedIn: Boolean = false,
    ): Fixture {
        val profile = MutableStateFlow(TrackerProfile(id, name, authentication, loggedIn))
        val invocations = mutableListOf<LoginInvocation>()
        val logoutIds = mutableListOf<Long>()
        val service = mockk<DesktopAuthenticatingTrackerService>(relaxed = true) {
            every { this@mockk.profile } returns profile
            coEvery { login(any(), any()) } coAnswers {
                invocations += LoginInvocation(TrackerAuthentication.USERNAME_PASSWORD, firstArg(), secondArg())
            }
            coEvery { loginWithApiKey(any()) } coAnswers {
                invocations += LoginInvocation(TrackerAuthentication.API_KEY, null, firstArg())
            }
            coEvery { logout() } coAnswers {
                logoutIds += id
                profile.value = profile.value.copy(loggedIn = false)
            }
        }
        return Fixture(service, invocations, logoutIds)
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

    suspend fun activateExactlyOnce(
        scene: ImageComposeScene,
        label: String,
        key: Key,
        last: Boolean = false,
        calls: () -> Int,
    ) {
        val node = action(scene, label, last)
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

    suspend fun click(scene: ImageComposeScene, label: String) {
        assertTrue(requireNotNull(action(scene, label, false).config[SemanticsActions.OnClick].action).invoke())
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
