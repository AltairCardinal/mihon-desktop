package mihon.test.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class SourceLoginClientContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "token \"quoted\" \\ value"
    @Test
    fun `client and browse robot preserve server shaped source login contract`() {
        val requestBody = AtomicReference<JsonObject>()
        val source = json.parseToJsonElement(SOURCE_JSON) as JsonObject
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange, source, requestBody) }
            start()
        }
        DesktopTestClient("127.0.0.1", server.address.port).use { client ->
            try {
                val state = requireNotNull(client.browse.state())
                assertEquals(77, state.sourceId)
                assertEquals(SourceBrowseTestPhase.FAILURE, state.phase)
                assertEquals(SourceBrowseTestQueryKind.SEARCH, state.request?.queryKind)
                assertEquals("query", state.request?.queryText)
                assertEquals("Authentication", state.error?.type)
                assertEquals(401, state.error?.statusCode)
                assertEquals("unit", state.error?.failedUnits?.single()?.unitId)
                assertEquals(SourceBrowseTestRecovery.OPEN_LOGIN, state.recovery)
                assertEquals("login.example", state.login?.host)
                assertEquals(SourceBrowseTestLoginFeedback.TIMED_OUT, state.login?.feedback)
                assertFalse(state.login?.terminal ?: true)
                assertEquals(token, state.login?.attemptToken)

                val cancelled = requireNotNull(client.browse.cancelLogin(token))
                assertNull(cancelled.login)
                assertEquals(token, requestBody.get().getValue("attemptToken").jsonPrimitive.content)
                val error = assertThrows(IllegalStateException::class.java) { client.browse.cancelLogin("wrong") }
                assertTrue(error.message.orEmpty().contains("ATTEMPT_MISMATCH"))
                assertFalse(client.browse.javaClass.methods.any { it.name == "search" })
            } finally {
                server.stop(0)
            }
        }
    }
    private fun handle(exchange: HttpExchange, source: JsonObject, requestBody: AtomicReference<JsonObject>) {
        val action = exchange.requestURI.path.substringAfterLast('/')
        val response = if (exchange.requestURI.path == "/test/state") {
            buildJsonObject { put("currentScreen", JsonPrimitive("SourceBrowseScreen")); put("source", source) }
        } else {
            val body = json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()) as JsonObject
            requestBody.set(body)
            val accepted = body["attemptToken"]?.jsonPrimitive?.content == token
            exchange.setStatus(if (accepted) 200 else 409)
            buildJsonObject {
                put("success", JsonPrimitive(accepted))
                put("action", JsonPrimitive(action))
                put("error", if (accepted) JsonNull else JsonPrimitive("ATTEMPT_MISMATCH"))
                put("source", if (accepted) JsonObject(source + ("login" to JsonNull)) else source)
            }
        }.toString().toByteArray()
        exchange.sendResponseHeaders(exchange.status(), response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    private fun HttpExchange.setStatus(value: Int) = setAttribute("status", value)
    private fun HttpExchange.status() = getAttribute("status") as? Int ?: 200

    private companion object {
        val SOURCE_JSON = """
            {
              "sourceId":77,"phase":"FAILURE",
              "request":{"sourceId":77,"page":2,"generation":9,"queryKind":"SEARCH","queryText":"query"},
              "itemCount":3,"loading":false,"hasNextPage":true,
              "error":{"type":"Authentication","statusCode":401,"retryAfterSeconds":2,"message":"login",
                "failures":[{"type":"Network","message":"offline"}],
                "failedUnits":[{"unitId":"unit","error":{"type":"Server","statusCode":503}}]},
              "recovery":"OPEN_LOGIN",
              "login":{"host":"login.example","feedback":"TIMED_OUT","terminal":false,
                "attemptToken":"token \"quoted\" \\ value"}
            }
        """.trimIndent()
    }
}
