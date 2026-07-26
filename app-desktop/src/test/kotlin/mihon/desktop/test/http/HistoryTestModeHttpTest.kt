package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.history.HistoryScreenModel
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Date

class HistoryTestModeHttpTest {
    @Test
    fun `search action loads production history owner instead of legacy state`() = runBlocking {
        val repository = FakeHistoryRepository().also {
            it.addHistory(history(1, "Naruto"))
            it.addHistory(history(2, "One Piece"))
        }
        val model = model(repository)
        val controller = HistoryTestModeController(model)
        HistoryTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/history_search", """{"query":"One"}""")

                assertEquals("One", model.state.value.searchQuery)
                assertEquals(listOf("One Piece"), model.state.value.items.map { it.title })
            }
        } finally {
            HistoryTestModeBridge.clear(controller)
        }
    }

    @Test
    fun `history actions search remove clear and select through production owner`() = runBlocking {
        val repository = FakeHistoryRepository().also {
            it.addHistory(history(1, "Naruto"))
            it.addHistory(history(2, "One Piece"))
        }
        val chapterRepository = FakeChapterRepository().also {
            it.seed(Chapter.create().copy(id = 1, mangaId = 10, name = "Chapter 1", url = "/chapter/1"))
        }
        val mangaRepository = FakeMangaRepository().also {
            it.seed(Manga.create().copy(id = 10, source = 1, title = "Naruto", url = "/manga"))
        }
        val model = model(repository, chapterRepository, mangaRepository)
        val controller = HistoryTestModeController(model)
        HistoryTestModeBridge.install(controller)
        TestNavigationController.reset()
        try {
            withServer { baseUrl ->
                val search = post(baseUrl, "/test/action/history_search", """{"query":"One"}""")
                assertEquals(200, search.statusCode())
                assertEquals(listOf("One Piece"), model.state.value.items.map { it.title })
                assertTrue(search.body().contains("\"searchQuery\":\"One\""))

                assertEquals(200, post(baseUrl, "/test/action/history_remove", """{"index":0}""").statusCode())
                assertTrue(model.state.value.items.isEmpty())

                assertEquals(200, post(baseUrl, "/test/action/history_search", """{"query":""}""").statusCode())
                assertEquals(listOf("Naruto"), model.state.value.items.map { it.title })
                assertEquals(200, post(baseUrl, "/test/action/history_select", """{"index":0}""").statusCode())
                assertTrue(TestNavigationController.pendingReaderScreen.value is DesktopReaderScreen)

                assertEquals(200, post(baseUrl, "/test/action/history_clear_all", "{}").statusCode())
                assertTrue(model.state.value.items.isEmpty())
            }
        } finally {
            controller.close()
            TestNavigationController.reset()
        }
    }

    @Test
    fun `history failures distinguish unavailable closed missing row and rejected selection`() = runBlocking {
        withServer { baseUrl ->
            val unavailable = post(baseUrl, "/test/action/history_search", """{"query":""}""")
            assertEquals(503, unavailable.statusCode())
            assertTrue(unavailable.body().contains("HISTORY_OWNER_UNAVAILABLE"))

            val repository = FakeHistoryRepository().also { it.addHistory(history(1, "Naruto")) }
            val controller = HistoryTestModeController(model(repository))
            HistoryTestModeBridge.install(controller)
            try {
                val missingQuery = post(baseUrl, "/test/action/history_search", "{}")
                assertEquals(400, missingQuery.statusCode())
                assertTrue(missingQuery.body().contains("MISSING_PARAMETER"))

                assertEquals(200, post(baseUrl, "/test/action/history_search", """{"query":""}""").statusCode())
                val missingRow = post(baseUrl, "/test/action/history_remove", """{"index":5}""")
                assertEquals(404, missingRow.statusCode())
                assertTrue(missingRow.body().contains("ROW_NOT_FOUND"))

                val rejected = post(baseUrl, "/test/action/history_select", """{"index":0}""")
                assertEquals(409, rejected.statusCode())
                assertTrue(rejected.body().contains("OPERATION_REJECTED"))

                controller.close()
                HistoryTestModeBridge.install(controller)
                val closed = post(baseUrl, "/test/action/history_clear_all", "{}")
                assertEquals(503, closed.statusCode())
                assertTrue(closed.body().contains("OWNER_CLOSED"))
            } finally {
                HistoryTestModeBridge.clear(controller)
            }
        }
    }

    private fun model(
        repository: FakeHistoryRepository,
        chapterRepository: FakeChapterRepository = FakeChapterRepository(),
        mangaRepository: FakeMangaRepository = FakeMangaRepository(),
    ) = HistoryScreenModel(
        getHistory = GetHistory(repository),
        removeHistory = RemoveHistory(repository),
        getChapter = GetChapter(chapterRepository),
        getManga = GetManga(mangaRepository),
    )

    private fun history(id: Long, title: String) = HistoryWithRelations(
        id = id,
        chapterId = id,
        mangaId = 10,
        title = title,
        chapterNumber = id.toDouble(),
        readAt = Date(),
        readDuration = 100,
        coverData = MangaCover(10, 1, true, null, 0),
    )

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private fun post(base: String, path: String, body: String) =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
