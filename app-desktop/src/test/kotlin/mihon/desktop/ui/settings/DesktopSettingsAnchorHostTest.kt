package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    @Test
    fun `lazy host uses descriptors to scroll and highlight first exact duplicate`() = runBlocking {
        val scene = lazyScene(RouteA(), RouteA(), "duplicate")
        try {
            render(scene)
            val highlighted = nodes(scene, true).single {
                it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
            }
            assertEquals("item-40", highlighted.config[SemanticsProperties.TestTag])
            assertTrue(highlighted.boundsInRoot.height > 0f)
            assertFalse(
                nodes(scene, true).any {
                    it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "item-0"
                },
            )
            val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(scroll.value() > 0f)
        } finally {
            scene.close()
            DesktopSettingsAnchorOwner.clear()
        }
    }

    @Test
    fun `lazy host rejects prefix wrong route and consumes request once`() = runBlocking {
        suspend fun assertNoAnchor(scene: ImageComposeScene) {
            try {
                render(scene)
                assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
                val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals(0f, scroll.value())
            } finally {
                scene.close()
            }
        }

        assertNoAnchor(lazyScene(RouteA(), RouteA(), "duplic"))
        assertNoAnchor(lazyScene(RouteB(), RouteA(), "duplicate"))
        val consumed = lazyScene(RouteA(), RouteA(), "duplicate")
        render(consumed)
        consumed.close()
        assertNoAnchor(lazyScene(RouteA(), null, null))
        DesktopSettingsAnchorOwner.clear()
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

    private fun lazyScene(hostRoute: Screen, publishRoute: Screen?, title: String?): ImageComposeScene {
        if (publishRoute != null && title != null) DesktopSettingsAnchorOwner.publish(publishRoute, title)
        val anchors = listOf(
            DesktopSettingsLazyAnchor("duplicate extra", "prefix", 20),
            DesktopSettingsLazyAnchor("duplicate", "first", 40),
            DesktopSettingsLazyAnchor("duplicate", "second", 60),
        )
        return ImageComposeScene(300, 100) {
            val host = rememberDesktopSettingsAnchorLazyListHost(hostRoute, anchors)
            LazyColumn(state = host.listState, modifier = Modifier.fillMaxWidth().height(100.dp)) {
                items((0 until 80).toList(), key = { "item-$it" }) { index ->
                    val anchor = anchors.firstOrNull { it.index == index }
                    Text(
                        "item-$index",
                        Modifier
                            .desktopSettingsAnchor(anchor?.title ?: "", anchor?.key, host)
                            .testTag("item-$index")
                            .fillMaxWidth()
                            .height(40.dp),
                    )
                }
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
