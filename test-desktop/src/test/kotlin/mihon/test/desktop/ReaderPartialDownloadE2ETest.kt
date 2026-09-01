package mihon.test.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPartialDownloadE2ETest {
    private val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).parent

    @Test
    fun `external Test Mode contract accepts one production local hit with zero image network`() {
        val result = validate(partialState())

        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun `external Test Mode contract rejects a valid local current page that falls through to network`() {
        val result = validate(
            partialState().toMutableMap().let { fields ->
                fields["networkFallbacks"] = JsonPrimitive(1)
                fields["imageRequests"] = JsonPrimitive(1)
                JsonObject(fields)
            },
        )

        assertEquals(1, result.exitCode, result.output)
        assertTrue(result.output.contains("network") || result.output.contains("imageRequests"), result.output)
    }

    @Test
    fun `external Test Mode contract rejects counters attributed to a different current page`() {
        val result = validate(
            partialState().toMutableMap().let { fields ->
                fields["currentPageIndex"] = JsonPrimitive(1)
                JsonObject(fields)
            },
        )

        assertEquals(1, result.exitCode, result.output)
        assertTrue(result.output.contains("currentPageIndex"), result.output)
    }

    private fun validate(state: JsonObject): RunResult {
        val input = createTempDirectory("mihon-partial-reader-e2e").resolve("state.json")
        Files.writeString(input, state.toString())
        val python = System.getenv("MIHON_PYTHON")?.takeIf(String::isNotBlank) ?: "python"
        val process = ProcessBuilder(
            python,
            "-c",
            "import json,sys; from reader_test_mode import validate_reader_state; " +
                "validate_reader_state(json.load(open(sys.argv[1], encoding='utf-8')), 'partial_download')",
            input.toString(),
        )
            .directory(repositoryRoot.toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONUTF8"] = "1"
                it.environment()["PYTHONIOENCODING"] = "utf-8"
                it.environment()["PYTHONPATH"] = repositoryRoot.resolve("test-desktop/src/main/python").toString()
            }
            .start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            return RunResult(-1, "partial reader validator timed out")
        }
        return RunResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun partialState() = buildJsonObject {
        put("readerFixture", buildJsonObject {
            put("source", JsonPrimitive("partial_download"))
            put("pageCount", JsonPrimitive(180))
            put("width", JsonPrimitive(2400))
            put("height", JsonPrimitive(3500))
            put("format", JsonPrimitive("JPEG"))
            put("partialPageCount", JsonPrimitive(5))
        })
        put("sourcePageListCalls", JsonPrimitive(0))
        put("onlineImageRequests", JsonPrimitive(0))
        put("route", JsonPrimitive("partial_download"))
        put("currentPageIndex", JsonPrimitive(0))
        put("snapshotGeneration", JsonPrimitive(9L))
        put("localHits", JsonPrimitive(1))
        put("networkFallbacks", JsonPrimitive(0))
        put("partialPageProbes", JsonPrimitive(1))
        put("partialPageOpens", JsonPrimitive(1))
        put("partialPageCopies", JsonPrimitive(1))
        put("imageRequests", JsonPrimitive(0))
        put("scenarioPartialPageCopies", JsonPrimitive(5))
        put("scenarioImageRequests", JsonPrimitive(0))
        put("downloadIoLockViolations", JsonPrimitive(0))
        put(
            "productionEvents",
            JsonArray(
                listOf(
                    event("OPEN_READER_INTENT", 100L, null, "READER_OPEN"),
                    event("PAGE_LIST_READY", 110L, null, "PAGE_LIST"),
                    event("OPEN_PAGE", 120L, 0, "CURRENT_PAGE"),
                    event("DECODE", 130L, 0, "VISIBLE_DECODE"),
                    event("FIRST_PAGE_PRESENTED", 140L, 0, "FIRST_PRESENTATION"),
                ),
            ),
        )
    }

    private fun event(type: String, nanos: Long, pageIndex: Int?, purpose: String) = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("monotonicNanos", JsonPrimitive(nanos))
        put("chapterId", JsonPrimitive(90_005L))
        if (pageIndex == null) put("pageIndex", kotlinx.serialization.json.JsonNull) else put("pageIndex", JsonPrimitive(pageIndex))
        put("generation", JsonPrimitive(1L))
        put("purpose", JsonPrimitive(purpose))
    }

    private data class RunResult(val exitCode: Int, val output: String)
}
