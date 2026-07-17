package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mihon.desktop.network.CloudflareChallengeManager
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState

class SourceSharedStateWiringTest {

    @Test
    fun `source projector preserves content while a later page loads and fails`() {
        val request = SourcePageRequest(1, 2, 7, SourceQuery.Popular)
        val item = manga("/kept")

        val loading = SourceBrowseStateProjector.project(SourceQueryState.Loading(request, listOf(item)))
        val failed = SourceBrowseStateProjector.project(
            SourceQueryState.Content(request, listOf(item), false, pageError = tachiyomi.domain.source.service.SourcePageError(AppError.Server(500), tachiyomi.domain.source.service.SourceRecoveryAction.Retry)),
        )

        assertEquals(listOf("/kept"), loading.items.map(SManga::url))
        assertTrue(loading.loading)
        assertEquals(listOf("/kept"), failed.items.map(SManga::url))
        assertInstanceOf(AppError.Server::class.java, failed.pageError?.error)
    }

    @Test
    fun `source projector distinguishes first load from a successful empty page`() {
        val request = SourcePageRequest(1, 1, 1, SourceQuery.Popular)
        val screen = SourceBrowseScreen(1)
        assertTrue(screen.projectState(SourceQueryState.Loading(request)).loading)
        assertFalse(screen.projectState(SourceQueryState.Empty(request)).loading)
        assertTrue(screen.projectState(SourceQueryState.Empty(request)).empty)
    }

    @Test
    fun `source coordinator publishes shared loading and retries the exact request`() = runBlocking {
        val source = PagingSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val observed = mutableListOf<SourceQueryState>()

        coordinator.load(source, 1, SourceQuery.Popular, observed::add)
        coordinator.load(source, 2, SourceQuery.Popular, observed::add)
        val failedRequest = coordinator.state!!.request
        coordinator.retry(source, observed::add)

        assertInstanceOf(SourceQueryState.Loading::class.java, observed.first())
        assertTrue(observed.any { it.request == failedRequest && it.isLoading && it.items.isNotEmpty() })
        assertEquals(failedRequest, coordinator.state!!.request)
        assertEquals(listOf(1, 2, 2), source.pages)
    }

    @Test
    fun `first page retry preserves the failed request generation`() = runBlocking {
        val source = FirstPageRetrySource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())

        coordinator.load(source, 1, SourceQuery.Popular)
        val failedRequest = coordinator.state!!.request
        coordinator.retry(source)

        assertEquals(failedRequest, coordinator.state!!.request)
    }

    @Test
    fun `recovery adapter replays request and publishes login through challenge manager`() = runBlocking {
        val manager = CloudflareChallengeManager()
        val adapter = DesktopSourceRecoveryActionAdapter(manager)
        val request = SourcePageRequest(9, 3, 11, SourceQuery.Search("old", FilterList()))
        var replayed: SourcePageRequest? = null

        adapter.execute(DesktopSourceRecoveryIntent.Retry(request)) { replayed = it }
        adapter.execute(DesktopSourceRecoveryIntent.OpenLogin("https://example.com/path")) {}

        assertEquals(request, replayed)
        assertEquals("example.com", manager.tryReceive()!!.request.url.host)
    }

    private class PagingSource : NamedSource(1, "Paging") {
        val pages = mutableListOf<Int>()
        override suspend fun getPopularManga(page: Int): MangasPage {
            pages += page
            return when {
                page == 1 -> MangasPage(listOf(manga("/kept")), true)
                pages.count { it == 2 } == 1 -> throw HttpException(500)
                else -> MangasPage(listOf(manga("/next")), false)
            }
        }
    }

    private class FirstPageRetrySource : NamedSource(2, "First page") {
        private var attempts = 0
        override suspend fun getPopularManga(page: Int): MangasPage =
            if (attempts++ == 0) throw HttpException(500) else MangasPage(listOf(manga("/retry")), false)
    }

    private open class NamedSource(override val id: Long, override val name: String) : CatalogueSource {
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private companion object {
        fun manga(url: String) = SManga.create().apply {
            this.url = url
            title = url
            initialized = true
        }
    }
}
