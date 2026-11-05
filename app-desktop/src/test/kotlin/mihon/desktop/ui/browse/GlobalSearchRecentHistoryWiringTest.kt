package mihon.desktop.ui.browse

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceMangaSearchService
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class)
class GlobalSearchRecentHistoryWiringTest {

    private class DetailScreen : Screen {
        @Composable
        override fun Content() {
            Text("detail")
        }
    }

    @Test
    fun `prefilled entry keeps focus and accepts typing without showing history in any frame`() = runBlocking {
        for (history in listOf("[]", "[\"previous\"]")) {
            val fixture = Fixture(coroutineContext)
            fixture.seedHistory(history)
            val scene = fixture.mount("original")
            try {
                settleWithoutHistory(scene)
                assertTrue(inputFocused(scene))
                assertEquals(
                    TextRange(8),
                    nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.TextSelectionRange],
                )
                assertEquals(
                    AnnotatedString("original"),
                    nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.EditableText],
                )
                assertTrue(
                    requireNotNull(fixture.persistedHistory()).contains("original"),
                    "automatic search still records the query",
                )
                typeCharacter(scene, 'a')
                settleWithoutHistory(scene)
                assertTrue(inputFocused(scene))
                val edited = nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.EditableText].text
                assertEquals("originala", edited)

