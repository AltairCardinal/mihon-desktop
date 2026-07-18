package mihon.desktop.tracking

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry

class TrackingAutoSyncPreferenceWiringTest {

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
            every { trackRepository } returns mockk()
            every { trackerServiceRegistry } returns registry
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(TrackingSettingsScreen()) { CurrentScreen() }
            }
        }
        scene.render()

        assertTrue(autoUpdateTrack.get())
        assertTrue(nodes(scene).any { it.config.toString().contains("Automatically update tracking") })
        val enabledToggle = toggleNode(scene)
        assertEquals(ToggleableState.On, enabledToggle.config[SemanticsProperties.ToggleableState])
        assertTrue(requireNotNull(enabledToggle.config[SemanticsActions.OnClick].action).invoke())
        scene.render()

        assertFalse(autoUpdateTrack.get())
        assertEquals(ToggleableState.Off, toggleNode(scene).config[SemanticsProperties.ToggleableState])
        scene.close()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun toggleNode(scene: ImageComposeScene): SemanticsNode = nodes(scene).single {
        it.config.contains(SemanticsProperties.ToggleableState) && it.config.contains(SemanticsActions.OnClick)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
