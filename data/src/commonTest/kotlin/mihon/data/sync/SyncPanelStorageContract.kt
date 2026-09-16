package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.crypto.SyncRecoveryCodec
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.concurrent.ConcurrentHashMap

@Timeout(30)
abstract class SyncPanelStorageContract {
    protected abstract fun open(): SyncRuntimeStorageContract.Storage
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync-v1")

    @Test
    fun `opening observes upload queue rather than existing library totals`() = runBlocking {
        open().use { storage ->
            storage.favorite("/before-connection")
            storage.connect("local", repository)
            storage.favorite("/queued-one")
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                assertTrue(panel.state.value.visible)
                assertTrue(panel.state.value.loaded)
                assertEquals(1L, panel.state.value.queuedMembership)
                assertEquals(0L, panel.state.value.queuedReading)
                assertEquals(1L, panel.state.value.importRemaining)
                assertEquals(2, storage.manga.getLibraryManga().size)
            }
        }
    }

    @Test
    fun `settings return and close retain local schedule while clearing session selection`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            withPanel(storage) { panel, runtime ->
                panel.act(SyncPanelAction.Open)
                val due = panel.state.value.nextSyncAtMillis
                assertEquals(3_601_000L, due)
                panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                panel.act(SyncPanelAction.SetPeriod(15))
                panel.act(SyncPanelAction.SetStartup(false))
                panel.act(SyncPanelAction.SetDeviceName("电脑 "))
                assertEquals("电脑 ", panel.state.value.deviceName)
                panel.act(SyncPanelAction.SetDeviceName("电脑 B"))
                panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
                assertEquals(15, runtime.preferences.intervalMinutes())
                assertFalse(runtime.preferences.startup.get())
                assertEquals("电脑 B", runtime.preferences.deviceName.get())
                panel.act(SyncPanelAction.SelectionMode(true))
                panel.act(SyncPanelAction.Close)
                panel.act(SyncPanelAction.Open)
                assertFalse(panel.state.value.selecting)
                assertNull(panel.state.value.notice)
                assertEquals(901_000L, panel.state.value.nextSyncAtMillis)
            }
        }
    }

    @Test
    fun `all selection covers unloaded rows and freezes confirmation before new arrivals`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 120)
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                assertEquals(120L, panel.state.value.pendingTotal)
                assertTrue(panel.state.value.pending.size in 1..100)
                assertTrue(panel.state.value.hasMore)
                panel.act(SyncPanelAction.SelectAll)
                assertEquals(120, panel.state.value.selected.size)
                panel.act(SyncPanelAction.InvertSelection)
                assertTrue(panel.state.value.selected.isEmpty())
                panel.act(SyncPanelAction.SelectAll)
                panel.act(
                    SyncPanelAction.PrepareDecision(SyncCancellationDecision.KEEP_LOCAL, SyncDecisionScope.SELECTED),
                )
                val confirmation = panel.state.value.confirmation
                assertNotNull(confirmation)
                assertEquals(120L, confirmation!!.manga)
                pending(storage, 1, offset = 120)
                val frozen = storage.handler.await {
                    sync_inboxQueries.getBulkItems(confirmation.jobId, 1000).executeAsList()
                }
                assertEquals(120, frozen.size)
                assertEquals(121, storage.manga.getLibraryManga().size)
                panel.act(SyncPanelAction.CancelDecision)
                assertNull(panel.state.value.confirmation)
                assertEquals(120, panel.state.value.selected.size)
            }
        }
    }

    @Test
    fun `range selection uses complete ordered pending identities and closes without decisions`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 5)
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                val ids = panel.state.value.pending.map { it.id }
                assertEquals(5, ids.size)
                panel.act(SyncPanelAction.ToggleItem(ids[1], range = true))
                panel.act(SyncPanelAction.ToggleItem(ids[4], range = true))
                assertEquals(ids.subList(1, 5).toSet(), panel.state.value.selected)
                panel.act(SyncPanelAction.Close)
                assertTrue(panel.state.value.selected.isEmpty())
                assertEquals(5, storage.projector.pending("space", 1).size)
            }
        }
    }

    protected fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    @Test
    fun `bulk confirmation performs real decisions and completed feedback never replays on reopen`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.KEEP_LOCAL, SyncDecisionScope.ALL))
                panel.act(SyncPanelAction.ConfirmDecision)
                withTimeout(5_000) { panel.state.first { it.notice?.bulk?.completed == 3L } }
                assertEquals(0L, panel.state.value.pendingTotal)
                assertEquals(0L, panel.state.value.queuedTotal)
                assertEquals(3, storage.manga.getLibraryManga().size)
                panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                panel.act(SyncPanelAction.Back)
                assertEquals(3L, panel.state.value.notice?.bulk?.completed)
                panel.act(SyncPanelAction.Close)
                panel.act(SyncPanelAction.Open)
                assertNull(panel.state.value.notice)
            }
        }
    }

    @Test
    fun `closing device authorization clears the code and never stores an unfinished credential`() = runBlocking {
        MockWebServer().use { auth ->
            auth.start()
            auth.enqueue(deviceCode())
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(auth)) { panel, runtime ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(3_000) { panel.state.first { it.deviceCode != null } }
                    panel.act(SyncPanelAction.Close)
                    assertNull(panel.state.value.deviceCode)
                    assertFalse(panel.state.value.setupBusy)
                    assertNull(runtime.credentials.read())
                    panel.act(SyncPanelAction.Open)
                    assertNull(panel.state.value.deviceCode)
                }
            }
        }
    }

    @Test
    fun `closing repository discovery cancels its busy state and allows setup again`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body("""{"installations":[]}""")
                    .headersDelay(1, java.util.concurrent.TimeUnit.SECONDS).build(),
            )
            server.enqueue(MockResponse(body = """{"installations":[]}"""))
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(server)) { panel, runtime ->
                    runtime.credentials.replace(
                        null,
                        GitHubAccessToken("fixture", null, "bearer", emptySet(), null, null),
                    )
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.BeginSetup)
                    assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS))
                    panel.act(SyncPanelAction.Close)
                    assertFalse(panel.state.value.setupBusy)
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.BeginSetup)
                    withTimeout(3_000) { panel.state.first { !it.setupBusy } }
                    assertEquals(SyncSetupStep.REPOSITORY, panel.state.value.setupStep)
                }
            }
        }
    }

    @Test
    fun `paused durable batch cannot be replaced by a new decision`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            withPanel(storage) { panel, runtime ->
                val job = runtime.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
                runtime.preferences.activeBulkJob("space", 1).set(job)
                panel.act(SyncPanelAction.Open)
                assertEquals(3L, panel.state.value.bulk?.remaining)
                assertFalse(panel.state.value.bulk!!.running)
                panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.CONFIRM, SyncDecisionScope.ALL))
                assertNull(panel.state.value.confirmation)
                assertEquals(job, panel.state.value.bulk?.jobId)
                panel.act(SyncPanelAction.ResumeBulk)
                withTimeout(5_000) { panel.state.first { it.notice?.bulk?.completed == 3L } }
                assertEquals(3, storage.manga.getLibraryManga().size)
            }
        }
    }

    @Test
    fun `expired device authorization can acquire a fresh code without reusing the old value`() = runBlocking {
        MockWebServer().use { auth ->
            auth.start()
            auth.enqueue(deviceCode())
            auth.enqueue(MockResponse(body = """{"error":"expired_token"}"""))
            auth.enqueue(deviceCode("NEXT-CODE"))
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(auth)) { panel, _ ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(4_000) { panel.state.first { it.authFailure != null } }
                    assertEquals(GitHubAuthFailureReason.EXPIRED, panel.state.value.authFailure)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(3_000) { panel.state.first { it.deviceCode?.userCode == "NEXT-CODE" } }
                    assertNull(panel.state.value.authFailure)
                    panel.act(SyncPanelAction.CancelAuthorization)
                }
            }
        }
    }

    @Test
    fun `saved recovery acknowledgement cannot confirm different newly generated material`() = runBlocking {
        authorized { panel, _, _ ->
            val candidate = panel.state.value.repositories.single().repository
            panel.act(SyncPanelAction.ChooseRepository(candidate, true))
            val previous = panel.state.value.recoveryText
            panel.act(SyncPanelAction.ChooseRepository(candidate, true))
            assertTrue(previous != panel.state.value.recoveryText)
            panel.act(SyncPanelAction.RecoverySaved(previous))
            panel.act(SyncPanelAction.PrepareMerge)
            assertEquals(SyncSetupStep.RECOVERY, panel.state.value.setupStep)
            assertFalse(panel.state.value.recoverySaved)
        }
    }

    @Test
    fun `inactive space stops a running durable batch without consuming its remaining decisions`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            val id = storage.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = object : tachiyomi.domain.creator.repository.CreatorArchiveBootstrap {
                override suspend fun awaitReady() {
                    storage.bootstrap.awaitReady()
                    entered.complete(Unit)
                    release.await()
                }
            }
            withPanel(storage, bootstrap = gate) { panel, runtime ->
                runtime.preferences.activeBulkJob("space", 1).set(id)
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.ResumeBulk)
                withTimeout(3_000) { entered.await() }
                storage.baseline.connectAndImport("other", 1, repository, "other-local", 1)
                release.complete(Unit)
                withTimeout(3_000) { panel.awaitBulkIdle() }
                assertEquals(3L, storage.projector.bulkProgress(id).queued)
            }
        }
    }

    @Test
    fun `switching spaces retains each paused bulk resume entry`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            val original = storage.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
            withPanel(storage) { panel, runtime ->
                runtime.preferences.activeBulkJob("space", 1).set(original)
                panel.act(SyncPanelAction.Open)
                assertEquals(original, panel.state.value.bulk?.jobId)
                storage.baseline.connectAndImport("other", 1, repository, "other-local", 1)
                pending(storage, 1, offset = 100, spaceId = "other")
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.KEEP_LOCAL, SyncDecisionScope.ALL))
                panel.act(SyncPanelAction.ConfirmDecision)
                withTimeout(5_000) { panel.state.first { it.notice?.bulk?.completed == 1L } }
                storage.connect("local", repository)
                panel.act(SyncPanelAction.Open)
                assertEquals(original, panel.state.value.bulk?.jobId)
                assertEquals(3L, panel.state.value.bulk?.remaining)
            }
        }
    }

    @Test
    fun `existing space requires valid recovery data before first merge confirmation`() = runBlocking {
        authorized { panel, runtime, _ ->
            val candidate = panel.state.value.repositories.single().repository
            panel.act(SyncPanelAction.ChooseRepository(candidate, newSpace = false))
            panel.act(SyncPanelAction.SetRecovery("invalid recovery"))
            panel.act(SyncPanelAction.PrepareMerge)
            assertEquals(SyncSetupStep.RECOVERY, panel.state.value.setupStep)
            assertTrue(panel.state.value.recoveryInvalid)
            assertNull(runtime.connection())
        }
    }

    @Test
    fun `new space connects only after recovery is saved and first merge is confirmed`() = runBlocking {
        authorized { panel, runtime, _ ->
            val candidate = panel.state.value.repositories.single().repository
            panel.act(SyncPanelAction.ChooseRepository(candidate, newSpace = true))
            val recoveryText = panel.state.value.recoveryText
            assertTrue(SyncRecoveryCodec.decode(recoveryText).isSuccess)
            panel.act(SyncPanelAction.PrepareMerge)
            assertEquals(SyncSetupStep.RECOVERY, panel.state.value.setupStep)
            assertNull(runtime.connection())
            panel.act(SyncPanelAction.RecoverySaved(recoveryText))
            panel.act(SyncPanelAction.PrepareMerge)
            assertEquals(SyncSetupStep.MERGE, panel.state.value.setupStep)
            assertNull(runtime.connection())
            panel.act(SyncPanelAction.ConfirmMerge)
            withTimeout(5_000) { panel.state.first { it.connection != null } }
            assertEquals(candidate, runtime.connection()?.repository)
            assertTrue(runtime.connection()?.enabled == true)
            panel.act(SyncPanelAction.ShowRecovery)
            assertEquals(recoveryText, panel.state.value.recoveryText)
            panel.act(SyncPanelAction.Close)
            assertEquals("", panel.state.value.recoveryText)
        }
    }

    private suspend fun authorized(
        block: suspend (SyncPanelController, SyncRuntime, SyncGitSafetyContractTest.GitFixture) -> Unit,
    ) {
        MockWebServer().use { auth ->
            auth.start()
            auth.enqueue(deviceCode())
            auth.enqueue(MockResponse(body = """{"access_token":"fixture-token","token_type":"bearer","scope":""}"""))
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val delegate = git.server.dispatcher
                git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                        "/user/installations" -> MockResponse(body = """{"installations":[{"id":1}]}""")
                        "/user/installations/1/repositories" -> MockResponse(
                            body = """
                                {"repositories":[{"full_name":"fixture-owner/private-sync",
                                "private":true,"permissions":{"push":true}}]}
                            """.trimIndent(),
                        )
                        else -> delegate.dispatch(request)
                    }
                }
                open().use { storage ->
                    withPanel(storage, endpoints = endpoints(auth).copy(apiBaseUrl = git.baseUrl)) { panel, runtime ->
                        panel.act(SyncPanelAction.Open)
                        panel.act(SyncPanelAction.Authorize)
                        withTimeout(5_000) { panel.state.first { it.repositories.isNotEmpty() } }
                        assertNotNull(runtime.credentials.read())
                        block(panel, runtime, git)
                    }
                }
            }
        }
    }

    @Test
    fun `pausing initial import still exchanges independent changes and resuming drains the baseline`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            git.transport().initialize(repository, "space", 1)
            open().use { storage ->
                storage.favorite("/baseline")
                withPanel(storage, endpoints = GitHubAuthEndpoints(apiBaseUrl = git.baseUrl)) { panel, runtime ->
                    runtime.credentials.replace(
                        null,
                        GitHubAccessToken("fixture", null, "bearer", emptySet(), null, null),
                    )
                    val recovery = SyncRecoveryCodec.generate(
                        "space",
                        1,
                        "fixture-recovery-key",
                        { ByteArray(32) { (it + 1).toByte() } },
                        1,
                    ).data
                    runtime.connect(repository, recovery)
                    storage.favorite("/independent")
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.PauseImport)
                    val result = runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                    assertEquals(1, result.uploaded)
                    panel.act(SyncPanelAction.Open)
                    assertTrue(panel.state.value.importPaused)
                    assertEquals(1L, panel.state.value.importRemaining)
                    assertEquals(0L, panel.state.value.queuedMembership)
                    assertEquals(0L, panel.state.value.queuedTotal)
                    panel.act(SyncPanelAction.ResumeImport)
                    withTimeout(5_000) { panel.state.first { it.importRemaining == 0L && !it.busy } }
                    assertFalse(panel.state.value.importPaused)
                    panel.act(SyncPanelAction.Open)
                    assertEquals(0L, panel.state.value.queuedTotal)
                }
            }
        }
    }

    private fun deviceCode(code: String = "FIRST-CODE") = MockResponse(
        body = """
            {"device_code":"fixture-secret","user_code":"$code",
            "verification_uri":"https://github.com/login/device","expires_in":600,"interval":1}
        """.trimIndent(),
    )

    private fun endpoints(server: MockWebServer) = GitHubAuthEndpoints(
        server.url("/device").toString(),
        server.url("/token").toString(),
        server.url("/").toString(),
    )

    private suspend fun SyncPanelController.act(action: SyncPanelAction) {
        dispatch(action)
        awaitIdle()
    }

    private suspend fun withPanel(
        storage: SyncRuntimeStorageContract.Storage,
        endpoints: GitHubAuthEndpoints = GitHubAuthEndpoints(),
        bootstrap: tachiyomi.domain.creator.repository.CreatorArchiveBootstrap = storage.bootstrap,
        block: suspend (SyncPanelController, SyncRuntime) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient()
        val secure = object : SyncSecureStore {
            private val values = mutableMapOf<String, String>()
            override suspend fun read(key: String) = values[key]
            override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean {
                if (values[key] != expected) return false
                if (value == null) values.remove(key) else values[key] = value
                return true
            }
        }
        val defaults = InMemoryPreferenceStore()
        val strings = ConcurrentHashMap<String, Preference<String>>()
        val preferences = object : PreferenceStore by defaults {
            override fun getString(key: String, defaultValue: String) =
                strings.computeIfAbsent(key) { defaults.getString(key, defaultValue) }
        }
        val runtime = SyncRuntime(
            storage.handler, bootstrap, storage.creators, storage.creators, { true }, secure,
            preferences, client, endpoints, clock = { 1000L },
        )
        val panel = SyncPanelController(runtime, storage.handler, scope) { 1000L }
        try {
            block(panel, runtime)
        } finally {
            panel.stop()
            scope.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun pending(
        storage: SyncRuntimeStorageContract.Storage,
        count: Int,
        offset: Int = 0,
        spaceId: String = "space",
    ) {
        val inbox = SyncInboxStore(storage.handler)
        repeat(count) { index ->
            val number = index + offset
            val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/remote-$number")
            val add = SyncEventEnvelope(
                1, spaceId, 1, "remote-$number", 1, 1, SyncCategory.FAVORITE,
                listOf(SyncEffect("membership", key, SyncField.FAVORITE, SyncEffectKind.ADD)), SyncOrigin.USER,
                batchId = "add-$number",
            )
            val remove = add.copy(
                seq = 2,
                batchId = "remove-$number",
                effects = listOf(
                    add.effects.single().copy(kind = SyncEffectKind.REMOVE, parents = listOf(add.ref("membership"))),
                ),
            )
            assertTrue(
                inbox.ingest(
                    SyncBatch(
                        1,
                        spaceId,
                        1,
                        "add-$number",
                        listOf(add),
                        listOf(SyncObjectDescriptor(key, "漫画 $number")),
                    ),
                ).accepted,
            )
            while (storage.projector.project(spaceId, 1) == 50) Unit
            assertTrue(inbox.ingest(SyncBatch(1, spaceId, 1, "remove-$number", listOf(remove))).accepted)
            while (storage.projector.project(spaceId, 1) == 50) Unit
        }
    }
}
