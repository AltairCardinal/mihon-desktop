package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadStatus
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class DownloadTestModeHttpTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `pause action mutates production download owner instead of legacy test state`() = runBlocking {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        val controller = DownloadTestModeController(manager)
        DownloadTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/downloads_pause_all", "{}")

                assertTrue(manager.isPaused.value)
            }
        } finally {
            DownloadTestModeBridge.clear(controller)
            manager.stopAndJoin()
        }
    }

    @Test
    fun `download actions execute queue owner and expose errors ordering and typed failures`() = runBlocking {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        manager.enqueue(item(3, "Three"))
        manager.enqueue(item(1, "One"))
        manager.enqueue(
            item(
                2,
                "Two",
                DownloadStatus.ERROR,
                AppError.Network(IllegalStateException("offline")),
            ),
        )
        val controller = DownloadTestModeController(manager)
        DownloadTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val initial = get(baseUrl, "/test/state")
                assertEquals(3, initial.json.getValue("downloadQueueSize").jsonPrimitive.content.toInt())
                val errorRow = initial.json.getValue("downloads").jsonObject
                    .getValue("rows").jsonArray.single {
                        it.jsonObject.getValue("chapterId").jsonPrimitive.content == "2"
                    }.jsonObject
                assertEquals("Network", errorRow.getValue("failure").jsonObject.getValue("type").jsonPrimitive.content)

                assertEquals(200, post(baseUrl, "/test/action/downloads_pause_all", "{}").status)
                assertTrue(manager.isPaused.value)
                assertEquals(200, post(baseUrl, "/test/action/downloads_resume_all", "{}").status)
                assertFalse(manager.isPaused.value)

                assertEquals(
                    200,
                    post(baseUrl, "/test/action/downloads_reorder", """{"from":"0","to":"1"}""").status,
                )
                assertEquals(listOf(1L, 3L, 2L), manager.queue.value.map(DownloadItem::chapterId))
                assertEquals(
                    200,
                    post(baseUrl, "/test/action/downloads_sort", """{"by":"chapter_id"}""").status,
                )
                assertEquals(listOf(1L, 2L, 3L), manager.queue.value.map(DownloadItem::chapterId))
                assertEquals(200, post(baseUrl, "/test/action/downloads_reverse", "{}").status)
                assertEquals(listOf(3L, 2L, 1L), manager.queue.value.map(DownloadItem::chapterId))
                assertEquals(
                    400,
                    post(baseUrl, "/test/action/downloads_sort", """{"by":"unknown"}""").status,
                )

                assertEquals(200, post(baseUrl, "/test/action/downloads_retry_errors", "{}").status)
                assertEquals(DownloadStatus.QUEUED, manager.queue.value.single { it.chapterId == 2L }.status)
                assertNull(manager.queue.value.single { it.chapterId == 2L }.failure)

                manager.enqueue(item(4, "Four", DownloadStatus.ERROR, AppError.Unknown()))
                assertEquals(200, post(baseUrl, "/test/action/downloads_clear_errors", "{}").status)
                assertTrue(manager.queue.value.none { it.chapterId == 4L })

                assertEquals(
                    200,
                    post(baseUrl, "/test/action/downloads_cancel", """{"chapterId":"3"}""").status,
                )
                assertEquals(
                    404,
                    post(baseUrl, "/test/action/downloads_cancel", """{"chapterId":"99"}""").status,
                )
                assertEquals(200, post(baseUrl, "/test/action/downloads_cancel_all", "{}").status)
                assertTrue(manager.queue.value.isEmpty())
            }
        } finally {
            DownloadTestModeBridge.clear(controller)
            manager.stopAndJoin()
        }
    }

    @Test
    fun `download owner unavailable closed and partial failures are typed`() = runBlocking {
        DownloadTestModeBridge.controller?.let(DownloadTestModeBridge::clear)
        withServer { baseUrl ->
            val unavailable = post(baseUrl, "/test/action/downloads_pause_all", "{}")
            assertEquals(503, unavailable.status)
            assertEquals("DOWNLOAD_OWNER_UNAVAILABLE", unavailable.json.getValue("error").jsonPrimitive.content)
        }

        val manager = mockk<DesktopDownloadManager>()
        val paused = MutableStateFlow(false)
        val queue = MutableStateFlow(listOf(item(1, "One"), item(2, "Two")))
        every { manager.isPaused } returns paused
        every { manager.queue } returns queue
        every { manager.cancelAll() } answers {
            queue.value = queue.value.drop(1)
            throw IllegalStateException("second item rejected")
        }
        val partialController = DownloadTestModeController(manager)
        val partial = partialController.execute("downloads_cancel_all", emptyMap())
        assertFalse(partial.success)
        assertEquals(DownloadTestFailureCode.PARTIAL_FAILURE, partial.failureCode)
        assertEquals(listOf(2L), partial.snapshot.rows.map(DownloadTestRow::chapterId))

        partialController.close()
        DownloadTestModeBridge.install(partialController)
        try {
            withServer { baseUrl ->
                val closed = post(baseUrl, "/test/action/downloads_pause_all", "{}")
                assertEquals(503, closed.status)
                assertEquals("OWNER_CLOSED", closed.json.getValue("error").jsonPrimitive.content)
            }
        } finally {
            DownloadTestModeBridge.clear(partialController)
        }
    }

    @Test
    fun `cancel rejects unstable index and cancels the requested stable chapter id`() = runBlocking {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        manager.enqueue(item(1, "One"))
        manager.enqueue(item(2, "Two"))
        val controller = DownloadTestModeController(manager)
        DownloadTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val indexOnly = post(baseUrl, "/test/action/downloads_cancel", """{"index":"0"}""")
                assertEquals(400, indexOnly.status)
                assertEquals("INVALID_PARAMETER", indexOnly.json.getValue("error").jsonPrimitive.content)
                assertEquals(listOf(1L, 2L), manager.queue.value.map(DownloadItem::chapterId))

                assertEquals(
                    200,
                    post(baseUrl, "/test/action/downloads_cancel", """{"chapterId":"2"}""").status,
                )
                assertEquals(listOf(1L), manager.queue.value.map(DownloadItem::chapterId))
            }
        } finally {
            controller.close()
            manager.stopAndJoin()
        }
    }

    @Test
    fun `date added sort is rejected when manager has no stable enqueue timestamp semantics`() = runBlocking {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        manager.enqueue(item(3, "Three"))
        manager.enqueue(item(1, "One"))
        val controller = DownloadTestModeController(manager)
        DownloadTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val response = post(baseUrl, "/test/action/downloads_sort", """{"by":"date_added"}""")

                assertEquals(400, response.status)
                assertEquals("INVALID_PARAMETER", response.json.getValue("error").jsonPrimitive.content)
                assertEquals(listOf(3L, 1L), manager.queue.value.map(DownloadItem::chapterId))
            }
        } finally {
            controller.close()
            manager.stopAndJoin()
        }
    }

    private fun item(
        id: Long,
        name: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
        failure: AppError? = null,
    ) = DownloadItem(
        sourceId = 1,
        mangaTitle = "Manga",
        chapterName = name,
        chapterId = id,
        status = status,
        failure = failure,
    )

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private fun post(base: String, path: String, body: String): Response {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Response(response.statusCode(), Json.parseToJsonElement(response.body()).jsonObject)
    }

    private fun get(base: String, path: String): Response {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Response(response.statusCode(), Json.parseToJsonElement(response.body()).jsonObject)
    }

    private data class Response(val status: Int, val json: JsonObject)
}
