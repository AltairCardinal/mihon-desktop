package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.paging.PagingSource
import eu.kanade.presentation.browse.performBrowseSourceRecovery
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceRecoveryAction

class BrowseSourceRecoveryBehaviorTest {

    @Test
    fun `refresh 403 preserves OpenLogin through the real paging source`() = runTest {
        val source = FailingCatalogueSource(failPage = 1)
        val result = pagingSource(source).load(refresh())

        val error = assertInstanceOf(PagingSource.LoadResult.Error::class.java, result)
        val pageError = assertInstanceOf(SourcePageException::class.java, error.throwable).pageError

        assertEquals(SourceRecoveryAction.OpenLogin, pageError.recoveryAction)
    }

    @Test
    fun `append 403 preserves OpenLogin through the real paging source`() = runTest {
        val source = FailingCatalogueSource(failPage = 2)
        val pagingSource = pagingSource(source)

        assertInstanceOf(PagingSource.LoadResult.Page::class.java, pagingSource.load(refresh()))
        val error = assertInstanceOf(PagingSource.LoadResult.Error::class.java, pagingSource.load(append(2)))
        val pageError = assertInstanceOf(SourcePageException::class.java, error.throwable).pageError

        assertEquals(SourceRecoveryAction.OpenLogin, pageError.recoveryAction)
    }

    @Test
    fun `Browse OpenLogin recovery uses the existing source WebView screen`() {
        val source = LoginHttpSource()

        val destination = browseSourceRecoveryScreen(
            source,
            SourcePageError(AppError.Authentication(), SourceRecoveryAction.OpenLogin),
        )

        assertInstanceOf(WebViewScreen::class.java, destination)
    }

    @Test
    fun `Browse UI recovery action retries the failed load instead of opening login`() {
        var retries = 0
        var loginAttempts = 0

        performBrowseSourceRecovery(
            pageError = SourcePageError(AppError.Server(500), SourceRecoveryAction.Retry),
            retry = { retries += 1 },
            openLogin = { loginAttempts += 1 },
        )

        assertEquals(1, retries)
        assertEquals(0, loginAttempts)
    }

    private fun pagingSource(source: CatalogueSource): SharedSourcePagingSource {
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
        return SharedSourcePagingSource(
            source = source,
            listing = BrowseSourceScreenModel.Listing.Popular,
            generation = 1,
            sourceMangaSearchService = SourceMangaSearchService(),
            networkToLocalManga = NetworkToLocalManga(repository),
        )
    }

    private fun refresh() = PagingSource.LoadParams.Refresh<Long>(
        key = null,
        loadSize = 25,
        placeholdersEnabled = false,
    )

    private fun append(page: Long) = PagingSource.LoadParams.Append(
        key = page,
        loadSize = 25,
        placeholdersEnabled = false,
    )

    private class FailingCatalogueSource(private val failPage: Int) : CatalogueSource {
        override val id = 1L
        override val name = "Paging source"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            if (page == failPage) throw HttpException(403)
            return MangasPage(listOf(manga("/first", "First")), true)
        }

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class LoginHttpSource : HttpSource() {
        override val id = 7L
        override val name = "Login source"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://example.com"
        override val client = OkHttpClient()

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

    private companion object {
        fun manga(url: String, title: String) = SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
