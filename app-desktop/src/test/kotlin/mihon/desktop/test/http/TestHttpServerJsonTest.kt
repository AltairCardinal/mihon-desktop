package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.test.state.applicationState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

class TestHttpServerJsonTest {

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
    fun `screenshot endpoint encodes Windows paths and preserves failure contract`() = runBlocking {
        val path = """D:\Shell\Github\mihon\build\evidence\feedback.png"""
        val captureResult = AtomicReference<String?>(path)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer(screenshotCapture = { captureResult.get() })
        }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            val request = {
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/screenshot"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"name":"feedback"}"""))
                    .build()
            }

            val success = client.send(request(), HttpResponse.BodyHandlers.ofString())
            val successJson = Json.parseToJsonElement(success.body()).jsonObject
            assertEquals(200, success.statusCode())
            assertEquals(setOf("success", "path", "timestamp"), successJson.keys)
            assertEquals(true, successJson.getValue("success").jsonPrimitive.content.toBoolean())
            assertEquals(path, successJson.getValue("path").jsonPrimitive.content)

            captureResult.set(null)
            val failure = client.send(request(), HttpResponse.BodyHandlers.ofString())
            val failureJson = Json.parseToJsonElement(failure.body()).jsonObject
            assertEquals(500, failure.statusCode())
            assertEquals(setOf("success", "error", "timestamp"), failureJson.keys)
            assertEquals(false, failureJson.getValue("success").jsonPrimitive.content.toBoolean())
            assertEquals("Screenshot capture failed", failureJson.getValue("error").jsonPrimitive.content)
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
            assertEquals(listOf("sentinel_action"), state.getValue("actions").jsonArray.map { it.jsonPrimitive.content })
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
}
