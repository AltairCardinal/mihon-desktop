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
}
