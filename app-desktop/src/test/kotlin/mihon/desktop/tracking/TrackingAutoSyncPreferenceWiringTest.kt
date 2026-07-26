package mihon.desktop.tracking

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.settings.DesktopSettingsAnchorHighlighted
import mihon.desktop.ui.settings.DesktopSettingsAnchorOwner
import mihon.desktop.ui.settings.DesktopSettingsCatalog
import mihon.desktop.ui.settings.rememberDesktopSettingsAnchorLazyListHost
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.i18n.MR

@OptIn(ExperimentalComposeUiApi::class)
class TrackingAutoSyncPreferenceWiringTest {

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `catalog login result scrolls to first exact service once and rejects wrong requests`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val sourceManaged = (0L until 18L).map { id ->
            trackerService(id, "Source $id", loggedIn = true, authenticating = false)
        }
        val loginServices = listOf(
            trackerService(18L, "Login service first", loggedIn = false, authenticating = true),
            trackerService(19L, "Login service second", loggedIn = false, authenticating = true),
        )
        val registry = object : TrackerServiceRegistry {
            override val services = sourceManaged + loginServices
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns preferences
            every { getChaptersByMangaId } returns mockk()
            every { getManga } returns mockk()
            every { getTracks } returns mockk(relaxed = true)
            every { insertTrack } returns mockk(relaxed = true)
            every { deleteTrack } returns mockk(relaxed = true)
            every { trackerServiceRegistry } returns registry
        }
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(900, 360, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(EmptyScreen()) { nav -> navigator = nav; CurrentScreen() }
                }
            }
            render(scene)
            val title = MR.strings.login.localized()
            val result = DesktopSettingsCatalog.search(title).single {
                it.route is TrackingSettingsScreen && it.anchorTitle == title
            }
            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            navigator.replace(result.route)
            render(scene)
            val highlighted = nodes(scene, true).single {
                it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
            }
            assertTrue(texts(highlighted).containsAll(listOf("Login service first", title)))
            assertFalse(texts(highlighted).contains("Login service second"))
            assertTrue(highlighted.boundsInRoot.height > 0f)
            assertTrue(scroll(scene).value() > 0f)

            navigator.replace(EmptyScreen())
            render(scene)
            navigator.replace(TrackingSettingsScreen())
            render(scene)
            assertNoAnchor(scene)

            DesktopSettingsAnchorOwner.publish(result.route, title.dropLast(1))
            navigator.replace(EmptyScreen())
            render(scene)
            navigator.replace(TrackingSettingsScreen())
            render(scene)
            assertNoAnchor(scene)

            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            navigator.replace(WrongRouteScreen())
            render(scene)
            navigator.replace(EmptyScreen())
            render(scene)
            navigator.replace(TrackingSettingsScreen())
            render(scene)
            assertNoAnchor(scene)
        } finally {
            DesktopSettingsAnchorOwner.clear()
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `tracking settings toggles the real automatic tracker update preference`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val autoUpdateTrack = preferences.autoUpdateTrack
        val registry = object : TrackerServiceRegistry {
            override val services: List<TrackerService> = emptyList()
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { appPreferences } returns preferences
            every { getChaptersByMangaId } returns mockk()
            every { getManga } returns mockk()
            every { getTracks } returns mockk()
            every { insertTrack } returns mockk()
            every { deleteTrack } returns mockk()
            every { trackerServiceRegistry } returns registry
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(TrackingSettingsScreen()) { CurrentScreen() }
                }
            }
            scene.render()

            assertTrue(autoUpdateTrack.get())
            assertTrue(
                nodes(scene).any {
                    it.config.toString().contains(MR.strings.pref_auto_update_manga_sync.localized())
                },
            )
            val enabledToggle = toggleNode(scene)
            assertEquals(ToggleableState.On, enabledToggle.config[SemanticsProperties.ToggleableState])
            assertTrue(requireNotNull(enabledToggle.config[SemanticsActions.OnClick].action).invoke())
            assertFalse(autoUpdateTrack.get())
            withTimeout(15_000) {
                while (toggleNode(scene).config[SemanticsProperties.ToggleableState] != ToggleableState.Off) {
                    scene.render()
                    yield()
                }
            }

            assertEquals(ToggleableState.Off, toggleNode(scene).config[SemanticsProperties.ToggleableState])
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun toggleNode(scene: ImageComposeScene): SemanticsNode = nodes(scene).single {
        it.config.contains(SemanticsProperties.ToggleableState) && it.config.contains(SemanticsActions.OnClick)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun render(scene: ImageComposeScene) = repeat(24) {
        scene.render()
        delay(16)
    }

    private fun assertNoAnchor(scene: ImageComposeScene) {
        assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
        assertEquals(0f, scroll(scene).value())
    }

    private fun scroll(scene: ImageComposeScene) = nodes(scene, true)
        .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun texts(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) {
            it.config[SemanticsProperties.Text].map { text -> text.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false): List<SemanticsNode> =
        scene.semanticsOwners.flatMap {
            flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
        }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun trackerService(id: Long, name: String, loggedIn: Boolean, authenticating: Boolean): TrackerService {
        val profile = MutableStateFlow(TrackerProfile(id, name, TrackerAuthentication.API_KEY, loggedIn))
        return if (authenticating) {
            mockk<DesktopAuthenticatingTrackerService>(relaxed = true) {
                every { this@mockk.profile } returns profile
            }
        } else {
            mockk<TrackerService>(relaxed = true) {
                every { this@mockk.profile } returns profile
            }
        }
    }

    private class EmptyScreen : Screen { @Composable override fun Content() = Unit }

    private class WrongRouteScreen : Screen {
        @Composable
        override fun Content() {
            val host = rememberDesktopSettingsAnchorLazyListHost(this, emptyList())
            LazyColumn(state = host.listState) {}
        }
    }
}
