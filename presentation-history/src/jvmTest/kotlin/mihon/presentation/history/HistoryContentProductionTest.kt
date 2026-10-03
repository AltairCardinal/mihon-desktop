package mihon.presentation.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.service.HistoryState

@OptIn(ExperimentalComposeUiApi::class)
class HistoryContentProductionTest {
    @Test
    fun `shared history keeps official relative day labels`() {
        val locale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.ENGLISH)
        val state =
            HistoryState(
                list = listOf(
                    tachiyomi.domain.history.service.HistoryUiModel.Header(java.time.LocalDate.now().minusDays(2)),
                ),
            )
        val scene =
            ImageComposeScene(700, 600) {
                MaterialTheme { HistoryContent(state, {}, {}, {}, {}, {}, {}, { _, _, _ -> }) }
            }
        try {
            scene.render().close()
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.Text) &&
                        it.config[SemanticsProperties.Text].any { text -> text.text == "2 days ago" }
                },
            )
        } finally {
            scene.close()
            java.util.Locale.setDefault(locale)
        }
    }

    @Test
    fun `real input keeps text selection and clearing separate from closing search`() {
        var state by mutableStateOf(HistoryState(searchQuery = "", list = emptyList()))
        val clipboard = io.mockk.mockk<androidx.compose.ui.platform.Clipboard>(relaxed = true)
        val scene = ImageComposeScene(700, 600) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalClipboard provides clipboard,
            ) {
                MaterialTheme {
                    HistoryContent(state, { state = state.copy(searchQuery = it) }, {}, {}, {}, {}, {}, { _, _, _ -> })
                }
            }
        }
        try {
            fun input() = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            scene.render().close()
            requireNotNull(input().config[SemanticsActions.SetText].action)(AnnotatedString("abc"))
            settle(scene) { input().config[SemanticsProperties.EditableText].text == "abc" }
            assertEquals("abc", state.searchQuery)
            requireNotNull(input().config[SemanticsActions.SetSelection].action)(1, 1, false)
            settle(scene) {
                input().config[SemanticsProperties.TextSelectionRange] ==
                    androidx.compose.ui.text.TextRange(1)
            }
            assertEquals(androidx.compose.ui.text.TextRange(1), input().config[SemanticsProperties.TextSelectionRange])
            requireNotNull(input().config[SemanticsActions.InsertTextAtCursor].action)(AnnotatedString("X"))
            settle(scene) {
                input().config[SemanticsProperties.EditableText].text == "aXbc" &&
                    input().config[SemanticsProperties.TextSelectionRange] == androidx.compose.ui.text.TextRange(2)
            }
            assertEquals("aXbc", state.searchQuery)
            requireNotNull(input().config[SemanticsActions.SetSelection].action)(1, 3, false)
            settle(scene) {
                input().config[SemanticsProperties.TextSelectionRange] ==
                    androidx.compose.ui.text.TextRange(1, 3)
            }
            assertEquals(
                androidx.compose.ui.text.TextRange(1, 3),
                input().config[SemanticsProperties.TextSelectionRange],
            )
            requireNotNull(input().config[SemanticsActions.InsertTextAtCursor].action)(AnnotatedString("中"))
            settle(scene) { input().config[SemanticsProperties.EditableText].text == "a中c" }
            assertEquals("a中c", state.searchQuery)
            click(scene, "history_search_clear")
            scene.render().close()
            assertEquals("", state.searchQuery)
            assertTrue(nodes(scene).any { it.config.contains(SemanticsActions.SetText) })
            click(scene, "history_search_close")
            scene.render().close()
            assertEquals(null, state.searchQuery)
            assertTrue(nodes(scene).any { tag(it) == "history_clear_all" })
        } finally {
            scene.close()
        }
    }

    private fun click(scene: ImageComposeScene, tag: String) {
        val node = nodes(scene).single { tag(it) == tag }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action)())
    }
    private fun settle(scene: ImageComposeScene, ready: () -> Boolean) = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(5_000) {
            do {
                scene.render().close()
                kotlinx.coroutines.yield()
            } while (!ready())
        }
    }
    private fun tag(
        node: SemanticsNode,
    ) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun nodes(
        scene: ImageComposeScene,
    ): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
