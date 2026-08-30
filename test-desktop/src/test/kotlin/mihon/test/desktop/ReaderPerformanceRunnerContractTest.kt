package mihon.test.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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

class ReaderPerformanceRunnerContractTest {
    private val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).parent
    private val runner = repositoryRoot.resolve("scripts/reader-performance.py")

    @Test
    fun `runner measures one warmup and twenty samples for both downloaded routes`() {
        FakeReaderPerformanceServer().use { server ->
            val output = createTempDirectory("mihon-reader-performance").resolve("report.json")

            val result = runRunner(server.baseUrl, output)

            assertEquals(0, result.exitCode, result.output)
            assertEquals(21, server.requests.count { it == DIRECTORY })
            assertEquals(21, server.requests.count { it == CBZ })
            assertTrue(output.exists(), "runner did not publish its performance report")
            val report = Json.parseToJsonElement(Files.readString(output)).jsonObject
            assertEquals(1, report.getValue("warmups").jsonPrimitive.content.toInt())
            assertEquals(20, report.getValue("iterations").jsonPrimitive.content.toInt())
            val scenarios = report.getValue("scenarios").jsonArray
                .map { it.jsonObject }
                .associateBy { it.getValue("source").jsonPrimitive.content }
            assertEquals(setOf(DIRECTORY, CBZ), scenarios.keys)
            assertScenarioReport(scenarios.getValue(DIRECTORY), expectedP95Millis = 500.0, expectedMaxMillis = 500.0)
            assertScenarioReport(scenarios.getValue(CBZ), expectedP95Millis = 1_000.0, expectedMaxMillis = 1_000.0)
        }
    }

    @Test
    fun `runner exits one when a real timing sample exceeds the route budget`() {
        FakeReaderPerformanceServer(slowDirectory = true).use { server ->
            val output = createTempDirectory("mihon-reader-performance-slow").resolve("report.json")

            val result = runRunner(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains(DIRECTORY), result.output)
            assertTrue(result.output.contains("2000") || result.output.contains("2.0"), result.output)
        }
    }

    @Test
    fun `runner exits one when first frame omits required production IO or decode`() {
        FakeReaderPerformanceServer(missingCbzDecode = true).use { server ->
            val output = createTempDirectory("mihon-reader-performance-io").resolve("report.json")

            val result = runRunner(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains(CBZ), result.output)
            assertTrue(result.output.contains("DECODE"), result.output)
        }
    }

    @Test
    fun `runner exits one when downloaded directory falls through to online source IO`() {
        FakeReaderPerformanceServer(directoryLeaksOnlineIo = true).use { server ->
            val output = createTempDirectory("mihon-reader-performance-route-proof").resolve("report.json")

            val result = runRunner(server.baseUrl, output)

            assertEquals(1, result.exitCode, result.output)
            assertTrue(result.output.contains(DIRECTORY), result.output)
            assertTrue(
                result.output.contains("sourcePageListCalls") || result.output.contains("onlineImageRequests"),
                "failure must identify the violated production route counter: ${result.output}",
            )
        }
    }

    private fun assertScenarioReport(
        scenario: JsonObject,
        expectedP95Millis: Double,
        expectedMaxMillis: Double,
    ) {
        assertEquals("PASS", scenario.getValue("status").jsonPrimitive.content)
        assertEquals(expectedP95Millis, scenario.getValue("p95Millis").jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(expectedMaxMillis, scenario.getValue("maxMillis").jsonPrimitive.content.toDouble(), 0.001)
        val gate = scenario.getValue("ioGate").jsonObject
        assertEquals(1, gate.getValue("pageListReady").jsonPrimitive.content.toInt())
        assertEquals(1, gate.getValue("currentPageOpens").jsonPrimitive.content.toInt())
        assertEquals(1, gate.getValue("currentPageDecodes").jsonPrimitive.content.toInt())
        assertEquals(0, gate.getValue("nonCurrentPageOpens").jsonPrimitive.content.toInt())
        assertEquals(0, gate.getValue("nonCurrentPageDecodes").jsonPrimitive.content.toInt())
        assertEquals(0, gate.getValue("cacheReconciles").jsonPrimitive.content.toInt())
        assertEquals(0, gate.getValue("adjacentIo").jsonPrimitive.content.toInt())
    }

    private fun runRunner(baseUrl: String, output: Path): RunResult {
        val python = System.getenv("MIHON_PYTHON")?.takeIf(String::isNotBlank) ?: "python"
        val process = ProcessBuilder(
            python,
            runner.toString(),
            "--base-url",
            baseUrl,
            "--output",
            output.toString(),
            "--warmups",
            "1",
            "--iterations",
            "20",
        )
            .directory(repositoryRoot.toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONUTF8"] = "1"
                it.environment()["PYTHONIOENCODING"] = "utf-8"
            }
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            return RunResult(-1, "reader performance runner timed out")
        }
        return RunResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private class FakeReaderPerformanceServer(
        private val slowDirectory: Boolean = false,
        private val missingCbzDecode: Boolean = false,
        private val directoryLeaksOnlineIo: Boolean = false,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            start()
        }
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            when (exchange.requestMethod to exchange.requestURI.path) {
                "POST" to "/test/reset" -> exchange.respond("""{"success":true}""")
                "POST" to "/test/action/read_chapter" -> {
                    val body = Json.parseToJsonElement(
                        exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText(),
                    ).jsonObject
                    requests += body.getValue("readerFixture").jsonPrimitive.content
                    exchange.respond("""{"success":true,"action":"read_chapter"}""")
                }
                "GET" to "/test/reader/state" -> exchange.respond(readerState().toString())
                "POST" to "/test/reader/close" -> exchange.respond("""{"success":true}""")
                else -> exchange.respond("""{"success":false,"error":"NOT_FOUND"}""", status = 404)
            }
        }

        private fun readerState(): JsonObject {
            val source = requests.lastOrNull() ?: error("reader state requested before read_chapter")
            val offlineRouteLeak = source == DIRECTORY && directoryLeaksOnlineIo
            val durationMillis = if (source == DIRECTORY && slowDirectory) 2_100L else if (source == CBZ) 1_000L else 500L
            val chapterId = requests.size.toLong()
            val events = buildList {
                add(event("OPEN_READER_INTENT", 0L, null, "READER_OPEN", chapterId))
                add(event("PAGE_LIST_READY", 100_000_000L, null, "PAGE_LIST", chapterId))
                add(event("OPEN_PAGE", 200_000_000L, 0, "CURRENT_PAGE", chapterId))
                if (!(source == CBZ && missingCbzDecode)) {
                    add(event("DECODE", 300_000_000L, 0, "VISIBLE_DECODE", chapterId))
                }
                add(event("FIRST_PAGE_PRESENTED", durationMillis * 1_000_000L, 0, "FIRST_PRESENTATION", chapterId))
            }
            return buildJsonObject {
                put("isOpen", JsonPrimitive(true))
                put("totalPages", JsonPrimitive(180))
                put("firstPagePresented", JsonPrimitive(true))
                put("productionEvents", JsonArray(events))
                put("sourcePageListCalls", JsonPrimitive(if (offlineRouteLeak) 1 else 0))
                put("onlineImageRequests", JsonPrimitive(if (offlineRouteLeak) 1 else 0))
                put(
                    "readerFixture",
                    buildJsonObject {
                        put("source", JsonPrimitive(source))
                        put("pageCount", JsonPrimitive(180))
                        put("width", JsonPrimitive(2400))
                        put("height", JsonPrimitive(3500))
                        put("format", JsonPrimitive("JPEG"))
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

    private data class RunResult(val exitCode: Int, val output: String)

    private companion object {
        const val DIRECTORY = "downloaded_directory"
        const val CBZ = "downloaded_cbz"
    }
}
