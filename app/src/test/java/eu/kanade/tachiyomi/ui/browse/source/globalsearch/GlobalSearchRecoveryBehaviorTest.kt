package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
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

    @Test
    fun `prepared login uses only the registered title and ID and refuses unknown or foreign snapshots`() {
        val source = io.mockk.mockk<HttpSource>()
        var urlReads = 0
        io.mockk.every { source.id } throws NoSuchMethodError("late ID")
        io.mockk.every { source.name } throws NoSuchMethodError("late name")
        io.mockk.every { source.baseUrl } answers {
            urlReads++
            "https://example.com"
        }
        val error = SourcePageError(AppError.Authentication(), SourceRecoveryAction.OpenLogin)
        val snapshot = tachiyomi.domain.source.service.SourceMetadataSnapshot(source, 9L, "Prepared login", "en", null)
        val actual = runCatching { globalSearchRecoveryScreen(source, error, snapshot) }
        org.junit.jupiter.api.Assertions.assertNull(actual.exceptionOrNull())
        val screen = assertInstanceOf(WebViewScreen::class.java, actual.getOrThrow())
        assertEquals(9L, screen.savedField("sourceId"))
        assertEquals("Prepared login", screen.savedField("initialTitle"))
        val unknown = tachiyomi.domain.source.service.SourceMetadataSnapshot(source, null, "Unknown", "en", null)
        org.junit.jupiter.api.Assertions.assertNull(globalSearchRecoveryScreen(source, error, unknown))
        val other = io.mockk.mockk<HttpSource>()
        org.junit.jupiter.api.Assertions.assertNull(globalSearchRecoveryScreen(other, error, snapshot))
        assertEquals(1, urlReads)
    }

    @Test
    fun `prepared login target linkage is typed while controls and host Errors remain original`() {
        val source = io.mockk.mockk<HttpSource>()
        val snapshot = tachiyomi.domain.source.service.SourceMetadataSnapshot(source, 9L, "Login", "en", null)
        val page = SourcePageError(AppError.Authentication(), SourceRecoveryAction.OpenLogin)
        val fatal = object : VirtualMachineError("controlled") {}
        val cancel = kotlinx.coroutines.CancellationException("cancelled")
        val host = AssertionError("host")
        val missing = java.io.IOException("target", NoSuchMethodError("future baseURL"))
        for ((thrown, expected) in listOf(
            missing to null,
            java.io.IOException("wrapped", fatal) to fatal,
            ExceptionInInitializerError(cancel) to cancel,
            host to host,
        )) {
            io.mockk.every { source.baseUrl } throws thrown
            val actual = runCatching { globalSearchRecoveryScreen(source, page, snapshot) }.exceptionOrNull()
            if (expected == null) {
                val typed = assertInstanceOf(SourceCompatibilityException::class.java, actual)
                org.junit.jupiter.api.Assertions.assertSame(missing, typed.cause)
                assertEquals(SourceOperationPhase.METADATA, typed.phase)
            } else {
                org.junit.jupiter.api.Assertions.assertSame(expected, actual)
            }
        }
    }

    private fun WebViewScreen.savedField(name: String): Any? =
        WebViewScreen::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

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
