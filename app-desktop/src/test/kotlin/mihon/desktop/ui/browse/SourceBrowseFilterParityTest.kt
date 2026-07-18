package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.source.FakeDesktopSourceManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter

class SourceBrowseFilterParityTest {

    @Test
    fun `desktop listing replays original filter UI apply latest and reset semantics`() = runBlocking {
        val filters = FilterList(
            Filter.Header("Section"),
            Filter.Separator(),
            object : Filter.TriState("Licensed") {},
            object : Filter.Text("Keyword") {},
            object : Filter.Select<String>("Language", arrayOf("First", "Second")) {},
            object : Filter.Sort("Order", arrayOf("Newest", "Oldest")) {},
            object : Filter.Group<Filter<*>>(
                "Outer",
                listOf(object : Filter.Group<Filter<*>>("Inner", listOf(object : Filter.CheckBox("Completed") {})) {}),
            ) {},
        )
        val source = RecordingSource(filters)
        val scene = sourceScene(source)
        source.await(Kind.Popular)

        click(scene, "Filters")
        assertTrue(semantics(scene).contains("Section"))
        assertTrue(semantics(scene).contains("Inner"))
        click(scene, "Apply")
        source.await(Kind.Search).also {
            assertEquals("", it.query)
            assertSame(filters, it.filters)
        }

        click(scene, "Filters")
        click(scene, "Licensed")
        click(scene, "Newest")
        click(scene, "Completed")
        setText(scene, "Keyword", "hero")
        click(scene, "Second")
        click(scene, "Apply")
        source.await(Kind.Search).also { assertSame(filters, it.filters) }
        assertEquals(Filter.TriState.STATE_INCLUDE, filters.filterIsInstance<Filter.TriState>().single().state)
        assertEquals(Filter.Sort.Selection(0, true), filters.filterIsInstance<Filter.Sort>().single().state)
        assertEquals("hero", filters.filterIsInstance<Filter.Text>().single().state)
        assertEquals(1, filters.filterIsInstance<Filter.Select<*>>().single().state)
        val nested = filters.filterIsInstance<Filter.Group<*>>().single().state
            .filterIsInstance<Filter.Group<*>>().single().state.filterIsInstance<Filter.CheckBox>().single()
        assertTrue(nested.state)

        click(scene, "Latest")
        assertNull(source.await(Kind.Latest).filters)
        click(scene, "Popular")
        source.await(Kind.Popular)
        click(scene, "Filters")
        click(scene, "Reset")
        assertNull(source.await(Kind.Popular).filters)
        scene.close()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun CoroutineScope.sourceScene(source: RecordingSource): ImageComposeScene {
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { sourceLoginSessionFactory } returns DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, _ -> false },
            )
        }
        return ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}.also {
            it.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(SourceBrowseScreen(source.id)) { CurrentScreen() }
                }
            }
            it.render()
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(label) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        scene.render()
    }

    private fun setText(scene: ImageComposeScene, label: String, value: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.SetText) && it.config.toString().contains(label) }
        assertTrue(requireNotNull(node.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        scene.render()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun semantics(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }

    private enum class Kind { Popular, Latest, Search }
    private data class Call(val kind: Kind, val query: String? = null, val filters: FilterList? = null)

    private class RecordingSource(private val filterList: FilterList) : CatalogueSource {
        private val calls = Channel<Call>(Channel.UNLIMITED)
        override val id = 73L
        override val name = "Filter source"
        override val lang = "en"
        override val supportsLatest = true
        suspend fun await(kind: Kind): Call {
            while (true) {
                val call = withTimeout(3_000) { calls.receive() }
                if (call.kind == kind) return call
            }
        }
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false).also { calls.send(Call(Kind.Popular)) }
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false).also { calls.send(Call(Kind.Latest)) }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false).also { calls.send(Call(Kind.Search, query, filters)) }
        override fun getFilterList() = filterList
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }
}
