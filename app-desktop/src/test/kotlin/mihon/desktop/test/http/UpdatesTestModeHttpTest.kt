package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.domain.fakes.FakeUpdatesRepository
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.updates.UpcomingScreen
import mihon.desktop.updates.UpdatesScreenModel
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.download.DownloadRepository
import mihon.domain.download.EnqueueDownload
import mihon.domain.download.IsChapterDownloaded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class UpdatesTestModeHttpTest {
    @Test
    fun `refresh action loads production updates owner instead of legacy no-op`() = runBlocking {
        val repository = FakeUpdatesRepository().also { it.addUpdate(update(1)) }
        val model = model(repository).model
        val controller = UpdatesTestModeController(model)
        UpdatesTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/updates_refresh", "{}")

                assertEquals(listOf(1L), model.state.value.items.map { it.chapterId })
            }
        } finally {
            UpdatesTestModeBridge.clear(controller)
        }
    }

    @Test
    fun `update actions mutate owner filters read state downloads and navigation`() = runBlocking {
        val repository = FakeUpdatesRepository().also {
            it.addUpdate(update(1, read = false))
            it.addUpdate(update(2, read = true))
        }
        val fixture = model(repository)
        val controller = UpdatesTestModeController(fixture.model)
        UpdatesTestModeBridge.install(controller)
        TestNavigationController.reset()
        try {
            withServer { baseUrl ->
                assertEquals(200, post(baseUrl, "/test/action/updates_refresh", "{}").statusCode())

                val unread = post(
                    baseUrl,
                    "/test/action/updates_filter",
                    """{"type":"unread","enabled":true}""",
                )
                assertEquals(200, unread.statusCode())
                assertTrue(unread.body().contains("\"unreadFilter\":\"ENABLED_IS\""))
                assertEquals(listOf(1L), fixture.model.state.value.items.map { it.chapterId })

                assertEquals(200, post(baseUrl, "/test/action/updates_clear_filters", "{}").statusCode())
                assertEquals(listOf(1L, 2L), fixture.model.state.value.items.map { it.chapterId })

                assertEquals(200, post(baseUrl, "/test/action/updates_mark_read", """{"index":0}""").statusCode())
                assertTrue(fixture.model.state.value.items.first().read)
                assertEquals(200, post(baseUrl, "/test/action/updates_mark_all_read", "{}").statusCode())
                assertTrue(fixture.model.state.value.items.all { it.read })

                assertEquals(200, post(baseUrl, "/test/action/updates_download", """{"index":1}""").statusCode())
                assertEquals(listOf(2L), fixture.enqueued.map { it.chapterId })

                assertEquals(200, post(baseUrl, "/test/action/updates_select", """{"index":0}""").statusCode())
                assertTrue(TestNavigationController.pendingReaderScreen.value is DesktopReaderScreen)

                val upcoming = post(baseUrl, "/test/action/updates_open_upcoming", "{}")
                assertEquals(200, upcoming.statusCode())
                assertTrue(TestNavigationController.pendingScreenNavigation.value is UpcomingScreen)
                assertTrue(upcoming.body().contains("\"upcomingOpened\":true"))
            }
        } finally {
            controller.close()
            TestNavigationController.reset()
        }
    }

    @Test
    fun `update action failures distinguish unavailable closed invalid and missing rows`() = runBlocking {
        withServer { baseUrl ->
            val unavailable = post(baseUrl, "/test/action/updates_refresh", "{}")
            assertEquals(503, unavailable.statusCode())
            assertTrue(unavailable.body().contains("UPDATES_OWNER_UNAVAILABLE"))

            val controller = UpdatesTestModeController(model(FakeUpdatesRepository()).model)
            UpdatesTestModeBridge.install(controller)
            try {
                val invalid = post(
                    baseUrl,
                    "/test/action/updates_filter",
                    """{"type":"unknown","enabled":true}""",
                )
                assertEquals(400, invalid.statusCode())
                assertTrue(invalid.body().contains("INVALID_PARAMETER"))

                val missing = post(baseUrl, "/test/action/updates_download", """{"index":0}""")
                assertEquals(404, missing.statusCode())
                assertTrue(missing.body().contains("ROW_NOT_FOUND"))

                controller.close()
                UpdatesTestModeBridge.install(controller)
                val closed = post(baseUrl, "/test/action/updates_refresh", "{}")
                assertEquals(503, closed.statusCode())
                assertTrue(closed.body().contains("OWNER_CLOSED"))
            } finally {
                UpdatesTestModeBridge.clear(controller)
            }
        }
    }

    private fun model(repository: FakeUpdatesRepository): ModelFixture {
        val enqueued = mutableListOf<DownloadQueueEntry>()
        val downloads = object : DownloadRepository {
            override val queueEntries = flowOf(emptyList<DownloadQueueEntry>())
            override fun enqueue(entry: DownloadQueueEntry) {
                enqueued += entry
            }
            override fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String) = false
            override fun cancel(chapterId: Long) = false
            override fun retry(chapterId: Long) = false
            override fun transition(chapterId: Long, target: DownloadQueueStatus) = false
            override fun recover() = emptyList<DownloadQueueEntry>()
        }
        val mangaRepository = FakeMangaRepository().also {
            it.seed(Manga.create().copy(id = 10, source = 1, url = "/manga", title = "Manga"))
        }
        return ModelFixture(
            model = UpdatesScreenModel(
            GetUpdates(repository),
            UpdateChapter(FakeChapterRepository()),
            GetManga(mangaRepository),
            UpdatesPreferences(InMemoryPreferenceStore()),
            IsChapterDownloaded(downloads),
            EnqueueDownload(downloads),
            ),
            enqueued = enqueued,
        )
    }

    private data class ModelFixture(
        val model: UpdatesScreenModel,
        val enqueued: List<DownloadQueueEntry>,
    )

    private fun update(id: Long, read: Boolean = false) = UpdatesWithRelations(
        mangaId = 10,
        mangaTitle = "Manga",
        chapterId = id,
        chapterName = "Chapter $id",
        scanlator = null,
        chapterUrl = "/chapter/$id",
        read = read,
        bookmark = false,
        lastPageRead = 0,
        sourceId = 1,
        dateFetch = System.currentTimeMillis(),
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
