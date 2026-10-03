package mihon.presentation.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
class SyncSpaceRecoveryPanelIntegrationTest {
    @Test
    fun `real recovery click discovers other spaces without resuming the missing old repository`() = fixture {
        click("sync-recovery-open")
        withTimeout(5_000) { panel.state.first { it.page == SyncPanelPage.RECOVERY } }
        scroll("sync-recovery-page", 3)
        click("sync-recovery-connect-other")
        withTimeout(5_000) {
            panel.state.first { it.setupStep == SyncSetupStep.CHOOSE_SPACE && !it.setupBusy }
        }
        awaitNode("sync-recovery-no-spaces")
        assertTrue(panel.state.value.spaces.isEmpty())
        assertEquals(oldConnection, runtime.connection())
        assertFalse(paths.any { it.startsWith("/repos/fixture/old-sync") })
        assertTrue(methods.all { it == "GET" })
        click("sync-close")
        withTimeout(5_000) { panel.state.first { !it.visible } }
        assertNotNull(runtime.spaceRecovery())
        assertEquals(oldCredential, runtime.credentials.read())
    }

    @Test
    fun `real new space wizard keeps the old connection through browser return back and close`() = fixture {
        click("sync-recovery-open")
        scroll("sync-recovery-page", 4)
        click("sync-recovery-create")
        click("sync-confirm-question")
        withTimeout(5_000) {
            panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY && !it.setupBusy }
        }
        click("sync-create-private-repo")
        assertEquals(1, opened.size)
        assertTrue(opened.single().startsWith("https://github.com/new?name=mihon-sync&visibility=private"))
        assertEquals(oldConnection, runtime.connection())
        assertTrue(methods.all { it == "GET" })
        val beforeContinue = paths.size
        click("sync-recovery-repository-created")
        withTimeout(5_000) { panel.state.first { !it.setupBusy && paths.size > beforeContinue } }
        assertEquals(oldConnection, runtime.connection())
        assertTrue(node("sync-password-input") == null, "an uncreated repository cannot start initialization")
        click("sync-back")
        click("sync-close")
        withTimeout(5_000) { panel.state.first { !it.visible } }
        assertEquals(oldConnection, runtime.connection())
        assertEquals(oldCredential, runtime.credentials.read())
        assertNotNull(runtime.spaceRecovery())
        assertTrue(methods.all { it == "GET" })
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(ImageComposeScene(560, 720, coroutineContext = currentCoroutineContext()) {})
        try {
            fixture.initialize()
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(val scene: ImageComposeScene) {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val opened = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.url.encodedPath
                    methods += request.method
                    return when (request.url.encodedPath) {
                        "/user" -> MockResponse(body = """{"id":42,"login":"fixture","type":"User"}""")
                        "/user/installations" -> MockResponse(
                            body = """
                                {"installations":[{"id":7,"app_slug":"mihon-desktop",
                                "account":{"id":42,"type":"User"},"suspended_at":null,
                                "repository_selection":"selected",
                                "permissions":{"contents":"write","metadata":"read"}}]}
                            """.trimIndent(),
                        )
                        "/user/installations/7/repositories" -> MockResponse(
                            body = """{"repositories":[],"total_count":0}""",
                        )
                        else -> MockResponse(code = 404, body = "{}")
                    }
                }
            }
            start()
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = run {
            Database.Schema.create(driver)
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        }
        val handler = JvmDatabaseHandler(database, driver)
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)

        // A v2 persisted connection fixture is read by production storage; no storage implementation is mocked.
        val stored = ConcurrentHashMap<String, String>().apply {
            put(
                "space-1413a576fe80d48eb03e00f8d72e177007f4805e0a23778ba83bf044b13baf6b",
                """{"version":2,"accountId":42,"accountLogin":"fixture","repositoryId":99,"owner":"fixture","repository":"old-sync","branch":"mihon-sync-v1","material":{"descriptor":"{\"application\":\"mihon-sync\",\"spaceFormatVersion\":2,\"eventProtocolVersion\":1,\"spaceId\":\"old-space\",\"generation\":1,\"protection\":{\"mode\":\"none\"}}","keyHex":null},"actorId":"fixture-actor","epoch":1}""",
            )
        }
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
        val panel get() = runtime.panel
        var oldConnection: mihon.data.sync.runtime.SyncConnection? = null
        var oldCredential: mihon.domain.sync.auth.GitHubStoredCredential? = null

        suspend fun initialize() {
            runtime.credentials.replace(
                null,
                GitHubAccessToken("fixture-token", null, "bearer", emptySet(), null, null),
            )
            runtime.baseline.connectAndImport(
                "old-space",
                1,
                SyncRepository("fixture", "old-sync", "mihon-sync-v1"),
                "fixture-actor",
                1,
            )
            oldConnection = runtime.connection()
            oldCredential = runtime.credentials.read()
            scene.setContent { MaterialTheme { SyncPanelContent(panel, onOpenBrowser = opened::add, onCopyCode = {}) } }
            panel.dispatch(SyncPanelAction.Open)
            withTimeout(5_000) { panel.state.first { it.visible && it.loaded } }
            panel.dispatch(SyncPanelAction.RecheckSpace)
            withTimeout(5_000) {
                panel.state.first { it.visible && it.loaded && it.recovery?.busy == false }
            }
            // Recheck legitimately leaves the methods page; reopening exercises the production MAIN entry.
            panel.dispatch(SyncPanelAction.Close)
            withTimeout(5_000) { panel.state.first { !it.visible } }
            panel.dispatch(SyncPanelAction.Open)
            withTimeout(5_000) { panel.state.first { it.visible && it.loaded && it.page == SyncPanelPage.MAIN } }
        }

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
        suspend fun click(tag: String) {
            assertTrue(requireNotNull(awaitNode(tag).config[SemanticsActions.OnClick].action).invoke())
        }
        suspend fun scroll(tag: String, index: Int) {
            requireNotNull(awaitNode(tag).config[SemanticsActions.ScrollToIndex].action).invoke(index)
            repeat(3) {
                scene.render()
                yield()
            }
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        suspend fun close() {
            scene.close()
            runtime.stopPanel()
            handler.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }
}
