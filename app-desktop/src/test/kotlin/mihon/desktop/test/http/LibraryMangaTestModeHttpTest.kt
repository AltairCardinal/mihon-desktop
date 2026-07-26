package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.domain.SortMode
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.ui.library.LibraryScreenModel
import mihon.desktop.ui.library.MangaDetailScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.prefs.Preferences

class LibraryMangaTestModeHttpTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `library search action mutates the DI owned production screen model`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        )
        val model = context.libraryScreenModel
        try {
            withServer { baseUrl ->
                insertManga("Production row")
                assertNotNull(
                    awaitLibraryRows(baseUrl) { it == listOf("Production row") },
                    "DI owner did not collect LibraryScreenModel.libraryMangaFlow() from the real repository",
                )
                val response = post(baseUrl, "/test/action/search", """{"query":"production-query"}""")

                assertEquals(200, response.statusCode())
                assertEquals("production-query", model.state.value.searchQuery)
                assertEquals(
                    "production-query",
                    Json.parseToJsonElement(response.body()).jsonObject
                        .getValue("library").jsonObject
                        .getValue("searchQuery").toString().trim('"'),
                )
            }
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `library filter sort and selection execute production state and expose rows`(@TempDir tempDir: File) = runBlocking {
        val context = context(tempDir)
        try {
            withServer { baseUrl ->
                val zulu = insertManga("Zulu")
                val alpha = insertManga("Alpha")
                insertChapters(zulu, total = 3, read = 3)
                insertChapters(alpha, total = 5, read = 1)
                assertNotNull(
                    awaitLibraryRows(baseUrl) { it.toSet() == setOf("Alpha", "Zulu") },
                    "real repository rows never reached the DI-owned library model",
                )

                val filter = post(baseUrl, "/test/action/filter", """{"type":"unread"}""")
                assertEquals(200, filter.statusCode(), filter.body())
                assertTrue(context.libraryScreenModel.state.value.filterUnread)
                assertEquals(listOf("Alpha"), filter.libraryRows())

                val sort = post(baseUrl, "/test/action/sort", """{"mode":"unreadCount","ascending":"false"}""")
                assertEquals(200, sort.statusCode(), sort.body())
                assertEquals(SortMode.UNREAD_COUNT, context.libraryScreenModel.state.value.sortMode)
                assertFalse(context.libraryScreenModel.state.value.sortAscending)

                val select = post(baseUrl, "/test/action/select", """{"index":"0"}""")
                assertEquals(200, select.statusCode(), select.body())
                assertEquals(alpha.id, select.detail().getValue("mangaId").jsonPrimitive.content.toLong())
                assertEquals(5, select.detail().getValue("chapters").let { it as kotlinx.serialization.json.JsonArray }.size)
            }
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `library owner close removes state and rejects later actions`(@TempDir tempDir: File) = runBlocking {
        val context = context(tempDir)
        withServer { baseUrl ->
            context.closeAndJoin()

            assertSame(
                kotlinx.serialization.json.JsonNull,
                get(baseUrl, "/test/state").json()["library"],
            )
            val response = post(baseUrl, "/test/action/search", """{"query":"ignored"}""")
            assertEquals(503, response.statusCode())
            assertFalse(response.json().getValue("success").jsonPrimitive.boolean)
            assertEquals("LIBRARY_OWNER_UNAVAILABLE", response.json().getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun `manga detail HTTP actions publish production mutations`(@TempDir tempDir: File) = runBlocking {
        val context = context(tempDir)
        try {
            withServer { baseUrl ->
                val manga = insertManga("Detail")
                val chapterRow = insertChapters(manga, total = 1, read = 0).single()
                Injekt.get<CategoryRepository>().insert(Category(id = 3, name = "Three", order = 0, flags = 0))
                Injekt.get<CategoryRepository>().insert(Category(id = 4, name = "Four", order = 1, flags = 0))
                assertNotNull(
                    awaitLibraryRows(baseUrl) { it == listOf("Detail") },
                    "detail fixture never reached the DI-owned library model",
                )

                assertEquals(200, post(baseUrl, "/test/action/select", """{"index":"0"}""").statusCode())
                assertEquals(
                    "[${chapterRow.id}]",
                    get(baseUrl, "/test/state").detail().getValue("chapters").toString(),
                )
                val read = post(baseUrl, "/test/action/select", """{"type":"chapter","index":"0"}""")
                assertEquals(200, read.statusCode(), read.body())
                assertTrue(get(baseUrl, "/test/reader/state").json().getValue("isOpen").jsonPrimitive.boolean)

                val removed = post(baseUrl, "/test/action/removeFromLibrary", "{}")
                assertEquals(200, removed.statusCode(), removed.body())
                assertFalse(removed.detail().getValue("favorite").jsonPrimitive.boolean)

                val added = post(baseUrl, "/test/action/addToLibrary", "{}")
                assertEquals(200, added.statusCode(), added.body())
                assertTrue(added.detail().getValue("favorite").jsonPrimitive.boolean)

                val categories = post(baseUrl, "/test/action/detail_categories", """{"categoryIds":"3,4"}""")
                assertEquals(200, categories.statusCode(), categories.body())
                assertEquals("[3,4]", categories.detail().getValue("categoryIds").toString())

                val chapter = post(
                    baseUrl,
                    "/test/action/detail_chapter",
                    """{"operation":"read","chapterIds":"${chapterRow.id}","read":"true"}""",
                )
                assertEquals(200, chapter.statusCode(), chapter.body())
                assertEquals("[${chapterRow.id}]", chapter.detail().getValue("lastSucceededChapterIds").toString())

                val cover = post(baseUrl, "/test/action/detail_cover", """{"operation":"delete"}""")
                assertEquals(200, cover.statusCode(), cover.body())
                assertEquals("Cover deleted", cover.detail().getValue("coverFeedback").jsonPrimitive.content)

                val download = post(baseUrl, "/test/action/download", """{"chapterIds":"${chapterRow.id}"}""")
                assertEquals(200, download.statusCode(), download.body())
                assertEquals("[${chapterRow.id}]", download.detail().getValue("lastSucceededChapterIds").toString())
            }
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `manga detail HTTP exposes partial download failure`() = runBlocking {
        val first = Chapter.create().copy(id = 91, mangaId = 9, name = "First", url = "/first")
        val second = Chapter.create().copy(id = 92, mangaId = 9, name = "Second", url = "/second")
        val item = libraryManga(9, "Partial", total = 2, read = 0)
        val getMangaWithChapters = mockk<GetMangaWithChapters> {
            coEvery { subscribe(9, true) } returns MutableStateFlow(item.manga to listOf(first, second))
        }
        val detail = MangaDetailScreenModel(
            mangaId = 9,
            getMangaWithChapters = getMangaWithChapters,
            enqueueDownload = { if (it.chapterId == second.id) error("queue rejected") },
        )
        val getLibraryManga = mockk<GetLibraryManga> {
            every { subscribe() } returns flow {
                emit(listOf(item))
                awaitCancellation()
            }
        }
        val getCategories = mockk<GetCategories> { coEvery { await() } returns emptyList() }
        val library = LibraryScreenModel(getLibraryManga = getLibraryManga, getCategories = getCategories)
        val controller = LibraryMangaTestModeController(library) { detail }
        LibraryMangaTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                assertEquals(200, post(baseUrl, "/test/action/select", """{"index":"0"}""").statusCode())

                val response = post(baseUrl, "/test/action/download", "{}")

                assertEquals(409, response.statusCode(), response.body())
                assertEquals("PARTIAL_FAILURE", response.json().getValue("error").jsonPrimitive.content)
                assertEquals("[91]", response.detail().getValue("lastSucceededChapterIds").toString())
                assertEquals("[92]", response.detail().getValue("lastFailedChapterIds").toString())
            }
        } finally {
            controller.closeAndJoin()
        }
    }

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private fun post(baseUrl: String, path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(baseUrl: String, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun context(tempDir: File) = runBlocking {
        initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        )
    }

    private fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject
    private fun HttpResponse<String>.libraryRows() = json().getValue("library").jsonObject
        .getValue("rows").let { rows -> rows as kotlinx.serialization.json.JsonArray }
        .map { it.jsonObject.getValue("title").jsonPrimitive.content }
    private fun HttpResponse<String>.detail() = json().getValue("detail").jsonObject

    private suspend fun awaitLibraryRows(baseUrl: String, predicate: (List<String>) -> Boolean): List<String>? =
        withTimeoutOrNull(1_000) {
            while (true) {
                val rows = get(baseUrl, "/test/state").libraryRows()
                if (predicate(rows)) return@withTimeoutOrNull rows
                delay(20)
            }
            null
        }

    private suspend fun insertManga(title: String, favorite: Boolean = true): Manga =
        Injekt.get<MangaRepository>().insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = 1,
                    url = "/${title.lowercase().replace(' ', '-')}",
                    title = title,
                    favorite = favorite,
                ),
            ),
        ).single()

    private suspend fun insertChapters(manga: Manga, total: Int, read: Int): List<Chapter> =
        Injekt.get<ChapterRepository>().addAll(
            (0 until total).map { index ->
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = "/${manga.id}/chapter-$index",
                    name = "Chapter $index",
                    read = index < read,
                )
            },
        )

    private fun libraryManga(id: Long, title: String, total: Long, read: Long, favorite: Boolean = true) = LibraryManga(
        manga = Manga.create().copy(id = id, title = title, source = 1L, favorite = favorite),
        categories = emptyList(),
        totalChapters = total,
        readCount = read,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = id,
    )
}
