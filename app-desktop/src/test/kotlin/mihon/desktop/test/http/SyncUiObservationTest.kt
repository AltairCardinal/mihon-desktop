package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SyncUiObservationTest {
    @Test
    fun `sync ui observation is read only and not ready without mounted window`() = runBlocking {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            val uri = URI("http://127.0.0.1:$port/test/sync/ui")
            val result = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, result.statusCode())
            assertFalse(Json.parseToJsonElement(result.body()).jsonObject.getValue("ready").jsonPrimitive.content.toBoolean())
            val write = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, write.statusCode())
        } finally { server.stop(0, 0) }
    }
}
