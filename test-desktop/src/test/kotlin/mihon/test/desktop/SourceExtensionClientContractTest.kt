package mihon.test.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import mihon.test.desktop.robot.ExtensionsRobot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections

class SourceExtensionClientContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `client and robot preserve extension contract across every HTTP status`() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val snapshot = snapshot()
        val wireSnapshot = wireSnapshot()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handle(exchange, wireSnapshot, requests)
                } catch (error: Throwable) {
                    error.printStackTrace()
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
            start()
        }
        DesktopTestClient("127.0.0.1", server.address.port).use { client ->
            try {
                val robot = ExtensionsRobot(client)
                val state = requireNotNull(robot.state())
                assertEquals(snapshot, state)
                val partial = state.errors.getValue("pkg.partial")
                assertEquals("Network", partial.failures.single().type)
                assertEquals("unit-1", partial.failedUnits.single().unitId)
                assertEquals("Server", partial.failedUnits.single().error.type)
                assertEquals(503, partial.failedUnits.single().error.statusCode)
                val special = "quoted \"query\" \\ path"
                robot.refresh().search(special).install("pkg.one").update("pkg.one").retry("pkg.one")
                    .cancel("pkg.one").updateAll().uninstall("pkg.one").trustConfirm("pkg.one").trustDismiss("pkg.one")

                assertEquals(
                    listOf(
                        "extension_refresh", "extension_search", "extension_install", "extension_update", "extension_retry",
                        "extension_cancel", "extension_update_all", "extension_uninstall", "extension_trust_confirm",
                        "extension_trust_dismiss",
                    ),
                    requests.take(10).map(Request::action),
                )
                assertEquals(special, requests[1].body.getValue("query").jsonPrimitive.content)
                listOf(2, 3, 4, 5, 7, 8, 9).forEach {
                    assertEquals("pkg.one", requests[it].body.getValue("packageName").jsonPrimitive.content)
                }

                val typed = client.executeAction(
                    "extension_search",
                    mapOf("query" to special, "nullable" to null, "enabled" to true, "count" to 7),
                )
                assertEquals(snapshot, typed.extension)
                val typedBody = requests.last().body
                assertEquals(special, typedBody.getValue("query").jsonPrimitive.content)
                assertEquals(JsonNull, typedBody["nullable"])
                assertTrue(typedBody.getValue("enabled").jsonPrimitive.boolean)
                assertEquals(7, typedBody.getValue("count").jsonPrimitive.int)

                val rejected = client.executeAction("extension_install", mapOf("packageName" to "missing"))
                assertFalse(rejected.success)
                assertEquals("UNKNOWN_PACKAGE", rejected.error)
                assertEquals(snapshot, rejected.extension)
                val thrown = assertThrows(IllegalStateException::class.java) { robot.install("missing") }
                assertTrue(thrown.message.orEmpty().contains("UNKNOWN_PACKAGE"))
            } finally {
                server.stop(0)
            }
        }
    }

    private fun handle(exchange: HttpExchange, snapshot: JsonObject, requests: MutableList<Request>) {
        val path = exchange.requestURI.path
        val payload = when {
            path == "/test/state" -> buildJsonObject {
                put("currentScreen", JsonPrimitive("ExtensionListScreen"))
                put("extension", snapshot)
            }.toString()
            path.startsWith("/test/action/") -> {
                val action = path.substringAfterLast('/')
                val body = json.parseToJsonElement(exchange.requestBody.bufferedReader().readText().ifBlank { "{}" }) as JsonObject
                requests += Request(action, body)
                val rejected = action == "extension_install" && body["packageName"]?.jsonPrimitive?.contentOrNull == "missing"
                exchange.setStatus(if (rejected) 409 else 200)
                buildJsonObject {
                    put("success", JsonPrimitive(!rejected))
                    put("action", JsonPrimitive(action))
                    put("error", if (rejected) JsonPrimitive("UNKNOWN_PACKAGE") else JsonNull)
                    put("extension", snapshot)
                }.toString()
            }
            else -> "{}"
        }
        val bytes = payload.toByteArray()
        exchange.sendResponseHeaders(exchange.status(), bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun snapshot() = SourceExtensionTestSnapshot(
        searchQuery = "fixture",
        refreshing = false,
        installed = emptyList(),
        available = listOf(
            SourceExtensionTestItem(
                "pkg.one", "Fixture", "en", false, true, false,
                listOf(SourceExtensionTestSource(1, "en", "Source", "https://source")),
            ),
        ),
        updates = emptyList(),
        installSteps = mapOf("pkg.one" to "Idle"),
        errors = mapOf(
            "pkg.error" to SourceExtensionStoredAppError("Network", message = "offline"),
            "pkg.partial" to SourceExtensionStoredAppError(
                "PartialFailure",
                message = "partial",
                failures = listOf(SourceExtensionStoredAppError("Network", message = "network")),
                failedUnits = listOf(
                    SourceExtensionStoredFailedUnit("unit-1", SourceExtensionStoredAppError("Server", 503)),
                ),
            ),
        ),
        repositoryErrors = listOf(
            SourceExtensionRepositoryError("https://repo", "Repo", "fingerprint", SourceExtensionStoredAppError("Server", 503)),
        ),
        pendingTrust = SourceExtensionTrustSnapshot("pkg.one", "request", "old", "new", listOf("FingerprintChanged")),
    )

    private fun wireSnapshot() = json.parseToJsonElement(
        """
        {
          "searchQuery":"fixture","refreshing":false,"installed":[],
          "available":[{"packageName":"pkg.one","name":"Fixture","language":"en","installed":false,
            "available":true,"hasUpdate":false,"sources":[{"id":1,"language":"en","name":"Source","baseUrl":"https://source"}]}],
          "updates":[],"installSteps":{"pkg.one":"Idle"},
          "errors":{
            "pkg.error":{"type":"Network","message":"offline"},
            "pkg.partial":{"type":"PartialFailure","message":"partial",
              "failures":[{"type":"Network","message":"network"}],
              "failedUnits":[{"unitId":"unit-1","error":{"type":"Server","statusCode":503}}]}
          },
          "repositoryErrors":[{"repositoryBaseUrl":"https://repo","repositoryName":"Repo",
            "repositoryFingerprint":"fingerprint","error":{"type":"Server","statusCode":503}}],
          "pendingTrust":{"packageName":"pkg.one","requestId":"request","existingFingerprint":"old",
            "incomingFingerprint":"new","reasons":["FingerprintChanged"]}
        }
        """.trimIndent(),
    ) as JsonObject

    private fun HttpExchange.setStatus(value: Int) = setAttribute("status", value)
    private fun HttpExchange.status() = getAttribute("status") as? Int ?: 200
    private data class Request(val action: String, val body: JsonObject)
}
