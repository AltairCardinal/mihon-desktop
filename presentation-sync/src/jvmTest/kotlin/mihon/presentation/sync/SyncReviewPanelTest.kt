package mihon.presentation.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncRunState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class SyncReviewPanelTest {
    @Test
    fun `scenario IDs are stable and each starts with a renderable state`() {
        assertEquals(
            listOf("disconnected", "setup", "connected", "settings", "progress"),
            SyncReviewScenarios.all.map { it.id },
        )
        assertEquals(SyncPanelPage.SETUP, SyncReviewScenarios.find("setup")?.initialState?.page)
        assertEquals(SyncPanelPage.SETTINGS, SyncReviewScenarios.find("settings")?.initialState?.page)
        assertNotNull(SyncReviewScenarios.find("progress")?.initialState?.progress)
        assertTrue(SyncReviewScenarios.all.all { it.label.isNotBlank() && it.description.isNotBlank() })

        SyncReviewScenarios.all.forEach { scenario ->
            val panel = SyncReviewPanel(scenario)
            assertEquals(scenario.initialState, panel.state.value)
            assertEquals(null, panel.state.value.deviceCode)
        }
    }

    @Test
    fun `navigation and settings affect only the review panel state`() {
        val panel = SyncReviewPanel(requireNotNull(SyncReviewScenarios.find("disconnected")))
        panel.dispatch(SyncPanelAction.Open)
        assertTrue(panel.state.value.visible)
        panel.dispatch(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
        assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page)
        panel.dispatch(SyncPanelAction.SetStartup(false))
        panel.dispatch(SyncPanelAction.SetPeriod(30))
        panel.dispatch(SyncPanelAction.SetDeviceName("Review device"))
        assertFalse(panel.state.value.startup)
        assertEquals(30, panel.state.value.periodMinutes)
        assertEquals("Review device", panel.state.value.deviceName)
        panel.dispatch(SyncPanelAction.Back)
        assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
        panel.dispatch(SyncPanelAction.Back)
        assertFalse(panel.state.value.visible)
        assertEquals(SyncPanelPage.MAIN, SyncReviewScenarios.find("disconnected")?.initialState?.page)
    }

    @Test
    fun `review actions cannot start sync or authorize a real account`() {
        val disconnected = SyncReviewPanel(requireNotNull(SyncReviewScenarios.find("disconnected")))
        disconnected.dispatch(SyncPanelAction.Synchronize)
        assertEquals(SyncPanelPage.SETUP, disconnected.state.value.page)
        disconnected.dispatch(SyncPanelAction.Authorize)
        assertEquals(null, disconnected.state.value.deviceCode)

        val progress = SyncReviewPanel(requireNotNull(SyncReviewScenarios.find("progress")))
        progress.dispatch(SyncPanelAction.PauseSync)
        assertEquals(SyncRunState.PAUSED_USER, progress.state.value.run?.state)
        assertEquals(SyncProgressHold.PAUSED, progress.state.value.progress?.hold)
        progress.dispatch(SyncPanelAction.ResumeSync)
        assertEquals(SyncRunState.RUNNING, progress.state.value.run?.state)
        assertEquals(SyncProgressHold.ACTIVE, progress.state.value.progress?.hold)
        progress.dispatch(SyncPanelAction.CancelSync)
        assertEquals(SyncRunState.RUNNING, progress.state.value.run?.state)
    }

    @Test
    fun `review scenarios render the production panel content`() = runBlocking {
        SyncReviewScenarios.all.forEach { scenario ->
            val panel = SyncReviewPanel(scenario)
            val scene = ImageComposeScene(560, 720, coroutineContext = coroutineContext) {}
            try {
                scene.setContent {
                    MaterialTheme {
                        val state by panel.state.collectAsState()
                        if (state.visible) {
                            SyncPanelContent(panel, onOpenBrowser = {}, onCopyCode = {})
                        }
                    }
                }
                repeat(3) {
                    scene.render()
                    yield()
                }
                val tags = scene.semanticsOwners.flatMap { owner ->
                    fun walk(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                        (
                            if (node.config.contains(SemanticsProperties.TestTag)) {
                                listOf(node.config[SemanticsProperties.TestTag])
                            } else {
                                emptyList()
                            }
                            ) + node.children.flatMap(::walk)
                    walk(owner.rootSemanticsNode)
                }
                assertTrue(tags.isNotEmpty(), "${scenario.id} rendered no production controls")
            } finally {
                scene.close()
            }
        }
    }
}
