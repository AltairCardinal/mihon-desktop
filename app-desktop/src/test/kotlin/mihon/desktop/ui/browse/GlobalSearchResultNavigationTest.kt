package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceMangaSearchService
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class)
class GlobalSearchResultNavigationTest {

    @Test
    fun `source title uses current query and target first load is search with fresh filters`() = runBlocking {
        listOf<String?>(null, "", "edited query").forEach { edited ->
            val fixture = Fixture()
            val scene = fixture.mount(GlobalSearchScreen("initial query"), coroutineContext)
            try {
                awaitText(scene, "Authority (12)")
                if (edited != null) {
                    setText(scene, edited)
                    scene.render()
                }
                click(scene, "Authority (12)")
                val target = requireNotNull(fixture.navigator).items.last() as SourceBrowseScreen
                assertFalse((target as Screen) is Tab)
                assertEquals(fixture.source.id, target.sourceId)
                assertEquals(edited ?: "initial query", target.initialQuery)

                withTimeout(2_000) {
                    while (fixture.searchQueries.size < 2) {
                        scene.render()
                        yield()
                    }
                }
                assertEquals(listOf("initial query", edited ?: "initial query"), fixture.searchQueries)
                assertTrue(fixture.searchFilters.all(FilterList::isNotEmpty))
                assertTrue(fixture.searchFilters[0] !== fixture.searchFilters[1])
                assertEquals(0, fixture.popularCalls)
                assertTrue(text(scene).contains(edited ?: "initial query"))
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `all twelve canonical results remain reachable in the lazy row`() = runBlocking {
        val fixture = Fixture()
        val scene = fixture.mount(GlobalSearchScreen("initial query"), coroutineContext, width = 320)
        try {
            awaitText(scene, "Authority (12)")
            withTimeout(2_000) {
                while (fixture.activeSubscriptions.isEmpty()) {
                    scene.render()
                    yield()
                }
            }
            assertTrue(fixture.activeSubscriptions.size in 1..<10)
            assertTrue(fixture.seenSubscriptions.size in 1..<10)
            val row = nodes(scene).first {
                it.config.contains(SemanticsActions.ScrollToIndex) && it.config.toString().contains("HorizontalScrollAxisRange")
            }
            assertTrue(requireNotNull(row.config[SemanticsActions.ScrollToIndex].action).invoke(11))
            withTimeout(2_000) {
                while (!text(scene).contains("canonical 11") ||
                    !fixture.seenSubscriptions.contains(fixture.source.id to "/canonical/11")
                ) {
                    scene.render()
                    yield()
                }
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `default source browse still loads popular`() = runBlocking {
        val fixture = Fixture()
        val scene = fixture.mount(SourceBrowseScreen(fixture.source.id), coroutineContext)
        try {
            withTimeout(2_000) {
                while (fixture.popularCalls == 0) {
                    scene.render()
                    yield()
                }
            }
            assertEquals(emptyList<String>(), fixture.searchQueries)
        } finally {
            scene.close()
        }
    }

    private class Fixture {
        val searchQueries = mutableListOf<String>()
        val searchFilters = mutableListOf<FilterList>()
        val seenSubscriptions = ConcurrentHashMap.newKeySet<Pair<Long, String>>()
        val activeSubscriptions = ConcurrentHashMap.newKeySet<Pair<Long, String>>()
        var filterListCalls = 0
        var popularCalls = 0
        val source: CatalogueSource = mockk {
            every { id } returns 91L
            every { name } returns "Authority"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } answers { FilterList(Filter.Header("filters-${++filterListCalls}")) }
            coEvery { getSearchManga(1, any(), any()) } answers {
                searchQueries += secondArg<String>()
                searchFilters += thirdArg<FilterList>()
                MangasPage((0 until 12).map(::manga), false)
            }
            coEvery { getPopularManga(1) } answers {
                popularCalls++
                MangasPage(emptyList(), false)
            }
        }
        private val preferences = mockk<DesktopAppPreferences> {
            every { enabledLanguages } returns preference(setOf("en"))
            every { disabledSources } returns preference(emptySet())
            every { pinnedSources } returns preference(setOf(source.id.toString()))
            every { globalSearchFilterState } returns mockk {
                every { get() } returns false
                every { changes() } returns flowOf(false)
            }
        }
        private val saver = mockk<SaveSourceMangaForDetails>(relaxed = true) {
            coEvery { awaitSearchResults(any(), any()) } answers {
                val sourceId = secondArg<Long>()
                firstArg<List<SManga>>().mapIndexed { index, item -> item.toDomainManga(sourceId).copy(id = index + 1L) }
            }
        }
        private val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { appPreferences } returns preferences
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns saver
            every { getManga } returns mockk<GetManga> {
                every { subscribe(any<String>(), any<Long>()) } answers {
                    val key = secondArg<Long>() to firstArg<String>()
                    flow {
                        seenSubscriptions += key
                        activeSubscriptions += key
                        try {
                            awaitCancellation()
                        } finally {
                            activeSubscriptions -= key
                        }
                    }
                }
            }
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        var navigator: Navigator? = null

        fun mount(screen: Screen, coroutineContext: CoroutineContext, width: Int = 900) =
            ImageComposeScene(width, 700, coroutineContext = coroutineContext) {}.also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) {
                        navigator = LocalNavigator.currentOrThrow
                        CurrentScreen()
                    }
                }
            }
        }

        private fun preference(value: Set<String>): Preference<Set<String>> = mockk { every { get() } returns value }

        private fun manga(index: Int) = SManga.create().apply {
            url = "/canonical/$index"
            title = "canonical $index"
        }
    }

    private suspend fun awaitText(scene: ImageComposeScene, expected: String) = withTimeout(2_000) {
        while (!text(scene).contains(expected)) {
            scene.render()
            yield()
        }
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(owner.rootSemanticsNode)
    }
    private fun text(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }
    private fun click(scene: ImageComposeScene, label: String) = requireNotNull(
        nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(label) }
            .config[SemanticsActions.OnClick].action,
    ).invoke()
    private fun setText(scene: ImageComposeScene, value: String) = requireNotNull(
        nodes(scene).first { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action,
    ).invoke(AnnotatedString(value))
}
