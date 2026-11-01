package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.test.state.applicationState
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.imageio.ImageIO

class TestHttpServerJsonTest {

    @Test
    fun `online 200 page fixture serves one encoded wide reset and echoes its configuration`() = runBlocking {
        withReaderFixtureServer { base, client, controller ->
            val response = readerFixtureAction(
                base,
                client,
                """
                {"mangaId":42,"readerFixture":"online","pageCount":200,
                 "width":1200,"height":1800,"widePageIndex":21}
                """.trimIndent(),
            )
            assertEquals(200, response.statusCode(), response.body())
            for ((page, dimensions) in listOf(21 to (1800 to 1200), 0 to (1200 to 1800), 125 to (1200 to 1800))) {
                assertEquals(dimensions, fixtureImageDimensions(base, client, controller, page))
            }
            val state = client.send(
                HttpRequest.newBuilder(URI.create("$base/test/reader/state")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val descriptor = Json.parseToJsonElement(state.body()).jsonObject.getValue("readerFixture").jsonObject
            assertEquals(200, descriptor.getValue("pageCount").jsonPrimitive.content.toInt())
            assertEquals(1200, descriptor.getValue("width").jsonPrimitive.content.toInt())
            assertEquals(1800, descriptor.getValue("height").jsonPrimitive.content.toInt())
            assertEquals(21, descriptor.getValue("widePageIndex").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `online wide reset below the old cap changes only the requested encoded page`() = runBlocking {
        withReaderFixtureServer { base, client, controller ->
            val response = readerFixtureAction(
                base,
                client,
                """{"mangaId":42,"readerFixture":"online","pageCount":24,"width":12,"height":18,"widePageIndex":21}""",
            )
            assertEquals(200, response.statusCode(), response.body())
            assertEquals(18 to 12, fixtureImageDimensions(base, client, controller, 21))
            assertEquals(12 to 18, fixtureImageDimensions(base, client, controller, 20))
            assertEquals(12 to 18, fixtureImageDimensions(base, client, controller, 22))
            val unchanged = readerFixtureAction(
                base,
                client,
                """{"mangaId":42,"readerFixture":"online","pageCount":24,"width":12,"height":18}""",
            )
            assertEquals(200, unchanged.statusCode(), unchanged.body())
            assertEquals(12 to 18, fixtureImageDimensions(base, client, controller, 21))
            assertArrayEquals(
                fixtureImageBytes(base, client, controller, 0),
                fixtureImageBytes(base, client, controller, 21),
                "Omitting the optional wide page preserves the original uniform encoded bytes",
            )
        }
    }

    @Test
    fun `wide reset rejects bad indices sources and nonportrait bases while omission keeps defaults`() = runBlocking {
        withReaderFixtureServer { base, client, controller ->
            val default = readerFixtureAction(base, client, """{"mangaId":42}""")
            assertEquals(200, default.statusCode(), default.body())
            val original = requireNotNull(controller.fixtureDescriptor())
            assertEquals(ReaderTestFixtureSource.DOWNLOADED_DIRECTORY, original.spec.source)
            assertEquals(3, original.spec.pageCount)
            assertEquals(16, original.spec.width)
            assertEquals(24, original.spec.height)
            val state = client.send(
                HttpRequest.newBuilder(URI.create("$base/test/reader/state")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(
                false,
                "widePageIndex" in Json.parseToJsonElement(state.body()).jsonObject
                    .getValue("readerFixture").jsonObject,
            )
            val invalid = listOf(
                """"readerFixture":"online","pageCount":201""",
                *listOf("-1", "24", "21.5", "2147483648", "null", "true", "\"bad\"", "\"\"").map {
                    """"readerFixture":"online","pageCount":24,"widePageIndex":$it"""
                }.toTypedArray(),
                *listOf("downloaded_directory", "downloaded_cbz", "local_archive", "partial_download").map {
                    """"readerFixture":"$it","pageCount":24,"widePageIndex":21"""
                }.toTypedArray(),
                """"readerFixture":"online","pageCount":24,"width":24,"height":16,"widePageIndex":21""",
                """"readerFixture":"online","pageCount":24,"width":16,"height":16,"widePageIndex":21""",
            )
            for (fields in invalid) {
                val response = readerFixtureAction(base, client, """{"mangaId":42,$fields}""")
                assertEquals(400, response.statusCode(), fields + ": " + response.body())
                assertEquals(original, controller.fixtureDescriptor(), "Rejected input cannot replace the fixture")
            }
            for (page in listOf(0, 23)) {
                val response = readerFixtureAction(
                    base,
                    client,
                    """
                    {"mangaId":42,"readerFixture":"online","pageCount":24,
                     "width":12,"height":18,"widePageIndex":$page}
                    """.trimIndent(),
                )
                assertEquals(200, response.statusCode(), response.body())
                assertEquals(18 to 12, fixtureImageDimensions(base, client, controller, page))
            }
        }
    }

    private suspend fun withReaderFixtureServer(
        block: suspend (String, HttpClient, ReaderTestModeController) -> Unit,
    ) {
        val controller = ReaderTestModeController()
        ReaderIoTestModeBridge.install(controller)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            block("http://127.0.0.1:$port", HttpClient.newHttpClient(), controller)
        } finally {
            server.stop(0, 0)
            TestNavigationController.reset()
            applicationState.reset()
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
        }
    }

    private fun readerFixtureAction(base: String, client: HttpClient, body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("$base/test/action/read_chapter"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun fixtureImageDimensions(
        base: String,
        client: HttpClient,
        controller: ReaderTestModeController,
        page: Int,
    ): Pair<Int, Int> {
        val bytes = fixtureImageBytes(base, client, controller, page)
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        return image.width to image.height
    }

    private fun fixtureImageBytes(
        base: String,
        client: HttpClient,
        controller: ReaderTestModeController,
        page: Int,
    ): ByteArray {
        val token = requireNotNull(controller.fixtureDescriptor()).token
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$base/test/reader/fixture-content/$token/$page.jpg")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, response.statusCode())
        return response.body()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, String> {
        val method = Class.forName("mihon.desktop.test.http.TestHttpServerKt")
            .getDeclaredMethod("parseJsonBody", String::class.java)
        method.isAccessible = true
        return method.invoke(null, body) as Map<String, String>
    }

    @Test
    fun `json body parser preserves urls colons and commas`() {
        val parsed = parse(
            """
            {
              "chapterUrl": "https://example.com/read/1?page=2,extra",
              "chapterTitle": "Chapter 1: The Start, Part A",
              "mangaId": 42
            }
            """.trimIndent(),
        )

        assertEquals("https://example.com/read/1?page=2,extra", parsed["chapterUrl"])
        assertEquals("Chapter 1: The Start, Part A", parsed["chapterTitle"])
        assertEquals("42", parsed["mangaId"])
    }

    @Test
    fun `screenshot endpoint is not exposed`() = runBlocking {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer()
        }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            val response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/screenshot"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"name":"feedback"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(404, response.statusCode())
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `state endpoint exposes registered screen and action capabilities`() = runBlocking {
        val previousScreens = applicationState.screens.value
        val previousActions = applicationState.actions.value
        applicationState.registerScreens(listOf("SentinelScreen"))
        applicationState.registerActions(listOf("sentinel_action"))
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/state")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val state = Json.parseToJsonElement(response.body()).jsonObject

            assertEquals(listOf("SentinelScreen"), state.getValue("screens").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                listOf("sentinel_action"),
                state.getValue("actions").jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
        } finally {
            server.stop(0, 0)
            applicationState.registerScreens(previousScreens)
            applicationState.registerActions(previousActions)
        }
    }

    @Test
    fun `history endpoint preserves external action rejection target`() = runBlocking {
        applicationState.reset()
        applicationState.recordExternalAction("Rejected", "ParserRejected")
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/history")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val record = Json.parseToJsonElement(response.body()).jsonArray.single().jsonObject

            assertEquals("ExternalActionRejected", record.getValue("action").jsonPrimitive.content)
            assertEquals(
                "ParserRejected",
                record.getValue("params").jsonObject.getValue("target").jsonPrimitive.content,
            )
        } finally {
            server.stop(0, 0)
            applicationState.reset()
        }
    }

    @Test
    fun `reader action accepts the standard fixture matrix without collapsing source routes`() = runBlocking {
        val controller = ReaderTestModeController()
        ReaderIoTestModeBridge.install(controller)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            val fixtureSources = listOf("downloaded_directory", "downloaded_cbz", "local_archive", "online")

            fixtureSources.forEachIndexed { index, source ->
                client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
                val actionResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/action/read_chapter"))
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """
                                {
                                  "mangaId": 42,
                                  "chapterId": ${700 + index},
                                  "chapterTitle": "RUA-07B $source",
                                  "readerFixture": "$source",
                                  "dualPage": true,
                                  "pageCount": 180,
                                  "width": 2400,
                                  "height": 3500,
                                  "format": "JPEG"
                                }
                                """.trimIndent(),
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, actionResponse.statusCode(), "$source action failed: ${actionResponse.body()}")

                // The test-only screen label is an acknowledgement, not evidence that production Reader UI mounted.
                assertEquals("ReaderScreen", applicationState.currentScreen.value, source)
                val readerScreen = assertInstanceOf(
                    DesktopReaderScreen::class.java,
                    TestNavigationController.pendingScreenRequest.value?.screen,
                    "$source must request the real Reader screen through HomeScreen's outer navigator",
                )
                assertEquals(
                    listOf(readerScreen),
                    TestNavigationController.pushedScreens.value,
                    "$source must publish exactly one Reader navigation request",
                )
                assertEquals(42L, readerScreen.mangaId)
                assertEquals(true, readerScreen.isDualPage)

                val stateResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/reader/state")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val state = Json.parseToJsonElement(stateResponse.body()).jsonObject
                val fixture = state.getValue("readerFixture").jsonObject

                assertEquals(0, state.getValue("totalPages").jsonPrimitive.content.toInt(), source)
                assertEquals(kotlinx.serialization.json.JsonNull, state.getValue("mountedReader"), source)
                assertEquals(source, fixture.getValue("source").jsonPrimitive.content)
                assertEquals(180, fixture.getValue("pageCount").jsonPrimitive.content.toInt())
                assertEquals(2400, fixture.getValue("width").jsonPrimitive.content.toInt())
                assertEquals(3500, fixture.getValue("height").jsonPrimitive.content.toInt())
                assertEquals("JPEG", fixture.getValue("format").jsonPrimitive.content)
            }
            val invalidMode = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/action/read_chapter"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"mangaId":42,"dualPage":"invalid"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, invalidMode.statusCode())
            assertEquals(
                42L,
                (TestNavigationController.pendingScreenRequest.value?.screen as DesktopReaderScreen).mangaId,
            )
        } finally {
            server.stop(0, 0)
            TestNavigationController.reset()
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
        }
    }

    @Test
    fun `reader state exposes the complete monotonic first frame event contract`() = runBlocking {
        val controller = ReaderTestModeController()
        ReaderIoTestModeBridge.install(controller)
        listOf(
            event(ReaderIoEventType.OPEN_READER_INTENT, 10L, null, ReaderIoPurpose.READER_OPEN),
            event(ReaderIoEventType.PAGE_LIST_READY, 12L, null, ReaderIoPurpose.PAGE_LIST),
            event(ReaderIoEventType.OPEN_PAGE, 15L, 0, ReaderIoPurpose.CURRENT_PAGE),
            event(ReaderIoEventType.DECODE, 18L, 0, ReaderIoPurpose.VISIBLE_DECODE),
            event(ReaderIoEventType.FIRST_PAGE_PRESENTED, 21L, 0, ReaderIoPurpose.FIRST_PRESENTATION),
        ).forEach(controller::record)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/reader/state")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val state = Json.parseToJsonElement(response.body()).jsonObject
            val events = state.getValue("productionEvents").jsonArray.map { it.jsonObject }

            assertEquals(true, state.getValue("firstPagePresented").jsonPrimitive.content.toBoolean())
            assertEquals(
                listOf("OPEN_READER_INTENT", "PAGE_LIST_READY", "OPEN_PAGE", "DECODE", "FIRST_PAGE_PRESENTED"),
                events.map { it.getValue("type").jsonPrimitive.content },
            )
            events.forEach { event ->
                assertEquals(7L, event.getValue("chapterId").jsonPrimitive.content.toLong())
                assertEquals(1L, event.getValue("generation").jsonPrimitive.content.toLong())
            }
            assertEquals(
                11L,
                events.last().getValue("monotonicNanos").jsonPrimitive.content.toLong() -
                    events.first().getValue("monotonicNanos").jsonPrimitive.content.toLong(),
            )
            assertEquals("FIRST_PRESENTATION", events.last().getValue("purpose").jsonPrimitive.content)
            assertEquals(0, events.last().getValue("pageIndex").jsonPrimitive.content.toInt())
        } finally {
            server.stop(0, 0)
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
        }
    }

    @Test
    fun `reader state freezes online route counters when the first page is presented`() = runBlocking {
        val controller = ReaderTestModeController()
        ReaderIoTestModeBridge.install(controller)
        val fixture = controller.prepareFixture(
            spec = ReaderTestFixtureSpec(
                source = ReaderTestFixtureSource.ONLINE,
                pageCount = 2,
                width = 16,
                height = 24,
                format = ReaderTestImageFormat.JPEG,
            ),
            mangaId = 42L,
            chapterId = 7L,
            chapterTitle = "Frozen first-frame route counters",
        )
        controller.onlinePageUrls(fixture.chapterUrl)
        controller.onlineImage(fixture.token, 0)
        controller.record(
            event(ReaderIoEventType.FIRST_PAGE_PRESENTED, 21L, 0, ReaderIoPurpose.FIRST_PRESENTATION),
        )
        controller.onlinePageUrls(fixture.chapterUrl)
        controller.onlineImage(fixture.token, 1)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/reader/state")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val state = Json.parseToJsonElement(response.body()).jsonObject

            assertEquals(true, state.getValue("firstPagePresented").jsonPrimitive.content.toBoolean())
            assertEquals(1, state.getValue("sourcePageListCalls").jsonPrimitive.content.toInt())
            assertEquals(1, state.getValue("onlineImageRequests").jsonPrimitive.content.toInt())
        } finally {
            server.stop(0, 0)
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
        }
    }

    private fun event(
        type: ReaderIoEventType,
        monotonicNanos: Long,
        pageIndex: Int?,
        purpose: ReaderIoPurpose,
    ) = ReaderIoEvent(
        type = type,
        monotonicNanos = monotonicNanos,
        chapterId = ReaderChapterId(7L),
        pageId = pageIndex?.let { ReaderPageId(ReaderChapterId(7L), it) },
        generation = 1L,
        purpose = purpose,
    )
}
