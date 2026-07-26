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
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.source.FakeDesktopSourceManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.MR
import java.util.Locale

class SourceBrowseFilterParityTest {

    @Test
    fun `listing and filter draft follow fixed original request semantics`() = runBlocking {
        val previousLocale = Locale.getDefault()
        val locale = Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(locale)
        val source = RecordingSource()
        val scene = sourceScene(source)
        val filter = MR.strings.action_filter.localized(locale)
        val apply = MR.strings.action_apply.localized(locale)
        val reset = MR.strings.action_reset.localized(locale)
        val cancel = MR.strings.action_cancel.localized(locale)
        val ascending = MR.strings.action_asc.localized(locale)
        val descending = MR.strings.action_desc.localized(locale)
        val popular = MR.strings.popular.localized(locale)
        val latest = MR.strings.latest.localized(locale)

        try {
            assertEquals(Call(Kind.Popular, 1), source.next())

            click(scene, filter)
            click(scene, apply)
            source.assertNextSearchRuntimeTypes()
            val defaultApply = source.next()
            assertEquals(Kind.Search, defaultApply.kind)
            assertEquals(1, defaultApply.page)
            assertEquals("", defaultApply.query)
            assertTrue(selected(scene, filter))

            click(scene, "Search")
            setText(scene, "hero")
            submitText(scene)
            source.assertNextSearchRuntimeTypes()
            val textSearch = source.next()
            assertEquals(Kind.Search, textSearch.kind)
            assertEquals("hero", textSearch.query)
            assertTrue(selected(scene, filter))
            assertTrue(semantics(scene).contains(filter), "Filter entry must remain visible after text search")

            click(scene, filter)
            click(scene, "Licensed")
            click(scene, cancel)
            assertNoCall(source)
            assertEquals(Filter.TriState.STATE_IGNORE, textSearch.filters!!.filterIsInstance<Filter.TriState>().single().state)

            click(scene, filter)
            click(scene, "Licensed")
            click(scene, reset)
            assertNoCall(source)
            assertTrue(semantics(scene).contains(apply), "Reset must keep the draft dialog open")
            click(scene, "Newest")
            assertTrue(semantics(scene).contains("Newest ($ascending)"))
            click(scene, "Newest")
            assertTrue(semantics(scene).contains("Newest ($descending)"))

            source.paginateSearch = true
            click(scene, apply)
            source.assertNextSearchRuntimeTypes()
            val page1 = source.next()
            assertEquals(Kind.Search, page1.kind)
            assertEquals(1, page1.page)
            assertEquals("", page1.query)
            val page2 = awaitNextRendering(scene, source)
            source.assertNextSearchRuntimeTypes()
            assertEquals(2, page2.page)
            assertEquals(page1.query, page2.query)
            assertSame(page1.filters, page2.filters)
            assertEquals(Filter.Sort.Selection(0, false), page2.filters!!.filterIsInstance<Filter.Sort>().single().state)

            source.paginateSearch = false
            click(scene, popular)
            assertEquals(Call(Kind.Popular, 1), source.next())
            assertTrue(selected(scene, popular))
            click(scene, filter)
            click(scene, apply)
            source.assertNextSearchRuntimeTypes()
            val afterPopular = source.next()
            assertNull(afterPopular.filters!!.filterIsInstance<Filter.Sort>().single().state)

            click(scene, latest)
            assertEquals(Call(Kind.Latest, 1), source.next())
            assertTrue(selected(scene, latest))
        } finally {
            scene.close()
            Locale.setDefault(previousLocale)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun CoroutineScope.sourceScene(source: RecordingSource): ImageComposeScene {
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { appPreferences } returns sourceBrowseHistoryPreferences()
            every { extensionManager } returns sourceBrowseExtensionManager()
            every { sourceExtensionLookup } returns sourceBrowseExtensionManager()
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { getManga } returns mockk<GetManga>(relaxed = true)
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

    private suspend fun awaitNextRendering(scene: ImageComposeScene, source: RecordingSource): Call = withTimeout(3_000) {
        while (true) {
            scene.render()
            source.tryNext()?.let { return@withTimeout it }
            yield()
        }
        error("unreachable")
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(label) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        scene.render()
    }

    private fun setText(scene: ImageComposeScene, value: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.SetText) }
        assertTrue(requireNotNull(node.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        scene.render()
    }

    private fun submitText(scene: ImageComposeScene) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnImeAction) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnImeAction].action).invoke())
        scene.render()
    }

    private fun selected(scene: ImageComposeScene, label: String): Boolean = nodes(scene).any {
        it.config.toString().contains(label) &&
            it.config.contains(SemanticsProperties.Selected) &&
            it.config[SemanticsProperties.Selected]
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun semantics(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }
    private fun assertNoCall(source: RecordingSource) = assertNull(source.tryNext(), "unexpected source request")

    private enum class Kind { Popular, Latest, Search }
    private data class Call(
        val kind: Kind,
        val page: Int,
        val query: String? = null,
        val filters: FilterList? = null,
    )

    private class ExtensionSelect(state: Int = 0) : Filter.Select<String>(
        "Language",
        arrayOf("First", "Second"),
        state,
    )

    private class ExtensionGroup(state: List<Filter<*>>) : Filter.Group<Filter<*>>("Outer", state)

    private class RecordingSource : CatalogueSource {
        private val calls = Channel<Call>(Channel.UNLIMITED)
        private val searchRuntimeTypeFailures = Channel<Throwable?>(Channel.UNLIMITED)
        var paginateSearch = false
        override val id = 73L
        override val name = "Filter source"
        override val lang = "en"
        override val supportsLatest = true
        suspend fun next(): Call = withTimeout(3_000) { calls.receive() }
        fun tryNext(): Call? = calls.tryReceive().getOrNull()
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false).also {
            calls.send(Call(Kind.Popular, page))
        }
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false).also {
            calls.send(Call(Kind.Latest, page))
        }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val runtimeTypes = runCatching {
                val group = filters.filterIsInstance<ExtensionGroup>().single()
                val select = group.state.filterIsInstance<ExtensionSelect>().single()
                check(select.values.javaClass == arrayOf<String>().javaClass) {
                    "Extension Select values must remain String[]"
                }
            }
            searchRuntimeTypeFailures.send(runtimeTypes.exceptionOrNull())
            runtimeTypes.getOrThrow()
            calls.send(Call(Kind.Search, page, query, filters))
            return if (paginateSearch && page == 1) MangasPage(listOf(manga()), true) else MangasPage(emptyList(), false)
        }
        suspend fun assertNextSearchRuntimeTypes() {
            assertNull(
                withTimeout(3_000) { searchRuntimeTypeFailures.receive() },
                "source must receive its own Filter runtime subtypes and String[] values",
            )
        }
        override fun getFilterList() = filters()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()

        private fun filters() = FilterList(
            Filter.Header("Section"),
            Filter.Separator(),
            object : Filter.TriState("Licensed") {},
            object : Filter.Text("Keyword") {},
            object : Filter.Sort("Order", arrayOf("Newest", "Oldest")) {},
            ExtensionGroup(
                listOf(
                    ExtensionSelect(),
                    object : Filter.Group<Filter<*>>("Inner", listOf(object : Filter.CheckBox("Completed") {})) {},
                ),
            ),
        )

        private fun manga() = SManga.create().apply { url = "/page"; title = "Page" }
    }
}
