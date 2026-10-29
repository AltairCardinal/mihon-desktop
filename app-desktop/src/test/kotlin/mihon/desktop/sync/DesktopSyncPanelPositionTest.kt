package mihon.desktop.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelState
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSyncPanelPositionTest {
    @ParameterizedTest
    @ValueSource(ints = [600, 900, 1200])
    fun `toolbar opens sync panel against the window bottom`(height: Int) = runBlocking {
        val state = MutableStateFlow(SyncPanelState(loaded = true))
        val panel = mockk<SyncPanel>(relaxed = true) {
            every { this@mockk.state } returns state
            every { dispatch(SyncPanelAction.Open) } answers { state.value = state.value.copy(visible = true) }
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) { every { syncPanel } returns panel }
        val scene = ImageComposeScene(1200, height, coroutineContext = coroutineContext) {}
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        var frameTime = 0L
        suspend fun settle() {
            repeat(80) {
                frameTime += 16_000_000L
                scene.render(frameTime)
                yield()
            }
        }
        try {
            scene.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        DesktopLibrarySyncAction()
                    }
                }
            }
            settle()
            val button = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "sync-open" }
            assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
            settle()
            val sheet = nodes().single { it.config.contains(SemanticsProperties.PaneTitle) }
            assertEquals(height.toFloat(), sheet.boundsInRoot.bottom, 1f, "Sync panel must reach the window bottom")
            assertTrue(sheet.boundsInRoot.height <= 720f, "Sync panel must retain its height limit")
            assertTrue(sheet.boundsInRoot.top >= 0f, "Sync panel must remain inside a short window")
        } finally {
            scene.close()
        }
    }
}
