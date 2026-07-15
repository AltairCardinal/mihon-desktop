package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.test.runTest
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class DesktopReaderPageLoaderIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `current chapter with one failed page publishes error instead of loaded pages with a blank url`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = "page-one"))
            server.enqueue(MockResponse(code = 500))
            val source = PageSource(
                listOf(
                    Page(0, imageUrl = server.url("/page-1.jpg").toString()),
                    Page(1, imageUrl = server.url("/page-2.jpg").toString()),
                ),
            )
            val loader = DesktopReaderPageLoader(
                downloadProvider = DesktopDownloadProvider(tempDir),
                sourceManager = FakeDesktopSourceManager(listOf(source)),
                networkHelper = NetworkHelper(OkHttpClient()),
            )
            val chapterUrl = "/chapter/${UUID.randomUUID()}"
            val model = ReaderScreenModel(
                chapterId = 7L,
                sourceId = source.id,
                chapterUrl = chapterUrl,
            )

            loader.load(
                model = model,
                sourceId = source.id,
                chapterUrl = chapterUrl,
                mangaTitle = "Manga",
                chapterTitle = "Chapter 7",
                initialPage = 0,
            )

            assertTrue(model.state.value.chapterState is ReaderChapterState.Error)

            server.enqueue(MockResponse(body = "retried-page"))
            model.requestRetry()
            loader.load(
                model = model,
                sourceId = source.id,
                chapterUrl = chapterUrl,
                mangaTitle = "Manga",
                chapterTitle = "Chapter 7",
                initialPage = 0,
            )

            val retried = model.state.value.chapterState as ReaderChapterState.Loaded
            assertTrue(retried.pages.all { !it.imageUrl.isNullOrBlank() })
        }
    }

    @Test
    fun `adjacent chapter with one failed page publishes retryable error then real loader retry succeeds`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = "page-one"))
            server.enqueue(MockResponse(code = 500))
            val source = PageSource(
                listOf(
                    Page(0, imageUrl = server.url("/adjacent-1.jpg").toString()),
                    Page(1, imageUrl = server.url("/adjacent-2.jpg").toString()),
                ),
            )
            val loader = DesktopReaderPageLoader(
                downloadProvider = DesktopDownloadProvider(tempDir),
                sourceManager = FakeDesktopSourceManager(listOf(source)),
                networkHelper = NetworkHelper(OkHttpClient()),
            )
            val chapter = ReaderChapterModel(
                id = 8L,
                url = "/chapter/${UUID.randomUUID()}",
                name = "Chapter 8",
                chapterNumber = 8.0,
            )

            val failed = loader.loadAdjacentChapter(chapter, source.id, "Manga")

            assertTrue(failed is ReaderChapterState.Error)
            assertTrue((failed as ReaderChapterState.Error).retryTargetChapterId == chapter.id)

            server.enqueue(MockResponse(body = "retried-page"))
            val retried = loader.loadAdjacentChapter(chapter, source.id, "Manga")

            val loaded = retried as ReaderChapterState.Loaded
            assertTrue(loaded.pages.all { !it.imageUrl.isNullOrBlank() })
        }
    }

    private class PageSource(
        private val pages: List<Page>,
    ) : CatalogueSource {
        override val id = 42L
        override val name = "reader-pages"
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPageList(chapter: SChapter): List<Page> = pages
        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override fun getFilterList(): FilterList = FilterList()
    }
}
