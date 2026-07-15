package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction

class DesktopSourceQueryBehaviorTest {

    @Test
    fun `desktop source errors use stable i18n messages instead of class names`() {
        assertEquals("No Internet connection", desktopSourceErrorMessage(AppError.Network()))
        assertEquals("Login", desktopSourceErrorMessage(AppError.Authentication()))
        assertEquals("Unknown error", desktopSourceErrorMessage(AppError.Server(500)))
    }

    @Test
    fun `later page failure stays visible and retry reloads the same page`() = runBlocking {
        val source = RetryPageSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())

        coordinator.load(source, page = 1, query = SourceQuery.Popular)
        val failed = coordinator.load(source, page = 2, query = SourceQuery.Popular) as SourceQueryState.Content

        assertEquals(listOf("/first"), failed.items.map { it.url })
        assertInstanceOf(AppError.Server::class.java, failed.pageError?.error)
        assertEquals(SourceRecoveryAction.Retry, failed.pageError?.recoveryAction)
        assertEquals(2, failed.request.page)

        val retried = coordinator.retry(source) as SourceQueryState.Content

        assertEquals(listOf(1, 2, 2), source.requestedPages)
        assertEquals(listOf("/first", "/second"), retried.items.map { it.url })
    }

    @Test
    fun `authentication recovery opens the existing source URL intent`() = runBlocking {
        val source = AuthenticationSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())

        coordinator.load(source, page = 1, query = SourceQuery.Popular)

        assertEquals(
            DesktopSourceRecoveryIntent.OpenExternalUrl(source.baseUrl),
            coordinator.recoveryIntent(source),
        )
    }

    private class AuthenticationSource : eu.kanade.tachiyomi.source.online.HttpSource() {
        override val id = 7L
        override val name = "Login source"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://example.com"
        override val client = OkHttpClient.Builder().addInterceptor { throw HttpException(403) }.build()

        override fun popularMangaRequest(page: Int) = Request.Builder().url(baseUrl).build()
        override fun popularMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
        override fun latestUpdatesParse(response: Response) = MangasPage(emptyList(), false)
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = popularMangaRequest(page)
        override fun searchMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun mangaDetailsParse(response: Response) = SManga.create()
        override fun chapterListParse(response: Response) = emptyList<SChapter>()
        override fun chapterPageParse(response: Response) = SChapter.create()
        override fun pageListParse(response: Response) = emptyList<Page>()
        override fun imageUrlParse(response: Response) = ""
    }

    @Test
    fun `late global search generation cannot overwrite current UI state`() = runBlocking {
        val source = HangingSearchSource()
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())

        val old = async { coordinator.search(listOf(source), "old") }
        source.oldStarted.await()
        val current = async { coordinator.search(listOf(source), "new") }
        source.newStarted.await()
        source.newResult.complete(MangasPage(listOf(manga("/new", "New")), false))
        current.await()
        source.oldResult.complete(MangasPage(listOf(manga("/old", "Old")), false))
        old.await()

        val state = coordinator.state
        val result = state.queryStates.getValue(source.id) as SourceQueryState.Content
        assertEquals(listOf("/new"), result.items.map { it.url })
        assertEquals(false, state.isSearching)
        assertEquals("new", (result.request.query as SourceQuery.Search).query)
    }

    private class RetryPageSource : CatalogueSource {
        val requestedPages = mutableListOf<Int>()

        override val id = 1L
        override val name = "Retry"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            requestedPages += page
            return when (requestedPages.count { it == page }) {
                1 -> if (page == 1) MangasPage(listOf(manga("/first", "First")), true) else throw HttpException(500)
                else -> MangasPage(listOf(manga("/second", "Second")), false)
            }
        }

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class HangingSearchSource : CatalogueSource {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<MangasPage>()
        val newResult = CompletableDeferred<MangasPage>()

        override val id = 2L
        override val name = "Hanging"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val (started, result) = if (query == "old") oldStarted to oldResult else newStarted to newResult
            started.complete(Unit)
            return withContext(NonCancellable) { result.await() }
        }
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private companion object {
        fun manga(url: String, title: String) = SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
