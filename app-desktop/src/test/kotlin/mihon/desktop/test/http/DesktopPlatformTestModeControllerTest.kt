package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.InstallCancelled
import mihon.desktop.update.InstallManualOnly
import mihon.desktop.update.ManualOnly
import mihon.desktop.update.DesktopUpdateState
import mihon.desktop.ui.settings.DesktopUpdateScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
class DesktopPlatformTestModeControllerTest {
    @Test
    fun `update routes expose production state and reject illegal transitions`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Throwable>()
        val controller = DesktopUpdateController(
            { entered.complete(Unit); try { awaitCancellation() } catch (error: CancellationException) { cancelled.complete(error); throw error } },
            { release, _ -> ManualOnly(release.releaseLink) },
            { _, _ -> InstallManualOnly },
            { _, _ -> InstallCancelled },
        )
        val model = DesktopUpdateScreenModel(controller, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        withServer(model) { base ->
            assertEquals(409, post(base, "update_download").statusCode())
            assertEquals(200, post(base, "update_check").statusCode())
            entered.await()
            assertEquals(409, post(base, "update_check").statusCode())
            assertEquals("checking", get(base).bodyJson().getValue("updateStatus").jsonPrimitive.content)
            assertEquals(200, post(base, "update_cancel").statusCode())
            withTimeout(2_000) { model.state.first { it is DesktopUpdateState.Cancelled } }
            assertTrue(cancelled.await() is CancellationException)
            assertEquals(400, post(base, "update_unknown").statusCode())
        }
        withServer(null) { base -> assertEquals(503, post(base, "update_check").statusCode()) }
    }
    private suspend fun withServer(model: DesktopUpdateScreenModel?, block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer(model) }.start()
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