                // Clicking the already focused editable anchor is an explicit history request.
                val center = nodeByTag(scene, SEARCH_INPUT_TAG).boundsInWindow.center
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    center,
                    buttons = PointerButtons(isPrimaryPressed = true),
                    button = PointerButton.Primary,
                )
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    center,
                    buttons = PointerButtons(),
                    button = PointerButton.Primary,
                )
                awaitTag(scene, HISTORY_MENU_TAG)
                assertTrue(inputFocused(scene), "editable history anchor must keep text input focus")
            } finally {
                fixture.close(scene)
            }
        }
    }

    @Test
    fun `manual selection survives recomposition refocus and detail navigation`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val scene = fixture.mount("original")
        try {
            settleWithoutHistory(scene)
            val input = nodeByTag(scene, SEARCH_INPUT_TAG)
            assertTrue(requireNotNull(input.config[SemanticsActions.SetSelection].action).invoke(2, 2, false))
            settle(scene)
            assertEquals(
                TextRange(2),
                nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.TextSelectionRange],
            )
            assertTrue(requireNotNull(input.config[SemanticsActions.SetSelection].action).invoke(1, 4, false))
            settle(scene)
            fixture.updateHistory(listOf("previous"))
            settle(scene)
            requestFocus(scene, SEARCH_SUBMIT_TAG)
            settle(scene)
            requestFocus(scene, SEARCH_INPUT_TAG)
            settle(scene)
            assertEquals(
                TextRange(1, 4),
                nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.TextSelectionRange],
            )
            requireNotNull(fixture.navigator).push(DetailScreen())
            settle(scene)
            requireNotNull(fixture.navigator).pop()
            settle(scene)
            assertEquals(
                TextRange(1, 4),
                nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.TextSelectionRange],
            )
            requestFocus(scene, SEARCH_INPUT_TAG)
            typeCharacter(scene, 'a')
            settle(scene)
            assertEquals(
                AnnotatedString("oainal"),
                nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.EditableText],
            )
        } finally {
            fixture.close(scene)
        }
    }

    @Test
    fun `whitespace entry focuses input and opens existing history`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.seedHistory("[\"previous\"]")
        val scene = fixture.mount("   ")
        try {
            awaitTag(scene, HISTORY_MENU_TAG)
            assertTrue(inputFocused(scene))
        } finally {
            fixture.close(scene)
        }
    }

    private suspend fun settleWithoutHistory(scene: ImageComposeScene) {
        repeat(8) {
            scene.render()
            assertTrue(
                nodes(scene).none {
                    it.testTag() == HISTORY_MENU_TAG
                },
                "history must never flash during automatic entry",
            )
            yield()
        }
    }

    @Test
    fun `entering global search accepts keyboard input without clicking with and without history`() = runBlocking {
        for (history in listOf("[]", "[\"previous\"]")) {
            val fixture = Fixture(coroutineContext)
            fixture.seedHistory(history)
            val scene = fixture.mount()
            try {
                settle(scene)
                assertTrue(inputFocused(scene), "new search must focus its input, history=$history")
                typeCharacter(scene, 'a')
                settle(scene)
                assertEquals(
                    AnnotatedString("a"),
                    nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.EditableText],
                )
                if (history != "[]") awaitTag(scene, HISTORY_MENU_TAG)
            } finally {
                fixture.close(scene)
            }
        }
    }

    @Test
    fun `initial query is preserved and focus is not requested again after navigation or updates`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val scene = fixture.mount("original")
        try {
            settle(scene)
            assertTrue(inputFocused(scene))
            assertEquals(
                AnnotatedString("original"),
                nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.EditableText],
            )

            requestFocus(scene, SEARCH_SUBMIT_TAG)
            settle(scene)
            assertFalse(inputFocused(scene))
            fixture.updateHistory(listOf("new history"))
            settle(scene)
            assertFalse(inputFocused(scene), "history updates must not steal focus")

            requireNotNull(fixture.navigator).push(DetailScreen())
            settle(scene)
            requireNotNull(fixture.navigator).pop()
            settle(scene)
            assertFalse(inputFocused(scene), "returning to the same search must not request input focus again")
            assertEquals(
                AnnotatedString("original"),
                nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.EditableText],
            )

            requireNotNull(fixture.navigator).replace(GlobalSearchScreen())
            settle(scene)
            assertTrue(inputFocused(scene), "a new search screen must focus independently")
        } finally {
            fixture.close(scene)
        }
    }

    private suspend fun settle(scene: ImageComposeScene) {
        repeat(5) {
            scene.render()
            yield()
        }
    }

    private fun inputFocused(scene: ImageComposeScene) =
        nodeByTag(scene, SEARCH_INPUT_TAG).config[SemanticsProperties.Focused]

    private fun typeCharacter(scene: ImageComposeScene, character: Char) {
        val nativeEvent = java.awt.event.KeyEvent(
            java.awt.Canvas(),
            java.awt.event.KeyEvent.KEY_TYPED,
            0L,
            0,
            java.awt.event.KeyEvent.VK_UNDEFINED,
            character,
        )
        val composeEvent = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            .getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java)
            .invoke(null, nativeEvent)
        scene.sendKeyEvent(KeyEvent(composeEvent))
    }

    @Test
    fun `focusing search shows persisted history and delete removes only that record`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.seedHistory("""["third","second","first"]""")
        val scene = fixture.mount()

        try {
            scene.render()
            requestFocus(scene, SEARCH_INPUT_TAG)
            awaitTag(scene, HISTORY_MENU_TAG)

            assertEquals("third", branchText(scene, historyItemTag(0)))
            assertEquals("second", branchText(scene, historyItemTag(1)))
            assertEquals("first", branchText(scene, historyItemTag(2)))

            clickTag(scene, historyDeleteTag(1))
            scene.render()

            assertEquals("third", branchText(scene, historyItemTag(0)))
            assertEquals("first", branchText(scene, historyItemTag(1)))
            assertFalse(semantics(scene).contains("second"))
            assertEquals("""["third","first"]""", fixture.persistedHistory())
        } finally {
            fixture.close(scene)
        }
    }

    @Test
    fun `submitting searches promotes duplicates and persists only the latest three`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val scene = fixture.mount()

        try {
            scene.render()
            listOf("one", "two", "three", "two", "four").forEach { query ->
                setText(scene, SEARCH_INPUT_TAG, query)
                scene.render()
                clickTag(scene, SEARCH_SUBMIT_TAG)
                scene.render()
            }

            // Semantics OnClick does not move focus like keyboard navigation to the button.
            requestFocus(scene, SEARCH_SUBMIT_TAG)
            requestFocus(scene, SEARCH_INPUT_TAG)
            awaitTag(scene, HISTORY_MENU_TAG)

            assertEquals("four", branchText(scene, historyItemTag(0)))
            assertEquals("two", branchText(scene, historyItemTag(1)))
            assertEquals("three", branchText(scene, historyItemTag(2)))
            assertFalse(semantics(scene).contains("one"))
            assertEquals("""["four","two","three"]""", fixture.persistedHistory())
        } finally {
            fixture.close(scene)
        }
    }

    @Test
    fun `selecting a history record fills the field searches and promotes it`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.seedHistory("""["third","second","first"]""")
        val scene = fixture.mount()

        try {
            scene.render()
            requestFocus(scene, SEARCH_INPUT_TAG)
            awaitTag(scene, HISTORY_MENU_TAG)
            clickTag(scene, historyItemTag(1))
            scene.render()

            val input = nodeByTag(scene, SEARCH_INPUT_TAG)
            assertEquals(AnnotatedString("second"), input.config[SemanticsProperties.EditableText])
            assertEquals(TextRange(6), input.config[SemanticsProperties.TextSelectionRange])
            assertEquals("""["second","third","first"]""", fixture.persistedHistory())
        } finally {
            fixture.close(scene)
        }
    }

    private class Fixture(private val coroutineContext: CoroutineContext) {
        private val root = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        private val storeNode = root.node("store")
        private val preferences by lazy {
            DesktopAppPreferences(DesktopPreferenceStore(storeNode), root.node("legacy"))
        }
        private val dependencies by lazy {
            mockk<DesktopUiDependencies> {
                every { sourceManager } returns FakeDesktopSourceManager(emptyList())
                every { appPreferences } returns preferences
                every { sourceMangaSearchService } returns SourceMangaSearchService()
                every { extensionPresentationService } returns mockk {
                    every { installedExtensions } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
                }
                every { saveSourceMangaForDetails } returns mockk(relaxed = true)
                every { getManga } returns mockk<GetManga> {
                    every { subscribe(any<String>(), any<Long>()) } returns flowOf(null)
                }
                every { sourceLoginSessionFactory } returns mockk(relaxed = true)
                every { creatorDiscoverySourcePort } returns null
            }
        }

        fun seedHistory(serialized: String) {
            storeNode.put(RECENT_SEARCHES_KEY, serialized)
        }

        fun persistedHistory(): String? = storeNode.get(RECENT_SEARCHES_KEY, null)

        fun updateHistory(queries: List<String>) {
            preferences.browseRecentSearches.set(queries)
        }

        var navigator: Navigator? = null

        fun mount(initialQuery: String = "") = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {
        }.also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(GlobalSearchScreen(initialQuery)) {
                        navigator = it
                        CurrentScreen()
                    }
                }
            }
        }

        fun close(scene: ImageComposeScene) {
            scene.close()
            root.removeNode()
        }
    }

    private suspend fun awaitTag(scene: ImageComposeScene, tag: String) = withTimeout(2_000) {
        while (nodes(scene).none { it.testTag() == tag }) {
            scene.render()
            yield()
        }
    }

    private fun requestFocus(scene: ImageComposeScene, tag: String) {
        val node = nodeByTag(scene, tag)
        assertTrue(requireNotNull(node.config[SemanticsActions.RequestFocus].action).invoke())
    }

    private fun setText(scene: ImageComposeScene, tag: String, value: String) {
        val node = nodeByTag(scene, tag)
        assertTrue(requireNotNull(node.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
    }

    private fun clickTag(scene: ImageComposeScene, tag: String) {
        val node = nodeByTag(scene, tag)
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun nodeByTag(scene: ImageComposeScene, tag: String) = nodes(scene).single { it.testTag() == tag }

    private fun branchText(scene: ImageComposeScene, tag: String): String =
        flatten(nodeByTag(scene, tag)).joinToString { it.config.toString() }
            .let { semantics ->
                listOf("third", "second", "first", "one", "two", "three", "four")
                    .single { semantics.contains(it) }
            }

    private fun semantics(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }

    private fun SemanticsNode.testTag(): String? =
        if (config.contains(SemanticsProperties.TestTag)) config[SemanticsProperties.TestTag] else null

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private companion object {
        const val RECENT_SEARCHES_KEY = "browse_recent_searches"
        const val SEARCH_INPUT_TAG = "global-search-input"
        const val SEARCH_SUBMIT_TAG = "global-search-submit"
        const val HISTORY_MENU_TAG = "global-search-history"

        fun historyItemTag(index: Int) = "global-search-history-item-$index"
        fun historyDeleteTag(index: Int) = "global-search-history-delete-$index"
    }
}
