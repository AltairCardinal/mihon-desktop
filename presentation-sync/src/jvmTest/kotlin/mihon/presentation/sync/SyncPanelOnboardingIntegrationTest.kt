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
import mihon.data.sync.SyncPanelUiFixture
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.runtime.SyncCreateProtection
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
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
            val repository = """
                {"id":99,"name":"mihon-sync","full_name":"fixture/mihon-sync",
                "owner":{"id":42,"login":"fixture","type":"User"},"private":true,
                "permissions":{"push":true},"size":0,"default_branch":"main",
                "archived":false,"disabled":false}
            """.trimIndent()
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
                        MockResponse(body = """{"repositories":[$repository],"total_count":1}""")
                    "/repos/fixture/mihon-sync" -> MockResponse(body = repository)
                    "/repos/fixture/mihon-sync/git/ref/heads/mihon-sync-v1" ->
                        MockResponse(code = 404, body = "{}")
                    "/repos/fixture/mihon-sync/git/matching-refs/" -> MockResponse(
                        code = 409,
                        body = """{"message":"Git Repository is empty."}""",
                    )
                    "/repos/fixture/mihon-sync/git/ref/heads/main" -> MockResponse(
                        code = 409,
                        body = """{"message":"Git Repository is empty."}""",
                    )
                    "/repos/fixture/mihon-sync/contents/" -> MockResponse(
                        code = 404,
                        body = """{"message":"This repository is empty."}""",
                    )
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
                requireNotNull(awaitNode("sync-password-enabled").config[SemanticsActions.OnClick].action).invoke()
                val input = awaitNode("sync-password-input")
                val password = "界".repeat(342)
                requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(password))
                repeat(3) {
                    scene.render()
                    yield()
                }
                awaitNode("sync-password-error")
                val context = requireNotNull(panel.state.value.createContextId)
                panel.dispatch(
                    SyncPanelAction.SubmitCreateSpace(context, SyncCreateProtection.PASSWORD, password, true),
                )
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

    @Test
    fun `real compose creates an unprotected descriptor and merges local work`() = runBlocking {
        SyncPanelUiFixture().use { f ->
            f.authorize()
            f.favorite()
            withScene(f) {
                click("sync-now")
                awaitNode("sync-create-space")
                assertNull(find("sync-password-input"))
                click("sync-create-space")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("none", f.descriptor().mode)
                assertEquals("none", f.runtime.connection()?.protectionMode)
                assertEquals(0L, f.panel.state.value.queuedTotal)
                assertEquals(1, f.bootstrapWrites)
                assertEquals(null, SyncSpaceCrypto.unlock(f.descriptor(), "").getOrThrow().secret)
            }
        }
    }

    @Test
    fun `real compose confirms protected creation and descriptor verifies the submitted password`() = runBlocking {
        SyncPanelUiFixture().use { f ->
            f.authorize()
            f.favorite()
            withScene(f) {
                click("sync-now")
                click("sync-password-enabled")
                text("ui-private-password")
                scroll("sync-setup-list", 5)
                assertTrue(awaitNode("sync-create-space").config.contains(SemanticsProperties.Disabled))
                click("sync-password-ack")
                click("sync-create-space")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("password", f.descriptor().mode)
                assertEquals("password", f.runtime.connection()?.protectionMode)
                assertTrue(SyncSpaceCrypto.unlock(f.descriptor(), "ui-private-password").isSuccess)
                assertTrue(SyncSpaceCrypto.unlock(f.descriptor(), "wrong").isFailure)
                assertEquals(1, f.bootstrapWrites)
                assertEquals(0L, f.panel.state.value.queuedTotal)
                f.panel.dispatch(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                scroll("sync-settings-list", 5)
                click("sync-password-help")
                val requests = f.requestCount
                awaitNode("sync-password-help-back")
                click("sync-password-help-back")
                awaitNode("sync-password-help")
                assertEquals(SyncPanelPage.SETTINGS, f.panel.state.value.page)
                assertEquals(requests, f.requestCount)
            }
        }
    }

    @Test
    fun `real compose unlock help clears draft and returns to the same encrypted space`() = runBlocking {
        SyncPanelUiFixture().use { f ->
            f.existing("existing-ui-password")
            f.authorize()
            withScene(f) {
                click("sync-now")
                text("discarded-ui-password")
                click("sync-password-help")
                awaitNode("sync-password-help-back")
                val requests = f.requestCount
                click("sync-password-help-back")
                assertEquals("", awaitNode("sync-password-input").config[SemanticsProperties.EditableText].text)
                assertTrue(awaitNode("sync-password-help").config[SemanticsProperties.Focused])
                assertEquals(requests, f.requestCount)
                text("wrong")
                click("sync-password-submit")
                withTimeout(5_000) { f.panel.state.first { it.passwordProblem == SyncPasswordProblem.INCORRECT } }
                assertNull(f.runtime.connection())
                text("existing-ui-password")
                click("sync-password-submit")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("password", f.runtime.connection()?.protectionMode)
            }
        }
    }

    private suspend fun withScene(f: SyncPanelUiFixture, action: suspend Ui.() -> Unit) {
        val scene = ImageComposeScene(560, 800, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        try {
            scene.setContent { MaterialTheme { SyncPanelContent(f.panel, onOpenBrowser = {}, onCopyCode = {}) } }
            f.panel.dispatch(SyncPanelAction.Open)
            Ui(scene).action()
        } finally {
            scene.close()
        }
    }

    private class Ui(private val scene: ImageComposeScene) {
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun find(tag: String) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.firstOrNull {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
        }
        suspend fun render() {
            repeat(4) {
                scene.render()
                yield()
            }
        }
        suspend fun awaitNode(tag: String): SemanticsNode = withTimeout(5_000) {
            while (find(tag) == null) render()
            render()
            requireNotNull(find(tag))
        }
        suspend fun click(tag: String) {
            requireNotNull(awaitNode(tag).config[SemanticsActions.OnClick].action).invoke()
            render()
        }
        suspend fun text(value: String) {
            requireNotNull(awaitNode("sync-password-input").config[SemanticsActions.SetText].action)
                .invoke(AnnotatedString(value))
            render()
        }
        suspend fun scroll(tag: String, index: Int) {
            requireNotNull(awaitNode(tag).config[SemanticsActions.ScrollToIndex].action).invoke(index)
            render()
        }
    }

    @Test
    fun missingInstallationPresentsDedicatedRepoStepsAndProductionRecheck() = runBlocking {
        MockWebServer().use { server ->
            var appInstalledWithOtherRepositories = false
            val authorizedRepositories = listOf("reader-data", "comic-backups").mapIndexed { index, name ->
                """{"id":${91 + index},"name":"$name","full_name":"fixture-owner/$name","owner":{"id":42,"login":"fixture-owner","type":"User"},"private":true,"permissions":{"push":true},"size":1,"default_branch":"main","archived":false,"disabled":false}"""
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/user" -> MockResponse(body = """{"id":42,"login":"fixture-owner","type":"User"}""")
                        path == "/user/installations" -> MockResponse(
                            body = if (appInstalledWithOtherRepositories) {
                                """{"installations":[{"id":55,"app_slug":"mihon-desktop","account":{"id":42,"type":"User"},"suspended_at":null,"repository_selection":"selected","permissions":{"contents":"write","metadata":"read"}}]}"""
                            } else {
                                """{"installations":[]}"""
                            },
                        )
                        path == "/user/installations/55/repositories" -> MockResponse(
                            body = """{"repositories":[${authorizedRepositories.joinToString()}],"total_count":2}""",
                        )
                        path.startsWith("/repos/fixture-owner/reader-data/git/ref/heads/") ||
                            path.startsWith("/repos/fixture-owner/comic-backups/git/ref/heads/") ->
                            MockResponse(code = 404, body = "{}")
                        path == "/repos/fixture-owner/mihon-sync" -> MockResponse(code = 404, body = "{}")
                        else -> MockResponse(code = 404, body = "{}")
                    }
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
            val opened = mutableListOf<String>()
            val scene = ImageComposeScene(360, 720, coroutineContext = coroutineContext) {}
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
                    MaterialTheme { SyncPanelContent(panel, onOpenBrowser = opened::add, onCopyCode = {}) }
                }
                panel.dispatch(SyncPanelAction.Open)
                requireNotNull(awaitNode("sync-now").config[SemanticsActions.OnClick].action).invoke()
                withTimeout(5_000) {
                    panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy }
                }

                awaitNode("sync-setup-error")
                assertTrue(
                    node("sync-create-private-repo") != null,
                    "missing-installation guidance should link to GitHub repository creation",
                )
                assertTrue(
                    node("sync-install-app") != null,
                    "missing-installation guidance should link to the App installation flow",
                )
                assertTrue(
                    node("sync-recheck-installation") != null,
                    "browser completion should have an explicit production recheck action",
                )
                assertEquals("fixture-owner", panel.state.value.setupAccountLogin)
                assertNull(node("sync-password-input"), "an uninstalled account must not enter initialization")

                requireNotNull(node("sync-create-private-repo")?.config?.get(SemanticsActions.OnClick)?.action).invoke()
                assertEquals(
                    "https://github.com/new?name=mihon-sync&visibility=private&owner=fixture-owner",
                    opened.last(),
                )
                requireNotNull(node("sync-install-app")?.config?.get(SemanticsActions.OnClick)?.action).invoke()
                assertEquals("https://github.com/apps/mihon-desktop/installations/new", opened.last())
                requireNotNull(
                    node("sync-recheck-installation")?.config?.get(SemanticsActions.OnClick)?.action,
                ).invoke()
                withTimeout(5_000) {
                    panel.state.first {
                        it.setupProblem == mihon.data.sync.auth.SyncDiscoveryProblem.NEEDS_INSTALLATION && !it.setupBusy
                    }
                }
                assertNull(
                    node("sync-password-input"),
                    "retry must revalidate installation before exposing initialization",
                )
                assertTrue(
                    server.requestCount >= 4,
                    "the explicit recheck must query GitHub through the production controller",
                )

                appInstalledWithOtherRepositories = true
                requireNotNull(
                    node("sync-recheck-installation")?.config?.get(SemanticsActions.OnClick)?.action,
                ).invoke()
                withTimeout(5_000) {
                    panel.state.first {
                        it.setupProblem == mihon.data.sync.auth.SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS &&
                            !it.setupBusy
                    }
                }
                assertEquals(
                    mihon.data.sync.auth.SyncAppInstallation(
                        55,
                        mihon.data.sync.auth.SyncRepositorySelection.SELECTED,
                        authorizedRepositoryCount = 2,
                    ),
                    panel.state.value.setupInstallation,
                )
                awaitNode("sync-installation-scope-warning")
                requireNotNull(awaitNode("sync-install-app").config[SemanticsActions.OnClick]?.action).invoke()
                assertEquals("https://github.com/settings/installations/55", opened.last())
                assertNull(node("sync-password-input"), "a missing or inaccessible target cannot start initialization")
                assertTrue(
                    server.requestCount >= 8,
                    "recheck must read the installation and its paginated repositories",
                )
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
