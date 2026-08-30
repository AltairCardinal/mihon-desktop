package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestModeShutdownHttpTest {

    @Test
    fun `shutdown endpoint accepts the request and invokes the lifecycle callback once`() = runBlocking {
        val callbackCalls = AtomicInteger()
        val callbackInvoked = CountDownLatch(1)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer(
                onShutdownRequested = {
                    callbackCalls.incrementAndGet()
                    callbackInvoked.countDown()
                },
            )
        }.start()

        try {
            val port = server.resolvedConnectors().single().port
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/shutdown"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(202, response.statusCode())
            assertTrue(Json.parseToJsonElement(response.body()).jsonObject.getValue("success").jsonPrimitive.boolean)
            assertTrue(callbackInvoked.await(1, TimeUnit.SECONDS), "shutdown callback was not invoked")
            assertEquals(1, callbackCalls.get())
        } finally {
            server.stop(0, 0)
        }
    }
}
