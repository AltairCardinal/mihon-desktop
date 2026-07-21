package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.InstallCancelled
import mihon.desktop.update.InstallManualOnly
import mihon.desktop.update.ManualOnly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.interactor.GetApplicationRelease
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
class DesktopPlatformTestModeControllerTest {
    @Test
    fun `update routes expose production state and reject illegal transitions`() = runBlocking {
        val controller = DesktopUpdateController(
            { GetApplicationRelease.Result.NoNewUpdate },
            { release, _ -> ManualOnly(release.releaseLink) },
            { _, _ -> InstallManualOnly },
            { _, _ -> InstallCancelled },
        )
        withServer(controller) { base ->
            assertEquals(409, post(base, "update_download").statusCode())
            assertEquals(200, post(base, "update_check").statusCode())
            val state = get(base).bodyJson()
            assertEquals("up_to_date", state.getValue("updateStatus").jsonPrimitive.content)
            assertEquals("null", state.getValue("updateProgress").toString())
        }
        withServer(null) { base -> assertEquals(503, post(base, "update_check").statusCode()) }
    }
    private suspend fun withServer(controller: DesktopUpdateController?, block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer(controller) }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }
    private val client = HttpClient.newHttpClient()
    private fun post(base: String, action: String) = client.send(
        HttpRequest.newBuilder(URI.create("$base/test/action/$action")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun get(base: String) = client.send(
        HttpRequest.newBuilder(URI.create("$base/test/state")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun HttpResponse<String>.bodyJson() = Json.parseToJsonElement(body()).jsonObject
}
