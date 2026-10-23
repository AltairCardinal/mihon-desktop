package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Starts the real test mode HTTP server in a JVM that does not carry the full Kotlin
 * reflection implementation and asks it for the state document.
 *
 * The server is the only consumer of the Ktor server stack in the desktop application.
 * The stack is only there because the navigation library needs the class metadata of the
 * screen model, and the full reflection implementation answers that question by
 * deserialising the metadata on the thread that composes the first user interface.
 * Removing the implementation from the runtime class path keeps that cost out of the
 * start up, but the test mode server still has to work without it.
 *
 * The probe runs in its own JVM because the test JVM itself carries the reflection
 * implementation for the Kotlin test infrastructure.
 */
object TestModeServerReflectionProbe {

    private const val READY = "PROBE_OK"

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val reflectionPresent = runCatching {
            Class.forName("kotlin.reflect.jvm.internal.KClassImpl")
        }.isSuccess
        if (reflectionPresent) {
            System.err.println("the probe JVM still carries the full reflection implementation")
            kotlin.system.exitProcess(2)
        }

        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer()
        }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/health")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                System.err.println("unexpected status ${response.statusCode()}: ${response.body()}")
                kotlin.system.exitProcess(3)
            }
            val state = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/state")).GET().build()
            val stateResponse = client.send(state, HttpResponse.BodyHandlers.ofString())
            if (stateResponse.statusCode() != 200 || !stateResponse.body().contains("currentScreen")) {
                System.err.println("unexpected state response ${stateResponse.statusCode()}: ${stateResponse.body()}")
                kotlin.system.exitProcess(4)
            }
            println(READY)
        } finally {
            server.stop(0, 0)
        }
    }
}
