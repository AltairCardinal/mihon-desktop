package mihon.data.sync

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.http.InMemorySyncMetrics
import mihon.data.sync.inbox.SyncDiscoveryStore
import mihon.data.sync.inbox.SyncInboxExchange
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressReporter
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunLogStatus
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRunStore
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.data.sync.transport.StoredSyncBatch
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncEventId
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncProtocol
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sequential, file-backed scale acceptance through production HTTP, AEAD, SQL and panel wiring.
 * Device counts describe independent event actors, not simultaneous OS application instances.
 * Fixture history construction is reported separately from measured production exchanges.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class SyncScaleAcceptanceTest {
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    private val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `missing remote parent cannot complete or confirm a received run`() = runBlocking {
        val observed = runMissingParent(telemetryEnabled = true)
        assertEquals("DEPENDENCY", observed.projectionStatus)
        assertEquals(0L, observed.pendingDecisions)
        assertAll(
            { assertEquals(SyncRunStatus.PARTIAL, observed.result.status) },
            { assertEquals(0, observed.result.pending) },
            { assertEquals(SyncRunState.PARTIAL, observed.runState) },
            { assertEquals("projection_pending", observed.stopReason) },
            { assertEquals(0L, observed.durableConfirmed) },
            { assertEquals(0L, observed.liveConfirmed) },
            { assertEquals(0L, observed.panelConfirmed) },
        )
    }

    @Test
    fun `missing parent remains incomplete when display telemetry is disabled`() = runBlocking {
        val observed = runMissingParent(telemetryEnabled = false)
        assertEquals("DEPENDENCY", observed.projectionStatus)
        assertEquals(0L, observed.pendingDecisions)
        assertAll(
            { assertEquals(SyncRunStatus.PARTIAL, observed.result.status) },
            { assertEquals(0, observed.result.pending) },
            { assertEquals(SyncRunState.PARTIAL, observed.runState) },
            { assertEquals("projection_pending", observed.stopReason) },
            { assertEquals(0L, observed.durableConfirmed) },
        )
    }

    @Test
    fun `retry keeps an unresolved received batch in the same incomplete run`() = runBlocking {
        val observed = runMissingParent(telemetryEnabled = true, retryTrigger = SyncTrigger.MANUAL)
        assertAll(
            { assertEquals(SyncRunStatus.PARTIAL, observed.firstResult?.status) },
            { assertEquals(observed.firstRunId, observed.runId) },
            { assertEquals(SyncRunStatus.PARTIAL, observed.result.status) },
            { assertEquals(SyncRunState.PARTIAL, observed.runState) },
            { assertEquals("projection_pending", observed.stopReason) },
            { assertEquals("DEPENDENCY", observed.projectionStatus) },
            { assertEquals(0L, observed.pendingDecisions) },
            { assertEquals(0L, observed.durableConfirmed) },
            { assertEquals(0L, observed.liveConfirmed) },
            { assertEquals(0L, observed.panelConfirmed) },
            { assertEquals(1L, observed.receivedBatches) },
        )
    }

    @Test
    fun `periodic sync continues an unresolved received batch without claiming success`() = runBlocking {
        val observed = runMissingParent(telemetryEnabled = true, retryTrigger = SyncTrigger.PERIODIC)
        assertAll(
            { assertEquals(observed.firstRunId, observed.runId) },
            { assertEquals(SyncRunStatus.PARTIAL, observed.result.status) },
            { assertEquals("projection_pending", observed.stopReason) },
            { assertEquals(0L, observed.durableConfirmed) },
            { assertEquals(1L, observed.receivedBatches) },
        )
    }

    private data class MissingParentObservation(
        val result: SyncRunResult,
        val runState: SyncRunState?,
        val stopReason: String?,
        val durableConfirmed: Long?,
        val liveConfirmed: Long?,
        val panelConfirmed: Long?,
        val pendingDecisions: Long,
        val projectionStatus: String?,
        val firstResult: SyncRunResult? = null,
        val firstRunId: String? = null,
        val runId: String? = null,
        val receivedBatches: Long? = null,
    )

    private suspend fun runMissingParent(
        telemetryEnabled: Boolean,
        retryTrigger: SyncTrigger? = null,
    ): MissingParentObservation {
        FileStorage().use { file ->
            SyncOnboardingFixture(file.storage, InMemoryPreferenceStore(), OkHttpClient()).use { setup ->
                val material = setup.existing("")
                setup.authorize()
                setup.begin()
                val transport = GitHubSyncTransport(
                    setup.client,
                    { "synthetic-token" },
                    setup.git.baseUrl,
                    spaceMaterial = material,
                )
                val initial = transport.readSnapshot(setup.repository, "space", 1).getOrThrow()
                RemoteHistory(setup.git, initial, transport, setup.repository, material)
                    .append(1, 1, "missing-parent", SyncEffectKind.REMOVE, missingParent = true) { 7 }
                val runtime = setup.runtime(progressTelemetryEnabled = telemetryEnabled)
                try {
                    val panel = (runtime.panel as SyncPanelController).takeIf { telemetryEnabled }
                    panel?.act(SyncPanelAction.Open)
                    val firstResult = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    panel?.awaitIdle()
                    val firstRun = runtime.runStore.latest("space", 1)
                    val head = setup.git.head(setup.repository.branch)
                    if (retryTrigger != null) {
                        assertEquals(false, runtime.hasResumableRun())
                        assertEquals(false, runtime.resumeIfNeeded())
                        assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.STARTUP).status)
                    }
                    val result = retryTrigger?.let { runtime.coordinator.synchronize(it) } ?: firstResult
                    panel?.awaitIdle()
                    assertEquals(head, setup.git.head(setup.repository.branch))
                    val run = runtime.runStore.latest("space", 1)
                    val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/scale-7")
                    val projectionStatus = file.storage.handler.await {
                        sync_inboxQueries.getFieldState("space", 1, key.stableKey, SyncField.FAVORITE.name)
                            .executeAsOneOrNull()?.status
                    }
                    val plan = file.explainPendingRuntimeReceipt(requireNotNull(run).runId)
                    assertTrue(plan.contains("USING INDEX sqlite_autoindex_sync_runtime_confirmations_1"), plan)
                    val receivedBatches = file.storage.handler.await {
                        sync_inboxQueries.countInboxBatches("space", 1).executeAsList()
                            .singleOrNull { it.status == "RECEIVED" }?.count ?: 0L
                    }
                    return MissingParentObservation(
                        result,
                        run?.state,
                        run?.stopReason,
                        run?.confirmedItems,
                        runtime.liveProgress.value?.confirmedThisRun,
                        panel?.state?.value?.progress?.confirmedThisRun,
                        mihon.data.sync.inbox.SyncInboxStore(file.storage.handler)
                            .status("space", 1).pendingDecisions,
                        projectionStatus,
                        firstResult,
                        firstRun?.runId,
                        run.runId,
                        receivedBatches,
                    )
                } finally {
                    runtime.stopPanel()
                }
            }
        }
    }

    @Test
    fun `frozen upload round excludes a later edit smaller than its remaining work`() = runBlocking {
        FileStorage().use { file ->
            file.storage.connect("sender", repository)
            file.seedPublishedHistory(512)
            file.storage.favorite("/old-1")
            file.storage.favorite("/old-2")
            file.storage.handler.await { sync_journalQueries.sealSpaceBatches("space", 1) }
            file.storage.favorite("/old-3")
            val outbox = SyncOutboxStore(file.storage.handler)

            val frozen = outbox.freezeRound("space", 1)
            assertEquals(3L, frozen.totalItems)
            assertEquals(2, frozen.batchIds.size)
            val queryPlan = file.explainPendingUploadRound()
            assertTrue(queryPlan.contains("USING INDEX sync_pending_upload_round"), queryPlan)
            file.storage.favorite("/new-edit") // One event is less than the three remaining old events.

            val first = requireNotNull(outbox.nextBatch("space", 1))
            assertEquals(2, first.events.size)
            file.storage.handler.await {
                sync_journalQueries.markBatchPublished("space", 1, first.batchId)
            }
            val second = requireNotNull(outbox.nextBatch("space", 1))
            assertEquals(1, second.events.size)
            file.storage.handler.await {
                sync_journalQueries.markBatchPublished("space", 1, second.batchId)
            }
            val later = requireNotNull(outbox.nextBatch("space", 1))
            assertEquals(1, later.events.size)
            assertTrue(later.batchId != second.batchId)
        }
    }

    @Test
    fun `exchange starts a new visible round for an edit added during the first publish`() = runBlocking {
        val client = OkHttpClient()
        FileStorage().use { file ->
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = GitHubSyncTransport(
                    client,
                    { "scale-fixture-token" },
                    git.baseUrl,
                    indexSecret = secret,
                )
                transport.initialize(repository, "space", 1)
                file.storage.connect("sender", repository)
                file.storage.favorite("/old-1")
                file.storage.favorite("/old-2")
                file.storage.handler.await { sync_journalQueries.sealSpaceBatches("space", 1) }
                file.storage.favorite("/old-3")
                var published = 0
                val concurrentEdit = object : SyncTransportPort by transport {
                    override suspend fun publish(
                        repository: SyncRepository,
                        snapshot: SyncSnapshot,
                        upload: SyncPreparedUpload,
                        observeSnapshot: suspend (SyncSnapshot) -> Unit,
                    ): SyncPublishResult {
                        if (published++ == 0) file.storage.favorite("/new-during-publish")
                        return transport.publish(repository, snapshot, upload, observeSnapshot)
                    }
                }
                val sink = MutableStateFlow<SyncProgressFact?>(null)
                val facts = CopyOnWriteArrayList<SyncProgressFact>()
                val collector = launch(Dispatchers.Unconfined) { sink.filterNotNull().collect { facts += it } }
                val clock = AtomicLong(0)
                val progress = mihon.data.sync.runtime.SyncLiveProgressSession(
                    "added-round",
                    sink,
                    millis = { clock.addAndGet(251) },
                ).also { it.activate() }
                try {
                    val exchange = mihon.data.sync.runtime.SyncDatabaseExchange(
                        file.storage.handler,
                        file.storage.baseline,
                        file.storage.projector,
                        concurrentEdit,
                        secret,
                        liveProgress = progress,
                    )
                    assertExchange(exchange.exchange("space", 1, repository), uploaded = 4)
                    val first = facts.filter {
                        it.scope.endsWith(":upload-round-0") && it.direction == SyncProgressDirection.UPLOAD
                    }
                    assertTrue(first.isNotEmpty())
                    assertTrue(first.all { it.totalItems == 3L && it.completedItems <= 3L })
                    val second = facts.filter {
                        it.scope.endsWith(":upload-round-1") && it.direction == SyncProgressDirection.UPLOAD
                    }
                    assertTrue(second.any { it.additionalWork && it.stage == SyncProgressStage.PREPARING })
                    assertTrue(second.all { it.totalItems == 1L && it.completedItems <= 1L })
                } finally {
                    collector.cancel()
                }
            }
        }
    }

    @Test
    fun `upload preparation stays visible while real artifact generation is blocked`() = runBlocking {
        val client = OkHttpClient()
        FileStorage().use { file ->
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = GitHubSyncTransport(
                    client,
                    { "scale-fixture-token" },
                    git.baseUrl,
                    indexSecret = secret,
                )
                transport.initialize(repository, "space", 1)
                file.storage.connect("sender", repository)
                file.storage.favorite("/slow-preparation")
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocked = object : SyncTransportPort by transport {
                    override fun prepare(
                        snapshot: SyncSnapshot,
                        encryptedBatch: SyncEncryptedBatch,
                    ): SyncPreparedUpload {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return transport.prepare(snapshot, encryptedBatch)
                    }
                }
                val sink = MutableStateFlow<SyncProgressFact?>(null)
                val progress = mihon.data.sync.runtime.SyncLiveProgressSession("slow-prepare", sink)
                    .also { it.activate() }
                val exchange = mihon.data.sync.runtime.SyncDatabaseExchange(
                    file.storage.handler,
                    file.storage.baseline,
                    file.storage.projector,
                    blocked,
                    secret,
                    liveProgress = progress,
                )
                val running = async(Dispatchers.IO) { exchange.exchange("space", 1, repository) }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    assertEquals(SyncProgressStage.PREPARING, sink.value?.stage)
                    assertTrue(sink.value?.scope?.endsWith(":upload-round-0") == true)
                    assertEquals(1L, sink.value?.totalItems)
                    assertEquals(0L, sink.value?.completedItems)
                    val prepared = file.storage.handler.await {
                        sync_journalQueries.getNextUploadBatch("space", 1).executeAsOne().prepared_upload
                    }
                    assertNull(prepared)
                } finally {
                    release.countDown()
                }
                assertExchange(running.await(), uploaded = 1)
            }
        }
    }

    @Test
    fun `slow received body exposes local eta in the production runtime panel before completion`() = runBlocking {
        val client = OkHttpClient()
        FileStorage().use { sender ->
            SyncOnboardingFixture(sender.storage, InMemoryPreferenceStore(), client).use { setup ->
                val material = setup.existing("")
                setup.authorize()
                setup.begin()
                repeat(128) { sender.storage.favorite("/slow-body-$it") }
                val sent = setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                assertEquals(SyncRunStatus.SUCCESS, sent.status)
                assertEquals(128, sent.uploaded)

                FileStorage().use { receiver ->
                    val receiverRuntime = SyncRuntime(
                        receiver.storage.handler,
                        receiver.storage.bootstrap,
                        receiver.storage.creators,
                        receiver.storage.creators,
                        { true },
                        MemorySyncSecureStore(),
                        InMemoryPreferenceStore(),
                        OkHttpClient(),
                        setup.endpoints,
                    )
                    try {
                        receiverRuntime.credentials.replace(
                            null,
                            GitHubAccessToken("synthetic-token", null, "bearer", emptySet(), null, null),
                        )
                        val space = (
                            receiverRuntime.onboarding.discover() as mihon.data.sync.auth.SyncSpaceDiscovery.Found
                            ).space
                        receiverRuntime.onboarding.resume(receiverRuntime.onboarding.join(space, material))
                        val panel = receiverRuntime.panel as SyncPanelController
                        panel.act(SyncPanelAction.Open)
                        val delegate = setup.git.server.dispatcher
                        setup.git.server.dispatcher = object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse {
                                val response = delegate.dispatch(request)
                                return if (request.method == "GET" &&
                                    request.url.encodedPath.contains("/git/blobs/")
                                ) {
                                    response.newBuilder().throttleBody(8_192, 1, TimeUnit.SECONDS).build()
                                } else {
                                    response
                                }
                            }
                        }
                        val exchange = async(Dispatchers.IO) {
                            receiverRuntime.coordinator.synchronize(SyncTrigger.MANUAL)
                        }
                        val inFlight = withTimeout(15_000) {
                            panel.state.first { it.progress?.activeBodyEtaSeconds != null }.progress
                        }
                        assertNotNull(inFlight)
                        assertTrue(!exchange.isCompleted, "the body ETA must appear before transfer finishes")
                        assertEquals(SyncProgressDirection.DOWNLOAD, inFlight?.direction)
                        assertTrue((inFlight?.activeBodyEtaSeconds ?: 0L) > 0L)
                        assertTrue((inFlight?.activeBodyBytes ?: 0L) < (inFlight?.activeBodyTotal ?: 0L))
                        assertNull(inFlight?.wholeEtaSeconds)
                        assertEquals(SyncRunStatus.SUCCESS, exchange.await().status)
                    } finally {
                        receiverRuntime.stopPanel()
                    }
                }
            }
        }
    }

    @Test
    fun `one frozen remote head keeps cumulative item count across discovery pages`() = runBlocking {
        val client = OkHttpClient()
        FileStorage().use { file ->
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport =
                    GitHubSyncTransport(client, { "scale-fixture-token" }, git.baseUrl, indexSecret = secret)
                transport.initialize(repository, "space", 1)
                val history = RemoteHistory(git, transport.readSnapshot(repository, "space", 1).getOrThrow(), transport)
                history.append(129, 129, "paged", SyncEffectKind.ADD) { it }
                file.storage.connect("receiver", repository)
                val sink = MutableStateFlow<mihon.data.sync.runtime.SyncProgressFact?>(null)
                val progress = mihon.data.sync.runtime.SyncLiveProgressSession("paged-run", sink).also { it.activate() }
                val exchange = mihon.data.sync.runtime.SyncDatabaseExchange(
                    file.storage.handler,
                    file.storage.baseline,
                    file.storage.projector,
                    transport,
                    secret,
                    liveProgress = progress,
                )
                assertExchange(exchange.exchange("space", 1, repository), downloaded = 129)
                assertEquals(129L, sink.value?.completedItems)
                assertNull(sink.value?.totalItems)
            }
        }
    }

    @Test
    fun `received download is not confirmed until the real projector finishes`() = runBlocking {
        val client = OkHttpClient()
        FileStorage().use { file ->
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = GitHubSyncTransport(
                    client,
                    { "scale-fixture-token" },
                    git.baseUrl,
                    indexSecret = secret,
                )
                transport.initialize(repository, "space", 1)
                val history = RemoteHistory(git, transport.readSnapshot(repository, "space", 1).getOrThrow(), transport)
                history.append(1, 1, "received", SyncEffectKind.ADD) { it }
                file.storage.connect("receiver", repository)
                val runStore = SyncRunStore(file.storage.handler)
                val run = runStore.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runStore.claim(run.runId, "receiver-owner", 1))
                val durableReporter = runStore.reporter(run.runId, "receiver-owner")
                val received = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val reporter = object : SyncProgressReporter by durableReporter {
                    override suspend fun totals(uploaded: Long, downloaded: Long) {
                        durableReporter.totals(uploaded, downloaded)
                        if (downloaded > 0L) {
                            received.complete(Unit)
                            release.await()
                        }
                    }
                }
                val sink = MutableStateFlow<mihon.data.sync.runtime.SyncProgressFact?>(null)
                val progress = mihon.data.sync.runtime.SyncLiveProgressSession("receiver-run", sink)
                    .also { it.activate() }
                val exchange = mihon.data.sync.runtime.SyncDatabaseExchange(
                    file.storage.handler,
                    file.storage.baseline,
                    file.storage.projector,
                    transport,
                    secret,
                    progress = reporter,
                    liveProgress = progress,
                )
                val pending = async(Dispatchers.IO) { exchange.exchange("space", 1, repository) }
                try {
                    withTimeout(15_000) { received.await() }
                    assertEquals(0L, sink.value?.confirmedThisRun)
                    assertEquals(1L, progress.snapshot().receivedItems)
                    assertEquals(0L, progress.snapshot().completedItems)
                } finally {
                    release.complete(Unit)
                }
                assertExchange(pending.await(), downloaded = 1)
                assertEquals(1L, sink.value?.confirmedThisRun)
                assertTrue(progress.snapshot().checkedFields > 0L)
                assertEquals(1L, runStore.get(run.runId)?.confirmedItems)
            }
        }
    }

    @Test
    fun `a missing durable receipt confirmation cannot be replaced by accepted download display`() = runBlocking {
        val client = OkHttpClient()
        FileStorage().use { file ->
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport =
                    GitHubSyncTransport(client, { "scale-fixture-token" }, git.baseUrl, indexSecret = secret)
                transport.initialize(repository, "space", 1)
                val history = RemoteHistory(git, transport.readSnapshot(repository, "space", 1).getOrThrow(), transport)
                history.append(1, 1, "receipt-silent", SyncEffectKind.ADD) { it }
                file.storage.connect("receiver", repository)
                val runStore = SyncRunStore(file.storage.handler)
                val run = runStore.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runStore.claim(run.runId, "receiver-owner", 1))
                val durableReporter = runStore.reporter(run.runId, "receiver-owner")
                val silentReporter = object : SyncProgressReporter by durableReporter {
                    override suspend fun confirmReceived(): List<Pair<String, Long>> = emptyList()
                }
                val sink = MutableStateFlow<SyncProgressFact?>(null)
                val progress = mihon.data.sync.runtime.SyncLiveProgressSession("silent-receipt", sink)
                    .also { it.activate() }
                val exchange = mihon.data.sync.runtime.SyncDatabaseExchange(
                    file.storage.handler,
                    file.storage.baseline,
                    file.storage.projector,
                    transport,
                    secret,
                    progress = silentReporter,
                    liveProgress = progress,
                )
                val result = exchange.exchange("space", 1, repository)
                assertEquals(SyncRunStatus.PARTIAL, result.status)
                assertEquals(0L, runStore.get(run.runId)?.confirmedItems)
                assertEquals(0L, sink.value?.confirmedThisRun)
            }
        }
    }

    @Test
    fun `diagnose projected fields with real inbox and file database`() = runBlocking {
        val fieldCount = System.getenv("SYNC_PROJECT_FIELDS")?.toIntOrNull() ?: 500
        require(fieldCount in 250..2_000)
        val actors = System.getenv("SYNC_PROJECT_ACTORS")?.toIntOrNull() ?: 1
        require(actors in 1..10)
        val sql = SqlTiming()
        val client = OkHttpClient()
        FileStorage(sql).use { file ->
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = GitHubSyncTransport(
                    client,
                    { "scale-fixture-token" },
                    git.baseUrl,
                    indexSecret = secret,
                )
                transport.initialize(repository, "space", 1)
                val history = RemoteHistory(git, transport.readSnapshot(repository, "space", 1).getOrThrow(), transport)
                file.storage.connect("receiver", repository)
                history.append(fieldCount * 10, actors, "diagnostic", SyncEffectKind.ADD) { it % fieldCount }
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val inbox = SyncInboxStore(file.storage.handler)
                val discovery = SyncDiscoveryStore(file.storage.handler)
                val exchange = SyncInboxExchange(inbox, SyncBatchSyncService(transport, secret))
                val ingestStart = System.nanoTime()
                inbox.observeSnapshot(snapshot, discovery)
                snapshot.batches.forEach { entry ->
                    assertTrue(exchange.receive(snapshot, entry, snapshotAlreadyObserved = true).accepted)
                }
                val ingestMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ingestStart)
                sql.reset()
                file.commitTiming?.reset()
                val profile = if (System.getenv("SYNC_PROJECT_JFR") == "1") {
                    Recording(Configuration.getConfiguration("profile")).apply {
                        enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10))
                        enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1))
                        enable("jdk.FileRead").withThreshold(Duration.ofMillis(1))
                        enable("jdk.FileWrite").withThreshold(Duration.ofMillis(1))
                        start()
                    }
                } else {
                    null
                }
                val projectStart = System.nanoTime()
                var projected = 0
                var pages = 0
                try {
                    while (true) {
                        val count = file.storage.projector.project("space", 1)
                        projected += count
                        pages++
                        if (count < 50) break
                    }
                } finally {
                    profile?.let {
                        it.stop()
                        val path = Files.createTempFile("sync-project-", ".jfr")
                        it.dump(path)
                        it.close()
                        println("SYNC_PROJECT_JFR path=$path")
                    }
                }
                val projectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - projectStart)
                val commits = file.commitTiming?.count ?: 0
                val commitMillis = TimeUnit.NANOSECONDS.toMillis(file.commitTiming?.nanos ?: 0)
                assertEquals(fieldCount, projected)
                assertEquals(fieldCount.toLong(), file.storage.manga.countLibraryMangaForCreatorIndex())
                val emptyTransactionStart = System.nanoTime()
                repeat(fieldCount) { file.storage.handler.await(inTransaction = true) {} }
                val emptyTransactionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - emptyTransactionStart)
                println(
                    "SYNC_PROJECT_DIAGNOSTIC events=${fieldCount * 10} actors=$actors " +
                        "uniqueFields=$projected pages=$pages ingestMs=$ingestMillis projectMs=$projectMillis " +
                        "commitCalls=$commits commitMs=$commitMillis emptyTransactionMs=$emptyTransactionMillis " +
                        "sqlCalls=${sql.calls} sqlMs=${TimeUnit.NANOSECONDS.toMillis(sql.nanos)} " +
                        "dirtyQueryCalls=${sql.dirtyCalls} dirtyQueryMs=${TimeUnit.NANOSECONDS.toMillis(
                            sql.dirtyNanos,
                        )} " +
                        "transactions=${sql.transactions} outerTransactions=${sql.outerTransactions}",
                )
                assertTrue(
                    commits <= (fieldCount + 49) / 50 + 3,
                    "projection committed $commits times for $fieldCount fields",
                )
            }
        }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Production runtime path with onboarding, account gate, space material and persistent cache. */
    @Test
    fun `runtime baseline first import complete exchange warm increment and recovery`() = runBlocking {
        val samples = System.getenv("SYNC_PERF_FIRST_SAMPLES")?.toIntOrNull() ?: 1
        require(samples in 1..7)
        repeat(samples) { runRuntimeBaseline(it + 1) }
    }

    @Test
    fun `controlled first import and exchange profile`() = runBlocking {
        val samples = System.getenv("SYNC_PERF_FIRST_SAMPLES")?.toIntOrNull() ?: 7
        require(samples in 1..7)
        repeat(samples) { runRuntimeBaseline(it + 1, controlledFirst = true) }
    }

    private suspend fun CoroutineScope.runRuntimeBaseline(sample: Int, controlledFirst: Boolean = false) {
        println("SYNC_SCALE_SAMPLE $sample")
        val scenario = Scenario(300, 1, 0)
        val sender = FileStorage()
        val receiver = FileStorage()
        val memory = HeapSampler()
        val senderCache = Files.createTempDirectory("mihon-sync-sender-cache-")
        val receiverCache = Files.createTempDirectory("mihon-sync-receiver-cache-")
        try {
            SyncOnboardingFixture(sender.storage).use { setup ->
                val material = setup.existing("")
                setup.authorize()
                val senderMetrics = InMemorySyncMetrics()
                val senderRuntime = setup.runtime(senderMetrics, senderCache.toString().toPath())
                val receiverMetrics = InMemorySyncMetrics()
                val receiverRuntime = SyncRuntime(
                    receiver.storage.handler,
                    receiver.storage.bootstrap,
                    receiver.storage.creators,
                    receiver.storage.creators,
                    { true },
                    MemorySyncSecureStore(),
                    InMemoryPreferenceStore(),
                    setup.client,
                    setup.endpoints,
                    clock = { setup.now },
                    persistentObjectCacheDirectory = receiverCache.toString().toPath(),
                    syncMetrics = receiverMetrics,
                )
                try {
                    measure(scenario, "runtime_fixture_local_library", setup.git, sender, memory) {
                        repeat(scenario.events) { sender.storage.favorite("/runtime-$it") }
                    }
                    val senderSpace = requireNotNull(
                        (senderRuntime.onboarding.discover() as mihon.data.sync.auth.SyncSpaceDiscovery.Found).space,
                    )
                    val senderIntent = senderRuntime.onboarding.join(senderSpace, material)
                    val profileRequestBytes = AtomicLong()
                    val profileResponseBytes = AtomicLong()
                    val profilePaths = CopyOnWriteArrayList<String>()
                    if (controlledFirst) {
                        val delegate = setup.git.server.dispatcher
                        setup.git.server.dispatcher = object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse {
                                profilePaths += "${request.method} ${request.url.encodedPath}" +
                                    (request.url.encodedQuery?.let { "?$it" } ?: "")
                                val response = delegate.dispatch(request)
                                val uploadBytes = request.body?.size?.toLong() ?: 0L
                                profileRequestBytes.addAndGet(uploadBytes)
                                profileResponseBytes.addAndGet(response.body?.contentLength ?: 0L)
                                val uploadMillis = (uploadBytes + 1_249L) / 1_250L
                                if (uploadMillis > 0) Thread.sleep(uploadMillis)
                                return response.newBuilder()
                                    .headersDelay(50, TimeUnit.MILLISECONDS)
                                    .throttleBody(12_500, 10, TimeUnit.MILLISECONDS)
                                    .build()
                            }
                        }
                    }
                    val acceptedAt = System.nanoTime()
                    measure(scenario, "runtime_first_connect_freeze", setup.git, sender, memory) {
                        senderRuntime.onboarding.resume(senderIntent)
                    }
                    if (controlledFirst) {
                        println("SYNC_CONTROLLED_CONNECT_PATHS sample=$sample paths=${profilePaths.joinToString("|")}")
                    }
                    val importComplete = async(Dispatchers.IO) {
                        withTimeout(60_000) {
                            sender.storage.handler.subscribeToOne {
                                sync_importQueries.countPendingImports("space", 1)
                            }.first { it == 0L }
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAt)
                        }
                    }
                    val firstConfirmed = async(Dispatchers.IO) {
                        withTimeout(60_000) {
                            sender.storage.handler.subscribeToOne {
                                sync_journalQueries.countPublishedEvents("space", 1)
                            }.first { it > 0L }
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAt)
                        }
                    }
                    val importFact = async(Dispatchers.IO) {
                        withTimeout(60_000) {
                            senderRuntime.liveProgress.first { (it?.importCompletedItems ?: 0L) > 0L }
                        }
                    }
                    val first = measure(
                        scenario,
                        "runtime_first_import_and_upload_confirmed_total",
                        setup.git,
                        sender,
                        memory,
                    ) {
                        senderRuntime.coordinator.synchronize(SyncTrigger.MANUAL)
                    }
                    val allConfirmedSinceConnectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAt)
                    val importCompleteMillis = importComplete.await()
                    val firstConfirmedMillis = firstConfirmed.await()
                    val observedImport = requireNotNull(importFact.await())
                    assertEquals(scenario.events.toLong(), observedImport.importTotalItems)
                    assertNull(observedImport.totalItems)
                    assertEquals(0L, observedImport.completedItems)
                    assertEquals(SyncRunStatus.SUCCESS, first.status, first.toString())
                    assertEquals(scenario.events, first.uploaded)
                    assertEquals(scenario.events.toLong(), sender.storage.eventCount())
                    assertTrue(SyncLocalJournal(sender.storage.handler).pendingEvents("space", 1).isEmpty())
                    println(
                        "SYNC_SCALE " + buildJsonObject {
                            put("initialEvents", scenario.events)
                            put("phase", "runtime_initial_import_generated")
                            put("elapsedSinceConnectMillis", importCompleteMillis)
                            put("observation", "sqldelight_query_notification")
                        },
                    )
                    println(
                        "SYNC_SCALE " + buildJsonObject {
                            put("initialEvents", scenario.events)
                            put("phase", "runtime_first_batch_locally_confirmed")
                            put("elapsedSinceConnectMillis", firstConfirmedMillis)
                            put("confirmation", "durable_published_batch")
                            put("observation", "sqldelight_query_notification")
                        },
                    )
                    println(
                        "SYNC_SCALE " + buildJsonObject {
                            put("initialEvents", scenario.events)
                            put("phase", "runtime_all_confirmed_since_connect")
                            put("elapsedSinceConnectMillis", allConfirmedSinceConnectMillis)
                            put("sample", sample)
                        },
                    )

                    receiverRuntime.credentials.replace(
                        null,
                        GitHubAccessToken("synthetic-token", null, "bearer", emptySet(), null, null),
                    )
                    val receiverSpace = requireNotNull(
                        (receiverRuntime.onboarding.discover() as mihon.data.sync.auth.SyncSpaceDiscovery.Found).space,
                    )
                    val receiverIntent = receiverRuntime.onboarding.join(receiverSpace, material)
                    measure(scenario, "runtime_first_download_connect_merge", setup.git, receiver, memory) {
                        receiverRuntime.onboarding.resume(receiverIntent)
                        receiverRuntime.coordinator.synchronize(SyncTrigger.MANUAL)
                    }.also { assertEquals(SyncRunStatus.SUCCESS, it.status, it.toString()) }
                    assertEquals(scenario.events.toLong(), receiver.storage.eventCount())
                    assertEquals(scenario.events.toLong(), receiver.storage.manga.countLibraryMangaForCreatorIndex())
                    if (controlledFirst) {
                        println(
                            "SYNC_SCALE " + buildJsonObject {
                                put("phase", "runtime_controlled_first_wire_bytes")
                                put("sample", sample)
                                put("requestBodyBytes", profileRequestBytes.get())
                                put("responseBodyBytes", profileResponseBytes.get())
                            },
                        )
                        return
                    }

                    sender.storage.favorite("/runtime-warm")
                    measure(scenario, "runtime_warm_upload_one", setup.git, sender, memory) {
                        senderRuntime.coordinator.synchronize(SyncTrigger.MANUAL)
                    }.also { assertEquals(SyncRunStatus.SUCCESS, it.status, it.toString()) }
                    sender.storage.favorite("/runtime-recovery")
                    setup.git.nextRefResponse = mockwebserver3.MockResponse(code = 500)
                    setup.git.failReadAfterPatch = true
                    val interrupted = measure(
                        scenario,
                        "runtime_lost_publish_and_read_failure",
                        setup.git,
                        sender,
                        memory,
                    ) {
                        senderRuntime.coordinator.synchronize(SyncTrigger.MANUAL)
                    }
                    assertTrue(interrupted.status != SyncRunStatus.SUCCESS, interrupted.toString())
                    val publishedHead = setup.git.head(setup.repository.branch)
                    val publications = setup.git.forceFlags.size
                    setup.now += 10_000
                    val reopened = setup.runtime(senderMetrics, senderCache.toString().toPath())
                    measure(scenario, "runtime_recovery_new_runtime", setup.git, sender, memory) {
                        reopened.coordinator.synchronize(SyncTrigger.RECOVERY)
                    }.also { assertEquals(SyncRunStatus.SUCCESS, it.status, it.toString()) }
                    assertEquals(publishedHead, setup.git.head(setup.repository.branch))
                    assertEquals(publications, setup.git.forceFlags.size)
                    assertTrue(SyncLocalJournal(sender.storage.handler).pendingEvents("space", 1).isEmpty())
                    reopened.stopPanel()
                } finally {
                    senderRuntime.stopPanel()
                    receiverRuntime.stopPanel()
                }
            }
        } finally {
            receiver.close()
            sender.close()
            memory.close()
            senderCache.toFile().deleteRecursively()
            receiverCache.toFile().deleteRecursively()
        }
    }

    /** Seven independent H=100 warm increments; fixture creation and cold download stay outside the profile. */
    @Test
    fun `controlled warm increment with one hundred historical batches`() = runBlocking {
        val scenario = Scenario(100, 1, 0)
        repeat(System.getenv("SYNC_PERF_SAMPLES")?.toIntOrNull() ?: 7) { sample ->
            val file = FileStorage()
            val memory = HeapSampler()
            val cache = Files.createTempDirectory("mihon-sync-h100-cache-")
            try {
                SyncOnboardingFixture(file.storage).use { setup ->
                    val material = setup.existing("")
                    setup.authorize()
                    val transport = GitHubSyncTransport(
                        setup.client,
                        { "synthetic-token" },
                        setup.git.baseUrl,
                        spaceMaterial = material,
                    )
                    val initial = transport.readSnapshot(setup.repository, "space", 1).getOrThrow()
                    val history = RemoteHistory(setup.git, initial, transport, setup.repository, material)
                    repeat(100) { index ->
                        history.append(1, 1, "seed-$index", SyncEffectKind.ADD) { index }
                    }
                    assertEquals(100, history.entries.size)
                    val runtime = setup.runtime(persistentObjectCacheDirectory = cache.toString().toPath())
                    try {
                        val space =
                            (runtime.onboarding.discover() as mihon.data.sync.auth.SyncSpaceDiscovery.Found).space
                        val intent = runtime.onboarding.join(space, material)
                        runtime.onboarding.resume(intent)
                        val cold = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                        assertEquals(SyncRunStatus.SUCCESS, cold.status, cold.toString())
                        assertEquals(100L, file.storage.eventCount())
                        val delegate = setup.git.server.dispatcher
                        val requestBytes = AtomicLong()
                        val responseBytes = AtomicLong()
                        setup.git.server.dispatcher = object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse {
                                val response = delegate.dispatch(request)
                                val uploadBytes = request.body?.size?.toLong() ?: 0L
                                requestBytes.addAndGet(uploadBytes)
                                responseBytes.addAndGet(response.body?.contentLength ?: 0L)
                                val uploadMillis = (uploadBytes + 1_249L) / 1_250L
                                if (uploadMillis > 0) Thread.sleep(uploadMillis)
                                return response.newBuilder()
                                    .headersDelay(50, TimeUnit.MILLISECONDS)
                                    .throttleBody(12_500, 10, TimeUnit.MILLISECONDS)
                                    .build()
                            }
                        }
                        file.storage.favorite("/warm-h100-$sample")
                        val result = measure(
                            scenario,
                            "h100_warm_increment_sample_${sample + 1}",
                            setup.git,
                            file,
                            memory,
                        ) {
                            runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                        }
                        assertEquals(SyncRunStatus.SUCCESS, result.status, result.toString())
                        assertEquals(1, result.uploaded)
                        println(
                            "SYNC_SCALE " + buildJsonObject {
                                put("phase", "h100_wire_bytes_sample_${sample + 1}")
                                put("requestBodyBytes", requestBytes.get())
                                put("responseBodyBytes", responseBytes.get())
                            },
                        )
                    } finally {
                        runtime.stopPanel()
                    }
                }
            } finally {
                file.close()
                memory.close()
                cache.toFile().deleteRecursively()
            }
        }
    }

    /** Candidate-only loopback measurement; fixture preparation is never counted as synchronization. */
    @Test
    fun `candidate baseline first import upload download warm exchange and recovery`() = runBlocking {
        val scenario = Scenario(1_000, 1, 0)
        val client = OkHttpClient()
        val memory = HeapSampler()
        val sender = FileStorage()
        val receiver = FileStorage()
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                fun transport() = GitHubSyncTransport(
                    client,
                    { "scale-fixture-token" },
                    git.baseUrl,
                    indexSecret = secret,
                )
                val upload = transport()
                upload.initialize(repository, "space", 1)
                measure(scenario, "candidate_fixture_local_library", git, sender, memory) {
                    repeat(scenario.events) { sender.storage.favorite("/candidate-$it") }
                }
                measure(scenario, "candidate_first_import", git, sender, memory) {
                    val importId = sender.storage.connect("candidate-sender", repository)
                    while (sender.storage.baseline.process(importId).remaining > 0) Unit
                }
                assertEquals(scenario.events.toLong(), sender.storage.eventCount())
                val first = measure(scenario, "candidate_first_upload_total", git, sender, memory) {
                    sender.storage.exchange(upload, secret, repository)
                }
                assertExchange(first, uploaded = scenario.events)
                assertTrue(SyncLocalJournal(sender.storage.handler).pendingEvents("space", 1).isEmpty())

                receiver.storage.connect("candidate-receiver", repository)
                val download = transport()
                val received = measure(scenario, "candidate_first_download_total", git, receiver, memory) {
                    receiver.storage.exchange(download, secret, repository)
                }
                assertExchange(received, downloaded = scenario.events)
                assertEquals(scenario.events.toLong(), receiver.storage.eventCount())
                assertEquals(scenario.events.toLong(), receiver.storage.manga.countLibraryMangaForCreatorIndex())

                sender.storage.favorite("/candidate-warm")
                val warm = measure(scenario, "candidate_warm_upload_one", git, sender, memory) {
                    sender.storage.exchange(upload, secret, repository)
                }
                assertExchange(warm, uploaded = 1)

                sender.storage.favorite("/candidate-recovery")
                git.nextRefResponse = mockwebserver3.MockResponse(code = 500)
                git.failReadAfterPatch = true
                val interrupted = measure(scenario, "candidate_lost_publish_and_read_failure", git, sender, memory) {
                    sender.storage.exchange(upload, secret, repository)
                }
                assertTrue(interrupted.status != SyncRunStatus.SUCCESS, interrupted.toString())
                val publishedHead = git.head(repository.branch)
                val publications = git.forceFlags.size
                // New transport removes process-local caches; the file-backed safety records survive.
                val resumed = measure(scenario, "candidate_recovery_fresh_transport", git, sender, memory) {
                    sender.storage.exchange(transport(), secret, repository)
                }
                assertEquals(SyncRunStatus.SUCCESS, resumed.status, resumed.toString())
                assertEquals(publishedHead, git.head(repository.branch))
                assertEquals(publications, git.forceFlags.size, "Recovery must not publish the same batch twice")
                assertTrue(SyncLocalJournal(sender.storage.handler).pendingEvents("space", 1).isEmpty())
                assertExchange(receiver.storage.exchange(download, secret, repository), downloaded = 2)
                assertEquals(scenario.events + 2L, receiver.storage.eventCount())
                assertEquals(scenario.events + 2L, receiver.storage.manga.countLibraryMangaForCreatorIndex())
            }
        } finally {
            receiver.close()
            sender.close()
            memory.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `complete histories and frozen decisions remain correct at ten and hundred thousand events`() = runBlocking {
        for (scenario in listOf(Scenario(10_000, 3, 120), Scenario(100_000, 10, 10_000))) {
            runScenario(scenario)
        }
    }

    @Test
    fun `diagnose frozen bulk decisions on file database`() = runBlocking {
        runScenario(Scenario(5_010, 10, 501), profileBulk = true)
    }

    private suspend fun runScenario(scenario: Scenario, profileBulk: Boolean = false) {
        val client = OkHttpClient()
        val memory = HeapSampler()
        val timing = if (profileBulk) SqlTiming() else null
        val primary = FileStorage(timing)
        // A second independent real receiver checks convergence without replaying 100k twice.
        val second = if (scenario.events == 10_000) FileStorage() else null
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = GitHubSyncTransport(
                    client,
                    { "scale-fixture-token" },
                    git.baseUrl,
                    indexSecret = secret,
                )
                transport.initialize(repository, "space", 1)
                val history = RemoteHistory(git, transport.readSnapshot(repository, "space", 1).getOrThrow(), transport)
                val storage = primary.storage
                storage.connect("receiver-primary", repository)
                val emptyBytes = primary.bytes()
                measure(scenario, "fixture_initial", git, primary, memory) {
                    history.append(scenario.events, scenario.devices, "initial", SyncEffectKind.ADD) {
                        it % (scenario.pending + 1)
                    }
                }
                assertEquals(scenario.events.toLong(), history.eventCount())
                assertEquals(scenario.devices, history.entries.map { it.actorId }.toSet().size)
                val first = measure(scenario, "first_exchange", git, primary, memory) {
                    storage.exchange(transport, secret, repository)
                }
                assertExchange(first, downloaded = scenario.events)
                assertEquals(scenario.events.toLong(), storage.eventCount())
                assertEquals(scenario.pending + 1L, storage.manga.countLibraryMangaForCreatorIndex())
                assertEquals(0L, SyncInboxStore(storage.handler).status("space", 1).pendingDecisions)
                assertTrue(primary.bytes() > emptyBytes)
                second?.storage?.let {
                    it.connect("receiver-secondary", repository)
                    assertExchange(it.exchange(transport, secret, repository), downloaded = scenario.events)
                    assertEquals(
                        storage.manga.countLibraryMangaForCreatorIndex(),
                        it.manga.countLibraryMangaForCreatorIndex(),
                    )
                }

                val initialRemoteBytes = history.bytes()
                val bootstrapBytes = history.bootstrapBytes()
                measure(scenario, "fixture_increment", git, primary, memory) {
                    history.append(scenario.pending, scenario.devices, "remove", SyncEffectKind.REMOVE) { it }
                }
                storage.favorite("/scale-local-upload")
                val increment = measure(scenario, "increment_exchange", git, primary, memory) {
                    storage.exchange(transport, secret, repository)
                }
                assertExchange(increment, downloaded = scenario.pending, uploaded = 1, pending = scenario.pending)
                assertTrue(SyncLocalJournal(storage.handler).pendingEvents("space", 1).isEmpty())
                assertTrue(history.bytes() > initialRemoteBytes)
                second?.storage?.let {
                    assertExchange(
                        it.exchange(transport, secret, repository),
                        downloaded = scenario.pending + 1,
                        pending = scenario.pending,
                    )
                    assertEquals(
                        storage.manga.countLibraryMangaForCreatorIndex(),
                        it.manga.countLibraryMangaForCreatorIndex(),
                    )
                }

                exercisePanel(scenario, storage, primary, history, transport, git, memory, timing)
                val expectedEvents = scenario.events + scenario.pending + 3L
                assertEquals(expectedEvents, storage.eventCount())
                val unchangedHead = git.head(repository.branch)
                val duplicate = measure(scenario, "unchanged_exchange", git, primary, memory) {
                    storage.exchange(transport, secret, repository)
                }
                assertExchange(duplicate)
                assertEquals(expectedEvents, storage.eventCount())
                assertEquals(unchangedHead, git.head(repository.branch))
                assertTrue(SyncLocalJournal(storage.handler).pendingEvents("space", 1).isEmpty())
                history.assertPreserved(storage)
                val finalSnapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                assertEquals(expectedEvents, finalSnapshot.batches.sumOf { it.lastSeq - it.firstSeq + 1 })
                val finalRemoteBytes = finalSnapshot.tree.entries.filter { it.type == "blob" }
                    .sumOf { requireNotNull(it.size) }
                assertTrue(git.forceFlags.isNotEmpty())
                assertTrue(git.forceFlags.none { it })
                assertTrue(git.invalidBaseTrees.isEmpty())
                second?.storage?.let {
                    assertExchange(
                        it.exchange(transport, secret, repository),
                        downloaded = 2,
                        pending = scenario.pending,
                    )
                    assertEquals(expectedEvents, it.eventCount())
                    // Keep/confirm decisions belong only to the receiving device.
                    assertEquals(scenario.pending + 2L, it.manga.countLibraryMangaForCreatorIndex())
                }
                metrics(
                    scenario, "complete", git.server.requestCount, primary.bytes(), memory.peak.get(),
                    "events" to expectedEvents,
                    "repositoryInitialBlobBytes" to (initialRemoteBytes + bootstrapBytes),
                    "repositoryReachableBlobBytes" to finalRemoteBytes,
                    "repositoryBlobGrowthBytes" to (finalRemoteBytes - initialRemoteBytes - bootstrapBytes),
                    "heapBaselineBytes" to memory.baseline,
                    "heapPeakGrowthBytes" to (memory.peak.get() - memory.baseline),
                )
            }
        } finally {
            second?.close()
            primary.close()
            memory.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun exercisePanel(
        scenario: Scenario,
        storage: SyncRuntimeStorageContract.Storage,
        file: FileStorage,
        history: RemoteHistory,
        transport: GitHubSyncTransport,
        git: SyncGitSafetyContractTest.GitFixture,
        memory: HeapSampler,
        timing: SqlTiming?,
    ) = withPanel(storage) { panel, runtime ->
        measure(scenario, "panel_open", git, file, memory) { panel.act(SyncPanelAction.Open) }
        assertEquals(scenario.pending.toLong(), panel.state.value.pendingTotal)
        assertEquals(100, panel.state.value.pending.size)
        assertTrue(panel.state.value.hasMore)
        measure(scenario, "panel_load_more", git, file, memory) { panel.act(SyncPanelAction.LoadMore) }
        assertEquals(minOf(200, scenario.pending), panel.state.value.pending.size)
        val visible = panel.state.value.pending
        panel.act(SyncPanelAction.ToggleItem(visible[1].id, range = true))
        panel.act(SyncPanelAction.ToggleItem(visible[4].id, range = true))
        assertEquals(visible.subList(1, 5).map { it.id }.toSet(), panel.state.value.selected)
        measure(scenario, "panel_select_all", git, file, memory) { panel.act(SyncPanelAction.SelectAll) }
        assertEquals(scenario.pending, panel.state.value.selected.size)
        panel.act(SyncPanelAction.InvertSelection)
        assertTrue(panel.state.value.selected.isEmpty())
        panel.act(SyncPanelAction.SelectAll)
        measure(scenario, "panel_freeze_selection", git, file, memory) {
            panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.KEEP_LOCAL, SyncDecisionScope.SELECTED))
        }
        val confirmation = requireNotNull(panel.state.value.confirmation)
        assertEquals(scenario.pending.toLong(), confirmation.total)
        assertEquals(scenario.pending.toLong(), runtime.projector.bulkProgress(confirmation.jobId).queued)

        // One frozen version becomes obsolete and one previously unselected object arrives.
        history.append(1, scenario.devices, "invalidate", SyncEffectKind.ADD) { 0 }
        history.append(1, scenario.devices, "arrive", SyncEffectKind.REMOVE) { scenario.pending }
        assertExchange(storage.exchange(transport, secret, repository), downloaded = 2, pending = scenario.pending)
        assertEquals(scenario.pending.toLong(), runtime.projector.bulkProgress(confirmation.jobId).total)
        timing?.reset()
        file.commitTiming?.reset()
        val bulkStarted = System.nanoTime()
        val firstChunk = measure(scenario, "bulk_first_chunk", git, file, memory) {
            runtime.projector.processBulk(confirmation.jobId)
        }
        assertEquals(scenario.pending - 50L, firstChunk.queued)
        assertEquals(50L, firstChunk.outcomes.filterKeys { it != "QUEUED" }.values.sum())
        measure(scenario, "bulk_resume_and_close", git, file, memory) {
            panel.act(SyncPanelAction.ConfirmDecision)
            panel.act(SyncPanelAction.Close)
            // This measures durable per-item transactions, not a three-minute product SLA.
            // Keep the whole test's twenty-minute safety limit unchanged.
            withTimeout(600_000) { panel.awaitBulkIdle() }
            panel.act(SyncPanelAction.Open)
        }
        if (timing != null) {
            val outerTransactions = timing.outerTransactions
            println(
                "SYNC_BULK_DIAGNOSTIC events=${scenario.events} pending=${scenario.pending} " +
                    "elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bulkStarted)} " +
                    "outerTransactions=$outerTransactions sqlCalls=${timing.calls} " +
                    "sqlMs=${TimeUnit.NANOSECONDS.toMillis(timing.nanos)} " +
                    "commitCount=${file.commitTiming?.count} " +
                    "commitMs=${TimeUnit.NANOSECONDS.toMillis(file.commitTiming?.nanos ?: 0)}",
            )
            assertTrue(outerTransactions <= 30, "501 decisions must use bounded page commits: $outerTransactions")
        }
        val completed = runtime.projector.bulkProgress(confirmation.jobId)
        assertEquals(0L, completed.queued)
        assertEquals(scenario.pending - 1L, completed.outcomes["KEPT_LOCAL"])
        assertEquals(1L, completed.outcomes["INVALIDATED"])
        assertNull(completed.outcomes["FAILED"])
        assertEquals(completed, runtime.projector.processBulk(confirmation.jobId))
        assertEquals(1L, panel.state.value.pendingTotal)
        assertEquals(scenario.pending - 1L, panel.state.value.bulk?.completed)
        assertEquals(1L, panel.state.value.bulk?.skipped)
        assertEquals(0L, panel.state.value.bulk?.failed)
        assertNull(panel.state.value.notice)
        assertEquals(scenario.pending + 2L, storage.manga.countLibraryMangaForCreatorIndex())

        panel.act(
            SyncPanelAction.PrepareDecision(
                SyncCancellationDecision.CONFIRM,
                SyncDecisionScope.ITEM,
                panel.state.value.pending.single().id,
            ),
        )
        panel.act(SyncPanelAction.ConfirmDecision)
        withTimeout(30_000) { panel.awaitBulkIdle() }
        panel.awaitIdle()
        assertEquals(0L, panel.state.value.pendingTotal)
        assertEquals(1L, panel.state.value.notice?.bulk?.completed)
        assertEquals(scenario.pending + 1L, storage.manga.countLibraryMangaForCreatorIndex())
        panel.act(SyncPanelAction.Close)
        panel.act(SyncPanelAction.Open)
        assertNull(panel.state.value.notice)
    }

    private fun assertExchange(result: SyncRunResult, downloaded: Int = 0, uploaded: Int = 0, pending: Int = 0) {
        assertEquals(
            if (pending >
                0
            ) {
                SyncRunStatus.PARTIAL
            } else {
                SyncRunStatus.SUCCESS
            },
            result.status,
            result.toString(),
        )
        assertEquals(downloaded, result.downloaded)
        assertEquals(uploaded, result.uploaded)
        assertEquals(pending, result.pending)
    }

    private suspend fun SyncRuntimeStorageContract.Storage.eventCount() = handler.await {
        sync_journalQueries.countEvents().executeAsOne()
    }

    private suspend fun <T> measure(
        scenario: Scenario,
        phase: String,
        git: SyncGitSafetyContractTest.GitFixture,
        file: FileStorage,
        memory: HeapSampler,
        action: suspend () -> T,
    ): T {
        val requests = git.server.requestCount
        val blobReads = git.blobReads
        val blobReadPosition = git.blobReadOids.size
        val existingBlobOids = git.blobOids()
        val treeReads = git.treeRequests
        val publications = git.forceFlags.size
        val started = System.nanoTime()
        try {
            return action()
        } finally {
            metrics(
                scenario,
                phase,
                git.server.requestCount - requests,
                file.bytes(),
                memory.peak.get(),
                "elapsedMillis" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                "blobReads" to (git.blobReads - blobReads).toLong(),
                "preexistingBlobReads" to git.blobReadOids.drop(blobReadPosition)
                    .count(existingBlobOids::contains).toLong(),
                "treeReads" to (git.treeRequests - treeReads).toLong(),
                "refUpdates" to (git.forceFlags.size - publications).toLong(),
            )
        }
    }

    private fun metrics(
        scenario: Scenario,
        phase: String,
        requests: Int,
        databaseBytes: Long,
        peakHeapBytes: Long,
        vararg values: Pair<String, Long>,
    ) {
        println(
            "SYNC_SCALE " + buildJsonObject {
                put("initialEvents", scenario.events)
                put("historyActors", scenario.devices)
                put("pendingDecisions", scenario.pending)
                put("phase", phase)
                put("httpRequests", requests)
                put("databaseBytes", databaseBytes)
                put("sampledJvmHeapBytesIncludingFixture", peakHeapBytes)
                values.forEach { (name, value) -> put(name, value) }
            },
        )
    }

    private suspend fun SyncPanelController.act(action: SyncPanelAction) = withTimeout(30_000) {
        dispatch(action)
        awaitIdle()
        assertNull(state.value.problem)
    }

    private suspend fun withPanel(
        storage: SyncRuntimeStorageContract.Storage,
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
            storage.handler,
            storage.bootstrap,
            storage.creators,
            storage.creators,
            { true },
            secure,
            preferences,
            client,
        )
        val panel = SyncPanelController(runtime, storage.handler, scope)
        try {
            block(panel, runtime)
        } finally {
            panel.stop()
            scope.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    /** Reuses the existing Git reachability fixture; all wire bytes use production codecs. */
    private inner class RemoteHistory(
        private val git: SyncGitSafetyContractTest.GitFixture,
        private val initial: SyncSnapshot,
        private val transport: GitHubSyncTransport,
        private val repository: SyncRepository = this@SyncScaleAcceptanceTest.repository,
        private val material: SyncSpaceMaterial? = null,
    ) {
        val entries = mutableListOf<SyncBatchIndexEntry>()
        private val engine = SyncAeadEngineFactory.create()
        private val sequences = mutableMapOf<Int, Long>()
        private val heads = mutableMapOf<Int, SyncEffectRef>()
        private val immutableDigests = linkedMapOf<String, String>()
        private val sizes = mutableMapOf<String, Long>()

        fun append(
            count: Int,
            actors: Int,
            phase: String,
            kind: SyncEffectKind,
            missingParent: Boolean = false,
            objectAt: (Int) -> Int,
        ) {
            val buffers = List(actors) { mutableListOf<SyncEventEnvelope>() }
            val batches = IntArray(actors)
            fun flush(actor: Int) {
                val events = buffers[actor]
                if (events.isEmpty()) return
                val batchId = requireNotNull(events.first().batchId)
                val objects = events.map { it.effects.single().objectKey }.distinct()
                    .map { SyncObjectDescriptor(it, "Scale ${it.originalUrl}") }
                val batch = SyncBatch(1, "space", 1, batchId, events.toList(), objects)
                val path = ".mihon-sync/batches/device-$actor/1/$batchId.json"
                val encrypted = SyncBatchEncryption.encrypt(engine, secret, batch, path, material)
                val prepared = transport.prepare(initial.copy(batches = entries.toList()), encrypted)
                val files = mapOf(
                    path to StoredSyncBatch.fromDomain(encrypted).body(),
                    prepared.indexPath to prepared.indexCiphertext.bytes,
                    prepared.headPath to prepared.headCiphertext.bytes,
                )
                files.forEach { (name, bytes) ->
                    git.replaceFile(repository.branch, name, bytes)
                    sizes[name] = bytes.size.toLong()
                    if (name != prepared.headPath) immutableDigests[name] = digest(bytes)
                }
                entries += SyncBatchIndexEntry(
                    batchId, path, encrypted.plaintextDigest.toByteString().hex(),
                    encrypted.firstSeq, encrypted.lastSeq,
                    encrypted.actorId, encrypted.epoch, prepared.indexPath, digest(prepared.indexCiphertext.bytes),
                )
                events.clear()
                batches[actor]++
            }
            repeat(count) { index ->
                val actor = index % actors
                val number = objectAt(index)
                val seq = (sequences[actor] ?: 0) + 1
                sequences[actor] = seq
                val event = SyncEventEnvelope(
                    1, "space", 1, "device-$actor", 1, seq, SyncCategory.FAVORITE,
                    listOf(
                        SyncEffect(
                            "membership",
                            SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/scale-$number"),
                            SyncField.FAVORITE,
                            kind,
                            parents = if (missingParent) {
                                listOf(SyncEffectRef(SyncEventId("unseen-parent", 1, 1), "membership", "space", 1))
                            } else {
                                listOfNotNull(heads[number])
                            },
                        ),
                    ),
                    SyncOrigin.USER, batchId = "$phase-$actor-${batches[actor]}",
                )
                heads[number] = event.ref("membership")
                buffers[actor] += event
                if (buffers[actor].size == SyncProtocol.MAX_EVENTS_PER_BATCH) flush(actor)
            }
            buffers.indices.forEach(::flush)
        }

        fun eventCount(): Long = entries.sumOf { it.lastSeq - it.firstSeq + 1 }

        fun bootstrapBytes(): Long = initial.tree.entries.filter { it.type == "blob" }.sumOf { requireNotNull(it.size) }

        // Reachable encrypted payload, immutable index and current heads; not Git packfile size.
        fun bytes(): Long = sizes.values.sum()

        suspend fun assertPreserved(storage: SyncRuntimeStorageContract.Storage) {
            immutableDigests.forEach { (path, expected) ->
                assertEquals(expected, digest(requireNotNull(git.file(repository.branch, path))), path)
            }
            storage.handler.await {
                entries.forEach { entry ->
                    val received = sync_inboxQueries.getReceivedBatch("space", 1, entry.batchId).executeAsOneOrNull()
                    assertNotNull(received, entry.batchId)
                    assertEquals(entry.digestHex, digest(requireNotNull(received).encodeToByteArray()), entry.batchId)
                }
            }
        }

        private fun digest(bytes: ByteArray) = engine.sha256(bytes).toByteString().hex()
    }

    private class SqlTiming {
        private val callCount = AtomicLong()
        private val callNanos = AtomicLong()
        private val dirtyCount = AtomicLong()
        private val dirtyNanosCount = AtomicLong()
        private val transactionCount = AtomicLong()
        private val outerTransactionCount = AtomicLong()
        val calls get() = callCount.get()
        val nanos get() = callNanos.get()
        val dirtyCalls get() = dirtyCount.get()
        val dirtyNanos get() = dirtyNanosCount.get()
        val transactions get() = transactionCount.get()
        val outerTransactions get() = outerTransactionCount.get()

        fun reset() {
            callCount.set(0)
            callNanos.set(0)
            dirtyCount.set(0)
            dirtyNanosCount.set(0)
            transactionCount.set(0)
            outerTransactionCount.set(0)
        }

        fun wrap(delegate: SqlDriver): SqlDriver = Proxy.newProxyInstance(
            SqlDriver::class.java.classLoader,
            arrayOf(SqlDriver::class.java),
        ) { _, method, arguments ->
            val measured = method.name == "execute" || method.name == "executeQuery"
            val dirty = (arguments?.getOrNull(1) as? String)?.contains("INDEXED BY sync_dirty_fields") == true
            val outerTransaction = method.name == "newTransaction" && delegate.currentTransaction() == null
            val started = System.nanoTime()
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            } finally {
                if (measured) {
                    val elapsed = System.nanoTime() - started
                    callCount.incrementAndGet()
                    callNanos.addAndGet(elapsed)
                    if (dirty) {
                        dirtyCount.incrementAndGet()
                        dirtyNanosCount.addAndGet(elapsed)
                    }
                }
                if (method.name == "newTransaction") {
                    transactionCount.incrementAndGet()
                    if (outerTransaction) outerTransactionCount.incrementAndGet()
                }
            }
        } as SqlDriver
    }

    private class FileStorage(sqlTiming: SqlTiming? = null) : AutoCloseable {
        private val path = Files.createTempFile("mihon-sync-scale-", ".db").toFile()

        // JDBC opens connections per operation/transaction; a one-off PRAGMA would not persist.
        private val rawDriver = JdbcSqliteDriver(
            "jdbc:sqlite:${path.absolutePath}",
            Properties().apply { setProperty("foreign_keys", "true") },
        )
        val commitTiming = sqlTiming?.let { CommitTimingDriver(rawDriver) }
        private val driver = sqlTiming?.wrap(requireNotNull(commitTiming)) ?: rawDriver
        val storage: SyncRuntimeStorageContract.Storage

        init {
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            storage = SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
        }

        fun bytes() = listOf(path, File("${path.absolutePath}-wal"), File("${path.absolutePath}-journal"))
            .sumOf { if (it.exists()) it.length() else 0L }

        fun seedPublishedHistory(count: Int) {
            DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}").use { connection ->
                connection.autoCommit = false
                connection.prepareStatement(
                    "INSERT INTO sync_batches(space_id, generation, batch_id, actor_id, epoch, first_seq, " +
                        "last_seq, event_count, plaintext_bytes, status) VALUES " +
                        "('space', 1, ?, 'sender', 1, ?, ?, 1, 1, 'PUBLISHED')",
                ).use { statement ->
                    repeat(count) { index ->
                        statement.setString(1, "old-history-$index")
                        statement.setLong(2, index.toLong() + 1)
                        statement.setLong(3, index.toLong() + 1)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            }
        }

        fun explainPendingUploadRound(): String = DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}")
            .use { connection ->
                connection.prepareStatement(
                    "EXPLAIN QUERY PLAN SELECT batch_id, event_count FROM sync_batches " +
                        "WHERE space_id = 'space' AND generation = 1 " +
                        "AND status != 'PUBLISHED' AND event_count > 0 " +
                        "ORDER BY actor_id, epoch, first_seq",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getString("detail")) }.joinToString("\n")
                    }
                }
            }

        fun explainPendingRuntimeReceipt(runId: String): String = DriverManager.getConnection(
            "jdbc:sqlite:${path.absolutePath}",
        )
            .use { connection ->
                connection.prepareStatement(
                    "EXPLAIN QUERY PLAN SELECT 1 FROM sync_runtime_confirmations " +
                        "WHERE run_id = ? AND direction = 'DOWNLOAD' AND status = 'PENDING' LIMIT 1",
                ).use { statement ->
                    statement.setString(1, runId)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getString("detail")) }.joinToString("\n")
                    }
                }
            }

        override fun close() {
            storage.close()
            listOf(
                path,
                File("${path.absolutePath}-wal"),
                File("${path.absolutePath}-shm"),
                File("${path.absolutePath}-journal"),
            ).forEach { file ->
                try {
                    Files.deleteIfExists(file.toPath())
                } catch (_: FileSystemException) {
                    // Preserve test assertions if Windows has not released the file handle at cleanup.
                    file.deleteOnExit()
                }
            }
        }
    }

    private class CommitTimingDriver(
        private val delegate: JdbcSqliteDriver,
    ) : JdbcDriver(), ConnectionManager by delegate {
        private val endCount = AtomicLong()
        private val endNanos = AtomicLong()
        val count get() = endCount.get()
        val nanos get() = endNanos.get()

        fun reset() {
            endCount.set(0)
            endNanos.set(0)
        }

        override fun Connection.endTransaction() {
            val start = System.nanoTime()
            try {
                with(delegate) { this@endTransaction.endTransaction() }
            } finally {
                endCount.incrementAndGet()
                endNanos.addAndGet(System.nanoTime() - start)
            }
        }

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
            delegate.addListener(*queryKeys, listener = listener)

        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
            delegate.removeListener(*queryKeys, listener = listener)

        override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)

        override fun close() = delegate.close()
    }

    private class HeapSampler : AutoCloseable {
        val baseline = usedHeap()
        val peak = AtomicLong(baseline)
        private val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "sync-scale-heap-sampler").apply { isDaemon = true }
        }

        init {
            executor.scheduleAtFixedRate({ peak.accumulateAndGet(usedHeap(), ::maxOf) }, 0, 25, TimeUnit.MILLISECONDS)
        }

        private fun usedHeap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        override fun close() {
            executor.shutdownNow()
        }
    }

    private data class Scenario(val events: Int, val devices: Int, val pending: Int)
}
