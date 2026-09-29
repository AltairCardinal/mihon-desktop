package mihon.desktop.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class SyncUiReviewTest {
    @Test
    fun `review root opens the production desktop sheet and settings`(): Unit = runBlocking {
        val scene = ImageComposeScene(1_024, 768, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { MaterialTheme { SyncUiReviewRoot() } }
            suspend fun find(tag: String): SemanticsNode {
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                return withTimeout(4_000) {
                    while (true) {
                        scene.render().close()
                        val node = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.firstOrNull {
                            it.config.contains(SemanticsProperties.TestTag) &&
                                it.config[SemanticsProperties.TestTag] == tag
                        }
                        if (node != null) return@withTimeout node
                        yield()
                    }
                    error("unreachable")
                }
            }
            suspend fun click(tag: String) {
                val node = find(tag)
                val action = if (node.config.contains(SemanticsActions.OnClick)) {
                    node.config[SemanticsActions.OnClick].action
                } else {
                    null
                }
                assertNotNull(action)
                action!!.invoke()
            }
            click("sync-review-scenario-connected")
            find("sync-now")
            click("sync-settings")
            find("sync-settings-list")
            click("sync-back")
            find("sync-now")
            click("sync-close")
            find("sync-review-scenario-connected")
            click("sync-review-scenario-connected")
            find("sync-now")
        } finally {
            scene.close()
        }
    }
}
