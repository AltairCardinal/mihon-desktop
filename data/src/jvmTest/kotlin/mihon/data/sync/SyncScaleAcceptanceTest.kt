package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.data.sync.transport.StoredSyncBatch
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncProtocol
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
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
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sequential, file-backed scale acceptance through production HTTP, AEAD, SQL and panel wiring.
 * Device counts describe independent event actors, not simultaneous OS application instances.
 * Fixture history construction is reported separately from measured production exchanges.
 * Run this module's JVM suite without other large module test suites on constrained hosts:
 * the twenty-minute bound covers both scenarios and shared resource contention can exhaust it.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class SyncScaleAcceptanceTest {
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    private val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `complete histories and frozen decisions remain correct at ten and hundred thousand events`() = runBlocking {
        for (scenario in listOf(Scenario(10_000, 3, 120), Scenario(100_000, 10, 10_000))) {
            runScenario(scenario)
        }
    }

    private suspend fun runScenario(scenario: Scenario) {
        val client = OkHttpClient()
        val memory = HeapSampler()
        val primary = FileStorage()
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

                exercisePanel(scenario, storage, primary, history, transport, git, memory)
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
        assertEquals(SyncRunStatus.SUCCESS, result.status, result.toString())
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
    ) {
        val entries = mutableListOf<SyncBatchIndexEntry>()
        private val engine = SyncAeadEngineFactory.create()
        private val sequences = mutableMapOf<Int, Long>()
        private val heads = mutableMapOf<Int, SyncEffectRef>()
        private val immutableDigests = linkedMapOf<String, String>()
        private val sizes = mutableMapOf<String, Long>()

        fun append(count: Int, actors: Int, phase: String, kind: SyncEffectKind, objectAt: (Int) -> Int) {
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
                val encrypted = SyncBatchEncryption.encrypt(engine, secret, batch, path)
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
                            parents = listOfNotNull(heads[number]),
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

    private class FileStorage : AutoCloseable {
        private val path = Files.createTempFile("mihon-sync-scale-", ".db").toFile()

        // JDBC opens connections per operation/transaction; a one-off PRAGMA would not persist.
        private val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${path.absolutePath}",
            Properties().apply { setProperty("foreign_keys", "true") },
        )
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

        override fun close() {
            storage.close()
            listOf(
                path,
                File("${path.absolutePath}-wal"),
                File("${path.absolutePath}-shm"),
                File("${path.absolutePath}-journal"),
            ).forEach { Files.deleteIfExists(it.toPath()) }
        }
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
