package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
                every { saveSourceMangaForDetails } returns mockk(relaxed = true)
                every { getManga } returns mockk<GetManga> {
                    every { subscribe(any<String>(), any<Long>()) } returns flowOf(null)
                }
                every { sourceLoginSessionFactory } returns mockk(relaxed = true)
            }
        }

        fun seedHistory(serialized: String) {
            storeNode.put(RECENT_SEARCHES_KEY, serialized)
        }

        fun persistedHistory(): String? = storeNode.get(RECENT_SEARCHES_KEY, null)

        fun mount() = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}.also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(GlobalSearchScreen()) { CurrentScreen() }
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
