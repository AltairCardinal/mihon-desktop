package mihon.test.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopFinalParityReaderClientContractTest {
    private val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).parent
    private val client = repositoryRoot.resolve("test-desktop/src/main/python/mihon_desktop_final_parity_client.py")
    private val inventory = repositoryRoot.resolve("app-desktop/src/test/resources/parity/test-mode-coverage-inventory.json")
    private val fixtureSources =
        listOf("downloaded_directory", "downloaded_cbz", "local_archive", "online")

    @Test
    fun `external client drives the standard reader fixture matrix through live Test Mode`() {
        FakeReaderTestMode(brokenProductionContent = false).use { server ->
            val output = createTempDirectory("mihon-reader-client-success").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(0, result.exitCode, result.output)
            assertEquals(fixtureSources, server.requests.map(ReaderFixtureRequest::source))
            server.requests.forEach { request ->
                assertEquals(180, request.pageCount)
                assertEquals(2400, request.width)
                assertEquals(3500, request.height)
                assertEquals("JPEG", request.format)
            }
            assertEquals(fixtureSources.size, server.resetCalls.get())
            assertEquals(fixtureSources.size, server.closeCalls.get())
            assertTrue(server.stateCalls.get() >= fixtureSources.size)
            assertTrue(output.exists(), "client did not publish its final-parity summary")
            val summary = Json.parseToJsonElement(Files.readString(output)).jsonObject
            val reader = summary.getValue("families").jsonArray
                .map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "reader" }
            assertEquals("PASS", reader.getValue("status").jsonPrimitive.content)
        }
    }

    @Test
    fun `external client fails when synthetic first presented state bypasses production page IO and decode`() {
        FakeReaderTestMode(brokenProductionContent = true).use { server ->
            val output = createTempDirectory("mihon-reader-client-broken").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(server.requests.isNotEmpty(), "client never called the live read_chapter action")
            assertTrue(server.stateCalls.get() > 0, "client never read live productionEvents")
            assertTrue(
                result.output.contains("OPEN_PAGE") || result.output.contains("DECODE"),
                "failure must identify the missing production content event: ${result.output}",
            )
        }
    }

    @Test
    fun `external client rejects downloaded directory that falls through to online source IO`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            downloadedDirectoryLeaksOnlineIo = true,
        ).use { server ->
            val output = createTempDirectory("mihon-reader-client-route-proof").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains("downloaded_directory"), result.output)
            assertTrue(
                result.output.contains("sourcePageListCalls") || result.output.contains("onlineImageRequests"),
                "failure must identify the violated production route counter: ${result.output}",
            )
        }
    }

    @Test
    fun `external client accepts the upstream online current plus nearby request window`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            onlineFirstFrameImageRequests = 5,
        ).use { server ->
            val output = createTempDirectory("mihon-reader-client-online-window").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(0, result.exitCode, result.output)
        }
    }

    @Test
    fun `external client rejects online requests beyond the upstream nearby window`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            onlineFirstFrameImageRequests = 6,
        ).use { server ->
            val output = createTempDirectory("mihon-reader-client-online-overflow").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains("onlineImageRequests"), result.output)
        }
    }

    @Test
    fun `external client rejects non-current decode before the first frame`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            invalidDecode = InvalidDecode.NON_CURRENT,
        ).use { server ->
            val output = createTempDirectory("mihon-reader-client-non-current-decode").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains("DECODE"), result.output)
            assertTrue(result.output.contains("pageIndex") || result.output.contains("non-current"), result.output)
        }
    }

    @Test
    fun `external client rejects decode without a page index before the first frame`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            invalidDecode = InvalidDecode.MISSING_PAGE_INDEX,
        ).use { server ->
            val output = createTempDirectory("mihon-reader-client-missing-decode-index").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains("DECODE"), result.output)
            assertTrue(result.output.contains("pageIndex") || result.output.contains("non-current"), result.output)
        }
    }

    @Test
    fun `external client requires the unique first presented event to identify page zero`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            firstPresentedPageIndex = 1,
        ).use { server ->
            val output = createTempDirectory("mihon-reader-client-first-page-index").resolve("summary.json")

            val result = runClient(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains("FIRST_PAGE_PRESENTED"), result.output)
            assertTrue(result.output.contains("pageIndex") || result.output.contains("page 0"), result.output)
        }
    }

    @Test
    fun `external close waits until Test Mode confirms production reader closure`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            productionCloseConfirmationPolls = 2,
        ).use { server ->
            val result = runCloseReaderClient(server.baseUrl, timeoutSeconds = 1.0)

            assertEquals(0, result.exitCode, result.output)
            assertEquals(1, server.closeCalls.get())
            assertEquals(3, server.closeStateCalls.get())
            assertEquals(2, server.unconfirmedCloseStateCalls.get())
        }
    }

    @Test
    fun `external close fails explicitly when production reader closure is never confirmed`() {
        FakeReaderTestMode(
            brokenProductionContent = false,
            neverConfirmProductionClose = true,
        ).use { server ->
            val result = runCloseReaderClient(server.baseUrl, timeoutSeconds = 0.1)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(
                result.output.contains("timed out waiting for productionClosed"),
                result.output,
            )
            assertTrue(server.closeStateCalls.get() > 1, "client did not poll production close state")
        }
    }

    @Test
    fun `final client preserves first frame timeout when required close cleanup also times out`() {
        val tempDirectory = createTempDirectory("mihon-reader-client-primary-failure")
        val output = tempDirectory.resolve("summary.json")
        val closeMarker = tempDirectory.resolve("close-attempted.txt")

        val result = runFixtureAndCleanupFailureClient(
            output = output,
            closeMarker = closeMarker,
            fixtureFails = true,
        )

        assertEquals(1, result.exitCode, result.output)
        assertTrue(
            result.output.contains("downloaded_directory: timed out waiting for FIRST_PAGE_PRESENTED"),
            "the original fixture failure must remain authoritative: ${result.output}",
        )
        assertTrue(
            !result.output.contains("Final parity client rejected input: timed out waiting for productionClosed=true"),
            "cleanup failure replaced the original fixture failure: ${result.output}",
        )
        assertTrue(closeMarker.exists(), "required close cleanup was not attempted")
    }

    @Test
    fun `final client preserves required close failure after a successful fixture`() {
        val tempDirectory = createTempDirectory("mihon-reader-client-close-failure")
        val output = tempDirectory.resolve("summary.json")
        val closeMarker = tempDirectory.resolve("close-attempted.txt")

        val result = runFixtureAndCleanupFailureClient(
            output = output,
            closeMarker = closeMarker,
            fixtureFails = false,
        )

        assertEquals(1, result.exitCode, result.output)
        assertTrue(
            result.output.contains("Final parity client rejected input: timed out waiting for productionClosed=true"),
            "the required close failure must remain authoritative: ${result.output}",
        )
        assertTrue(closeMarker.exists(), "required close was not attempted")
        assertTrue(!output.exists(), "a failed required close must not publish a success summary")
    }

    private fun runClient(baseUrl: String, output: Path): RunResult {
        val python = System.getenv("MIHON_PYTHON")?.takeIf(String::isNotBlank) ?: "python"
        val process = ProcessBuilder(
            python,
            client.toString(),
            "--inventory",
            inventory.toString(),
            "--output",
            output.toString(),
            "--base-url",
            baseUrl,
        )
            .directory(repositoryRoot.toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONUTF8"] = "1"
                it.environment()["PYTHONIOENCODING"] = "utf-8"
            }
            .start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            return RunResult(-1, "final-parity client timed out")
        }
        return RunResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun runCloseReaderClient(baseUrl: String, timeoutSeconds: Double): RunResult {
        val python = System.getenv("MIHON_PYTHON")?.takeIf(String::isNotBlank) ?: "python"
        val process = ProcessBuilder(
            python,
            "-c",
            "import sys; from reader_test_mode import ReaderTestModeClient; " +
                "ReaderTestModeClient(sys.argv[1], float(sys.argv[2])).close_reader()",
            baseUrl,
            timeoutSeconds.toString(),
        )
            .directory(repositoryRoot.resolve("test-desktop/src/main/python").toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONUTF8"] = "1"
                it.environment()["PYTHONIOENCODING"] = "utf-8"
            }
            .start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            return RunResult(-1, "reader close client timed out")
        }
        return RunResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun runFixtureAndCleanupFailureClient(
        output: Path,
        closeMarker: Path,
        fixtureFails: Boolean,
    ): RunResult {
        val python = System.getenv("MIHON_PYTHON")?.takeIf(String::isNotBlank) ?: "python"
        val script =
            """
            import pathlib
            import sys
            import mihon_desktop_final_parity_client as target

            close_marker = pathlib.Path(sys.argv[1])
            fixture_fails = sys.argv[4].lower() == "true"

            class FailingReaderClient:
                def __init__(self, base_url):
                    pass

                def run_fixture(self, source, chapter_id):
                    if fixture_fails:
                        raise target.ReaderContractError(
                            f"{source}: timed out waiting for FIRST_PAGE_PRESENTED"
                        )

                def close_reader(self):
                    close_marker.write_text("attempted", encoding="utf-8")
                    raise target.ReaderContractError(
                        "timed out waiting for productionClosed=true"
                    )

            target.ReaderTestModeClient = FailingReaderClient
            sys.argv = [
                "mihon_desktop_final_parity_client.py",
                "--inventory",
                sys.argv[2],
                "--output",
                sys.argv[3],
                "--base-url",
                "http://unused.invalid",
            ]
            raise SystemExit(target.main())
            """.trimIndent()
        val scriptPath = output.parent.resolve("fixture-and-cleanup-failure-client.py")
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8)
        val process = ProcessBuilder(
            python,
            scriptPath.toString(),
            closeMarker.toString(),
            inventory.toString(),
            output.toString(),
            fixtureFails.toString(),
        )
            .directory(repositoryRoot.resolve("test-desktop/src/main/python").toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONUTF8"] = "1"
                it.environment()["PYTHONIOENCODING"] = "utf-8"
                it.environment()["PYTHONPATH"] =
                    repositoryRoot.resolve("test-desktop/src/main/python").toString()
            }
            .start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            return RunResult(-1, "fixture and cleanup failure client timed out")
        }
        return RunResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private inner class FakeReaderTestMode(
        private val brokenProductionContent: Boolean,
        private val downloadedDirectoryLeaksOnlineIo: Boolean = false,
        private val invalidDecode: InvalidDecode? = null,
        private val firstPresentedPageIndex: Int = 0,
        private val onlineFirstFrameImageRequests: Int = 1,
        private val productionCloseConfirmationPolls: Int = 0,
        private val neverConfirmProductionClose: Boolean = false,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<ReaderFixtureRequest>()
        val resetCalls = AtomicInteger()
        val stateCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val closeStateCalls = AtomicInteger()
        val unconfirmedCloseStateCalls = AtomicInteger()
        private val closeRequested = AtomicBoolean()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            start()
        }
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            when (exchange.requestMethod to exchange.requestURI.path) {
                "POST" to "/test/reset" -> {
                    resetCalls.incrementAndGet()
                    closeRequested.set(false)
                    closeStateCalls.set(0)
                    exchange.respond("""{"success":true}""")
                }
                "POST" to "/test/action/read_chapter" -> {
                    val body = Json.parseToJsonElement(
                        exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText(),
                    ).jsonObject
                    requests += ReaderFixtureRequest(
                        source = body.getValue("readerFixture").jsonPrimitive.content,
                        chapterId = body.getValue("chapterId").jsonPrimitive.content.toLong(),
                        pageCount = body.getValue("pageCount").jsonPrimitive.content.toInt(),
                        width = body.getValue("width").jsonPrimitive.content.toInt(),
                        height = body.getValue("height").jsonPrimitive.content.toInt(),
                        format = body.getValue("format").jsonPrimitive.content,
                    )
                    exchange.respond("""{"success":true,"action":"read_chapter"}""")
                }
                "GET" to "/test/reader/state" -> {
                    stateCalls.incrementAndGet()
                    exchange.respond(readerState(productionClosed()).toString())
                }
                "POST" to "/test/reader/close" -> {
                    closeCalls.incrementAndGet()
                    closeStateCalls.set(0)
                    closeRequested.set(true)
                    exchange.respond("""{"success":true}""")
                }
                else -> exchange.respond("""{"success":false,"error":"NOT_FOUND"}""", status = 404)
            }
        }

        private fun productionClosed(): Boolean {
            if (!closeRequested.get()) return false
            val poll = closeStateCalls.incrementAndGet()
            val closed = !neverConfirmProductionClose && poll > productionCloseConfirmationPolls
            if (!closed) unconfirmedCloseStateCalls.incrementAndGet()
            return closed
        }

        private fun readerState(productionClosed: Boolean): JsonObject {
            val request = requests.lastOrNull()
            if (request == null) {
                return buildJsonObject {
                    put("productionClosed", JsonPrimitive(productionClosed))
                }
            }
            val offlineRouteLeak = request.source == "downloaded_directory" && downloadedDirectoryLeaksOnlineIo
            val sourcePageListCalls = if (request.source == "online" || offlineRouteLeak) 1 else 0
            val onlineImageRequests = when {
                offlineRouteLeak -> 1
                request.source == "online" -> onlineFirstFrameImageRequests
                else -> 0
            }
            val events = if (brokenProductionContent) {
                listOf(
                    event("OPEN_READER_INTENT", 100L, null, "READER_OPEN", request.chapterId),
                    event("FIRST_PAGE_PRESENTED", 105L, 0, "FIRST_PRESENTATION", request.chapterId),
                )
            } else {
                buildList {
                    add(event("OPEN_READER_INTENT", 100L, null, "READER_OPEN", request.chapterId))
                    add(event("PAGE_LIST_READY", 110L, null, "PAGE_LIST", request.chapterId))
                    add(event("OPEN_PAGE", 120L, 0, "CURRENT_PAGE", request.chapterId))
                    add(event("DECODE", 130L, 0, "VISIBLE_DECODE", request.chapterId))
                    when (invalidDecode) {
                        InvalidDecode.NON_CURRENT -> {
                            add(event("DECODE", 135L, 1, "VISIBLE_DECODE", request.chapterId))
                        }
                        InvalidDecode.MISSING_PAGE_INDEX -> {
                            add(event("DECODE", 135L, null, "VISIBLE_DECODE", request.chapterId))
                        }
                        null -> Unit
                    }
                    add(
                        event(
                            "FIRST_PAGE_PRESENTED",
                            140L,
                            firstPresentedPageIndex,
                            "FIRST_PRESENTATION",
                            request.chapterId,
                        ),
                    )
                }
            }
            return buildJsonObject {
                put("isOpen", JsonPrimitive(true))
                put("productionClosed", JsonPrimitive(productionClosed))
                put("totalPages", JsonPrimitive(request.pageCount))
                put("firstPagePresented", JsonPrimitive(true))
                put("productionEvents", JsonArray(events))
                put("sourcePageListCalls", JsonPrimitive(sourcePageListCalls))
                put("onlineImageRequests", JsonPrimitive(onlineImageRequests))
                put(
                    "readerFixture",
                    buildJsonObject {
                        put("source", JsonPrimitive(request.source))
                        put("pageCount", JsonPrimitive(request.pageCount))
                        put("width", JsonPrimitive(request.width))
                        put("height", JsonPrimitive(request.height))
                        put("format", JsonPrimitive(request.format))
                    },
                )
            }
        }

        private fun event(
            type: String,
            monotonicNanos: Long,
            pageIndex: Int?,
            purpose: String,
            chapterId: Long,
        ) = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("monotonicNanos", JsonPrimitive(monotonicNanos))
            put("chapterId", JsonPrimitive(chapterId))
            put("pageIndex", pageIndex?.let(::JsonPrimitive) ?: JsonNull)
            put("generation", JsonPrimitive(1L))
            put("purpose", JsonPrimitive(purpose))
        }

        private fun HttpExchange.respond(body: String, status: Int = 200) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class ReaderFixtureRequest(
        val source: String,
        val chapterId: Long,
        val pageCount: Int,
        val width: Int,
        val height: Int,
        val format: String,
    )

    private data class RunResult(val exitCode: Int, val output: String)

    private enum class InvalidDecode {
        NON_CURRENT,
        MISSING_PAGE_INDEX,
    }
}
