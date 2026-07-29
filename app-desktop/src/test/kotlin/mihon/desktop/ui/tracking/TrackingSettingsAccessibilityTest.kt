package mihon.desktop.ui.tracking

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
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
class TrackingSettingsAccessibilityTest {
    @Test
    fun `service rows expose one honest action and protected credential identities`() = runBlocking {
        val password = authenticatingService(1, "Password service")
        val apiKey = authenticatingService(2, "API service", TrackerAuthentication.API_KEY)
        val sourceManaged = sourceService(3, "Source managed")
        val unavailable = authenticatingService(4, "Unavailable service", unavailableReason = "Provider setup required")
        val scene = scene(listOf(password, apiKey, sourceManaged, unavailable))
        try {
            listOf("Password service", "API service").forEach { name ->
                assertServiceAction(scene, name, MR.strings.login.localized())
            }
            listOf("Source managed", "Unavailable service").forEach { name ->
                val disabled = nodes(scene, true).single {
                    it.config.contains(SemanticsProperties.Disabled) && name in subtreeText(it)
                }
                assertFalse(
                    flatten(disabled).any {
                        it.config.contains(SemanticsActions.OnClick) && name in subtreeText(it)
                    },
                    name,
                )
            }

            clickService(scene, "Password service")
            awaitText(scene, MR.strings.username.localized())
            assertPasswordField(MR.strings.username.localized(), scene, expected = false)
            assertPasswordField(MR.strings.password.localized(), scene, expected = true)
            click(scene, MR.strings.action_cancel.localized())
            await(scene, "password dialog dismissal") { MR.strings.username.localized() !in text(scene) }

            clickService(scene, "API service")
            awaitText(scene, MR.strings.desktop_tracking_api_key.localized())
            assertPasswordField(MR.strings.desktop_tracking_api_key.localized(), scene, expected = true)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `login logout and manage labels stay inside their unique service actions`() = runBlocking {
        val cases = listOf(
            ServiceActionCase("Logged out service", loggedIn = false, mangaId = null, actionLabel = MR.strings.login.localized()),
            ServiceActionCase("Logged in service", loggedIn = true, mangaId = null, actionLabel = MR.strings.logout.localized()),
            ServiceActionCase(
                "Manga service",
                loggedIn = true,
                mangaId = 42L,
                actionLabel = MR.strings.desktop_tracking_manage.localized(),
            ),
        )
        cases.forEachIndexed { index, case ->
            val service = authenticatingService(index.toLong() + 10, case.name, loggedIn = case.loggedIn)
            val scene = scene(listOf(service), TrackingSettingsScreen(mangaId = case.mangaId))
            try {
                assertServiceAction(scene, case.name, case.actionLabel)
            } finally {
                scene.close()
            }
        }
    }

    private suspend fun scene(
        services: List<TrackerService>,
        screen: TrackingSettingsScreen = TrackingSettingsScreen(),
    ): ImageComposeScene {
        val repository = mockk<TrackRepository>(relaxed = true) {
            coEvery { getTracksByMangaId(any()) } returns emptyList()
            every { getTracksAsFlow() } returns flowOf(emptyList())
            every { getTracksByMangaIdAsFlow(any()) } returns flowOf(emptyList())
        }
        val registry = object : TrackerServiceRegistry { override val services = services }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
            every { getTracks } returns tachiyomi.domain.track.interactor.GetTracks(repository)
            every { insertTrack } returns tachiyomi.domain.track.interactor.InsertTrack(repository)
            every { deleteTrack } returns tachiyomi.domain.track.interactor.DeleteTrack(repository)
            every { trackerServiceRegistry } returns registry
        }
        return ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
            await(scene, "all tracking services") { services.last().profile.value.name in text(scene) }
        }
    }

    private fun authenticatingService(
        id: Long,
        name: String,
        authentication: TrackerAuthentication = TrackerAuthentication.USERNAME_PASSWORD,
        loggedIn: Boolean = false,
        unavailableReason: String? = null,
    ): DesktopAuthenticatingTrackerService {
        val profile = MutableStateFlow(TrackerProfile(id, name, authentication, loggedIn, unavailableReason = unavailableReason))
        return mockk(relaxed = true) {
            every { this@mockk.profile } returns profile
            every { statuses } returns listOf(1L to "Reading")
            every { scores } returns listOf(10.0)
        }
    }

    private fun sourceService(id: Long, name: String): TrackerService {
        val profile = MutableStateFlow(TrackerProfile(id, name, TrackerAuthentication.API_KEY, true))
        return mockk(relaxed = true) { every { this@mockk.profile } returns profile }
    }

    private fun assertServiceAction(scene: ImageComposeScene, name: String, actionLabel: String) {
        val action = serviceAction(scene, name)
        assertEquals(Role.Button, action.config[SemanticsProperties.Role], "$name action role")
        assertEquals(1, flatten(action).count { it.config.contains(SemanticsActions.OnClick) }, name)
        assertEquals(true, listOf(name, actionLabel).all(subtreeText(action)::contains), "$name action content")
    }

    private fun assertPasswordField(label: String, scene: ImageComposeScene, expected: Boolean) {
        val fields = nodes(scene, true).filter { it.config.contains(SemanticsActions.SetText) }
        val field = fields.singleOrNull { label in subtreeText(it) }
            ?: error("No editable field matched '$label': ${fields.map { it.config }}")
        assertEquals(expected, field.config.contains(SemanticsProperties.Password), label)
    }

    private fun clickService(scene: ImageComposeScene, name: String) =
        assertEquals(true, requireNotNull(serviceAction(scene, name).config[SemanticsActions.OnClick].action).invoke())

    private fun click(scene: ImageComposeScene, label: String) {
        val action = nodes(scene, true).single {
            it.config.contains(SemanticsActions.OnClick) && label in subtreeText(it)
        }
        assertEquals(true, requireNotNull(action.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun serviceAction(scene: ImageComposeScene, name: String) = nodes(scene, true)
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .single { name in subtreeText(it) }

    private suspend fun awaitText(scene: ImageComposeScene, expected: String) =
        await(scene, "text '$expected'") { expected in text(scene) }

    private suspend fun await(scene: ImageComposeScene, description: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) {
                while (!condition()) {
                    scene.render()
                    yield()
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $description; text=${text(scene)}", timeout)
        }
    }

    private fun text(scene: ImageComposeScene) = nodes(scene).flatMap(::subtreeText).joinToString()
    private fun subtreeText(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private data class ServiceActionCase(val name: String, val loggedIn: Boolean, val mangaId: Long?, val actionLabel: String)
}
