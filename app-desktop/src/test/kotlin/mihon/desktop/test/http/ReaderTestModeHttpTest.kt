package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.download.DefaultDownloadFileOperations
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadFileOperations
import mihon.desktop.reader.DesktopReaderPartialPageFileCopyPort
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request as OkHttpRequest
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReaderTestModeHttpTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `reader state reports production partial route generation local copy network fallback and lock counts`() =
        runBlocking {
            val provider = DesktopDownloadProvider(directory.resolve("downloads"))
            lateinit var controller: ReaderTestModeController
            val manager = DesktopDownloadManager(
                provider = provider,
                httpClient = OkHttpClient(),
                workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                retryDelay = {},
                sourceResolver = { controller.onlineSource },
                fileOperations = successfulFixtureDownloadOperations(),
            )
            controller = ReaderTestModeController(
                configuredDownloadProvider = provider,
                downloadManager = manager,
                baseUrl = "http://fixture.invalid",
            )
            ReaderIoTestModeBridge.install(controller)
            manager.start()
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
            try {
                val fixture = controller.prepareFixtureAwait(
                    spec = ReaderTestFixtureSpec(
                        source = ReaderTestFixtureSource.PARTIAL_DOWNLOAD,
                        pageCount = 6,
                        width = 16,
                        height = 24,
                        format = ReaderTestImageFormat.JPEG,
                        partialPageCount = 2,
                    ),
                    mangaId = 101L,
                    chapterId = 201L,
                    chapterTitle = "Partial chapter",
                )
                val identity = fixture.identity()
                val candidate = checkNotNull(manager.committedPageCandidate(fixture.chapterId, identity, 0, 0))
                controller.beginScenario()

                DesktopReaderPartialPageFileCopyPort(
                    leaseSource = manager.partialPageReadLeaseSource,
                    ioProbe = ReaderIoTestModeBridge,
                ).copy(candidate, directory.resolve("encoded/001.jpg"))
                checkNotNull(controller.onlineImage(fixture.token, 2))

                val port = server.resolvedConnectors().single().port
                fun readState() = Json.parseToJsonElement(
                    HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/reader/state")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).body(),
                ).jsonObject
                val state = readState()

                assertEquals("partial_download", state.getValue("route").jsonPrimitive.content)
                assertEquals(fixture.partialAttemptGeneration, state.getValue("snapshotGeneration").jsonPrimitive.content.toLong())
                assertEquals(1, state.getValue("localHits").jsonPrimitive.content.toInt())
                assertEquals(0, state.getValue("networkFallbacks").jsonPrimitive.content.toInt())
                assertEquals(1, state.getValue("partialPageProbes").jsonPrimitive.content.toInt())
                assertEquals(1, state.getValue("partialPageOpens").jsonPrimitive.content.toInt())
                assertEquals(1, state.getValue("partialPageCopies").jsonPrimitive.content.toInt())
                assertEquals(0, state.getValue("imageRequests").jsonPrimitive.content.toInt())
                assertEquals(1, state.getValue("scenarioImageRequests").jsonPrimitive.content.toInt())
                assertEquals(0, state.getValue("downloadIoLockViolations").jsonPrimitive.content.toInt())

                controller.beginScenario(currentPageIndex = 2)
                checkNotNull(controller.onlineImage(fixture.token, 2))
                val missingPageState = readState()
                assertEquals(2, missingPageState.getValue("currentPageIndex").jsonPrimitive.content.toInt())
                assertEquals(0, missingPageState.getValue("localHits").jsonPrimitive.content.toInt())
                assertEquals(1, missingPageState.getValue("networkFallbacks").jsonPrimitive.content.toInt())
                assertEquals(1, missingPageState.getValue("imageRequests").jsonPrimitive.content.toInt())
            } finally {
                server.stop(0, 0)
                ReaderIoTestModeBridge.clear(controller)
                controller.close()
                manager.stopAndJoin()
            }
        }

    private fun successfulFixtureDownloadOperations() = object : DownloadFileOperations by DefaultDownloadFileOperations {
        override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
            .request(OkHttpRequest.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("GIF89aDATA".toResponseBody())
            .build()
    }

    private fun ReaderTestFixtureDescriptor.identity() = mihon.domain.reader.content.DownloadChapterIdentity(
        sourceDisplayName = ReaderTestModeController.READER_TEST_SOURCE_NAME,
        mangaTitle = mangaTitle,
        chapterName = chapterTitle,
        scanlator = null,
        chapterUrl = chapterUrl,
        disallowNonAsciiFilenames = false,
    )
}
