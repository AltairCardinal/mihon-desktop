package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQuery

@OptIn(ExperimentalComposeUiApi::class)
class SourceBrowsePagingTest {

    @Test
    fun `search listing appends the next page of the same query`() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val source = mockk<Source> {
            every { id } returns 94L
            every { name } returns "Paged source"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(any(), any(), any()) } answers {
                val page = firstArg<Int>()
                requestedPages += page
                MangasPage(listOf(manga(page)), hasNextPage = page == 1)
            }
        }
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val query = SourceQuery.Search("naruto", FilterList())

        coordinator.load(source, 1, query)
        assertEquals(1, coordinator.state!!.items.size)

        coordinator.load(source, 2, query)
        assertEquals(2, coordinator.state!!.items.size, "the next page of the same search must be appended")
        assertEquals(listOf(1, 2), requestedPages)
    }

    @Test
    fun `popular listing appends the next page`() = runBlocking {
        val source = mockk<Source> {
            every { id } returns 95L
            every { name } returns "Paged source"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getPopularManga(any()) } answers {
                val page = firstArg<Int>()
                MangasPage(listOf(manga(page)), hasNextPage = page == 1)
            }
        }
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())

        coordinator.load(source, 1, SourceQuery.Popular)
        coordinator.load(source, 2, SourceQuery.Popular)

        assertEquals(2, coordinator.state!!.items.size)
    }

    @Test
    fun `the browse screen loads the next page of a search listing`() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val source = mockk<Source> {
            every { id } returns 96L
            every { name } returns "Paged source"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(any(), any(), any()) } answers {
                val page = firstArg<Int>()
                requestedPages += page
                MangasPage(listOf(manga(page)), hasNextPage = page == 1)
            }
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { appPreferences } returns sourceBrowseHistoryPreferences()
            every { extensionManager } returns sourceBrowseExtensionManager()
            every { sourceExtensionLookup } returns sourceBrowseExtensionManager()
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk(relaxed = true) {
                coEvery { awaitSearchResults(any(), any()) } answers {
                    firstArg<List<SManga>>().map { it.toDomainManga(secondArg()) }
                }
            }
            every { getManga } returns mockk<GetManga> {
                every { subscribe(any<String>(), any<Long>()) } returns flowOf(null)
            }
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(SourceBrowseScreen(source.id, initialQuery = "query")) { CurrentScreen() }
                }
            }
            withTimeout(5_000) {
                while (requestedPages.size < 2) {
                    scene.render()
                    yield()
                }
            }
            assertEquals(listOf(1, 2), requestedPages, "the search listing must continue with the next page")
        } finally {
            scene.close()
        }
    }

    private fun manga(page: Int) = SManga.create().apply {
        url = "/page/$page"
        title = "page $page"
    }
}
