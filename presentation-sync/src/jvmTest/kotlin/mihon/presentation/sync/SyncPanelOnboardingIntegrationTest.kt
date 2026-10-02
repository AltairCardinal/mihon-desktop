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

    @Test
    fun reauthorizationKeepsOldRunAndShowsDiscoveryFailureAndScopeWarning() = runBlocking {
        MockWebServer().use { server ->
            var appInstalledWithOtherRepositories = false
            var discoveryFails = true
            val authorizedRepositories = listOf("reader-data", "comic-backups").mapIndexed { index, name ->
                """{"id":${91 + index},"name":"$name","full_name":"fixture-owner/$name","owner":{"id":42,"login":"fixture-owner","type":"User"},"private":true,"permissions":{"push":true},"size":1,"default_branch":"main","archived":false,"disabled":false}"""
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/device/code" -> MockResponse(
                            body = """
                                {"device_code":"fixture-code","user_code":"LOCAL",
                                "verification_uri":"https://github.com/login/device","expires_in":600,"interval":1}
                            """.trimIndent(),
                        )
                        path == "/access/token" -> MockResponse(
                            body = """{"access_token":"fixture-new-token","token_type":"bearer","scope":""}""",
                        )
                        path == "/user" -> MockResponse(body = """{"id":42,"login":"fixture-owner","type":"User"}""")
                        path == "/user/installations" && discoveryFails -> MockResponse(code = 503, body = "{}")
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
                GitHubAuthEndpoints(
                    deviceCodeUrl = server.url("/device/code").toString(),
                    accessTokenUrl = server.url("/access/token").toString(),
                    apiBaseUrl = server.url("/").toString(),
                ),
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
                runtime.baseline.connectAndImport(
                    "space",
                    1,
                    mihon.domain.sync.transport.SyncRepository("fixture", "sync", "main"),
                    "actor",
                    1,
                )
                val oldRun = runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                runtime.runStore.pause(oldRun.runId)
                scene.setContent {
                    MaterialTheme { SyncPanelContent(panel, onOpenBrowser = opened::add, onCopyCode = {}) }
                }
                panel.dispatch(SyncPanelAction.Open)
                withTimeout(5_000) { panel.state.first { it.loaded && it.run?.runId == oldRun.runId } }
                panel.dispatch(SyncPanelAction.BeginSetup)
                withTimeout(5_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
                panel.dispatch(SyncPanelAction.Authorize)
                withTimeout(5_000) {
                    while (runtime.credentials.read()?.credential?.accessToken != "fixture-new-token") yield()
                }
                withTimeout(5_000) {
                    panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy }
                }

                assertEquals(oldRun.runId, panel.state.value.run?.runId)
                assertEquals(mihon.data.sync.runtime.SyncRunState.PAUSED_USER, panel.state.value.run?.state)
                awaitNode("sync-setup-error")
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.RETRYABLE, panel.state.value.setupProblem)
                val requestsBeforeRetry = server.requestCount
                discoveryFails = false
                requireNotNull(awaitNode("sync-setup-retry").config[SemanticsActions.OnClick].action).invoke()
                withTimeout(5_000) {
                    panel.state.first {
                        it.setupProblem == mihon.data.sync.auth.SyncDiscoveryProblem.NEEDS_INSTALLATION && !it.setupBusy
                    }
                }
                assertTrue(server.requestCount > requestsBeforeRetry, "retry must perform real discovery")
                assertEquals(oldRun.runId, panel.state.value.run?.runId)
                awaitNode("sync-setup-error")
                appInstalledWithOtherRepositories = true
                requireNotNull(
                    awaitNode("sync-recheck-installation").config[SemanticsActions.OnClick].action,
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
                assertEquals(oldRun.runId, panel.state.value.run?.runId)
                assertEquals(
                    mihon.data.sync.runtime.SyncRunState.PAUSED_USER,
                    runtime.runStore.get(oldRun.runId)?.state,
                )
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
