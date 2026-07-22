package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSettingsAnchorHostTest {
    @Test
    fun `owner claims exact route once and clears mismatched route`() {
        val first = RouteA()
        DesktopSettingsAnchorOwner.publish(first, "target")
        assertEquals("target", DesktopSettingsAnchorOwner.claim(first))
        assertNull(DesktopSettingsAnchorOwner.claim(first))

        DesktopSettingsAnchorOwner.publish(first, "stale")
        assertNull(DesktopSettingsAnchorOwner.claim(RouteB()))
        assertNull(DesktopSettingsAnchorOwner.claim(first))
    }

    @Test
    fun `host scrolls highlights first exact duplicate and does not depend on focus`() = runBlocking {
        val scene = scene(RouteA(), "duplicate")
        try {
            render(scene)
            val anchors = nodes(scene, true).filter { it.config.contains(SemanticsProperties.TestTag) }
            val highlighted = anchors.single {
                it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
            }
            assertEquals("duplicate-first", highlighted.config[SemanticsProperties.TestTag])
            val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(scroll.value() > 0f, "scroll=${scroll.value()}/${scroll.maxValue()} bounds=${anchors.map { it.boundsInRoot }}")
            assertFalse(highlighted.config.contains(SemanticsProperties.Focused) && highlighted.config[SemanticsProperties.Focused])
        } finally {
            scene.close()
            DesktopSettingsAnchorOwner.clear()
        }
    }

    @Test
    fun `unknown exact title neither scrolls nor highlights`() = runBlocking {
        val scene = scene(RouteA(), "missing")
        try {
            render(scene)
            assertFalse(
                nodes(scene, true).any {
                    it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
                },
            )
            val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(0f, scroll.value())
        } finally {
            scene.close()
            DesktopSettingsAnchorOwner.clear()
        }
    }

    private fun scene(route: Screen, title: String): ImageComposeScene {
        DesktopSettingsAnchorOwner.publish(route, title)
        return ImageComposeScene(300, 100) {
            DesktopSettingsAnchorColumn(route, Modifier.fillMaxWidth().height(100.dp)) {
                Text("before", Modifier.fillMaxWidth().height(120.dp))
                Text(
                    "duplicate extra",
                    Modifier.desktopSettingsAnchor("duplicate extra").testTag("duplicate-prefix").fillMaxWidth().height(120.dp),
                )
                Text(
                    "duplicate",
                    Modifier.desktopSettingsAnchor("duplicate").testTag("duplicate-first").fillMaxWidth().height(120.dp),
                )
                Text(
                    "duplicate",
                    Modifier.desktopSettingsAnchor("duplicate").testTag("duplicate-second").fillMaxWidth().height(120.dp),
                )
            }
        }
    }

    private suspend fun render(scene: ImageComposeScene) = repeat(24) {
        scene.render()
        kotlinx.coroutines.delay(16)
    }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()

    private class RouteA : Screen { @Composable override fun Content() = Unit }
    private class RouteB : Screen { @Composable override fun Content() = Unit }
}
