package mihon.desktop.ui.tracking

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
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
            LoginCase(TrackerAuthentication.USERNAME_PASSWORD, Key.Enter, "enter-user", "enter-password"),
            LoginCase(TrackerAuthentication.USERNAME_PASSWORD, Key.NumPadEnter, "numpad-user", "numpad-password"),
            LoginCase(TrackerAuthentication.API_KEY, Key.Spacebar, null, "api-key"),
        ).forEachIndexed { index, case ->
            val fixture = LoginHarness.authenticatingService(index.toLong() + 1, "Confirm service $index", case.authentication)
            val scene = LoginHarness.scene(listOf(fixture.service), fixture.profile.value.name)
            try {
                LoginHarness.openLogin(scene, fixture.profile.value.name)
                case.username?.let { LoginHarness.setField(scene, MR.strings.username.localized(), it) }
                val secretLabel =
                    if (case.authentication == TrackerAuthentication.API_KEY) MR.strings.desktop_tracking_api_key.localized()
                    else MR.strings.password.localized()
                LoginHarness.setField(scene, secretLabel, case.secret)
                val expected = LoginInvocation(case.authentication, case.username, case.secret)
                LoginHarness.activate(
                    scene,
                    MR.strings.login.localized(),
                    case.key,
                    last = true,
                    afterInitialKeyUp = { assertTrue(fixture.invocations.isEmpty()) },
                    afterKeyDown = {
                        assertEquals(listOf(expected), fixture.invocations)
                    },
                    afterFinalKeyUp = { assertEquals(listOf(expected), fixture.invocations) },
                )
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `login cancel dismisses once on key down without authentication`() = runBlocking {
        val passwordMethod = TrackerAuthentication.USERNAME_PASSWORD
        keys.forEachIndexed { index, key ->
            val fixture = LoginHarness.authenticatingService(index.toLong() + 10, "Cancel service $index", passwordMethod)
            val scene = LoginHarness.scene(listOf(fixture.service), fixture.profile.value.name)
            val title = MR.strings.login_title.localized(java.util.Locale.getDefault(), fixture.profile.value.name)
            try {
                LoginHarness.openLogin(scene, fixture.profile.value.name)
                LoginHarness.activate(
                    scene,
                    MR.strings.action_cancel.localized(),
                    key,
                    afterInitialKeyUp = {
                        assertTrue(title in LoginHarness.text(scene))
                    },
                    afterKeyDown = {
                        LoginHarness.await(scene) { title !in LoginHarness.text(scene) }
                    },
                    afterFinalKeyUp = { assertTrue(fixture.invocations.isEmpty()) },
                )
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `invalid service specific credentials keep login disabled without authentication`() = runBlocking {
        listOf(TrackerAuthentication.USERNAME_PASSWORD, TrackerAuthentication.API_KEY).forEachIndexed { index, method ->
            val fixture = LoginHarness.authenticatingService(index.toLong() + 20, "Invalid service $index", method)
            val scene = LoginHarness.scene(listOf(fixture.service), fixture.profile.value.name)
            try {
                LoginHarness.openLogin(scene, fixture.profile.value.name)
                LoginHarness.assertDisabledForKeys(scene, MR.strings.login.localized(), keys)
                assertTrue(fixture.invocations.isEmpty())
            } finally {
                scene.close()
            }
        }
    }

    private data class LoginCase(
        val authentication: TrackerAuthentication,
        val key: Key,
        val username: String?,
        val secret: String,
    )
}

private typealias LoginInvocation = Triple<TrackerAuthentication, String?, String>

@OptIn(ExperimentalComposeUiApi::class)
private object LoginHarness {
    data class Fixture(
        val service: DesktopAuthenticatingTrackerService,
        val profile: MutableStateFlow<TrackerProfile>,
        val invocations: MutableList<LoginInvocation>,
    )

    fun authenticatingService(id: Long, name: String, authentication: TrackerAuthentication): Fixture {
        val profile = MutableStateFlow(TrackerProfile(id, name, authentication, loggedIn = false))
        val invocations = mutableListOf<LoginInvocation>()
        val service = mockk<DesktopAuthenticatingTrackerService>(relaxed = true) {
            every { this@mockk.profile } returns profile
            every { statuses } returns listOf(1L to "Reading")
            every { scores } returns listOf(10.0)
            coEvery { login(any(), any()) } coAnswers {
                invocations += LoginInvocation(TrackerAuthentication.USERNAME_PASSWORD, firstArg(), secondArg())
            }
            coEvery { loginWithApiKey(any()) } coAnswers {
                invocations += LoginInvocation(TrackerAuthentication.API_KEY, null, firstArg())
            }
        }
        return Fixture(service, profile, invocations)
    }

    suspend fun scene(services: List<TrackerService>, readyText: String): ImageComposeScene {
        val repository = mockk<TrackRepository>(relaxed = true) {
            coEvery { getTracksByMangaId(any()) } returns emptyList()
            every { getTracksAsFlow() } returns flowOf(emptyList())
            every { getTracksByMangaIdAsFlow(any()) } returns flowOf(emptyList())
        }
        val registry = object : TrackerServiceRegistry { override val services = services }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
            every { trackRepository } returns repository
            every { trackerServiceRegistry } returns registry
        }
        return ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(TrackingSettingsScreen()) { CurrentScreen() }
                }
            }
            await(scene) { readyText in text(scene) }
        }
    }

    suspend fun openLogin(scene: ImageComposeScene, serviceName: String) {
        assertTrue(requireNotNull(serviceAction(scene, serviceName).config[SemanticsActions.OnClick].action).invoke())
        val title = MR.strings.login_title.localized(java.util.Locale.getDefault(), serviceName)
        await(scene) { title in text(scene) }
    }

    suspend fun setField(scene: ImageComposeScene, label: String, value: String) {
        val field = nodes(scene, true)
            .filter { it.config.contains(SemanticsActions.SetText) }
            .single { label in subtreeText(it) }
        assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        render(scene)
    }

    suspend fun activate(
        scene: ImageComposeScene,
        label: String,
        key: Key,
        last: Boolean = false,
        afterInitialKeyUp: suspend () -> Unit,
        afterKeyDown: suspend () -> Unit,
        afterFinalKeyUp: suspend () -> Unit,
    ) {
        val node = action(scene, label, last)
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        assertTrue(requireNotNull(node.config[SemanticsActions.RequestFocus].action).invoke())
        send(scene, key, KeyEventType.KeyUp)
        afterInitialKeyUp()
        send(scene, key, KeyEventType.KeyDown)
        afterKeyDown()
        send(scene, key, KeyEventType.KeyUp)
        afterFinalKeyUp()
    }

    suspend fun assertDisabledForKeys(scene: ImageComposeScene, label: String, keys: List<Key>) {
        val node = nodes(scene, true).single {
            it.config.contains(SemanticsProperties.Disabled) &&
                it.config[SemanticsProperties.Role] == Role.Button &&
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

    private fun action(scene: ImageComposeScene, label: String, last: Boolean): SemanticsNode {
        val matches = nodes(scene, true).filter { it.config.contains(SemanticsActions.OnClick) && label in subtreeText(it) }
        return if (last) matches.last() else matches.single()
    }

    suspend fun await(scene: ImageComposeScene, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) render(scene)
        }
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
