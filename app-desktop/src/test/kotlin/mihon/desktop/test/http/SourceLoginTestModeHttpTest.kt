package mihon.desktop.test.http

import eu.kanade.tachiyomi.source.CatalogueSource
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.network.DesktopBrowserLoginTicket
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.ui.browse.DesktopSourceLoginAttempt
import mihon.desktop.ui.browse.DesktopSourceLoginController
import mihon.desktop.ui.browse.DesktopSourceLoginUiActions
import mihon.desktop.ui.browse.DesktopSourceLoginUiState
import mihon.desktop.ui.browse.DesktopSourceRecoveryIntent
import mihon.desktop.ui.browse.SourceBrowseQueryCoordinator
import mihon.desktop.ui.browse.SourceBrowseTestModeBridge
import mihon.desktop.ui.browse.SourceBrowseTestModeObservationPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceLoginState
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

class SourceLoginTestModeHttpTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `http exposes active login and cancels its real browser ticket`() = runBlocking {
        val ticket = AtomicReference<DesktopBrowserLoginTicket?>()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val controller = DesktopSourceLoginController(
            DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, value -> ticket.set(value); true },
            ),
            coordinator,
        )
        val attempt = controller.newAttempt()
        val login = AtomicReference<DesktopSourceLoginUiState?>(DesktopSourceLoginUiState(attempt, "login.example"))
        val port = port(
            41,
            coordinator,
            login,
            DesktopSourceLoginUiActions(controller::submitCookies, controller::cancel),
        )
        val source = mockk<CatalogueSource> { every { id } returns 41L }
        val request = SourcePageRequest(41, 1, 1, SourceQuery.Popular)
        val loginJob = async {
            controller.login(source, DesktopSourceRecoveryIntent.OpenLogin("https://login.example", request), attempt)
        }
        withTimeout(2_000) { while (ticket.get() == null) kotlinx.coroutines.yield() }
        SourceBrowseTestModeBridge.install(port)
        try {
            withServer { baseUrl ->
                val sourceState = get(baseUrl, "/test/state").json.getValue("source").jsonObject
                assertEquals(41, sourceState.getValue("sourceId").jsonPrimitive.content.toLong())
                assertEquals(
                    "login.example",
                    sourceState.getValue("login").jsonObject.getValue("host").jsonPrimitive.content,
                )
                assertFalse(sourceState.toString().contains("cookieHeader"))
                val token = sourceState.getValue("login").jsonObject.getValue("attemptToken").jsonPrimitive.content
                assertEquals(400, post(baseUrl, "/test/action/source_login_cancel", "{}").status)
                assertEquals(
                    409,
                    post(baseUrl, "/test/action/source_login_cancel", """{"attemptToken":"wrong"}""").status,
                )
                val cancelled = post(baseUrl, "/test/action/source_login_cancel", """{"attemptToken":"$token"}""")
                assertEquals(200, cancelled.status)
                assertTrue(cancelled.json.getValue("success").jsonPrimitive.content.toBoolean())
                assertNull(cancelled.json.getValue("source").jsonObject["login"])
                assertNull(login.get())
                assertFalse(requireNotNull(ticket.get()).cancel())
                assertEquals(SourceLoginState.Cancelled, loginJob.await())
                assertEquals(
                    409,
                    post(baseUrl, "/test/action/source_login_cancel", """{"attemptToken":"$token"}""").status,
                )
            }
        } finally {
            SourceBrowseTestModeBridge.clear(port)
            port.close()
            loginJob.cancel()
        }
    }

    @Test
    fun `source login status contract rejects unavailable terminal and rejected operations`() = runBlocking {
        SourceBrowseTestModeBridge.port?.let(SourceBrowseTestModeBridge::clear)
        withServer { baseUrl ->
            assertSame(JsonNull, get(baseUrl, "/test/state").json["source"])
            assertEquals(503, post(baseUrl, "/test/action/source_login_cancel", "{}").status)
            assertEquals(400, post(baseUrl, "/test/action/browse_search", """{"query":"fake"}""").status)

            val login = AtomicReference<DesktopSourceLoginUiState?>(
                DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "terminal", terminal = true),
            )
            val port = port(
                42,
                SourceBrowseQueryCoordinator(SourceMangaSearchService()),
                login,
                DesktopSourceLoginUiActions({ _, _ -> false }) { false },
            )
            SourceBrowseTestModeBridge.install(port)
            try {
                val token = get(baseUrl, "/test/state").json.getValue("source").jsonObject
                    .getValue("login").jsonObject.getValue("attemptToken").jsonPrimitive.content
                val terminal = post(baseUrl, "/test/action/source_login_cancel", """{"attemptToken":"$token"}""")
                assertEquals(409, terminal.status)
                assertEquals("TERMINAL", terminal.json.getValue("error").jsonPrimitive.content)
                login.set(DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "rejected"))
                val rejectedToken = get(baseUrl, "/test/state").json.getValue("source").jsonObject
                    .getValue("login").jsonObject.getValue("attemptToken").jsonPrimitive.content
                val rejected = post(
                    baseUrl,
                    "/test/action/source_login_cancel",
                    """{"attemptToken":"$rejectedToken"}""",
                )
                assertEquals(409, rejected.status)
                assertEquals("OPERATION_REJECTED", rejected.json.getValue("error").jsonPrimitive.content)
            } finally {
                SourceBrowseTestModeBridge.clear(port)
                port.close()
            }
        }
    }

    private fun port(
        sourceId: Long,
        coordinator: SourceBrowseQueryCoordinator,
        login: AtomicReference<DesktopSourceLoginUiState?>,
        actions: DesktopSourceLoginUiActions,
    ) = SourceBrowseTestModeObservationPort(
        sourceId,
        coordinator,
        CoroutineScope(Dispatchers.Default),
        login::get,
        login::set,
        actions,
    )

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try { block("http://127.0.0.1:${server.resolvedConnectors().single().port}") } finally { server.stop(0, 0) }
    }
    private fun get(base: String, path: String) = request(HttpRequest.newBuilder(URI.create(base + path)).GET().build())
    private fun post(base: String, path: String, body: String) = request(
        HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
    )
    private fun request(request: HttpRequest): Response {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), Json.parseToJsonElement(response.body()).jsonObject)
    }
    private data class Response(val status: Int, val json: JsonObject)
}
