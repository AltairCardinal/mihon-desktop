package mihon.presentation.history

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
            settle(scene) {
                nodes(scene).single { tag(it) == "history_search_input" }.config[SemanticsProperties.Focused]
            }
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

    @Test
    fun `restored missing cover keeps toolbar focus`() = kotlinx.coroutines.runBlocking {
        val item = tachiyomi.domain.history.model.HistoryWithRelations(
            id = 1,
            chapterId = 1,
            mangaId = 1,
            title = "History book",
            chapterNumber = 1.0,
            readAt = java.util.Date(),
            readDuration = 0,
            coverData = tachiyomi.domain.manga.model.MangaCover(1, 1, false, null, 0),
        )
        var state by mutableStateOf(
            HistoryState(
                list = listOf(tachiyomi.domain.history.service.HistoryUiModel.Item(item)),
            ),
        )
        var mounted by mutableStateOf(true)
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {
            val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            MaterialTheme {
                if (mounted) {
                    saved.SaveableStateProvider("history") {
                        HistoryContent(
                            state,
                            { state = state.copy(searchQuery = it) },
                            { mounted = false },
                            {},
                            {},
                            {},
                            {},
                            { _, modifier, click ->
                                androidx.compose.foundation.layout.Box(
                                    modifier.clickable(onClick = click),
                                )
                            },
                        )
                    }
                } else {
                    androidx.compose.material3.Button(
                        onClick = { mounted = true },
                        modifier = Modifier.testTag("shared-history-return"),
                    ) { androidx.compose.material3.Text("Return") }
                }
            }
        }
        try {
            settle(scene) { nodes(scene).any { tag(it) == "history_search_open" } }
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").methods.single {
                it.name.startsWith("KeyEvent") && it.parameterCount == 8
            }
            val down = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val native = events.invoke(
                null, androidx.compose.ui.input.key.Key.Tab.keyCode,
                down, 0, false, false, false, false, null,
            )
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native))
            scene.render().close()
            val search = nodes(scene).single { tag(it) == "history_search_open" }
            assertTrue(requireNotNull(search.config[SemanticsActions.RequestFocus].action)())
            settle(scene, "initial search trigger focus") {
                nodes(scene).single { tag(it) == "history_search_open" }.config[SemanticsProperties.Focused]
            }
            click(scene, "history_search_open")
            settle(scene) { nodes(scene).any { it.config.contains(SemanticsActions.SetText) } }
            requireNotNull(
                nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
                    .config[SemanticsActions.SetText].action,
            )(AnnotatedString("History"))
            settle(scene) {
                nodes(scene).any { tag(it) == "history_cover_1" } &&
                    nodes(scene).single { tag(it) == "history_search_input" }.config[SemanticsProperties.Focused]
            }
            val cover = nodes(scene).single { tag(it) == "history_cover_1" }
            assertTrue(requireNotNull(cover.config[SemanticsActions.RequestFocus].action)())
            settle(scene, "cover focus before navigation") {
                nodes(scene).single { tag(it) == "history_cover_1" }.config[SemanticsProperties.Focused]
            }
            click(scene, "history_cover_1")
            settle(scene) { nodes(scene).any { tag(it) == "shared-history-return" } }
            val details = nodes(scene).single { tag(it) == "shared-history-return" }
            assertTrue(requireNotNull(details.config[SemanticsActions.RequestFocus].action)())
            settle(scene, "details return focus") {
                nodes(scene).single { tag(it) == "shared-history-return" }.config[SemanticsProperties.Focused]
            }
            state = state.copy(list = emptyList())
            click(scene, "shared-history-return")
            settle(scene) { nodes(scene).any { tag(it) == "history_search_close" } }
            settle(scene, "restored history focus") {
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.Focused) &&
                        it.config[SemanticsProperties.Focused]
                }
            }
            assertTrue(
                nodes(scene).single { tag(it) == "history_search_close" }
                    .config[SemanticsProperties.Focused],
                "The restored owner must keep the fallback toolbar focus; actual=" +
                    nodes(scene).filter {
                        it.config.contains(SemanticsProperties.Focused) &&
                            it.config[SemanticsProperties.Focused]
                    }.map(::tag),
            )
            assertEquals("History", state.searchQuery)
        } finally {
            scene.close()
        }
    }

    private fun click(scene: ImageComposeScene, tag: String) {
        val node = nodes(scene).single { tag(it) == tag }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action)())
    }
    private fun settle(
        scene: ImageComposeScene,
        description: String = "shared UI settles",
        ready: () -> Boolean,
    ) = kotlinx.coroutines.runBlocking {
        try {
            kotlinx.coroutines.withTimeout(5_000) {
                do {
                    scene.render().close()
                    kotlinx.coroutines.yield()
                } while (!ready())
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "$description; focused=" + nodes(scene).filter {
                    it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
                }.map(::tag),
                failure,
            )
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
