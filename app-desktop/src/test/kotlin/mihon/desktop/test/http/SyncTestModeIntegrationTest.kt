package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestMode
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.security.SyncSecureStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
class SyncTestModeIntegrationTest {
    @Test
    fun `setup diagnostics accept retry without retaining the obsolete repository action`() = runBlocking {
        val actions = java.util.concurrent.CopyOnWriteArrayList<SyncPanelAction>()
        val panel = object : SyncPanel {
            override val state = MutableStateFlow(SyncPanelState())
            override fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean = false
            override fun dispatch(action: SyncPanelAction) {
                actions += action
            }
        }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer(syncPanel = panel)
        }.start()
        try {
            val base = "http://127.0.0.1:${server.resolvedConnectors().single().port}/test/sync"
            assertEquals(400, request("$base/refresh_repositories", post = true).statusCode())
            assertEquals(202, request("$base/retry_setup", post = true).statusCode())
            assertEquals(listOf(SyncPanelAction.RetrySetup), actions)
            val snapshot = Json.parseToJsonElement(request(base).body()).jsonObject
            assertEquals("0", snapshot.getValue("spaceCount").jsonPrimitive.content)
            assertFalse("repositoryCount" in snapshot)
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `a generic HTTP host does not resurrect a stopped application graph`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-stopped-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        val runtime = Injekt.get<SyncRuntime>()
        context.closeAndJoin()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val base = "http://127.0.0.1:${server.resolvedConnectors().single().port}/test/sync"
            assertEquals(503, request(base).statusCode())
        } finally {
            server.stop(0, 0)
            runtime.stopPanel()
            node.removeNode()
        }
    }

    @Test
    fun `runtime endpoint controls the same panel used by the native library`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-http-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        val port = ServerSocket(0).use { it.localPort }
        TestMode.start(TestArguments(testMode = true, httpPort = port, headless = true))
        try {
            val panel = Injekt.get<SyncRuntime>().panel
            assertSame(panel, DesktopUiDependencies.fromInjekt().syncPanel)
            assertEquals(Injekt.get<SyncRuntime>().diagnosticDirectory.toString(), panel.diagnosticDirectory)
            val base = "http://127.0.0.1:$port/test/sync"
            withTimeout(10_000) {
                while (runCatching { request(base).statusCode() }.getOrNull() != 200) delay(25)
            }
            assertEquals(202, request("$base/open", post = true).statusCode())
            withTimeout(5_000) { panel.state.first { it.visible && it.loaded } }
            assertEquals(202, request("$base/diagnostics", post = true).statusCode())
            withTimeout(5_000) { panel.state.first { it.page == mihon.data.sync.runtime.SyncPanelPage.DIAGNOSTICS } }
            assertEquals(202, request("$base/capture_diagnostics", post = true).statusCode())
            withTimeout(5_000) { panel.state.first { it.diagnosticSnapshot != null } }
            val diagnostic = request("$base/diagnostics")
            assertEquals(200, diagnostic.statusCode())
            val details = Json.parseToJsonElement(diagnostic.body()).jsonObject
            assertEquals("OK", details.getValue("status").jsonPrimitive.content)
            assertEquals(mihon.desktop.APP_VERSION,
                details.getValue("environment").jsonObject.getValue("appVersion").jsonPrimitive.content)
            assertEquals(mihon.desktop.BuildInfo.GIT_HASH,
                details.getValue("environment").jsonObject.getValue("sourceRevision").jsonPrimitive.content)
            assertFalse(diagnostic.body().contains(folder.canonicalPath))
            val response = request(base)
            assertEquals(200, response.statusCode())
            val snapshot = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals("true", snapshot.getValue("visible").jsonPrimitive.content)
            assertEquals("0", snapshot.getValue("queuedTotal").jsonPrimitive.content)
            for (privateKey in listOf(
                "recoveryText",
                "deviceCode",
                "accessToken",
                "refreshToken",
                "password",
                "secret",
            )) {
                assertFalse(privateKey in response.body())
            }
            assertEquals(400, request("$base/confirm_merge", post = true).statusCode())
            assertEquals(202, request("$base/close", post = true).statusCode())
            withTimeout(5_000) { panel.state.first { !it.visible } }
            assertFalse(panel.state.value.visible)
        } finally {
            TestMode.stop()
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `credential probe uses the native encrypted store across graph recreation`(
        @TempDir folder: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-probe-" + UUID.randomUUID())
        val values = mutableMapOf<String, String>()
        val backend = object : CredentialBackend {
            override fun save(account: String, secret: CharArray) {
                values[account] = secret.concatToString()
            }
            override fun load(account: String) = values[account]?.toCharArray()
            override fun delete(account: String) {
                values.remove(account)
            }
        }
        var context = initDesktopDIForTest(folder, DesktopPreferenceStore(node), credentialBackendFactory = { backend })
        fun startServer() = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer(
                syncPanel = Injekt.get<SyncRuntime>().panel,
                syncSecureStore = Injekt.get(),
            )
        }.start()
        var server = startServer()
        try {
            var base = "http://127.0.0.1:${server.resolvedConnectors().single().port}/test/sync/probe"
            val written = request("$base/write", post = true)
            assertEquals(201, written.statusCode())
            val id = Json.parseToJsonElement(written.body()).jsonObject.getValue("id").jsonPrimitive.content
            assertEquals(400, request("$base/verify/not-a-probe", post = true).statusCode())
            server.stop(0, 0)
            context.closeAndJoin()
            context = initDesktopDIForTest(folder, DesktopPreferenceStore(node), credentialBackendFactory = { backend })
            server = startServer()
            base = "http://127.0.0.1:${server.resolvedConnectors().single().port}/test/sync/probe"
            assertEquals(200, request("$base/verify/$id", post = true).statusCode())
            assertEquals(409, request("$base/verify/$id", post = true).statusCode())
        } finally {
            server.stop(0, 0)
            context.closeAndJoin()
            node.removeNode()
        }
    }

    private fun request(url: String, post: Boolean = false): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).apply {
            if (post) POST(HttpRequest.BodyPublishers.noBody()) else GET()
        }.build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `diagnostics expose only the user verification code and redact failures`() = runBlocking {
        val panel = object : SyncPanel {
            override val state = MutableStateFlow(
                SyncPanelState(
                    deviceCode = GitHubDeviceCode(
                        "private-device-marker",
                        "TEST-CODE",
                        "https://github.com/login/device",
                        60,
                        5,
                    ),
                ),
            )
            override fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean = false
            override fun dispatch(action: SyncPanelAction) = Unit
        }
        val brokenStore = object : SyncSecureStore {
            override suspend fun read(key: String): String = error("private-error-marker")
            override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean =
                error("private-error-marker")
        }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer(syncPanel = panel, syncSecureStore = brokenStore)
        }.start()
        try {
            val base = "http://127.0.0.1:${server.resolvedConnectors().single().port}/test/sync"
            val status = request(base)
            val authorization = request("$base/authorization")
            val failed = request("$base/probe/write", post = true)
            assertEquals(200, status.statusCode())
            assertEquals(200, authorization.statusCode())
            assertEquals(503, failed.statusCode())
            for (body in listOf(status.body(), authorization.body(), failed.body())) {
                assertFalse(body.contains("private-"))
            }
            val auth = Json.parseToJsonElement(authorization.body()).jsonObject
            assertEquals(setOf("userCode", "verificationUri"), auth.keys)
            assertEquals("TEST-CODE", auth.getValue("userCode").jsonPrimitive.content)
        } finally {
            server.stop(0, 0)
        }
    }
}
