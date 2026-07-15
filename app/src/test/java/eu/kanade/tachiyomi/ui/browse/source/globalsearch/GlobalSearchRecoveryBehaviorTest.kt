package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.domain.error.AppError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceRecoveryAction

class GlobalSearchRecoveryBehaviorTest {

    @Test
    fun `global search error preserves shared error and recovery action`() {
        val pageError = SourcePageError(AppError.Authentication(), SourceRecoveryAction.OpenLogin)

        val result = SearchItemResult.Error(pageError)

        assertEquals(pageError, result.pageError)
    }

    @Test
    fun `open login recovery uses the existing source WebView screen`() {
        val source = TestHttpSource()
        val pageError = SourcePageError(AppError.Authentication(), SourceRecoveryAction.OpenLogin)

        val destination = globalSearchRecoveryScreen(source, pageError)

        assertInstanceOf(WebViewScreen::class.java, destination)
    }

    private class TestHttpSource : HttpSource() {
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
}
