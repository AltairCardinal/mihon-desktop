package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.runtime.StoredSyncMaterial
import mihon.data.sync.runtime.StoredSyncSetup
import mihon.data.sync.runtime.SyncCreateProtection
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPasswordHelpSource
import mihon.data.sync.runtime.SyncPasswordProblem
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
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncInitializationIntent
import mihon.domain.sync.transport.SyncInitializationStage
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
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
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Path as NioPath

@Timeout(30)
abstract class SyncPanelStorageContract {
    @Test
    fun `panel failed bulk retry freezes a new confirmation and never replays applied or invalidated rows`() =
        runBlocking {
            open().use { storage ->
                storage.connect("local", repository)
                pending(storage, 3)
                storage.driver.execute(
                    null,
                    "CREATE TRIGGER fail_bulk BEFORE UPDATE OF favorite ON mangas WHEN OLD.url IN ('/remote-1','/remote-2') BEGIN SELECT RAISE(ABORT, 'temporary failure'); END",
                    0,
                )
                withPanel(storage) { panel, _ ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.CONFIRM, SyncDecisionScope.ALL))
                    val originalJob = requireNotNull(panel.state.value.confirmation).jobId
                    panel.act(SyncPanelAction.ConfirmDecision)
                    panel.awaitBulkIdle()
                    assertEquals(1L, storage.projector.bulkProgress(originalJob).outcomes["APPLIED"])
                    assertEquals(2L, storage.projector.bulkProgress(originalJob).outcomes["FAILED"])
                    storage.driver.execute(null, "DROP TRIGGER fail_bulk", 0)
                    val invalidated = storage.manga.getLibraryManga().single { it.manga.url == "/remote-2" }.manga
                    storage.manga.update(
                        tachiyomi.domain.manga.model.MangaUpdate(
                            invalidated.id,
                            favorite = false,
                            syncContext = mihon.domain.sync.SyncMutationContext.User,
                        ),
                    )
                    panel.act(SyncPanelAction.RetryFailedBulk(originalJob))
                    val retry = requireNotNull(panel.state.value.confirmation)
                    assertTrue(retry.jobId != originalJob)
                    assertEquals(1L, retry.total)
                    panel.act(SyncPanelAction.ConfirmDecision)
                    panel.awaitBulkIdle()
                    assertEquals(1L, storage.projector.bulkProgress(retry.jobId).outcomes["APPLIED"])
                    assertEquals(1L, storage.projector.bulkProgress(originalJob).outcomes["APPLIED"])
                    assertEquals(2L, storage.projector.bulkProgress(originalJob).outcomes["FAILED"])
                    assertTrue(storage.manga.getLibraryManga().isEmpty())
                }
            }
        }

    @Test
    fun `initialization changed checkpoint preserves exact required action and never mutates repository`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.authorize()
                    val material = SyncSpaceCrypto.create("missing-checkpoint-space", 1, "")
                    val setup = StoredSyncSetup(
                        accountId = f.accountId,
                        accountLogin = f.accountLogin,
                        attemptId = "missing-checkpoint-attempt-0001",
                        attemptNonce = "missing-checkpoint-nonce-0001",
                        newSpace = true,
                        material = StoredSyncMaterial.from(material),
                        stage = SyncInitializationStage.BOOTSTRAP_CONFIRMED,
                        repositoryId = 99,
                        owner = f.repository.owner,
                        repository = f.repository.name,
                        branch = f.repository.branch,
                        defaultBranch = "main",
                        confirmedBootstrapCommitSha = "a".repeat(40),
                        confirmedBootstrapTreeSha = "b".repeat(40),
                    )
                    f.runtime.onboarding.storage.save(setup, null)
                    val error = runCatching { f.runtime.onboarding.resume(setup) }.exceptionOrNull()
                    assertTrue(error is mihon.data.sync.runtime.SyncSetupException)
                    val failure = error as mihon.data.sync.runtime.SyncSetupException
                    assertEquals("INITIALIZATION_REQUIRES_ACTION", failure.problem.name)
                    assertEquals("BOOTSTRAP_CHANGED", failure.initialization?.reason?.name)
                    assertEquals(setup, f.runtime.onboarding.storage.pending(f.accountId))
                    assertEquals(0, f.repositoryWrites)
                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.BeginSetup)
                    withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                    assertEquals("BOOTSTRAP_CHANGED", f.panel.state.value.initializationFailure?.reason?.name)
                    assertEquals(0, f.repositoryWrites)
                }
            }
        }

    protected abstract fun open(): SyncRuntimeStorageContract.Storage
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync-v1")

    @Test
    fun `stop joins database and authorization observers without cancelling the caller scope`() = runBlocking {
        open().use { storage ->
            storage.connect("actor", repository)
            val parent = SupervisorJob()
            val callerScope = CoroutineScope(parent + Dispatchers.Default)
            val client = OkHttpClient()
            val runtime = SyncRuntime(
                storage.handler,
                storage.bootstrap,
                storage.creators,
                storage.creators,
                { true },
                MemorySyncSecureStore(),
                InMemoryPreferenceStore(),
                client,
            )
            val panel = SyncPanelController(runtime, storage.handler, callerScope)
            try {
                panel.act(SyncPanelAction.Open)
                val attached = parent.children.toList()
                panel.stop()
                assertTrue(parent.isActive, "Stopping a panel must preserve its caller scope")
                assertEquals(0, parent.children.count(), "All panel observers must be cancelled and joined")
                assertEquals(1, attached.size, "All observers must belong to the one panel lifetime")
            } finally {
                panel.stop()
                parent.cancelAndJoin()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `visible panel observes durable plan confirmations and cancels isolated run subscriptions`() = runBlocking {
        open().use { storage ->
            val subscriptions = java.util.concurrent.atomic.AtomicInteger()
            val readGate = java.util.concurrent.atomic.AtomicReference<
                Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>?,
                >(null)
            val tracked = SyncRuntimeStorageContract.Storage(
                storage.driver,
                object : DatabaseHandler by storage.handler {
                    override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                        val result = storage.handler.await(inTransaction, block)
                        if (result is mihon.data.sync.runtime.SyncRunSnapshot) {
                            readGate.getAndSet(null)?.let { (entered, release) ->
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                        return result
                    }
                    override fun <T : Any> subscribeToOneOrNull(block: Database.() -> app.cash.sqldelight.Query<T>) =
                        storage.handler.subscribeToOneOrNull(block)
                            .onStart { subscriptions.incrementAndGet() }
                            .onCompletion { subscriptions.decrementAndGet() }
                },
            )
            SyncOnboardingFixture(tracked).use { f ->
                tracked.connect("plan-observer", repository)
                val runs = f.runtime.runStore
                val run = runs.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertTrue(runs.claim(run.runId, "owner", 1))
                f.panel.act(SyncPanelAction.Open)
                withTimeout(5000) { f.panel.state.first { it.visible && it.run?.runId == run.runId } }
                withTimeout(5000) { while (subscriptions.get() < 2) kotlinx.coroutines.delay(10) }
                val planRead = CompletableDeferred<Unit>()
                val planRelease = CompletableDeferred<Unit>()
                readGate.set(planRead to planRelease)
                val stalePlanRefresh = async { f.panel.act(SyncPanelAction.Open) }
                withTimeout(5000) { planRead.await() }
                runs.freezePlan(
                    run.runId,
                    "owner",
                    listOf(
                        mihon.data.sync.runtime.SyncRunPlanBatch(
                            mihon.data.sync.runtime.SyncProgressDirection.UPLOAD,
                            "first",
                            3,
                        ),
                        mihon.data.sync.runtime.SyncRunPlanBatch(
                            mihon.data.sync.runtime.SyncProgressDirection.UPLOAD,
                            "second",
                            2,
                        ),
                    ),
                )
                withTimeout(5000) { f.panel.state.first { it.run?.plannedItems == 5L } }
                planRelease.complete(Unit)
                stalePlanRefresh.await()
                assertEquals(5L, f.panel.state.value.run!!.plannedItems)
                assertEquals(0L, f.panel.state.value.run!!.confirmedItems)
                val frames = mutableListOf<Pair<Long?, Long>>()
                val observing = launch {
                    f.panel.state.collect { current ->
                        current.run?.takeIf { it.runId == run.runId }?.let {
                            frames +=
                                it.plannedItems to it.confirmedItems
                        }
                    }
                }
                val countRead = CompletableDeferred<Unit>()
                val countRelease = CompletableDeferred<Unit>()
                readGate.set(countRead to countRelease)
                val staleCountRefresh = async { f.panel.act(SyncPanelAction.Open) }
                withTimeout(5000) { countRead.await() }
                runs.confirmed(run.runId, "owner", mihon.data.sync.runtime.SyncProgressDirection.UPLOAD, "first", 3)
                withTimeout(5000) { f.panel.state.first { it.run?.confirmedItems == 3L } }
                countRelease.complete(Unit)
                staleCountRefresh.await()
                assertEquals(3L, f.panel.state.value.run!!.confirmedItems)
                repeat(10) { f.panel.act(SyncPanelAction.Open) }
                assertEquals(5L, f.panel.state.value.run!!.plannedItems)
                f.panel.act(SyncPanelAction.Close)
                withTimeout(5000) { while (subscriptions.get() != 1) kotlinx.coroutines.delay(10) }
                runs.confirmed(run.runId, "owner", mihon.data.sync.runtime.SyncProgressDirection.UPLOAD, "second", 2)
                kotlinx.coroutines.delay(50)
                assertEquals(3L, f.panel.state.value.run!!.confirmedItems)
                f.panel.act(SyncPanelAction.Open)
                assertEquals(5L, f.panel.state.value.run!!.confirmedItems)
                runs.finish(run.runId, mihon.data.sync.runtime.SyncRunState.SUCCEEDED)
                f.panel.act(SyncPanelAction.Open)
                assertEquals(mihon.data.sync.runtime.SyncRunState.SUCCEEDED, f.panel.state.value.run!!.state)
                observing.cancelAndJoin()
                assertTrue(frames.isNotEmpty())
                assertTrue(frames.all { it.first == 5L }, frames.toString())
                assertTrue(
                    frames.zipWithNext().all { (before, after) ->
                        before.second <= after.second
                    },
                    frames.toString(),
                )
                val next = runs.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertTrue(runs.claim(next.runId, "next-owner", 1))
                f.panel.act(SyncPanelAction.Open)
                withTimeout(5000) { f.panel.state.first { it.run?.runId == next.runId } }
                runs.freezePlan(
                    next.runId,
                    "next-owner",
                    listOf(
                        mihon.data.sync.runtime.SyncRunPlanBatch(
                            mihon.data.sync.runtime.SyncProgressDirection.UPLOAD,
                            "old-space",
                            2,
                        ),
                    ),
                )
                withTimeout(5000) { f.panel.state.first { it.run?.plannedItems == 2L } }
                assertEquals(0L, f.panel.state.value.run!!.confirmedItems)
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                tracked.baseline.connectAndImport("other-space", 2, repository, "other-observer", 1)
                val other = runs.start("other-space", 2, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertTrue(runs.claim(other.runId, "other-owner", 1))
                f.panel.act(SyncPanelAction.Open)
                withTimeout(5000) { f.panel.state.first { it.run?.runId == other.runId } }
                runs.freezePlan(other.runId, "other-owner", emptyList())
                withTimeout(5000) { f.panel.state.first { it.run?.plannedItems == 0L } }
                runs.confirmed(
                    next.runId,
                    "next-owner",
                    mihon.data.sync.runtime.SyncProgressDirection.UPLOAD,
                    "old-space",
                    2,
                )
                kotlinx.coroutines.delay(50)
                assertEquals(other.runId, f.panel.state.value.run!!.runId)
                assertEquals("other-space", f.panel.state.value.run!!.spaceId)
                assertEquals(2L, f.panel.state.value.run!!.generation)
                assertEquals(0L, f.panel.state.value.run!!.confirmedItems)
                f.panel.act(SyncPanelAction.Close)
                withTimeout(5000) { while (subscriptions.get() != 1) kotlinx.coroutines.delay(10) }
            }
        }
    }

    @Test
    fun `diagnostic snapshot reads actual disconnected history and excludes sensitive output`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("diagnostic-private-actor", repository)
                val run = f.runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                f.runtime.runStore.finish(
                    run.runId,
                    mihon.data.sync.runtime.SyncRunState.CANCELLED,
                    "private-token-account-title-url",
                )
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.panel.act(SyncPanelAction.Open)
                val before = f.runtime.runStore.latest("space", 1)
                val snapshot = f.runtime.diagnostics.capture(f.panel.state.value)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.OK, snapshot.status)
                assertEquals(false, snapshot.connection.exchangeEnabled)
                assertEquals(mihon.data.sync.runtime.SyncBindingDecode.MISSING, snapshot.connection.storedBindingDecode)
                assertEquals("LATEST", snapshot.runSource)
                assertEquals("CANCELLED", snapshot.latestRun?.state)
                assertEquals("OTHER", snapshot.latestRun?.stopReason)
                assertEquals(before, f.runtime.runStore.latest("space", 1))
                val output = snapshot.json()
                listOf(
                    "fixture-owner",
                    "private-sync",
                    "diagnostic-private-actor",
                    run.runId,
                    "private-token-account-title-url",
                ).forEach { assertFalse(output.contains(it), it) }
                assertFalse(snapshot.crossProcessComparable)
                assertFalse(f.runtime.coordinator.activity.value.running)
            }
        }
    }

    @Test
    fun `diagnostic snapshot distinguishes raw unsupported binding from disabled projection`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                val key = "space-" + "1:space".encodeUtf8().sha256().hex()
                f.secure.values[key] = "{\"version\":1}"
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.panel.act(SyncPanelAction.Open)
                val snapshot = f.runtime.diagnostics.capture(f.panel.state.value)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.OK, snapshot.status)
                assertEquals(
                    mihon.data.sync.runtime.SyncBindingDecode.UNSUPPORTED,
                    snapshot.connection.storedBindingDecode,
                )
                assertEquals(false, snapshot.connection.panelUnsupportedFormat)
                assertEquals(false, snapshot.connection.panelConnectionEnabled)
            }
        }
    }

    @Test
    fun `diagnostic snapshot reports binding read failure without reporting disabled`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                f.secure.readFailure = true
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.READ_FAILED, snapshot.status)
                assertEquals("UNKNOWN", snapshot.runSource)
                assertEquals(
                    mihon.data.sync.runtime.SyncBindingDecode.READ_FAILED,
                    snapshot.connection.storedBindingDecode,
                )
                assertNull(snapshot.connection.exchangeEnabled)
                assertNull(snapshot.connection.panelConnectionEnabled)
                assertFalse(snapshot.json().contains("private-sensitive-read-error"))
                assertTrue(f.runtime.connection()!!.enabled)
            }
        }
    }

    @Test
    fun `diagnostic snapshot rejects identity changing during real storage decode`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val captureName = "identity-changing-diagnostic-capture"
                f.secure.nextSpaceReadCoroutineName = captureName
                f.secure.nextSpaceRead = entered to release
                val collecting = async(CoroutineName(captureName)) {
                    f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                }
                withTimeout(5000) { entered.await() }
                assertTrue(collecting.isActive, "the capture must still be waiting inside its real secure read")
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.runtime.baseline.connectAndImport("other-private-space", 2, repository, "actor", 1)
                release.complete(Unit)
                val snapshot = collecting.await()
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.INCONSISTENT, snapshot.status)
                assertNull(snapshot.connection.spaceAlias)
                assertNull(snapshot.activeRun)
                assertNull(snapshot.latestRun)
                assertFalse(snapshot.json().contains("other-private-space"))
            }
        }
    }

    @Test
    fun `diagnostic snapshot memory ring retains only last 128 transitions`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.runtime.stopPanel()
                repeat(200) { f.runtime.diagnostics.record(mihon.data.sync.runtime.SyncDiagnosticEventKind.OPEN) }
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(128, snapshot.events.size)
            }
        }
    }

    @Test
    fun `diagnostic snapshot preserves production user cancellation reason`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                val run = f.runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                f.runtime.runStore.cancel(run.runId)
                f.runtime.stopPanel()
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals("USER", snapshot.latestRun?.stopReason)
                assertEquals("user", f.runtime.runStore.get(run.runId)?.stopReason)
            }
        }
    }

    @Test
    fun `diagnostic snapshot rejects stale panel identity even when storage is stable`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                val oldConnection = f.runtime.connection()
                f.runtime.stopPanel()
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.runtime.baseline.connectAndImport("another-private-space", 2, repository, "actor", 1)
                val oldPanel = mihon.data.sync.runtime.SyncPanelState(loaded = true, connection = oldConnection)
                val snapshot = f.runtime.diagnostics.capture(oldPanel)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.INCONSISTENT, snapshot.status)
                assertNull(snapshot.connection.spaceAlias)
                assertFalse(snapshot.json().contains("another-private-space"))
            }
        }
    }

    @Test
    fun `diagnostic snapshot association survives restart only in opted in private session`() = runBlocking {
        open().use { storage ->
            val directory = Files.createTempDirectory("sync-diag-association").toString().toPath()
            SyncOnboardingFixture(storage, diagnosticDirectory = directory).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                assertTrue(f.runtime.diagnostics.beginSession())
                val first = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                val restarted = f.runtime()
                val second = restarted.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(first.snapshotAlias, second.previousSnapshotAlias)
                assertEquals(first.connection.spaceAlias, second.connection.spaceAlias)
                assertTrue(first.processSession != second.processSession)
                restarted.diagnostics.record(mihon.data.sync.runtime.SyncDiagnosticEventKind.OPEN)
                assertTrue(restarted.diagnostics.endSession())
                val after = restarted.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertFalse(after.crossProcessComparable)
                assertNull(after.previousSnapshotAlias)
                assertTrue(after.events.isEmpty())
                assertTrue(first.connection.spaceAlias != after.connection.spaceAlias)
                assertFalse(Files.exists(NioPath.of(directory.toString(), "private/session.json")))
            }
        }
    }

    @Test
    fun `diagnostic snapshot expired cache cleanup failure cannot break business observations`() = runBlocking {
        open().use { storage ->
            val directory = Files.createTempDirectory("sync-diag-expiry").toString().toPath()
            SyncOnboardingFixture(storage, diagnosticDirectory = directory).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                assertTrue(f.runtime.diagnostics.beginSession())
                val cache = NioPath.of(directory.toString(), "private/session.json")
                Files.delete(cache)
                Files.createDirectory(cache)
                Files.write(cache.resolve("blocked"), "synthetic".toByteArray(Charsets.UTF_8))
                f.now += 86_400_001
                f.runtime.diagnostics.record(mihon.data.sync.runtime.SyncDiagnosticEventKind.OPEN)
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertFalse(snapshot.crossProcessComparable)
                assertTrue(f.runtime.connection()!!.enabled)
            }
        }
    }

    @Test
    fun `diagnostic snapshot reads secure binding and preferences without HTTP or mutations`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.begin()
                f.runtime.stopPanel()
                f.runtime.preferences.startup.set(false)
                f.runtime.preferences.periodMinutes.set(15)
                f.runtime.preferences.importPaused.set(true)
                val runBefore = f.runtime.runStore.latest("space", 1)
                val secureBefore = f.secure.values.toMap()
                val requests = f.repositoryTokens.size
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(mihon.data.sync.runtime.SyncBindingDecode.OK, snapshot.connection.storedBindingDecode)
                assertEquals(true, snapshot.connection.panelConnectionEnabled)
                assertEquals(false, snapshot.startup)
                assertEquals(15, snapshot.periodMinutes)
                assertEquals(true, snapshot.importPaused)
                assertEquals(requests, f.repositoryTokens.size)
                assertEquals(secureBefore, f.secure.values.toMap())
                assertEquals(runBefore, f.runtime.runStore.latest("space", 1))
                assertFalse(snapshot.json().contains(f.accountLogin))
                assertFalse(snapshot.json().contains("synthetic-token"))
            }
        }
    }

    @Test
    fun `diagnostic snapshot live session expires by monotonic deadline after wall clock rollback`() = runBlocking {
        open().use { storage ->
            val directory = Files.createTempDirectory("sync-diag-monotonic").toString().toPath()
            SyncOnboardingFixture(storage).use { f ->
                f.runtime.stopPanel()
                var monotonic = 0L
                val diagnostics = mihon.data.sync.runtime.SyncDiagnostics(
                    f.runtime,
                    directory,
                    mihon.data.sync.runtime.SyncDiagnosticEnvironment(),
                    { f.now },
                    { monotonic },
                )
                assertTrue(diagnostics.beginSession())
                f.now -= 60_000
                monotonic = 86_400_000_000_001L
                val snapshot = diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertFalse(snapshot.crossProcessComparable)
                assertFalse(Files.exists(NioPath.of(directory.toString(), "private/session.json")))
            }
        }
    }

    @Test
    fun `diagnostic snapshot records real coordinator state and typed refresh failures`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.panel.act(SyncPanelAction.Open)
                val snapshot = f.runtime.diagnostics.capture(f.panel.state.value)
                assertTrue(
                    snapshot.events.any {
                        it.kind == mihon.data.sync.runtime.SyncDiagnosticEventKind.COORDINATOR &&
                            it.coordinatorRunning == false
                    },
                )
                f.panel.awaitIdle()
                f.runtime.diagnostics.record(
                    mihon.data.sync.runtime.SyncDiagnosticEventKind.REFRESH_END,
                    mihon.data.sync.runtime.SyncDiagnosticRefreshSource.OPEN,
                    failed = true,
                )
                val failed = f.runtime.diagnostics.capture(f.panel.state.value)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.READ_FAILED, failed.lastRefreshError)
            }
        }
    }

    @Test
    fun `pre-created empty repository onboarding completes without a password`() = runBlocking {
        createsSpace("")
    }

    @Test
    fun `existing empty repository completes setup with a password`() = runBlocking {
        createsSpace("private-test-password")
    }

    @Test
    fun `legacy password action cannot create a new space`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                kotlinx.coroutines.delay(300)
                assertEquals(0, f.repositoryWrites)
                assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                assertNull(f.runtime.connection())
            }
        }
    }

    @Test
    fun `unlock help returns one layer without requests or losing selected space`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("private-test-password")
                f.authorize()
                f.begin()
                val requests = f.git.server.requestCount
                f.panel.act(SyncPanelAction.ShowPasswordHelp)
                assertEquals(SyncPanelPage.PASSWORD_HELP, f.panel.state.value.page)
                assertEquals(SyncPasswordHelpSource.UNLOCK, f.panel.state.value.passwordHelpSource)
                f.panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.SETUP, f.panel.state.value.page)
                assertEquals(SyncSetupStep.UNLOCK, f.panel.state.value.setupStep)
                assertEquals(1L, f.panel.state.value.passwordHelpReturn)
                assertEquals(requests, f.git.server.requestCount)
                f.panel.act(SyncPanelAction.SubmitPassword("private-test-password"))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("password", f.runtime.connection()?.protectionMode)
            }
        }
    }

    @Test
    fun `explicit create validates choice and rejects stale hidden or repeated events`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                val context = requireNotNull(f.panel.state.value.createContextId)
                val requests = f.git.server.requestCount
                for ((protection, password, ack, problem) in listOf(
                    CreateCase(
                        SyncCreateProtection.NONE,
                        "residual",
                        false,
                        SyncPasswordProblem.INCONSISTENT_SELECTION,
                    ),
                    CreateCase(SyncCreateProtection.NONE, "", true, SyncPasswordProblem.INCONSISTENT_SELECTION),
                    CreateCase(SyncCreateProtection.PASSWORD, "", true, SyncPasswordProblem.EMPTY),
                    CreateCase(
                        SyncCreateProtection.PASSWORD,
                        "valid",
                        false,
                        SyncPasswordProblem.ACKNOWLEDGEMENT_REQUIRED,
                    ),
                    CreateCase(SyncCreateProtection.PASSWORD, "界".repeat(342), true, SyncPasswordProblem.TOO_LONG),
                    CreateCase(SyncCreateProtection.PASSWORD, "\uD800", true, SyncPasswordProblem.INVALID),
                )) {
                    f.panel.act(SyncPanelAction.SubmitCreateSpace(context, protection, password, ack))
                    assertEquals(problem, f.panel.state.value.passwordProblem)
                    assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                    assertEquals(requests, f.git.server.requestCount)
                    assertEquals(0, f.repositoryWrites)
                }
                val error = f.panel.state.value.passwordProblem
                f.panel.act(SyncPanelAction.SubmitCreateSpace(context - 1, SyncCreateProtection.NONE, "", false))
                assertEquals(error, f.panel.state.value.passwordProblem)
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.BeginSetup)
                withTimeout(5_000) { f.panel.state.first { !it.setupBusy } }
                assertTrue(f.panel.state.value.createContextId != context)
                f.panel.act(SyncPanelAction.SubmitCreateSpace(context, SyncCreateProtection.NONE, "", false))
                assertEquals(0, f.repositoryWrites)
                f.bootstrapPutEntered = java.util.concurrent.CountDownLatch(1)
                f.bootstrapPutRelease = java.util.concurrent.CountDownLatch(1)
                val active = requireNotNull(f.panel.state.value.createContextId)
                f.panel.act(
                    SyncPanelAction.SubmitCreateSpace(active, SyncCreateProtection.PASSWORD, "explicit-password", true),
                )
                assertTrue(f.bootstrapPutEntered!!.await(3, java.util.concurrent.TimeUnit.SECONDS))
                f.panel.act(SyncPanelAction.SubmitCreateSpace(active, SyncCreateProtection.NONE, "", false))
                f.bootstrapPutRelease!!.countDown()
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("password", f.runtime.connection()?.protectionMode)
                assertEquals(1, f.git.contentsPutBodies.size)
            }
        }
    }

    private data class CreateCase(
        val protection: SyncCreateProtection,
        val password: String,
        val ack: Boolean,
        val problem: SyncPasswordProblem,
    ) {
        override fun toString() = "CreateCase(<redacted>)"
    }

    @Test
    fun `failed initial secure save retries discovery but requires a new explicit submission`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                val previous = f.panel.state.value.createContextId
                f.secure.rejectInitialSetup = true
                f.panel.create("failure-before-pending")
                withTimeout(5_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                assertEquals(0, f.repositoryWrites)
                assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                f.secure.rejectInitialSetup = false
                f.panel.act(SyncPanelAction.RetrySetup)
                withTimeout(5_000) { f.panel.state.first { !it.setupBusy } }
                assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                assertTrue(f.panel.state.value.createContextId != previous)
                assertTrue(f.panel.state.value.createResubmissionRequired)
                assertEquals(0, f.repositoryWrites)
                f.panel.create("failure-before-pending")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("password", f.runtime.connection()?.protectionMode)
            }
        }
    }

    @Test
    fun `help return focus is not replayed after close and invalid sources return main`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("focus-password")
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.ShowPasswordHelp)
                f.panel.act(SyncPanelAction.Back)
                assertEquals(1L, f.panel.state.value.passwordHelpReturn)
                f.panel.act(SyncPanelAction.Close)
                assertEquals(0L, f.panel.state.value.passwordHelpReturn)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.Navigate(SyncPanelPage.PASSWORD_HELP))
                assertEquals(SyncPanelPage.MAIN, f.panel.state.value.page)
                f.panel.act(SyncPanelAction.BeginSetup)
                withTimeout(5_000) { f.panel.state.first { !it.setupBusy } }
                f.panel.act(SyncPanelAction.ShowPasswordHelp)
                f.authorize("new-account-credential")
                f.panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.MAIN, f.panel.state.value.page)
                assertEquals(0L, f.panel.state.value.passwordHelpReturn)
                assertNull(f.panel.state.value.passwordHelpSource)
            }
        }
    }

    @Test
    fun `same account token refresh before discovery permits explicit creation`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.runtime.credentials.replace(
                    null,
                    GitHubAccessToken("expired-fixture", "refresh-fixture", "bearer", emptySet(), 0, null),
                )
                f.begin()
                assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                f.panel.create("")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("none", f.runtime.connection()?.protectionMode)
            }
        }
    }

    @Test
    fun `normal token refresh while editing does not invalidate the current creation session`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.runtime.credentials.replace(
                    null,
                    GitHubAccessToken("expiring-fixture", "refresh-fixture", "bearer", emptySet(), 70_000, null),
                )
                f.begin()
                val context = f.panel.state.value.createContextId
                f.now = 20_000
                f.runtime.accessToken()
                f.panel.create("")
                kotlinx.coroutines.delay(300)
                assertTrue(f.panel.state.value.setupStep != SyncSetupStep.NEW_PASSWORD)
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("none", f.runtime.connection()?.protectionMode)
                assertNotNull(context)
            }
        }
    }

    @Test
    fun `completion from a closed creation session does not navigate a reopened setup page`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                f.bootstrapPutEntered = java.util.concurrent.CountDownLatch(1)
                f.bootstrapPutRelease = java.util.concurrent.CountDownLatch(1)
                f.panel.create("")
                assertTrue(f.bootstrapPutEntered!!.await(3, java.util.concurrent.TimeUnit.SECONDS))
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETUP))
                f.bootstrapPutRelease!!.countDown()
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals(SyncPanelPage.SETUP, f.panel.state.value.page)
                assertNull(f.panel.state.value.notice)
            }
        }
    }

    @Test
    fun `explicit account replacement invalidates old create and both help sources without requests`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                assertEquals(1L, f.runtime.credentials.authorizationEpoch.value)
                f.begin()
                val old = requireNotNull(f.panel.state.value.createContextId)
                val requests = f.git.server.requestCount
                f.accountId = 2
                f.authorize("replacement-account")
                f.panel.act(SyncPanelAction.SubmitCreateSpace(old, SyncCreateProtection.NONE, "", false))
                withTimeout(5_000) { f.panel.state.first { it.createContextId == null } }
                assertEquals(0, f.repositoryWrites)
                assertEquals(requests, f.git.server.requestCount)
                f.accountId = 1
                f.existing("help-account-password")
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.ShowPasswordHelp)
                val helpRequests = f.git.server.requestCount
                f.runtime.credentials.clear()
                withTimeout(5_000) { f.panel.state.first { it.page == SyncPanelPage.MAIN } }
                assertNull(f.panel.state.value.passwordHelpSource)
                assertEquals(helpRequests, f.git.server.requestCount)
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword("help-account-password"))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                f.panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                f.panel.act(SyncPanelAction.ShowPasswordHelp)
                val settingsRequests = f.git.server.requestCount
                f.accountId = 2
                f.authorize("another-account")
                withTimeout(5_000) { f.panel.state.first { it.page == SyncPanelPage.MAIN } }
                assertNull(f.panel.state.value.passwordHelpSource)
                assertEquals(settingsRequests, f.git.server.requestCount)
            }
        }
    }

    @Test
    fun `refresh epoch preserves both help sources and stale refresh cannot replace new authorization`() = runBlocking {
        for (settings in listOf(false, true)) {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("epoch-help-password")
                    val original = f.runtime.credentials.replace(
                        null,
                        GitHubAccessToken("expiring-fixture", "refresh-fixture", "bearer", emptySet(), 70_000, null),
                    )
                    f.begin()
                    if (settings) {
                        f.panel.act(SyncPanelAction.SubmitPassword("epoch-help-password"))
                        withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                        f.panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                    }
                    f.panel.act(SyncPanelAction.ShowPasswordHelp)
                    val epoch = f.runtime.credentials.authorizationEpoch.value
                    f.now = 20_000
                    f.runtime.accessToken()
                    assertEquals(epoch, f.runtime.credentials.authorizationEpoch.value)
                    assertTrue(f.runtime.credentials.read()!!.revision > original.revision)
                    f.panel.awaitIdle()
                    assertEquals(SyncPanelPage.PASSWORD_HELP, f.panel.state.value.page)
                    val requests = f.git.server.requestCount
                    f.panel.act(SyncPanelAction.Back)
                    assertEquals(
                        if (settings) SyncPanelPage.SETTINGS else SyncPanelPage.SETUP,
                        f.panel.state.value.page,
                    )
                    assertEquals(requests, f.git.server.requestCount)
                    val stale = f.runtime.credentials.read()!!.revision
                    f.authorize("fresh-explicit-authorization")
                    val latest = f.runtime.credentials.read()!!.revision
                    val changedEpoch = f.runtime.credentials.authorizationEpoch.value
                    val rejected = runCatching {
                        f.runtime.credentials.replaceRefreshed(
                            stale,
                            GitHubAccessToken(
                                "stale-refresh",
                                "stale-refresh-token",
                                "bearer",
                                emptySet(),
                                999_999,
                                9_999_999,
                            ),
                        )
                    }
                    assertTrue(rejected.isFailure)
                    assertEquals(latest, f.runtime.credentials.read()!!.revision)
                    assertEquals(changedEpoch, f.runtime.credentials.authorizationEpoch.value)
                    f.runtime.credentials.clear()
                    assertEquals(changedEpoch + 1, f.runtime.credentials.authorizationEpoch.value)
                }
            }
        }
    }

    private suspend fun SyncPanelController.create(password: String) = act(
        SyncPanelAction.SubmitCreateSpace(
            requireNotNull(state.value.createContextId),
            if (password.isEmpty()) SyncCreateProtection.NONE else SyncCreateProtection.PASSWORD,
            password,
            password.isNotEmpty(),
        ),
    )

    private suspend fun createsSpace(password: String) {
        open().use { storage ->
            storage.favorite("/first-book")
            SyncOnboardingFixture(storage).use { fixture ->
                fixture.authorize()
                fixture.begin()
                assertEquals(SyncSetupStep.NEW_PASSWORD, fixture.panel.state.value.setupStep)
                fixture.panel.create(password)
                withTimeout(10_000) { fixture.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals(0, fixture.userRepoPosts)
                assertEquals(0, fixture.repositorySettingsWrites)
                assertEquals(
                    if (password.isEmpty()) "none" else "password",
                    fixture.runtime.connection()?.protectionMode,
                )
                assertEquals(0L, fixture.panel.state.value.importRemaining)
                assertEquals(0L, fixture.panel.state.value.queuedTotal)
                if (password.isNotEmpty()) assertFalse(fixture.secure.values.toString().contains(password))
            }
        }
    }

    @Test
    fun `onboarding existing plain space merges immediately without a password or confirmation`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { fixture ->
                fixture.existing("")
                fixture.authorize()
                fixture.begin()
                assertEquals(SyncSetupStep.COMPLETE, fixture.panel.state.value.setupStep)
                assertEquals("none", fixture.runtime.connection()?.protectionMode)
                assertEquals(0, fixture.userRepoPosts)
                assertEquals(0, fixture.repositorySettingsWrites)
            }
        }
    }

    @Test
    fun `onboarding existing password space requires verification before connecting`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { fixture ->
                fixture.existing("private-test-password")
                fixture.authorize()
                fixture.begin()
                assertEquals(SyncSetupStep.UNLOCK, fixture.panel.state.value.setupStep)
                fixture.panel.act(SyncPanelAction.SubmitPassword("wrong"))
                withTimeout(5_000) { fixture.panel.state.first { it.passwordProblem != null } }
                assertNull(fixture.runtime.connection())
                fixture.panel.act(SyncPanelAction.SubmitPassword("private-test-password"))
                withTimeout(10_000) { fixture.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("password", fixture.runtime.connection()?.protectionMode)
                assertEquals(0, fixture.userRepoPosts)
                assertEquals(0, fixture.repositorySettingsWrites)
            }
        }
    }

    @Test
    fun `onboarding completion has one session notice and never replays after reopen`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                f.panel.create("")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertTrue(f.panel.state.value.notice?.setupCompleted == true)
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                assertNull(f.panel.state.value.notice)
            }
        }
    }

    @Test
    fun `onboarding confirmed background setup does not seize settings or replay completion`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                f.bootstrapPutEntered = java.util.concurrent.CountDownLatch(1)
                f.bootstrapPutRelease = java.util.concurrent.CountDownLatch(1)
                f.panel.create("")
                assertTrue(f.bootstrapPutEntered!!.await(3, java.util.concurrent.TimeUnit.SECONDS))
                f.panel.act(SyncPanelAction.SubmitPassword("second-click"))
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                f.bootstrapPutRelease!!.countDown()
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals(SyncPanelPage.SETTINGS, f.panel.state.value.page)
                assertNull(f.panel.state.value.notice)
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `paused onboarding resumes after restart without duplicate initialization`() = runBlocking {
        open().use { storage ->
            storage.favorite("/before-onboarding")
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.runtime.preferences.importPaused.set(true)
                f.begin()
                f.panel.create("restart-password")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.MERGING && !it.setupBusy } }
                assertEquals(1L, f.panel.state.value.importRemaining)
                assertTrue(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                f.runtime.stopPanel()
                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    val requests = f.git.server.requestCount
                    panel.act(SyncPanelAction.Open)
                    assertEquals(SyncSetupStep.MERGING, panel.state.value.setupStep)
                    assertFalse(panel.state.value.setupBusy)
                    assertEquals(requests, f.git.server.requestCount)
                    assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
                    panel.act(SyncPanelAction.ResumeImport)
                    withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                    assertEquals(0L, panel.state.value.importRemaining)
                    assertTrue(panel.state.value.notice?.setupCompleted == true)
                    assertEquals(0, f.userRepoPosts)
                    assertFalse(f.secure.values.toString().contains("restart-password"))
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `onboarding resumes the same durable bootstrap after a lost response and restart`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.git.loseNextBootstrapResponse = true
                f.begin()
                f.panel.create("")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }

                val pendingBeforeRestart = f.secure.values.entries.single { it.key.startsWith("sync-setup-v3-") }.value
                assertTrue(pendingBeforeRestart.contains(""""stage":"BOOTSTRAP_SUBMITTING""""))
                assertEquals(1, f.git.contentsPutBodies.size)
                assertEquals(0, f.userRepoPosts)
                assertNull(f.runtime.connection())
                f.runtime.stopPanel()

                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.BeginSetup)
                    withTimeout(10_000) {
                        panel.state.first {
                            !it.setupBusy && it.setupStep in setOf(SyncSetupStep.COMPLETE, SyncSetupStep.ERROR)
                        }
                    }

                    assertEquals(SyncSetupStep.COMPLETE, panel.state.value.setupStep)
                    assertEquals(1, f.git.contentsPutBodies.size, "restart must reuse the persisted bootstrap intent")
                    assertEquals(0, f.userRepoPosts)
                    assertEquals(0, f.repositorySettingsWrites)
                    assertEquals("none", reopened.connection()?.protectionMode)
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `legacy submitted v2 pending is rechecked without connecting or discarding material`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                val material = f.existing("")
                val record = legacySetupJson(material)
                f.secure.values["sync-setup-v2-${f.accountId}"] = record
                f.authorize()
                val requestsBefore = f.git.server.requestCount
                val writesBefore = f.repositoryWrites

                f.begin()
                val first = withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.COMPLETE)
                    }
                }

                assertEquals(SyncSetupStep.ERROR, first.setupStep)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.CREATION_UNCONFIRMED, first.setupProblem)
                assertNull(f.runtime.connection())
                assertEquals(record, f.secure.values["sync-setup-v2-${f.accountId}"])
                assertTrue(f.git.server.requestCount > requestsBefore, "legacy retry should recheck GitHub read-only")
                assertEquals(writesBefore, f.repositoryWrites, "legacy retry must not write to GitHub")

                val afterFirstRecheck = f.git.server.requestCount
                f.panel.act(SyncPanelAction.RetrySetup)
                val retried = withTimeout(10_000) {
                    f.panel.state.first { !it.setupBusy && it.setupStep == SyncSetupStep.ERROR }
                }

                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.CREATION_UNCONFIRMED, retried.setupProblem)
                assertTrue(f.git.server.requestCount > afterFirstRecheck)
                assertEquals(writesBefore, f.repositoryWrites)
                assertEquals(record, f.secure.values["sync-setup-v2-${f.accountId}"])
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `legacy pending can be explicitly abandoned without remote writes or local queue loss`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                val material = f.existing("")
                val record = legacySetupJson(material)
                f.secure.values["sync-setup-v2-${f.accountId}"] = record
                storage.favorite("/keep-legacy-queue")
                f.authorize()
                f.begin()
                withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep == SyncSetupStep.ERROR && it.legacyRecoveryAvailable
                    }
                }

                val queuedBefore = mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                    .pendingEvents(material.descriptor.spaceId, material.descriptor.generation)
                val remoteWritesBefore = f.git.contentsPutBodies.size
                val repositoryWritesBefore = f.repositoryWrites

                f.panel.act(SyncPanelAction.Ask(mihon.data.sync.runtime.SyncPanelQuestion.ABANDON_LEGACY))
                assertNotNull(f.panel.state.value.question)
                f.panel.act(SyncPanelAction.ConfirmQuestion)
                withTimeout(10_000) {
                    f.panel.state.first { !it.setupBusy && !it.legacyRecoveryAvailable }
                }

                assertNull(f.secure.values["sync-setup-v2-${f.accountId}"])
                assertEquals(
                    queuedBefore,
                    mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                        .pendingEvents(material.descriptor.spaceId, material.descriptor.generation),
                )
                assertEquals(remoteWritesBefore, f.git.contentsPutBodies.size)
                assertEquals(repositoryWritesBefore, f.repositoryWrites)
            }
        }
    }

    @Test
    fun `legacy v2 join pending resumes exact connected space and retains queued work`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                val material = f.existing("")
                f.authorize()
                f.begin()
                assertEquals(SyncSetupStep.COMPLETE, f.panel.state.value.setupStep)
                val existingBinding = requireNotNull(f.runtime.connection())
                val existingStoredBinding = requireNotNull(
                    f.runtime.onboarding.storage.connection(
                        material.descriptor.spaceId,
                        material.descriptor.generation,
                    ),
                )
                val pendingEventsBefore = mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                    .pendingEvents(material.descriptor.spaceId, material.descriptor.generation)
                storage.favorite("/queued-before-legacy-join-recovery")
                val queueBefore = mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                    .pendingEvents(material.descriptor.spaceId, material.descriptor.generation)
                assertTrue(queueBefore.size > pendingEventsBefore.size)

                val record = legacySetupJson(
                    material,
                    newSpace = false,
                    submitted = false,
                    connected = true,
                )
                f.secure.values["sync-setup-v2-${f.accountId}"] = record
                val requestsBefore = f.git.server.requestCount
                val writesBefore = f.repositorySettingsWrites
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.BeginSetup)
                val resumed = withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.COMPLETE)
                    }
                }

                assertEquals(SyncSetupStep.COMPLETE, resumed.setupStep)
                assertEquals(existingBinding.spaceId, f.runtime.connection()?.spaceId)
                assertEquals(existingBinding.generation, f.runtime.connection()?.generation)
                assertNull(f.secure.values["sync-setup-v2-${f.accountId}"])
                assertEquals(
                    existingStoredBinding.actorId,
                    f.runtime.onboarding.storage.connection(
                        material.descriptor.spaceId,
                        material.descriptor.generation,
                    )?.actorId,
                )
                assertTrue(f.git.server.requestCount > requestsBefore, "legacy join recovery must recheck GitHub")
                assertEquals(writesBefore, f.repositorySettingsWrites)
                assertEquals(0, f.userRepoPosts)
                assertTrue(
                    mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                        .pendingEvents(material.descriptor.spaceId, material.descriptor.generation).isEmpty(),
                    "the pre-existing connection must continue syncing its local queue",
                )
            }
        }
    }

    @Test
    fun `onboarding another account cannot transmit the original queued events`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.begin()
                storage.favorite("/keep-local-queue")
                f.accountId = 2
                f.accountLogin = "other-account"
                f.authorize("other-account-token")
                val requests = f.repositoryTokens.size
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.AUTHORIZATION, result.problem)
                assertEquals(requests, f.repositoryTokens.size)
                assertEquals(
                    1L,
                    storage.handler.await {
                        sync_journalQueries.getPendingCategoryCounts("space", 1).executeAsList().sumOf { it.count }
                    },
                )
            }
        }
    }

    @Test
    fun `onboarding rejects legacy binding before network without deleting its recovery data`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("legacy-device", f.repository)
                f.authorize()
                val key = "space-" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest("1:space".encodeToByteArray()).joinToString("") { "%02x".format(it) }
                val legacy = """{"recovery":{"rawKeyset":"legacy-test-material"},"actorId":"legacy-device","epoch":1}"""
                f.secure.values[key] = legacy
                storage.favorite("/keep-legacy-queue")
                f.panel.act(SyncPanelAction.Open)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.INCOMPATIBLE, f.panel.state.value.setupProblem)
                assertEquals(SyncSetupStep.ERROR, f.panel.state.value.setupStep)
                assertFalse(f.panel.state.value.connection!!.enabled)
                assertEquals(1L, f.panel.state.value.queuedTotal)
                assertEquals(0, f.git.server.requestCount)
                assertEquals(legacy, f.secure.values[key])
                f.panel.act(SyncPanelAction.BeginSetup)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.INCOMPATIBLE, f.panel.state.value.setupProblem)
                assertEquals(0, f.git.server.requestCount)
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.INVALID_DATA, result.problem)
                assertEquals(legacy, f.secure.values[key])
                assertEquals(0, f.repositoryTokens.size)
                assertNull(f.runtime.connection()?.protectionMode)
            }
        }
    }

    @Test
    fun `onboarding disconnected legacy binding permits fresh setup without consuming the old queue`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("legacy-device", f.repository)
                f.authorize()
                val key = "space-" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest("1:space".encodeToByteArray()).joinToString("") { "%02x".format(it) }
                val legacy = """{"recovery":{"rawKeyset":"legacy-test-material"},"actorId":"legacy-device","epoch":1}"""
                f.secure.values[key] = legacy
                storage.favorite("/keep-legacy-queue")
                f.panel.act(SyncPanelAction.Open)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.INCOMPATIBLE, f.panel.state.value.setupProblem)
                val queued = mihon.data.sync.journal.SyncLocalJournal(storage.handler).pendingEvents("space", 1)
                f.panel.act(SyncPanelAction.Ask(mihon.data.sync.runtime.SyncPanelQuestion.DISCONNECT))
                f.panel.act(SyncPanelAction.ConfirmQuestion)
                assertNull(f.runtime.credentials.read())
                assertFalse(f.runtime.connection()!!.enabled)
                f.authorize()
                f.panel.act(SyncPanelAction.Synchronize)
                withTimeout(5_000) { f.panel.state.first { !it.setupBusy } }
                assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                f.panel.create("")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertTrue(f.runtime.connection()!!.spaceId != "space")
                assertEquals("none", f.runtime.connection()!!.protectionMode)
                assertEquals(legacy, f.secure.values[key])
                assertEquals(
                    queued,
                    mihon.data.sync.journal.SyncLocalJournal(storage.handler).pendingEvents("space", 1),
                )
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `disconnected legacy binding verifies real device authorization before independent setup`() = runBlocking {
        for (accountId in listOf(1L, 2L)) {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    storage.connect("legacy-device", f.repository)
                    f.authorize("old-device-credential")
                    val key = "space-" + "1:space".encodeUtf8().sha256().hex()
                    val legacy = """{"version":1,"actorId":"legacy-device","epoch":1}"""
                    f.secure.values[key] = legacy
                    storage.favorite("/retained-device-queue")
                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.Ask(mihon.data.sync.runtime.SyncPanelQuestion.DISCONNECT))
                    f.panel.act(SyncPanelAction.ConfirmQuestion)
                    assertNull(f.runtime.credentials.read())
                    val queued = mihon.data.sync.journal.SyncLocalJournal(storage.handler).pendingEvents("space", 1)
                    f.accountId = accountId
                    if (accountId != 1L) f.accountLogin = "second-owner"
                    val releaseToken = java.util.concurrent.CountDownLatch(1)
                    val deviceRequests = java.util.concurrent.atomic.AtomicInteger()
                    val delegate = f.git.server.dispatcher
                    f.git.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            if (request.url.encodedPath == "/device") deviceRequests.incrementAndGet()
                            if (request.url.encodedPath == "/token") {
                                check(releaseToken.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            return delegate.dispatch(request)
                        }
                    }
                    try {
                        f.panel.act(SyncPanelAction.Authorize)
                        val waiting = withTimeout(5_000) {
                            f.panel.state.first { it.deviceCode != null || !it.setupBusy }
                        }
                        assertNotNull(waiting.deviceCode, "disconnected legacy data must not block the device request")
                        assertEquals(1, deviceRequests.get())
                        assertNull(f.runtime.credentials.read(), "a device code is not accepted authorization")
                        releaseToken.countDown()
                        withTimeout(10_000) {
                            f.panel.state.first {
                                !it.setupBusy && it.deviceCode == null &&
                                    it.setupStep in setOf(SyncSetupStep.NEW_PASSWORD, SyncSetupStep.ERROR) &&
                                    f.runtime.credentials.read() != null
                            }
                        }
                        assertEquals("synthetic-token", f.runtime.credentials.read()!!.credential.accessToken)
                        assertEquals(accountId, f.runtime.onboarding.session().account.id)
                        assertEquals(0, f.repositoryWrites, "authorization must not initialize or upload old data")
                        if (accountId == 1L) {
                            assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                            f.panel.create("")
                            withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                            assertTrue(f.runtime.connection()!!.spaceId != "space")
                        } else {
                            assertEquals(SyncSetupStep.ERROR, f.panel.state.value.setupStep)
                            assertEquals(
                                mihon.data.sync.auth.SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
                                f.panel.state.value.setupProblem,
                            )
                            assertFalse(f.runtime.connection()!!.enabled)
                        }
                        assertEquals(legacy, f.secure.values[key])
                        assertEquals(
                            queued,
                            mihon.data.sync.journal.SyncLocalJournal(storage.handler).pendingEvents("space", 1),
                        )
                        assertEquals(0, f.userRepoPosts)
                    } finally {
                        releaseToken.countDown()
                    }
                }
            }
        }
    }

    @Test
    fun `authorization keeps enabled unsupported unreadable and known other account bindings closed`() = runBlocking {
        for (kind in listOf("enabled-unsupported", "disabled-unreadable", "known-other-account")) {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    storage.connect("legacy-device", f.repository)
                    f.authorize("protected-device-credential")
                    val key = "space-" + "1:space".encodeUtf8().sha256().hex()
                    if (kind == "known-other-account") {
                        val material = SyncSpaceCrypto.create("space", 1, "")
                        f.runtime.onboarding.storage.bind(
                            mihon.data.sync.runtime.StoredSyncConnection(
                                accountId = 1,
                                accountLogin = f.accountLogin,
                                repositoryId = 99,
                                owner = f.repository.owner,
                                repository = f.repository.name,
                                branch = f.repository.branch,
                                material = StoredSyncMaterial.from(material),
                                actorId = "legacy-device",
                                epoch = 1,
                            ),
                            null,
                        )
                        f.accountId = 2
                    } else {
                        f.secure.values[key] = "{\"version\":1}"
                    }
                    if (kind == "disabled-unreadable") {
                        storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                    }
                    val secure = object : SyncSecureStore by f.secure {
                        override suspend fun read(key: String): String? {
                            if (kind == "disabled-unreadable" && key.startsWith("space-")) {
                                throw mihon.domain.sync.security.SyncSecureStoreException()
                            }
                            return f.secure.read(key)
                        }
                    }
                    val runtime = SyncRuntime(
                        storage.handler, storage.bootstrap, storage.creators, storage.creators, { true }, secure,
                        f.preferences, f.client, f.endpoints,
                    )
                    try {
                        val original = f.secure.values[key]
                        val revision = runtime.credentials.read()!!.revision
                        val checking = runCatching {
                            runtime.recordRecoveryAuthorization(
                                mihon.data.sync.runtime.SyncRecoveryAuthorization.CHECKING,
                            )
                        }
                        assertEquals(kind == "known-other-account", checking.isSuccess)
                        assertTrue(
                            runCatching {
                                runtime.acceptAuthorization(
                                    revision,
                                    GitHubAccessToken(
                                        "unaccepted-device-token",
                                        null,
                                        "bearer",
                                        emptySet(),
                                        null,
                                        null,
                                    ),
                                )
                            }.isFailure,
                        )
                        assertEquals("protected-device-credential", runtime.credentials.read()!!.credential.accessToken)
                        assertEquals(original, f.secure.values[key])
                        assertEquals(0, f.repositoryWrites)
                    } finally {
                        runtime.stopPanel()
                    }
                }
            }
        }
    }

    @Test
    fun `onboarding resumes after database binding succeeds but connected marker persistence fails`() = runBlocking {
        open().use { storage ->
            storage.favorite("/first-import")
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.runtime.preferences.importPaused.set(true)
                f.secure.rejectConnectedSetup = true
                f.begin()
                f.panel.create("crash-window-password")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                assertEquals("password", f.runtime.connection()!!.protectionMode)
                val before = f.secure.values.entries.single { it.key.startsWith("sync-setup-v3-") }.value
                assertTrue(before.contains("\"stage\":\"SPACE_CONFIRMED\""))
                f.runtime.stopPanel()
                f.secure.rejectConnectedSetup = false
                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    val requests = f.git.server.requestCount
                    panel.act(SyncPanelAction.Open)
                    assertEquals(SyncSetupStep.MERGING, panel.state.value.setupStep)
                    assertEquals(requests, f.git.server.requestCount)
                    assertEquals(1L, panel.state.value.importRemaining)
                    assertEquals(before, f.secure.values.entries.single { it.key.startsWith("sync-setup-v3-") }.value)
                    panel.act(SyncPanelAction.ResumeImport)
                    withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                    assertEquals(0, f.userRepoPosts)
                    assertEquals(0, f.repositorySettingsWrites)
                    assertTrue(panel.state.value.notice?.setupCompleted == true)
                    assertEquals(0L, panel.state.value.importRemaining)
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                    assertFalse(f.secure.values.toString().contains("crash-window-password"))
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `connected onboarding marker completes idempotently after binding commit`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.created = true
                f.authorize()
                f.begin()
                f.panel.create("")
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }

                val remoteDescriptor = SyncSpaceDescriptorCodec.decode(
                    requireNotNull(f.git.file(f.repository.branch, SyncSpaceDescriptorCodec.PATH)),
                ).getOrThrow()
                val runtimeConnection = requireNotNull(f.runtime.connection()) {
                    "initial onboarding must create a local database binding"
                }
                assertEquals(remoteDescriptor.spaceId, runtimeConnection.spaceId)
                assertEquals(remoteDescriptor.generation, runtimeConnection.generation)
                val binding = requireNotNull(
                    f.runtime.onboarding.storage.connection(remoteDescriptor.spaceId, remoteDescriptor.generation),
                ) {
                    "initial onboarding must persist the secure connection binding"
                }
                assertEquals(remoteDescriptor, binding.material.material().descriptor)
                val bootstrap = Json.parseToJsonElement(
                    requireNotNull(f.git.file("main", ".mihon-sync/bootstrap")).decodeToString(),
                ).jsonObject
                val setup = StoredSyncSetup(
                    accountId = binding.accountId,
                    accountLogin = binding.accountLogin,
                    attemptId = UUID.randomUUID().toString(),
                    attemptNonce = bootstrap.getValue("attemptNonce").jsonPrimitive.content,
                    newSpace = true,
                    material = binding.material,
                    stage = SyncInitializationStage.CONNECTED,
                    repositoryId = binding.repositoryId,
                    owner = binding.owner,
                    repository = binding.repository,
                    branch = binding.branch,
                    defaultBranch = "main",
                    confirmedBootstrapCommitSha = f.git.head("main"),
                    confirmedBootstrapTreeSha = f.git.treeSha("main"),
                )
                f.runtime.onboarding.storage.save(setup, null)
                val writesBeforeRecovery = f.repositoryWrites
                val createsBeforeRecovery = f.userRepoPosts
                f.runtime.stopPanel()

                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    panel.act(SyncPanelAction.Open)
                    assertEquals(SyncSetupStep.MERGING, panel.state.value.setupStep)
                    panel.act(SyncPanelAction.ResumeImport)
                    val recovered = withTimeout(10_000) {
                        panel.state.first {
                            !it.setupBusy && it.setupStep in setOf(SyncSetupStep.COMPLETE, SyncSetupStep.ERROR)
                        }
                    }

                    assertEquals(SyncSetupStep.COMPLETE, recovered.setupStep)
                    assertEquals(writesBeforeRecovery, f.repositoryWrites)
                    assertEquals(createsBeforeRecovery, f.userRepoPosts)
                    assertNull(f.runtime.onboarding.storage.pending(f.accountId))
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `losing bootstrap attempt joins a verified winner in the same repository`() = runBlocking {
        for (winnerPassword in listOf("", "winner-password")) {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    val winnerMaterial = SyncSpaceCrypto.create("winning-space", 1, winnerPassword)
                    val winnerIntent = SyncInitializationIntent(
                        accountId = f.accountId,
                        repositoryId = 99,
                        defaultBranch = "main",
                        attemptNonce = "winner-attempt-nonce-0001",
                        stage = SyncInitializationStage.VERIFIED_EMPTY,
                    )
                    val winnerResult = f.runtime.onboarding.transport("synthetic-token", winnerMaterial).initialize(
                        f.repository,
                        winnerMaterial.descriptor.spaceId,
                        winnerMaterial.descriptor.generation,
                        winnerIntent,
                    ) {}
                    assertTrue(winnerResult is mihon.domain.sync.transport.SyncInitializationResult.Initialized)

                    val loserMaterial = SyncSpaceCrypto.create("losing-space", 1, "loser-password")
                    val loserSetup = StoredSyncSetup(
                        accountId = f.accountId,
                        accountLogin = f.accountLogin,
                        attemptId = "loser-attempt-id-0001",
                        attemptNonce = "loser-attempt-nonce-0001",
                        newSpace = true,
                        material = StoredSyncMaterial.from(loserMaterial),
                        stage = SyncInitializationStage.BOOTSTRAP_SUBMITTING,
                        repositoryId = 99,
                        owner = f.repository.owner,
                        repository = f.repository.name,
                        branch = f.repository.branch,
                        defaultBranch = "main",
                    )
                    f.runtime.onboarding.storage.save(loserSetup, null)
                    f.authorize()
                    storage.favorite("/queued-during-losing-attempt")
                    val requestsBefore = f.git.server.requestCount

                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.BeginSetup)
                    val discoveredWinner = withTimeout(10_000) {
                        f.panel.state.first {
                            !it.setupBusy &&
                                it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.UNLOCK, SyncSetupStep.COMPLETE)
                        }
                    }
                    assertEquals(
                        if (winnerPassword.isEmpty()) SyncSetupStep.COMPLETE else SyncSetupStep.UNLOCK,
                        discoveredWinner.setupStep,
                    )
                    assertNull(f.runtime.onboarding.pending())
                    assertFalse(f.secure.values.values.any { "losing-space" in it })
                    assertFalse(f.secure.values.values.any { "loser-attempt-nonce-0001" in it })

                    if (winnerPassword.isNotEmpty()) {
                        f.panel.act(SyncPanelAction.SubmitPassword(winnerPassword))
                    }
                    val completed = withTimeout(10_000) {
                        f.panel.state.first {
                            !it.setupBusy && it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.COMPLETE)
                        }
                    }

                    assertEquals(SyncSetupStep.COMPLETE, completed.setupStep)
                    assertEquals(winnerMaterial.descriptor.spaceId, f.runtime.connection()?.spaceId)
                    assertEquals(winnerMaterial.descriptor.mode, f.runtime.connection()?.protectionMode)
                    assertNull(f.runtime.onboarding.pending())
                    assertEquals(1, f.git.contentsPutBodies.size, "the losing attempt must not issue another PUT")
                    assertEquals(0, f.userRepoPosts)
                    assertEquals(0, f.repositorySettingsWrites)
                    assertTrue(f.git.server.requestCount > requestsBefore)
                    assertTrue(
                        mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                            .pendingEvents("winning-space", 1).isEmpty(),
                        "joining the winner must resume the queued local work",
                    )
                }
            }
        }
    }

    @Test
    fun `onboarding invalid nonempty passwords cannot create an unprotected space`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                for ((password, problem) in listOf(
                    "界".repeat(342) to mihon.data.sync.runtime.SyncPasswordProblem.TOO_LONG,
                    "\uD800" to mihon.data.sync.runtime.SyncPasswordProblem.INVALID,
                )) {
                    f.panel.create(password)
                    assertEquals(problem, f.panel.state.value.passwordProblem)
                    assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                    assertEquals(0, f.userRepoPosts)
                    assertEquals(0, f.repositorySettingsWrites)
                    assertNull(f.runtime.connection())
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                }
            }
        }
    }

    @Test
    fun `onboarding verified password repairs a damaged local data key while preserving queued work`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("correct-password")
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword("correct-password"))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                storage.favorite("/preserved-work")
                val binding = f.secure.values.entries.single { it.key.startsWith("space-") }
                val json = kotlinx.serialization.json.Json.parseToJsonElement(binding.value)
                    as kotlinx.serialization.json.JsonObject
                val material = json.getValue("material") as kotlinx.serialization.json.JsonObject
                f.secure.values[binding.key] = kotlinx.serialization.json.JsonObject(
                    json + (
                        "material" to kotlinx.serialization.json.JsonObject(
                            material + (
                                "keyHex" to kotlinx.serialization.json.JsonPrimitive("00".repeat(32))
                                ),
                        )
                        ),
                ).toString()
                val damaged = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunStatus.FAILED, damaged.status)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.INVALID_DATA, damaged.problem)
                f.begin()
                assertEquals(SyncSetupStep.UNLOCK, f.panel.state.value.setupStep)
                f.panel.act(SyncPanelAction.SubmitPassword("wrong-password"))
                withTimeout(5_000) { f.panel.state.first { it.passwordProblem != null } }
                assertEquals(
                    1L,
                    storage.handler.await {
                        sync_journalQueries.getPendingCategoryCounts("space", 1).executeAsList().sumOf { it.count }
                    },
                )
                f.panel.act(SyncPanelAction.SubmitPassword("correct-password"))
                withTimeout(10_000) {
                    f.panel.state.first {
                        it.setupStep == SyncSetupStep.COMPLETE || it.setupStep == SyncSetupStep.ERROR
                    }
                }
                assertEquals(SyncSetupStep.COMPLETE, f.panel.state.value.setupStep)
                assertEquals(0L, f.panel.state.value.queuedTotal)
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `onboarding public repository stops uploads with explicit privacy feedback and retains queue`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.begin()
                storage.favorite("/private-work")
                f.repositoryPrivate = false
                val before = f.repositoryWrites
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.REPOSITORY_NOT_PRIVATE, result.problem)
                assertEquals(before, f.repositoryWrites)
                assertEquals(
                    1L,
                    storage.handler.await {
                        sync_journalQueries.getPendingCategoryCounts("space", 1).executeAsList().sumOf { it.count }
                    },
                )
                f.repositoryPrivate = true
                assertEquals(
                    mihon.domain.sync.runtime.SyncRunStatus.SUCCESS,
                    f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL).status,
                )
            }
        }
    }

    @Test
    fun `onboarding public space stops before binding with privacy feedback`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("private-password")
                f.authorize()
                f.begin()
                assertEquals(SyncSetupStep.UNLOCK, f.panel.state.value.setupStep)
                f.repositoryPrivate = false
                val before = f.repositoryWrites
                f.panel.act(SyncPanelAction.SubmitPassword("private-password"))
                withTimeout(5_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                assertEquals(
                    mihon.data.sync.auth.SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE,
                    f.panel.state.value.setupProblem,
                )
                assertNull(f.runtime.connection())
                assertEquals(before, f.repositoryWrites)
            }
        }
    }

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
                assertEquals(1L, panel.state.value.queuedFavorites)
                assertEquals(0L, panel.state.value.queuedFollows)
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
            auth.enqueue(deviceCode("SECOND-CODE"))
            open().use { storage ->
                var now = 1_000L
                withPanel(storage, endpoints = endpoints(auth), clock = { now }) { panel, runtime ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    assertEquals(1_000L, panel.state.value.authRequestStartedAtMillis)
                    withTimeout(3_000) { panel.state.first { it.deviceCode != null } }
                    val code = requireNotNull(panel.state.value.deviceCode)
                    assertTrue(panel.claimDeviceCodeBrowser(code))
                    assertFalse(panel.claimDeviceCodeBrowser(code))
                    panel.act(SyncPanelAction.Close)
                    assertNull(panel.state.value.deviceCode)
                    assertNull(panel.state.value.authRequestStartedAtMillis)
                    assertFalse(panel.claimDeviceCodeBrowser(code))
                    assertFalse(panel.state.value.setupBusy)
                    assertNull(runtime.credentials.read())
                    panel.act(SyncPanelAction.Open)
                    assertNull(panel.state.value.deviceCode)
                    now = 10_000L
                    panel.act(SyncPanelAction.Authorize)
                    assertEquals(10_000L, panel.state.value.authRequestStartedAtMillis)
                    withTimeout(3_000) { panel.state.first { it.deviceCode?.userCode == "SECOND-CODE" } }
                    assertTrue(panel.claimDeviceCodeBrowser(requireNotNull(panel.state.value.deviceCode)))
                    panel.act(SyncPanelAction.CancelAuthorization)
                    assertNull(panel.state.value.authRequestStartedAtMillis)
                }
            }
        }
    }

    @Test
    fun `failed device code request stops the spinner and leaves retry available`() = runBlocking {
        MockWebServer().use { auth ->
            auth.start()
            auth.enqueue(MockResponse(code = 503, body = ""))
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(auth)) { panel, runtime ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(3_000) { panel.state.first { it.authFailure == GitHubAuthFailureReason.HTTP } }
                    assertFalse(panel.state.value.setupBusy)
                    assertNull(panel.state.value.deviceCode)
                    assertNull(runtime.credentials.read())
                    assertEquals(1, auth.requestCount)
                }
            }
        }
    }

    @Test
    fun `closing repository discovery cancels its busy state and allows setup again`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body("""{"id":1,"login":"fixture-owner","type":"User"}""")
                    .headersDelay(1, java.util.concurrent.TimeUnit.SECONDS).build(),
            )
            repeat(2) { server.enqueue(MockResponse(body = """{"id":1,"login":"fixture-owner","type":"User"}""")) }
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
                    assertEquals(SyncSetupStep.ERROR, panel.state.value.setupStep)
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
                    val nextCode = requireNotNull(panel.state.value.deviceCode)
                    assertTrue(panel.claimDeviceCodeBrowser(nextCode))
                    assertFalse(panel.claimDeviceCodeBrowser(nextCode))
                    assertNull(panel.state.value.authFailure)
                    panel.act(SyncPanelAction.CancelAuthorization)
                }
            }
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
    fun `pausing initial import still exchanges independent changes and resuming drains the baseline`() = runBlocking {
        open().use { storage ->
            storage.favorite("/baseline")
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.runtime.preferences.importPaused.set(true)
                f.begin()
                assertEquals(SyncSetupStep.MERGING, f.panel.state.value.setupStep)
                storage.favorite("/independent")
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(1, result.uploaded)
                f.panel.act(SyncPanelAction.Open)
                assertTrue(f.panel.state.value.importPaused)
                assertEquals(1L, f.panel.state.value.importRemaining)
                assertEquals(0L, f.panel.state.value.queuedTotal)
                f.panel.act(SyncPanelAction.ResumeImport)
                withTimeout(5_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertFalse(f.panel.state.value.importPaused)
                assertEquals(0L, f.panel.state.value.queuedTotal)
            }
        }
    }

    @Test
    fun `cancelled history survives disconnected setup and a separate new manual round`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            withPanel(storage) { panel, runtime ->
                val old = runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertTrue(runtime.runStore.claim(old.runId, "fixture-owner", 1))
                runtime.runStore.confirmed(
                    old.runId,
                    "fixture-owner",
                    mihon.data.sync.runtime.SyncProgressDirection.UPLOAD,
                    "batch",
                    1536,
                )
                runtime.runStore.cancel(old.runId)
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                panel.act(SyncPanelAction.Open)
                assertEquals(mihon.data.sync.runtime.SyncPanelRunSource.LATEST, panel.state.value.runSource)
                panel.act(SyncPanelAction.BeginSetup)
                assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
                assertFalse(runtime.connection()!!.enabled)
                assertEquals(old.runId, runtime.runStore.latest("space", 1)!!.runId)
                assertEquals(1536L, runtime.runStore.get(old.runId)!!.confirmedItems)
                storage.handler.await { sync_journalQueries.activateSpace("space", 1) }
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.Synchronize)
                withTimeout(5_000) { panel.state.first { it.run != null && it.run!!.runId != old.runId } }
                withTimeout(5_000) {
                    panel.state.first { it.run?.state == mihon.data.sync.runtime.SyncRunState.BLOCKED }
                }
                assertEquals(mihon.data.sync.runtime.SyncPanelRunSource.ACTIVE, panel.state.value.runSource)
                assertEquals(0L, panel.state.value.run!!.confirmedItems)
                assertEquals(mihon.data.sync.runtime.SyncRunState.CANCELLED, runtime.runStore.get(old.runId)!!.state)
                assertEquals(1536L, runtime.runStore.get(old.runId)!!.confirmedItems)
            }
        }
    }

    @Test
    fun `process recovery reclaims the existing partial frozen plan instead of starting another run`() = runBlocking {
        open().use { storage ->
            storage.connect("actor", repository)
            withPanel(storage) { _, runtime ->
                val run = runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertTrue(runtime.runStore.claim(run.runId, "owner", 1))
                runtime.runStore.freezePlan(
                    run.runId,
                    "owner",
                    listOf(
                        mihon.data.sync.runtime.SyncRunPlanBatch(
                            mihon.data.sync.runtime.SyncProgressDirection.UPLOAD,
                            "remaining-upload",
                            3,
                        ),
                    ),
                )
                runtime.runStore.finish(run.runId, mihon.data.sync.runtime.SyncRunState.PARTIAL, ownerSession = "owner")
                assertTrue(runtime.hasResumableRun())
                assertTrue(runtime.isRecoveryDue())
                runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.RECOVERY)
                assertEquals(run.runId, runtime.runStore.latest("space", 1)!!.runId)
                assertEquals(3L, runtime.runStore.get(run.runId)!!.plannedItems)
                assertEquals(2L, runtime.runStore.get(run.runId)!!.attemptId)
                // This fixture has no secure connection material; recovery must retain that existing guard.
                assertEquals(mihon.data.sync.runtime.SyncRunState.BLOCKED, runtime.runStore.get(run.runId)!!.state)
            }
        }
    }

    @Test
    fun `process recovery does not automatically retry a partial run containing only pending receipts`() = runBlocking {
        open().use { storage ->
            storage.connect("actor", repository)
            withPanel(storage) { _, runtime ->
                val run = runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertTrue(runtime.runStore.claim(run.runId, "owner", 1))
                runtime.runStore.freezePlan(
                    run.runId,
                    "owner",
                    listOf(
                        mihon.data.sync.runtime.SyncRunPlanBatch(
                            mihon.data.sync.runtime.SyncProgressDirection.DOWNLOAD,
                            "pending-receipt",
                            1,
                        ),
                    ),
                )
                runtime.runStore.expectDownload(run.runId, "owner", "pending-receipt", 1)
                runtime.runStore.finish(run.runId, mihon.data.sync.runtime.SyncRunState.PARTIAL, ownerSession = "owner")
                assertFalse(runtime.hasResumableRun())
                assertFalse(runtime.isRecoveryDue())
                val result = runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.RECOVERY)
                assertEquals(mihon.domain.sync.runtime.SyncRunStatus.SKIPPED, result.status)
                assertEquals(run.runId, runtime.runStore.latest("space", 1)!!.runId)
                assertEquals(1L, runtime.runStore.get(run.runId)!!.attemptId)
            }
        }
    }

    @Test
    fun `resuming import never bypasses the durable user pause or creates another run`() = runBlocking {
        open().use { storage ->
            storage.favorite("/baseline")
            storage.connect("actor", repository)
            withPanel(storage) { panel, runtime ->
                runtime.preferences.importPaused.set(true)
                val run = runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                runtime.runStore.pause(run.runId)
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.ResumeImport)
                assertFalse(runtime.preferences.importPaused.get())
                repeat(10) {
                    kotlinx.coroutines.delay(20)
                    assertEquals(run.runId, runtime.runStore.latest("space", 1)!!.runId)
                    assertEquals(
                        mihon.data.sync.runtime.SyncRunState.PAUSED_USER,
                        runtime.runStore.get(run.runId)!!.state,
                    )
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

    protected suspend fun withPanel(
        storage: SyncRuntimeStorageContract.Storage,
        endpoints: GitHubAuthEndpoints = GitHubAuthEndpoints(),
        bootstrap: tachiyomi.domain.creator.repository.CreatorArchiveBootstrap = storage.bootstrap,
        handler: DatabaseHandler = storage.handler,
        clock: () -> Long = { 1000L },
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
            handler, bootstrap, storage.creators, storage.creators, { true }, secure,
            preferences, client, endpoints, clock = clock,
        )
        val panel = SyncPanelController(runtime, handler, scope, clock)
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

private fun legacySetupJson(
    material: SyncSpaceMaterial,
    newSpace: Boolean = true,
    submitted: Boolean = true,
    connected: Boolean = false,
): String {
    val storedMaterial = Json.encodeToString(StoredSyncMaterial.from(material))
    return """
        {
          "version": 2,
          "accountId": 1,
          "accountLogin": "fixture-owner",
          "attemptId": "legacy-pending-fixture-0001",
          "newSpace": $newSpace,
          "material": $storedMaterial,
          "submitted": $submitted,
          "repositoryId": 99,
          "owner": "fixture-owner",
          "repository": "mihon-sync",
          "branch": "mihon-sync-v1",
          "connected": $connected
        }
    """.trimIndent()
}
