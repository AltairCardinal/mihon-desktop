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
import kotlinx.serialization.json.buildJsonObject
import mihon.data.sync.SyncGitHttpFixture
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.journal.NoopBackupRestoreSync
import mihon.data.sync.journal.SyncRestoreOutcome
import mihon.data.sync.runtime.SyncConnection
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncRecoveryAction
import mihon.data.sync.runtime.SyncRecoveryActionAvailability
import mihon.data.sync.runtime.SyncRecoveryPlatformAction
import mihon.data.sync.runtime.SyncRecoveryPlatformRequest
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubStoredCredential
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
    fun `missing effective creation permission offers browser creation before any native request`() = fixture(
        deletedPendingSetup = true,
        nativePermission = null,
    ) {
        click("sync-now")
        click("sync-authorize")
        withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
        click("sync-setup-recovery-open")
        click("sync-recovery-create")
        click("sync-confirm-question")
        withTimeout(5_000) {
            panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY && !it.setupBusy }
        }
        awaitNode("sync-create-private-repo")
        scene.render()
        assertTrue(
            node("sync-repository-create-native") == null,
            "native creation must not be offered without permission",
        )
        click("sync-create-private-repo")
        assertTrue(opened.single().startsWith("https://github.com/new?name=mihon-sync&visibility=private"))
        repositoryCreated = true
        repositoryAuthorized = true
        click("sync-recovery-repository-created")
        withTimeout(5_000) { panel.state.first { it.question == SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY } }
        assertEquals(oldConnection, runtime.connection())
        click("sync-confirm-question")
        withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.NEW_PASSWORD && !it.setupBusy } }
        click("sync-password-submit")
        withTimeout(20_000) {
            panel.state.first { it.setupStep == SyncSetupStep.COMPLETE && it.run?.state == SyncRunState.SUCCEEDED }
        }
        assertTrue(paths.none { it == "/user/repos" })
    }

    @Test
    fun `creation permission revoked after proposal blocks the real confirmation POST`() = fixture(
        deletedPendingSetup = true,
    ) {
        click("sync-now")
        click("sync-authorize")
        withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
        click("sync-setup-recovery-open")
        click("sync-recovery-create")
        click("sync-confirm-question")
        withTimeout(5_000) {
            panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY && !it.setupBusy }
        }
        click("sync-repository-create-native")
        withTimeout(5_000) { panel.state.first { it.question == SyncPanelQuestion.CREATE_REPOSITORY } }
        nativePermission = null
        click("sync-confirm-question")
        withTimeout(5_000) { panel.state.first { !it.setupBusy } }
        assertFalse(paths.contains("/user/repos"), "revoked permission must never be tested with a creation POST")
        assertEquals(SyncDiscoveryProblem.NEEDS_CREATION_PERMISSION, panel.state.value.setupProblem)
        assertEquals(oldConnection, runtime.connection())
    }

    @Test
    fun `unknown creation permission retains browser path and recheck reads the newly accepted grant`() = fixture(
        deletedPendingSetup = true,
        nativePermission = null,
    ) {
        click("sync-now")
        click("sync-authorize")
        withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
        click("sync-setup-recovery-open")
        click("sync-recovery-create")
        installationFailure = 500
        click("sync-confirm-question")
        withTimeout(5_000) {
            panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY && !it.setupBusy }
        }
        awaitNode("sync-repository-permission-status")
        assertEquals(SyncDiscoveryProblem.RETRYABLE, panel.state.value.creationPermissionProblem)
        assertTrue(node("sync-repository-create-native") == null)
        click("sync-create-private-repo")
        assertEquals(1, opened.size)
        installationFailure = null
        nativePermission = "repository_creation"
        click("sync-repository-permission-recheck")
        withTimeout(5_000) { panel.state.first { it.setupInstallation?.canCreateRepository == true && !it.setupBusy } }
        awaitNode("sync-repository-create-native")
        assertTrue(paths.none { it == "/user/repos" })
        assertEquals(oldConnection, runtime.connection())
    }

    @Test
    fun `slow creation permission check can close before HTTP finishes and cannot unlock reopened session`() = fixture(
        deletedPendingSetup = true,
        nativePermission = null,
    ) {
        click("sync-now")
        click("sync-authorize")
        withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
        click("sync-setup-recovery-open")
        click("sync-recovery-create")
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        installationBarrier = entered to released
        try {
            click("sync-confirm-question")
            withTimeout(5_000) {
                while (entered.count != 0L) yield()
            }
            click("sync-close")
            withTimeout(1_000) { panel.state.first { !it.visible } }
            nativePermission = "repository_creation"
            panel.dispatch(SyncPanelAction.Open)
            withTimeout(1_000) { panel.state.first { it.visible && it.page == SyncPanelPage.MAIN } }
        } finally {
            released.countDown()
            installationBarrier = null
        }
        repeat(10) {
            scene.render()
            yield()
        }
        assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
        assertTrue(panel.state.value.setupInstallation?.canCreateRepository != true)
        assertFalse(panel.state.value.setupBusy)
        assertTrue(paths.none { it == "/user/repos" })
    }

    @Test
    fun `actual partial backup callback remains actionable after closing and reopening sync`() = fixture {
        click("sync-recovery-open")
        withTimeout(5_000) { panel.state.first { it.page == SyncPanelPage.RECOVERY } }
        for (index in 0..12) {
            scroll("sync-recovery-page", index)
            if (node("sync-recovery-backup") != null) break
        }
        click("sync-recovery-backup")
        withTimeout(5_000) {
            while (platformRequests.isEmpty()) {
                scene.render()
                yield()
            }
        }
        val request = platformRequests.single()
        val failure = SyncObjectKey(
            SyncObjectType.MANGA,
            sourceId = "7",
            originalUrl = "/failed",
        )
        val observer = SyncRecoveryBackupObserver(NoopBackupRestoreSync) { result ->
            panel.dispatch(SyncPanelAction.RecoveryPlatformCompleted(request.requestId, result))
        }
        observer.expectObjects(listOf(failure))
        runCatching { observer.restoreManga(null, 7, "/failed") { error("actual restore unit failed") } }
        observer.errorCount(1)
        observer.finish(null, SyncRestoreOutcome.PARTIAL)
        withTimeout(5_000) { panel.state.first { it.recoveryExternalScopes.isNotEmpty() } }
        click("sync-close")
        withTimeout(5_000) { panel.state.first { !it.visible } }
        panel.dispatch(SyncPanelAction.Open)
        withTimeout(5_000) { panel.state.first { it.visible && it.loaded && it.page == SyncPanelPage.MAIN } }
        awaitNode("sync-main-external-recovery")
        assertEquals(listOf(failure), panel.state.value.recoveryExternalScopes.single().failedObjects)
        assertEquals(1L, panel.state.value.recoveryExternalScopes.single().remaining)
        click("sync-main-external-recovery")
        withTimeout(5_000) { panel.state.first { it.page == SyncPanelPage.RECOVERY } }
        assertEquals(SyncRecoveryAction.BACKUP, panel.state.value.recoveryPrimaryAction.action)
        assertEquals(oldConnection, runtime.connection())
        assertEquals(oldCredential, runtime.credentials.read())
        assertTrue(methods.all { it == "GET" })
    }

    @Test
    fun `device code HTTP failure keeps its first screen and exposes real native network recovery`() = fixture(
        deviceCodeFails = true,
    ) {
        click("sync-now")
        click("sync-authorize")
        withTimeout(5_000) { panel.state.first { it.authFailure == GitHubAuthFailureReason.HTTP && !it.setupBusy } }
        assertEquals(SyncSetupStep.SIGN_IN, panel.state.value.setupStep)
        click("sync-auth-recovery-open")
        withTimeout(5_000) { panel.state.first { it.page == SyncPanelPage.RECOVERY } }
        click("sync-recovery-network")
        withTimeout(5_000) {
            while (platformRequests.isEmpty()) {
                scene.render()
                yield()
            }
        }
        assertEquals(SyncRecoveryPlatformAction.NETWORK, platformRequests.single().action)
        assertTrue(paths.contains("/device/code"))
        assertTrue(paths.none { it == "/access/token" })
        assertEquals(null, runtime.connection())
    }

    @Test
    fun `official installation checks next target without repeating authorization`() = fixture(
        missingInstallation = true,
    ) {
        click("sync-now")
        withTimeout(5_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
        assertEquals(SyncDiscoveryProblem.NEEDS_INSTALLATION, panel.state.value.setupProblem)
        click("sync-install-app")
        withTimeout(5_000) { panel.state.first { it.recoveryOfficialAction != null } }
        awaitNode("sync-recovery-official-waiting")
        assertEquals(listOf("https://github.com/apps/mihon-desktop/installations/new"), opened)
        installationAvailable = true
        val before = paths.size
        click("sync-setup-retry")
        withTimeout(5_000) {
            panel.state.first {
                !it.setupBusy && paths.size > before &&
                    it.setupProblem == SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS
            }
        }
        assertEquals(
            null,
            panel.state.value.recoveryOfficialAction,
            "the verified installation step must not be repeated",
        )
        val next = panel.state.value.recoveryPrimaryAction
        assertEquals(SyncRecoveryAction.AUTHORIZE_REPOSITORY, next.action)
        assertEquals(
            SyncRecoveryAction.CREATE_SPACE,
            (next.availability as SyncRecoveryActionAvailability.NeedsStep).step,
        )
        click("sync-repository-authorize-native")
        click("sync-confirm-question")
        withTimeout(5_000) { panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY && !it.setupBusy } }
        assertEquals(1, opened.size)
        assertTrue(paths.none { it == "/access/token" })
    }

    @Test
    fun `unfinished deleted setup recovers after real authorization to successful sync`() = fixture(
        deletedPendingSetup = true,
    ) {
        click("sync-now")
        click("sync-authorize")
        withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
        assertEquals(SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE, panel.state.value.setupProblem)
        assertFalse(panel.state.value.canChangeSpace)
        val error = awaitNode("sync-setup-error").config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertFalse(error.contains("已归档或停用"), error)
        assertTrue(
            listOf("可能已删除或失去访问权限", "may have been deleted or access permission lost").any(error::contains),
            error,
        )
        val credential = runtime.credentials.read()
        click("sync-setup-recovery-open")
        withTimeout(5_000) { panel.state.first { it.page == SyncPanelPage.RECOVERY } }
        click("sync-recovery-create")
        click("sync-confirm-question")
        withTimeout(5_000) { panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY && !it.setupBusy } }
        assertEquals(credential, runtime.credentials.read(), "accepted GitHub authorization must not be repeated")
        assertEquals(null, runtime.connection())
        assertEquals(1, paths.count { it == "/access/token" })
        click("sync-repository-create-native")
        click("sync-confirm-question")
        withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.NEW_PASSWORD && !it.setupBusy } }
        click("sync-password-submit")
        val completed = kotlinx.coroutines.withTimeoutOrNull(20_000) {
            panel.state.first {
                it.setupStep == SyncSetupStep.COMPLETE && it.run?.state == SyncRunState.SUCCEEDED
            }
        }
        assertNotNull(
            completed,
            "actual setup/sync did not complete: step=${panel.state.value.setupStep}, " +
                "problem=${panel.state.value.setupProblem}, run=${panel.state.value.run?.state}, " +
                "stop=${panel.state.value.run?.stopReason}, paths=${paths.takeLast(20)}",
        )
        assertEquals("mihon-sync", runtime.connection()?.repository?.name)
        assertEquals(1, paths.count { it == "/access/token" })
        assertEquals(1, paths.count { it == "/user/repos" })
        assertEquals("preserved", stored["unrelated-record"])
        assertTrue(
            stored.values.any {
                it.contains("fixture-attempt-00001") && it.contains("\"repositoryId\":99")
            },
            "the old fixed setup remains archived",
        )
        assertTrue(localMangas.getMangaByUrlAndSourceId("/survives", 7)!!.favorite)
    }

    @Test
    fun `first setup HTTP failure exposes native recovery without requiring an old binding`() = fixture(
        firstSetupFailure = true,
    ) {
        click("sync-now")
        withTimeout(5_000) { panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy } }
        click("sync-setup-recovery-open")
        withTimeout(5_000) { panel.state.first { it.page == SyncPanelPage.RECOVERY } }
        awaitNode("sync-recovery-network")
        click("sync-recovery-network")
        withTimeout(5_000) {
            while (platformRequests.isEmpty()) {
                scene.render()
                yield()
            }
        }
        assertEquals(SyncRecoveryPlatformAction.NETWORK, platformRequests.single().action)
        assertEquals(null, runtime.connection())
        assertTrue(methods.all { it == "GET" })
    }

    @Test
    fun `404 recovery uses accurate access uncertainty and primary create action in native UI`() = fixture {
        click("sync-recovery-open")
        withTimeout(5_000) { panel.state.first { it.page == SyncPanelPage.RECOVERY } }
        awaitNode("sync-recovery-create")
        val text = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.flatMap {
            if (it.config.contains(SemanticsProperties.Text)) {
                it.config[SemanticsProperties.Text].map { text -> text.text }
            } else {
                emptyList()
            }
        }.joinToString(" ")
        assertFalse(text.contains("已归档或停用"))
        assertTrue(
            listOf("可能已删除或失去访问权限", "may have been deleted or access permission lost").any(text::contains),
            text,
        )
        click("sync-recovery-create")
        click("sync-confirm-question")
        withTimeout(5_000) { panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY && !it.setupBusy } }
        assertEquals(oldConnection, runtime.connection())
        assertEquals(oldCredential, runtime.credentials.read())
        assertTrue(methods.all { it == "GET" })
    }

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
        withTimeout(5_000) {
            panel.state.first { it.question == SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY }
        }
        assertEquals(oldConnection, runtime.connection())
        assertEquals(oldCredential, runtime.credentials.read())
        assertEquals(beforeContinue, paths.size, "Preparing a manual target must not send requests before confirmation")
        click("sync-confirm-question")
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

    private fun fixture(
        firstSetupFailure: Boolean = false,
        deletedPendingSetup: Boolean = false,
        missingInstallation: Boolean = false,
        deviceCodeFails: Boolean = false,
        nativePermission: String? = "administration",
        block: suspend Fixture.() -> Unit,
    ) = runBlocking {
        val fixture = Fixture(
            ImageComposeScene(560, 720, coroutineContext = currentCoroutineContext()) {},
            firstSetupFailure,
            deletedPendingSetup,
            missingInstallation,
            deviceCodeFails,
            nativePermission,
        )
        try {
            fixture.initialize()
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        val scene: ImageComposeScene,
        val firstSetupFailure: Boolean,
        val deletedPendingSetup: Boolean,
        val missingInstallation: Boolean,
        val deviceCodeFails: Boolean,
        @Volatile var nativePermission: String?,
    ) {
        @Volatile var installationAvailable = !missingInstallation

        @Volatile var installationFailure: Int? = null

        @Volatile var installationBarrier:
            Pair<java.util.concurrent.CountDownLatch, java.util.concurrent.CountDownLatch>? =
            null
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val opened = mutableListOf<String>()
        val platformRequests = mutableListOf<SyncRecoveryPlatformRequest>()
        val git = if (deletedPendingSetup) {
            SyncGitHttpFixture(
                empty = true,
                repositoryOverride = SyncRepository("fixture", "mihon-sync", "mihon-sync-v1"),
            )
        } else {
            null
        }

        @Volatile var repositoryCreated = false

        @Volatile var repositoryAuthorized = false
        private var creationDescription = ""
        private fun createdRepository(): String = buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(199))
            put("name", kotlinx.serialization.json.JsonPrimitive("mihon-sync"))
            put("full_name", kotlinx.serialization.json.JsonPrimitive("fixture/mihon-sync"))
            put(
                "owner",
                buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(42))
                    put("login", kotlinx.serialization.json.JsonPrimitive("fixture"))
                    put("type", kotlinx.serialization.json.JsonPrimitive("User"))
                },
            )
            put("description", kotlinx.serialization.json.JsonPrimitive(creationDescription))
            put("private", kotlinx.serialization.json.JsonPrimitive(true))
            put("archived", kotlinx.serialization.json.JsonPrimitive(false))
            put("disabled", kotlinx.serialization.json.JsonPrimitive(false))
            put("size", kotlinx.serialization.json.JsonPrimitive(0))
            put("default_branch", kotlinx.serialization.json.JsonPrimitive("main"))
            put(
                "permissions",
                buildJsonObject {
                    put("admin", kotlinx.serialization.json.JsonPrimitive(true))
                    put("push", kotlinx.serialization.json.JsonPrimitive(true))
                },
            )
        }.toString()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.url.encodedPath
                    methods += request.method
                    if (request.url.encodedPath == "/user/installations") {
                        installationBarrier?.let { (entered, released) ->
                            entered.countDown()
                            check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        }
                    }
                    if (deletedPendingSetup) {
                        if (request.url.encodedPath == "/user/repos" && request.method == "POST") {
                            val creation = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.utf8())
                            creationDescription = (creation as kotlinx.serialization.json.JsonObject)["description"]
                                .toString().removeSurrounding("\"")
                            repositoryCreated = true
                            return MockResponse(code = 201, body = createdRepository())
                        }
                        if (request.url.encodedPath == "/user/installations/7/repositories/199" &&
                            request.method == "PUT"
                        ) {
                            repositoryAuthorized = true
                            return MockResponse(code = 204)
                        }
                        if (request.url.encodedPath in setOf("/repos/fixture/mihon-sync", "/repositories/199")) {
                            return if (repositoryCreated) {
                                MockResponse(body = createdRepository())
                            } else {
                                MockResponse(code = 404, body = "{}")
                            }
                        }
                        if (repositoryCreated && request.url.encodedPath.startsWith("/repos/fixture/mihon-sync/")) {
                            return git!!.server.dispatcher.dispatch(request)
                        }
                    }
                    return when (request.url.encodedPath) {
                        "/device/code" -> if (deviceCodeFails) {
                            MockResponse(code = 500, body = "{}")
                        } else {
                            MockResponse(
                                body = (
                                    "{\"device_code\":\"fixture-code\",\"user_code" +
                                        "\":\"LOCAL\",\"verification_uri\":\"https://gi" +
                                        "thub.com/login/device\",\"expires_in\":600," +
                                        "\"interval\":1}"
                                    ),
                            )
                        }
                        "/access/token" -> MockResponse(
                            body = """{"access_token":"fixture-new-token","token_type":"bearer","scope":""}""",
                        )
                        "/user" -> if (firstSetupFailure) {
                            MockResponse(code = 500, body = "{}")
                        } else {
                            MockResponse(body = """{"id":42,"login":"fixture","type":"User"}""")
                        }
                        "/user/installations" -> if (installationFailure != null) {
                            MockResponse(code = requireNotNull(installationFailure), body = "{}")
                        } else if (!installationAvailable) {
                            MockResponse(body = """{"installations":[]}""")
                        } else {
                            MockResponse(
                                body = """
                                {"installations":[{"id":7,"app_slug":"mihon-desktop",
                                "account":{"id":42,"type":"User"},"suspended_at":null,
                                "repository_selection":"selected",
                                "permissions":{"contents":"write","metadata":"read"
                                ${nativePermission?.let { ",\"$it\":\"write\"" }.orEmpty()}}}]}
                                """.trimIndent(),
                            )
                        }
                        "/user/installations/7/repositories" -> MockResponse(
                            body = if (repositoryAuthorized) {
                                """{"repositories":[${createdRepository()}],"total_count":1}"""
                            } else {
                                """{"repositories":[],"total_count":0}"""
                            },
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
        val localMangas = tachiyomi.data.manga.MangaRepositoryImpl(handler, creators)

        // A v2 persisted connection fixture is read by production storage; no storage implementation is mocked.
        val stored = ConcurrentHashMap<String, String>().apply {
            put(
                "space-1413a576fe80d48eb03e00f8d72e177007f4805e0a23778ba83bf044b13baf6b",
                (
                    "{\"version\":2,\"accountId\":42,\"accountLogi" +
                        "n\":\"fixture\",\"repositoryId\":99,\"owner\":\"" +
                        "fixture\",\"repository\":\"old-sync\",\"branch" +
                        "\":\"mihon-sync-v1\",\"material\":{\"descripto" +
                        "r\":\"{\\\"application\\\":\\\"mihon-sync\\\",\\\"sp" +
                        "aceFormatVersion\\\":2,\\\"eventProtocolVers" +
                        "ion\\\":1,\\\"spaceId\\\":\\\"old-space\\\",\\\"gene" +
                        "ration\\\":1,\\\"protection\\\":{\\\"mode\\\":\\\"no" +
                        "ne\\\"}}\",\"keyHex\":null},\"actorId\":\"fixtur" +
                        "e-actor\",\"epoch\":1}"
                    ),
            )
            if (deletedPendingSetup) {
                put(
                    "sync-setup-v3-42",
                    (
                        "{\"version\":3,\"accountId\":42,\"accountLogi" +
                            "n\":\"fixture\",\"attemptId\":\"fixture-attemp" +
                            "t-00001\",\"attemptNonce\":\"fixture-nonce-0" +
                            "00001\",\"newSpace\":false,\"material\":{\"des" +
                            "criptor\":\"{\\\"application\\\":\\\"mihon-sync\\" +
                            "\",\\\"spaceFormatVersion\\\":2,\\\"eventProtoc" +
                            "olVersion\\\":1,\\\"spaceId\\\":\\\"old-space\\\"," +
                            "\\\"generation\\\":1,\\\"protection\\\":{\\\"mode\\" +
                            "\":\\\"none\\\"}}\",\"keyHex\":null},\"stage\":\"SP" +
                            "ACE_CONFIRMED\",\"repositoryId\":99,\"owner\"" +
                            ":\"fixture\",\"repository\":\"old-sync\",\"bran" +
                            "ch\":\"mihon-sync-v1\"}"
                        ),
                )
            }
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
            GitHubAuthEndpoints(
                apiBaseUrl = server.url("/").toString().removeSuffix("/"),
                deviceCodeUrl = server.url("/device/code").toString(),
                accessTokenUrl = server.url("/access/token").toString(),
            ),
        )
        val panel get() = runtime.panel
        var oldConnection: SyncConnection? = null
        var oldCredential: GitHubStoredCredential? = null

        suspend fun initialize() {
            if (deletedPendingSetup) {
                stored["unrelated-record"] = "preserved"
                localMangas.insertNetworkManga(
                    listOf(
                        tachiyomi.domain.manga.model.Manga.create().copy(
                            source = 7,
                            url = "/survives",
                            title = "Local library survives setup recovery",
                            favorite = true,
                        ),
                    ),
                )
            }
            if (!deletedPendingSetup && !deviceCodeFails) {
                runtime.credentials.replace(
                    null,
                    GitHubAccessToken("fixture-token", null, "bearer", emptySet(), null, null),
                )
            }
            if (!firstSetupFailure && !deletedPendingSetup && !missingInstallation && !deviceCodeFails) {
                runtime.baseline.connectAndImport(
                    "old-space",
                    1,
                    SyncRepository("fixture", "old-sync", "mihon-sync-v1"),
                    "fixture-actor",
                    1,
                )
            }
            oldConnection = runtime.connection()
            oldCredential = runtime.credentials.read()
            scene.setContent {
                MaterialTheme {
                    SyncPanelContent(
                        panel,
                        onOpenBrowser = opened::add,
                        onCopyCode = {},
                        onOpenRecoveryPlatform = platformRequests::add,
                    )
                }
            }
            panel.dispatch(SyncPanelAction.Open)
            withTimeout(5_000) { panel.state.first { it.visible && it.loaded } }
            if (firstSetupFailure || deletedPendingSetup || missingInstallation || deviceCodeFails) return
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
            git?.close()
        }
    }
}
