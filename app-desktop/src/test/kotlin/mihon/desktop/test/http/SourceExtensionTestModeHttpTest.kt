package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopAvailableSource
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.ui.extension.DesktopExtensionPresentationPort
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.service.ExtensionInstallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.prefs.Preferences

class SourceExtensionTestModeHttpTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `http uses DI extension model and becomes unavailable after owner closes`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        )
        try {
            withServer { baseUrl ->
                val initial = get(baseUrl, "/test/state")
                assertEquals(200, initial.status)
                assertNotNull(initial.json["extension"])
                assertTrue(initial.json["notifications"] is kotlinx.serialization.json.JsonArray)
                assertTrue(initial.json.getValue("testMode").jsonPrimitive.booleanOrNull != null)

                val search = post(baseUrl, "/test/action/extension_search", """{"query":"wired-query"}""")
                assertEquals(200, search.status)
                assertActionEnvelope(search.json, "extension_search", true)
                assertEquals("wired-query", search.extensionSnapshot().getValue("searchQuery").jsonPrimitive.content)
                assertEquals("wired-query", context.extensionScreenModel.state.value.searchQuery)
                assertEquals("wired-query", get(baseUrl, "/test/state").extensionState().getValue("searchQuery").jsonPrimitive.content)

                context.closeAndJoin()
                assertSame(JsonNull, get(baseUrl, "/test/state").json["extension"])
                val unavailable = post(baseUrl, "/test/action/extension_search", """{"query":"ignored"}""")
                assertEquals(503, unavailable.status)
                assertActionEnvelope(unavailable.json, "extension_search", false)
                assertSame(JsonNull, unavailable.json["extension"])
            }
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `http extension actions execute production state and serialize dynamic errors safely`() = runBlocking {
        val extension = available("pkg.http")
        val message = "offline \"quoted\" \\ path"
        val starts = ArrayDeque(
            listOf(
                DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Failed(AppError.Network(IllegalStateException(message))))),
                DesktopExtensionInstallStart.Started(flow { emit(ExtensionInstallState.Preparing); awaitCancellation() }),
            ),
        )
        val installed = MutableStateFlow(emptyList<InstalledExtension>())
        val manager = mockk<DesktopExtensionManager>()
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns listOf(extension)
            coEvery { beginInstall(extension, manager) } answers { starts.removeFirst() }
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, installed),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val controller = SourceExtensionTestModeController(model)
        SourceExtensionTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/extension_refresh", "{}").also {
                    assertEquals(200, it.status)
                    assertActionEnvelope(it.json, "extension_refresh", true)
                }
                awaitState(model, "extension refresh did not publish the requested package") {
                    it.projection?.available?.any { item -> item.operationPackageName == extension.pkgName } == true
                }

                assertEquals(400, post(baseUrl, "/test/action/extension_search", "{}").status)
                assertEquals(400, post(baseUrl, "/test/action/extension_install", "{}").status)
                assertEquals(404, post(baseUrl, "/test/action/extension_install", """{"packageName":"missing"}""").status)
                val unsupported = post(baseUrl, "/test/action/extension_select", """{"packageName":"pkg.http"}""")
                assertEquals(400, unsupported.status)
                assertEquals("UNSUPPORTED_ACTION", unsupported.json.getValue("error").jsonPrimitive.content)

                val install = post(baseUrl, "/test/action/extension_install", """{"packageName":"pkg.http"}""")
                assertEquals(200, install.status, install.json.toString())
                awaitState(model, "failed install did not publish its error") { extension.pkgName in it.installErrors }
                val failed = get(baseUrl, "/test/state").extensionState()
                val storedError = failed.getValue("errors").jsonObject.getValue(extension.pkgName).jsonObject
                assertEquals("Network", storedError.getValue("type").jsonPrimitive.content)
                assertEquals(message, storedError.getValue("message").jsonPrimitive.content)
                assertEquals(409, post(baseUrl, "/test/action/extension_install", """{"packageName":"pkg.http"}""").status)

                assertEquals(200, post(baseUrl, "/test/action/extension_retry", """{"packageName":"pkg.http"}""").status)
                awaitState(model, "retry did not enter Downloading") {
                    it.actions.installSteps[extension.pkgName] == ExtensionPresentationInstallStep.Downloading
                }
                assertEquals(
                    "Downloading",
                    get(baseUrl, "/test/state").extensionState().getValue("installSteps").jsonObject
                        .getValue(extension.pkgName).jsonPrimitive.content,
                )
                assertEquals(200, post(baseUrl, "/test/action/extension_cancel", """{"packageName":"pkg.http"}""").status)
                awaitState(model, "cancel did not clear install state and error") {
                    extension.pkgName !in it.actions.installSteps && extension.pkgName !in it.installErrors
                }
                val cleared = get(baseUrl, "/test/state").extensionState()
                assertFalse(extension.pkgName in cleared.getValue("installSteps").jsonObject)
                assertFalse(extension.pkgName in cleared.getValue("errors").jsonObject)
            }
        } finally {
            SourceExtensionTestModeBridge.clear(controller)
            model.closeAndJoin()
        }
    }

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private suspend fun awaitState(
        model: ExtensionsScreenModel,
        description: String,
        predicate: (mihon.desktop.ui.extension.DesktopExtensionsState) -> Boolean,
    ) {
        val observed = withTimeoutOrNull(5_000) { model.state.first(predicate) }
        assertNotNull(observed, "$description; last state=${model.state.value}")
    }

    private fun get(baseUrl: String, path: String) = request(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build())
    private fun post(baseUrl: String, path: String, body: String) = request(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
    )
    private fun request(request: HttpRequest): Response {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), Json.parseToJsonElement(response.body()).jsonObject)
    }
    private fun assertActionEnvelope(json: JsonObject, action: String, success: Boolean) {
        assertEquals(success, json.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(action, json.getValue("action").jsonPrimitive.content)
        assertNotNull(json["error"])
        assertNotNull(json["timestamp"])
    }
    private fun Response.extensionSnapshot() = json.getValue("extension").jsonObject
    private fun Response.extensionState() = json.getValue("extension").jsonObject
    private data class Response(val status: Int, val json: JsonObject)

    private fun available(pkg: String) = DesktopAvailableExtension(
        "HTTP Extension", pkg, "1.0", 1, 1.5, "en", false, "https://repo/$pkg.jar", "", "https://repo",
        sources = listOf(DesktopAvailableSource(1, "en", "HTTP Source", "https://source")),
    )
}
