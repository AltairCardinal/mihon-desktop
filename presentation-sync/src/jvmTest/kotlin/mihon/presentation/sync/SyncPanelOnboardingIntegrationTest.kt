package mihon.presentation.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPasswordProblem
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.security.SyncSecureStore
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalComposeUiApi::class)
class SyncPanelOnboardingIntegrationTest {
    @Test
    fun `real UI dispatches invalid password to production controller without creating a plain space`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/user" -> MockResponse(body = """{"id":42,"login":"fixture","type":"User"}""")
                    "/user/installations" -> MockResponse(
                        body = """{"installations":[{"id":7,"app_slug":"mihon-desktop",
                            "account":{"id":42,"type":"User"},"suspended_at":null,
                            "repository_selection":"selected",
                            "permissions":{"administration":"write","contents":"write","metadata":"read"}}]}""",
                    )
                    "/user/installations/7/repositories" ->
                        MockResponse(body = """{"repositories":[],"total_count":0}""")
                    else -> MockResponse(code = 404, body = "{}")
                }
            }
            server.start()
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            val handler = JvmDatabaseHandler(database, driver)
            val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
            val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
            val stored = ConcurrentHashMap<String, String>()
            val secure = object : SyncSecureStore {
                override suspend fun read(key: String) = stored[key]
                override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean =
                    synchronized(stored) {
                        if (stored[key] != expected) return@synchronized false
                        if (value == null) stored.remove(key) else stored[key] = value
                        true
                    }
            }
            val client = OkHttpClient()
            val runtime = SyncRuntime(
                handler, bootstrap, creators, creators, { true }, secure, InMemoryPreferenceStore(), client,
                GitHubAuthEndpoints(apiBaseUrl = server.url("/").toString()),
            )
            val panel = runtime.panel
            val scene = ImageComposeScene(400, 800, coroutineContext = coroutineContext) {}
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            fun node(tag: String) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.firstOrNull {
                it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
            }
            suspend fun awaitNode(tag: String): SemanticsNode = withTimeout(5_000) {
                while (node(tag) == null) {
                    scene.render()
                    yield()
                }
                requireNotNull(node(tag))
            }
            try {
                runtime.credentials.replace(
                    null,
                    GitHubAccessToken("fixture-token", null, "bearer", emptySet(), null, null),
                )
                scene.setContent {
                    MaterialTheme { SyncPanelContent(panel, onOpenBrowser = {}, onCopyCode = {}) }
                }
                panel.dispatch(SyncPanelAction.Open)
                requireNotNull(awaitNode("sync-now").config[SemanticsActions.OnClick].action).invoke()
                val input = awaitNode("sync-password-input")
                val password = "界".repeat(342)
                requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(password))
                repeat(3) {
                    scene.render()
                    yield()
                }
                requireNotNull(awaitNode("sync-password-submit").config[SemanticsActions.OnClick].action).invoke()
                withTimeout(5_000) { panel.state.first { it.passwordProblem == SyncPasswordProblem.TOO_LONG } }
                awaitNode("sync-password-error")
                assertEquals(SyncSetupStep.NEW_PASSWORD, panel.state.value.setupStep)
                assertNull(runtime.connection())
                assertFalse(stored.values.any { it.contains(password) })
                repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
                panel.dispatch(SyncPanelAction.Close)
                withTimeout(5_000) { panel.state.first { !it.visible } }
                assertTrue(runtime.credentials.read() != null)
            } finally {
                scene.close()
                runtime.stopPanel()
                handler.close()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
