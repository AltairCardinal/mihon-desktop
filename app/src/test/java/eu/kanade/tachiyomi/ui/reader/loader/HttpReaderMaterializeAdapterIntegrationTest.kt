package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException

class HttpReaderMaterializeAdapterIntegrationTest {

    @Test
    fun `real HTTP adapter resolves page list image url and encoded image`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse(body = "page:/resolve"))
            server.enqueue(MockResponse(body = server.url("/image").toString()))
            server.enqueue(MockResponse(body = "image bytes"))
            val fixture = Fixture(server)
            val request = ReaderChapterContentRequest(ReaderChapterId(7), generation = 1)

            val chapterResult = CanonicalReaderMaterializeExecutor.materializeChapter(request, fixture.chapterPort)
            val page = fixture.chapterPort.materializedPages.single()
            val events = mutableListOf<ReaderPageMaterializeEvent>()
            val pageResult = CanonicalReaderMaterializeExecutor.materializePage(
                request = ReaderPageFetchRequest(ReaderPageId(ReaderChapterId(7), 0), 1, page.url, page.imageUrl),
                port = AndroidReaderPageFetchPort(page, fixture.source, fixture.cache),
                publish = { event -> events.add(event) },
            )

            assertInstanceOf(ReaderChapterMaterializeResult.Loaded::class.java, chapterResult)
            assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, pageResult)
            assertEquals(
                listOf(
                    ReaderPageMaterializeEvent.ResolvingImage,
                    ReaderPageMaterializeEvent.Downloading(server.url("/image").toString()),
                    events.last(),
                ),
                events,
            )
            assertInstanceOf(ReaderPageMaterializeEvent.Ready::class.java, events.last())
            assertEquals(listOf("/chapter", "/resolve", "/image"), serverPaths(server, 3))
        }
    }

    @Test
    fun `empty and malformed page lists retain shared errors`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse(body = "empty"))
            server.enqueue(MockResponse(body = "malformed"))
            val fixture = Fixture(server)
            val request = ReaderChapterContentRequest(ReaderChapterId(7), 1)

            val empty = CanonicalReaderMaterializeExecutor.materializeChapter(request, fixture.chapterPort)
            val malformed = CanonicalReaderMaterializeExecutor.materializeChapter(request, fixture.chapterPort)

            assertEquals(AppError.NoResults, (empty as ReaderChapterMaterializeResult.Failed).error)
            assertInstanceOf(
                AppError.MalformedData::class.java,
                (malformed as ReaderChapterMaterializeResult.Failed).error,
            )
        }
    }

    @Test
    fun `HTTP 403 429 and 500 retain authentication rate-limit and server errors`() = runTest {
        withServer { server ->
            listOf(403, 429, 500).forEach { server.enqueue(MockResponse(code = it, body = "failure")) }
            val fixture = Fixture(server)
            val request = ReaderChapterContentRequest(ReaderChapterId(7), 1)

            val errors = List(3) {
                (
                    CanonicalReaderMaterializeExecutor.materializeChapter(
                        request,
                        fixture.chapterPort,
                    ) as ReaderChapterMaterializeResult.Failed
                    ).error
            }

            assertInstanceOf(AppError.Authentication::class.java, errors[0])
            assertInstanceOf(AppError.RateLimited::class.java, errors[1])
            assertEquals(500, (errors[2] as AppError.Server).statusCode)
        }
    }

    @Test
    fun `blank image-url response is malformed and does not request an image`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse(body = "page:/resolve"))
            server.enqueue(MockResponse(body = "  "))
            val fixture = Fixture(server)
            val request = ReaderChapterContentRequest(ReaderChapterId(7), 1)
            CanonicalReaderMaterializeExecutor.materializeChapter(request, fixture.chapterPort)
            val page = fixture.chapterPort.materializedPages.single()

            val result = CanonicalReaderMaterializeExecutor.materializePage(
                ReaderPageFetchRequest(ReaderPageId(ReaderChapterId(7), 0), 1, page.url, null),
                AndroidReaderPageFetchPort(page, fixture.source, fixture.cache),
                publish = { true },
            )

            assertInstanceOf(AppError.MalformedData::class.java, (result as ReaderPageMaterializeResult.Failed).error)
            assertEquals(listOf("/chapter", "/resolve"), serverPaths(server, 2))
        }
    }

    private class Fixture(server: MockWebServer) {
        val source = TestHttpSource(server)
        val cache = mockk<ChapterCache>()
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1, url = "/chapter"))
        private val loader: HttpPageLoader
        val chapterPort: AndroidReaderChapterContentPort

        init {
            val cachedUrls = mutableSetOf<String>()
            every { cache.getPageListFromCache(any()) } throws IOException("cache miss")
            every { cache.isImageInCache(any()) } answers { firstArg<String>() in cachedUrls }
            every { cache.putImageToCache(any(), any()) } answers {
                cachedUrls += firstArg<String>()
                true
            }
            every { cache.removeImageFromCache(any()) } answers { cachedUrls.remove(firstArg<String>()) }
            every { cache.getImageFile(any()) } returns File("unused")
            loader = HttpPageLoader(
                chapter,
                source,
                cache,
                dispatcher = UnconfinedTestDispatcher(),
            )
            chapterPort = AndroidReaderChapterContentPort(chapter, loader)
        }
    }

    private class TestHttpSource(private val server: MockWebServer) : HttpSource() {
        override val id = 77L
        override val name = "Reader HTTP fixture"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = server.url("/").toString().removeSuffix("/")
        override val client = OkHttpClient()

        override fun headersBuilder() = Headers.Builder()

        override fun popularMangaRequest(page: Int) = Request.Builder().url(baseUrl).build()
        override fun popularMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
        override fun latestUpdatesParse(response: Response) = MangasPage(emptyList(), false)
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = popularMangaRequest(page)
        override fun searchMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun mangaDetailsParse(response: Response) = SManga.create()
        override fun chapterListParse(response: Response) = emptyList<SChapter>()
        override fun chapterPageParse(response: Response) = SChapter.create()

        override fun pageListParse(response: Response): List<Page> = when (val body = response.body.string()) {
            "empty" -> emptyList()
            "malformed" -> throw IllegalArgumentException("malformed page list")
            else -> listOf(Page(0, url = baseUrl + body.removePrefix("page:")))
        }

        override fun imageUrlParse(response: Response): String = response.body.string()
    }

    private suspend fun serverPaths(server: MockWebServer, count: Int): List<String> =
        List(count) { server.takeRequest().url.encodedPath }

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        MockWebServer().also { it.start() }.use { block(it) }
    }
}
