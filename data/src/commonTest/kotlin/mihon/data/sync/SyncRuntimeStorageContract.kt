package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncDiscoveryStore
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.data.sync.runtime.SyncDatabaseExchange
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressReporter
import mihon.data.sync.runtime.SyncRunPlanBatch
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRunStore
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitTree
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import mockwebserver3.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

abstract class SyncRuntimeStorageContract {
    protected abstract fun open(): Storage
    protected val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    protected val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `unconfirmed frozen members prevent success and retain partial recovery`() = runBlocking {
        open().use { storage ->
            storage.connect("plan-fixture", repository)
            val store = SyncRunStore(storage.handler)
            val run = store.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(store.claim(run.runId, "owner", 1))
            storage.handler.await {
                sync_runtimeQueries.insertRuntimeConfirmation(run.runId, "PLAN", "round", 0, "PLANNED")
                sync_runtimeQueries.insertRuntimeConfirmation(run.runId, "UPLOAD", "upload", 3, "PLANNED")
            }
            store.finish(run.runId, SyncRunState.SUCCEEDED, ownerSession = "owner")
            assertEquals(SyncRunState.PARTIAL, store.get(run.runId)!!.state)
            assertEquals(run.runId, store.active("space", 1)!!.runId)
            assertTrue(store.claim(run.runId, "replacement", 2))
            assertEquals(3L, store.get(run.runId)!!.plannedItems)
            assertEquals(0L, store.get(run.runId)!!.confirmedItems)
        }
    }

    @Test
    fun `frozen run plan is durable immutable and distinct from pending receipts`() = runBlocking {
        open().use { storage ->
            storage.connect("plan-fixture", repository)
            val store = SyncRunStore(storage.handler)
            val run = store.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(store.claim(run.runId, "owner", 1))
            assertNull(store.get(run.runId)!!.plannedItems)
            val upload = SyncRunPlanBatch(
                SyncProgressDirection.UPLOAD,
                "upload",
                3,
            )
            val download = SyncRunPlanBatch(
                SyncProgressDirection.DOWNLOAD,
                "download",
                2,
            )
            assertEquals(5L, store.freezePlan(run.runId, "owner", listOf(upload, download)).totalItems)
            assertEquals(0L, store.get(run.runId)!!.confirmedItems)
            assertEquals(5L, store.get(run.runId)!!.plannedItems)
            assertTrue(!store.hasUnconfirmedDownloads(run.runId, "owner"))
            store.expectDownload(run.runId, "owner", "download", 2)
            assertTrue(store.hasUnconfirmedDownloads(run.runId, "owner"))
            assertTrue(store.confirmReceived(run.runId, "owner").isEmpty())
            val confirmed = store.confirmed(run.runId, "owner", upload.direction, upload.batchId, upload.itemCount)
            assertEquals(3L, confirmed)
            val reopened = SyncRunStore(storage.handler)
            assertEquals(5L, reopened.plan(run.runId)!!.totalItems)
            assertEquals(3L, reopened.get(run.runId)!!.confirmedItems)
            assertEquals(5L, reopened.freezePlan(run.runId, "owner", emptyList()).totalItems)
            val empty = reopened.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(reopened.claim(empty.runId, "next-owner", 1))
            assertEquals(0L, reopened.freezePlan(empty.runId, "next-owner", emptyList()).totalItems)
            assertEquals(0L, reopened.get(empty.runId)!!.plannedItems)
        }
    }

    @Test
    fun `frozen uploads execute actor sequences rather than reverse lexical batch ids`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { storage ->
                storage.connect("ordered", repository)
                storage.seedUploadBatch("z-first", 1, 256)
                storage.seedUploadBatch("a-second", 257, 44)
                val runs = SyncRunStore(storage.handler)
                val run = runs.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runs.claim(run.runId, "owner", 1))
                val result = SyncDatabaseExchange(
                    storage.handler,
                    storage.baseline,
                    storage.projector,
                    transport,
                    secret,
                    progress = runs.reporter(run.runId, "owner"),
                ).exchange("space", 1, repository)
                assertEquals(SyncRunStatus.SUCCESS, result.status, result.toString())
                assertEquals(300, result.uploaded)
                assertEquals(300L, runs.get(run.runId)!!.plannedItems)
                assertEquals(300L, runs.get(run.runId)!!.confirmedItems)
                assertEquals(
                    300L,
                    storage.handler.await {
                        sync_journalQueries.countPublishedEvents("space", 1).executeAsOne()
                    },
                )
            }
        }
    }

    @Test
    fun `reopened frozen uploads preserve sequence after published confirmation interruption`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { storage ->
                storage.connect("ordered", repository)
                storage.seedUploadBatch("z-first", 1, 256)
                storage.seedUploadBatch("m-second", 257, 256)
                storage.seedUploadBatch("a-third", 513, 44)
                val runs = SyncRunStore(storage.handler)
                val run = runs.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runs.claim(run.runId, "owner", 1))
                val reporter = runs.reporter(run.runId, "owner")
                val interrupted = object : SyncProgressReporter by reporter {
                    override suspend fun confirmed(direction: SyncProgressDirection, batchId: String, itemCount: Long) {
                        assertEquals("z-first", batchId)
                        throw CancellationException("stop after first ordered publication before confirmation")
                    }
                }
                try {
                    SyncDatabaseExchange(
                        storage.handler,
                        storage.baseline,
                        storage.projector,
                        transport,
                        secret,
                        progress = interrupted,
                    ).exchange("space", 1, repository)
                    org.junit.jupiter.api.Assertions.fail<Unit>("first sequence must publish before interruption")
                } catch (_: CancellationException) {
                    // Published outbox is durable; its planned safety confirmation remains to reconcile.
                }
                assertEquals(556L, runs.get(run.runId)!!.plannedItems)
                assertEquals(0L, runs.get(run.runId)!!.confirmedItems)
                assertTrue(runs.releaseForRecovery(run.runId))
                val reopened = SyncRunStore(storage.handler)
                assertTrue(reopened.claim(run.runId, "replacement", 2))
                storage.favorite("/deferred-after-interruption")
                val result = SyncDatabaseExchange(
                    storage.handler,
                    storage.baseline,
                    storage.projector,
                    transport,
                    secret,
                    progress = reopened.reporter(run.runId, "replacement"),
                ).exchange("space", 1, repository)
                assertEquals(SyncRunStatus.SUCCESS, result.status, result.toString())
                assertEquals(300, result.uploaded)
                assertEquals(556L, reopened.get(run.runId)!!.plannedItems)
                assertEquals(556L, reopened.get(run.runId)!!.confirmedItems)
                assertEquals(1, SyncLocalJournal(storage.handler).pendingEvents("space", 1).size)
            }
        }
    }

    @Test
    fun `persistent run freezes both directions and defers new work without live progress`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { sender ->
                repeat(3) { sender.favorite("/remote-plan-$it") }
                sender.connect("sender", repository)
                assertEquals(3, sender.exchange(transport, secret, repository).uploaded)
                open().use { receiver ->
                    repeat(2) { receiver.favorite("/local-plan-$it") }
                    receiver.connect("receiver", repository)
                    val runs = SyncRunStore(receiver.handler)
                    val run = runs.start("space", 1, SyncTrigger.MANUAL)
                    assertTrue(runs.claim(run.runId, "owner", 1))
                    val receiptFrames = mutableListOf<Pair<Long?, Long>>()
                    val observing = object : SyncTransportPort by transport {
                        override suspend fun readBatch(
                            snapshot: SyncSnapshot,
                            entry: SyncBatchIndexEntry,
                        ): Result<SyncEncryptedBatch> {
                            val current = runs.get(run.runId)!!
                            receiptFrames += current.plannedItems to current.confirmedItems
                            if (receiptFrames.size == 1) receiver.favorite("/deferred-local")
                            return transport.readBatch(snapshot, entry)
                        }
                    }
                    val result = SyncDatabaseExchange(
                        receiver.handler,
                        receiver.baseline,
                        receiver.projector,
                        observing,
                        secret,
                        progress = runs.reporter(run.runId, "owner"),
                    ).exchange("space", 1, repository)
                    assertEquals(
                        5L to 0L,
                        receiptFrames.first(),
                        "the first receipt must see the complete fixed plan",
                    )
                    assertEquals(SyncRunStatus.SUCCESS, result.status, "problem=${result.problem}")
                    assertEquals(2, result.uploaded)
                    assertEquals(3, result.downloaded)
                    assertEquals(5L, runs.get(run.runId)!!.plannedItems)
                    assertEquals(5L, runs.get(run.runId)!!.confirmedItems)
                    assertEquals(1, SyncLocalJournal(receiver.handler).pendingEvents("space", 1).size)
                    assertEquals(1, receiver.exchange(transport, secret, repository).uploaded)
                }
            }
        }
    }

    @Test
    fun `published plan member recovers safety confirmation once without extending the run`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { storage ->
                storage.connect("recovery", repository)
                storage.favorite("/published-before-stop")
                val runs = SyncRunStore(storage.handler)
                val run = runs.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runs.claim(run.runId, "owner", 1))
                val persistent = runs.reporter(run.runId, "owner")
                val interrupted = object : SyncProgressReporter by persistent {
                    override suspend fun confirmed(
                        direction: SyncProgressDirection,
                        batchId: String,
                        itemCount: Long,
                    ) {
                        throw CancellationException("fixture stops after durable publication")
                    }
                }
                try {
                    SyncDatabaseExchange(
                        storage.handler,
                        storage.baseline,
                        storage.projector,
                        transport,
                        secret,
                        progress = interrupted,
                    ).exchange("space", 1, repository)
                    org.junit.jupiter.api.Assertions.fail<Unit>("fixture must interrupt after publication")
                } catch (_: CancellationException) {
                    // Outbox acknowledgement committed; run confirmation did not.
                }
                assertEquals(1L, runs.get(run.runId)!!.plannedItems)
                assertEquals(0L, runs.get(run.runId)!!.confirmedItems)
                assertTrue(runs.releaseForRecovery(run.runId))
                assertTrue(runs.claim(run.runId, "replacement", 2))
                storage.favorite("/next-round")
                repeat(2) {
                    val result = SyncDatabaseExchange(
                        storage.handler,
                        storage.baseline,
                        storage.projector,
                        transport,
                        secret,
                        progress = runs.reporter(run.runId, "replacement"),
                    ).exchange("space", 1, repository)
                    assertEquals(SyncRunStatus.SUCCESS, result.status, "problem=${result.problem}")
                    assertEquals(0, result.uploaded)
                    assertEquals(1L, runs.get(run.runId)!!.plannedItems)
                    assertEquals(1L, runs.get(run.runId)!!.confirmedItems)
                }
                assertEquals(1, SyncLocalJournal(storage.handler).pendingEvents("space", 1).size)
            }
        }
    }

    @Test
    fun `frozen run defers remote discovery after the final ref changes`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { storage ->
                storage.connect("receiver", repository)
                storage.favorite("/planned-local")
                val runs = SyncRunStore(storage.handler)
                val run = runs.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runs.claim(run.runId, "owner", 1))
                var latePublished = false
                val observing = object : SyncTransportPort by transport {
                    override suspend fun readCurrentHead(
                        repository: SyncRepository,
                        expectedSpaceId: String,
                        expectedGeneration: Long,
                    ): Result<String> {
                        if (!latePublished) {
                            latePublished = true
                            val result = publishRemoteBatch(
                                transport,
                                remoteFavoriteBatch("late-plan-remote", "other-device", "/late-plan"),
                            )
                            assertEquals(SyncPublishStatus.PUBLISHED, result.status)
                        }
                        return transport.readCurrentHead(repository, expectedSpaceId, expectedGeneration)
                    }
                }
                val result = SyncDatabaseExchange(
                    storage.handler,
                    storage.baseline,
                    storage.projector,
                    observing,
                    secret,
                    progress = runs.reporter(run.runId, "owner"),
                ).exchange("space", 1, repository)
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertEquals(1, result.uploaded)
                assertEquals(0, result.downloaded)
                assertEquals(1L, runs.get(run.runId)!!.plannedItems)
                assertEquals(1L, runs.get(run.runId)!!.confirmedItems)
                assertEquals(
                    listOf("late-plan-remote"),
                    SyncDiscoveryStore(storage.handler).allPending("space", 1).map { it.batchId },
                )
                assertEquals(1, storage.exchange(transport, secret, repository).downloaded)
            }
        }
    }

    @Test
    fun `exchange freezes the complete authenticated discovery before the first batch read`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val initial = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val service = SyncBatchSyncService(transport, secret)
            val files = mutableMapOf<String, ByteArray>()
            repeat(130) { index ->
                val actor = "plan-device-$index"
                val batch = remoteFavoriteBatch("plan-batch-$index", actor, "/large-plan-$index")
                val upload = service.prepare(initial, batch, ".mihon-sync/batches/$actor/1/${batch.batchId}.json")
                files[upload.encryptedBatch.path] =
                    mihon.data.sync.transport.StoredSyncBatch.fromDomain(upload.encryptedBatch).body()
                files[upload.indexPath] = upload.indexCiphertext.bytes
                files[upload.headPath] = upload.headCiphertext.bytes
            }
            // One bounded fixture commit holds 130 genuine encrypted batches/index shards/heads.
            git.replaceFiles(repository.branch, files)
            open().use { storage ->
                storage.connect("receiver", repository)
                storage.favorite("/planned-upload")
                val runs = SyncRunStore(storage.handler)
                val run = runs.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runs.claim(run.runId, "owner", 1))
                var firstRead: Pair<Long?, Long>? = null
                val observing = object : SyncTransportPort by transport {
                    override suspend fun readBatch(
                        snapshot: SyncSnapshot,
                        entry: SyncBatchIndexEntry,
                    ): Result<SyncEncryptedBatch> {
                        val current = runs.get(run.runId)!!
                        firstRead = current.plannedItems to current.confirmedItems
                        transport.readBatch(snapshot, entry).getOrThrow()
                        // A real network-class interruption preserves discovery and confirms nothing.
                        throw java.io.IOException("fixture stops after authenticating the first encrypted batch")
                    }
                }
                val result = SyncDatabaseExchange(
                    storage.handler,
                    storage.baseline,
                    storage.projector,
                    observing,
                    secret,
                    progress = runs.reporter(run.runId, "owner"),
                ).exchange("space", 1, repository)
                assertEquals(131L to 0L, firstRead)
                assertEquals(SyncRunStatus.FAILED, result.status)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.NETWORK, result.problem)
                assertEquals(131L, runs.get(run.runId)!!.plannedItems)
                assertEquals(0L, runs.get(run.runId)!!.confirmedItems)
                assertEquals(131, runs.plan(run.runId)!!.batches.size)
                assertEquals(130, SyncDiscoveryStore(storage.handler).allPending("space", 1).size)
                assertEquals(1, SyncLocalJournal(storage.handler).pendingEvents("space", 1).size)
                assertTrue(runs.confirmReceived(run.runId, "owner").isEmpty())
            }
        }
    }

    @Test
    fun `complete discovery plan includes more than one execution page`() = runBlocking {
        open().use { storage ->
            storage.connect("discovery-plan", repository)
            val entries = (0 until 130).map { index ->
                SyncBatchIndexEntry(
                    "remote-$index",
                    ".mihon-sync/batches/a/1/remote-$index.json",
                    "a".repeat(64),
                    index.toLong() + 1,
                    index.toLong() + 1,
                )
            }
            val discovery = SyncDiscoveryStore(storage.handler)
            discovery.observe(
                SyncSnapshot(
                    repository,
                    "a".repeat(40),
                    SyncGitTree("b".repeat(40), emptyList(), false),
                    "space",
                    1,
                    entries,
                ),
            )
            assertEquals(128, discovery.pending("space", 1).size)
            val runs = SyncRunStore(storage.handler)
            val run = runs.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(runs.claim(run.runId, "owner", 1))
            val complete = discovery.allPending("space", 1)
            assertEquals(130, complete.size)
            assertEquals(
                130L,
                runs.freezePlan(
                    run.runId,
                    "owner",
                    complete.map {
                        SyncRunPlanBatch(
                            SyncProgressDirection.DOWNLOAD,
                            it.batchId,
                            it.lastSeq - it.firstSeq + 1,
                        )
                    },
                ).totalItems,
            )
            assertEquals(0L, runs.terminalSummary(run.runId)!!.pendingDownloadEvents)
            assertTrue(!runs.hasUnconfirmedDownloads(run.runId, "owner"))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { runs.expectDownload(run.runId, "owner", "outside-plan", 1) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    runs.confirmed(run.runId, "owner", SyncProgressDirection.UPLOAD, "outside", 1)
                }
            }
            assertEquals(0L, runs.get(run.runId)!!.confirmedItems)
            assertEquals(130L, runs.get(run.runId)!!.plannedItems)
        }
    }

    @Test
    fun `frozen run reconstructs legacy counts and rejects stale ownership atomically`() = runBlocking {
        open().use { storage ->
            storage.connect("legacy-plan", repository)
            val runs = SyncRunStore(storage.handler)
            val run = runs.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(runs.claim(run.runId, "owner", 1))
            runs.confirmed(run.runId, "owner", SyncProgressDirection.UPLOAD, "old-upload", 3)
            runs.expectDownload(run.runId, "owner", "old-download", 2)
            assertNull(runs.get(run.runId)!!.plannedItems)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { runs.freezePlan(run.runId, "wrong-owner", emptyList()) }
            }
            assertNull(runs.plan(run.runId))
            val plan = runs.freezePlan(run.runId, "owner", emptyList())
            assertEquals(5L, plan.totalItems)
            assertEquals(3L, runs.get(run.runId)!!.confirmedItems)
            assertTrue(plan.batches.single { it.batchId == "old-upload" }.confirmed)
            assertEquals(2L, runs.terminalSummary(run.runId)!!.pendingDownloadEvents)
            assertTrue(
                !runs.hasPlannedWork(run.runId),
                "pending receipts alone must not create automatic recovery work",
            )
            val next = runs.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(runs.claim(next.runId, "next-owner", 1))
            val valid = SyncRunPlanBatch(
                SyncProgressDirection.UPLOAD,
                "valid-member",
                1,
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    val invalid = valid.copy(batchId = "bad", itemCount = 0)
                    runs.freezePlan(next.runId, "next-owner", listOf(valid, invalid))
                }
            }
            assertNull(runs.plan(next.runId))
            assertNull(
                storage.handler.await {
                    sync_runtimeQueries.getRuntimeConfirmation(next.runId, "UPLOAD", "valid-member")
                        .executeAsOneOrNull()
                },
            )
            SyncLocalJournal(storage.handler).disconnect("space", 1)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { runs.freezePlan(next.runId, "next-owner", listOf(valid)) }
            }
            assertNull(runs.plan(next.runId))
        }
    }

    @Test
    fun `frozen run with zero items succeeds after preflight with an explicit zero plan`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { storage ->
                storage.connect("empty-plan", repository)
                val runs = SyncRunStore(storage.handler)
                val run = runs.start("space", 1, SyncTrigger.MANUAL)
                assertTrue(runs.claim(run.runId, "owner", 1))
                val result = SyncDatabaseExchange(
                    storage.handler,
                    storage.baseline,
                    storage.projector,
                    transport,
                    secret,
                    progress = runs.reporter(run.runId, "owner"),
                ).exchange("space", 1, repository)
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertEquals(0L, runs.get(run.runId)!!.plannedItems)
                assertEquals(0L, runs.get(run.runId)!!.confirmedItems)
                assertTrue(!runs.hasUnfinishedPlan(run.runId, "owner"))
            }
        }
    }

    @Test
    fun `one exchange drains frozen baseline and multiple batches into another real database`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { first ->
                repeat(257) { first.favorite("/baseline-$it") }
                first.connect("a", repository)
                val snapshotReadsBeforeExchange = git.snapshotReads
                val result = first.exchange(transport, secret, repository)
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertEquals(257, result.uploaded)
                assertEquals(4, git.snapshotReads - snapshotReadsBeforeExchange)
                assertTrue(SyncLocalJournal(first.handler).pendingEvents("space", 1).isEmpty())
                open().use { second ->
                    second.connect("b", repository)
                    assertEquals(257, second.exchange(transport, secret, repository).downloaded)
                    assertEquals(257, second.manga.getLibraryManga().size)
                    var terminalRefProbes = 0
                    val noTerminalProbeTransport = object : SyncTransportPort by transport {
                        override suspend fun readCurrentHead(
                            repository: SyncRepository,
                            expectedSpaceId: String,
                            expectedGeneration: Long,
                        ): Result<String> {
                            terminalRefProbes++
                            return transport.readCurrentHead(repository, expectedSpaceId, expectedGeneration)
                        }
                    }
                    val again = second.exchange(noTerminalProbeTransport, secret, repository)
                    assertEquals(SyncRunStatus.SUCCESS, again.status)
                    assertEquals(0, again.downloaded)
                    assertEquals(0, again.uploaded)
                    assertEquals(0, terminalRefProbes, "a no-op run must not add a terminal ref probe")
                }
            }
        }
    }

    @Test
    fun `exchange conservatively rereads when publication has no confirmed snapshot`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val fallbackTransport = object : SyncTransportPort by transport {
                override suspend fun publish(
                    repository: SyncRepository,
                    snapshot: SyncSnapshot,
                    upload: SyncPreparedUpload,
                    observeSnapshot: suspend (SyncSnapshot) -> Unit,
                ): SyncPublishResult = transport.publish(repository, snapshot, upload, observeSnapshot)
                    .copy(confirmedSnapshot = null)
            }
            open().use { first ->
                repeat(257) { first.favorite("/fallback-$it") }
                first.connect("a", repository)
                val snapshotReadsBeforeExchange = git.snapshotReads
                val result = first.exchange(fallbackTransport, secret, repository)
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertEquals(6, git.snapshotReads - snapshotReadsBeforeExchange)
            }
        }
    }

    @Test
    fun `final ref read receives a remote batch published after the exchange snapshot`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { receiver ->
                receiver.connect("receiver", repository)
                receiver.favorite("/local-trigger")
                val remoteBatchId = "late-remote-batch"
                val remoteBatch = remoteFavoriteBatch(remoteBatchId, "late-device", "/late")
                var observedReads = 0
                val delayedRemoteTransport = object : SyncTransportPort by transport {
                    override suspend fun readCurrentHead(
                        repository: SyncRepository,
                        expectedSpaceId: String,
                        expectedGeneration: Long,
                    ): Result<String> {
                        observedReads++
                        if (observedReads == 1) {
                            val published = publishRemoteBatch(transport, remoteBatch)
                            assertEquals(SyncPublishStatus.PUBLISHED, published.status)
                        }
                        return transport.readCurrentHead(repository, expectedSpaceId, expectedGeneration)
                    }
                }

                val result = receiver.exchange(delayedRemoteTransport, secret, repository)

                val remoteKey = remoteBatch.events.single().effects.single().objectKey
                val fieldStatus = receiver.handler.await {
                    sync_inboxQueries.getFieldState("space", 1, remoteKey.stableKey, SyncField.FAVORITE.name)
                        .executeAsOneOrNull()?.status
                }
                val confirmable = SyncInboxStore(receiver.handler).canConfirmReceivedBatch("space", 1, remoteBatchId)
                assertEquals("DESCRIPTION", fieldStatus)
                assertEquals(false, confirmable)
                assertEquals(SyncRunStatus.PARTIAL, result.status)
                assertEquals(1, result.uploaded)
                assertEquals(1, result.downloaded)
                assertEquals(2, observedReads, "one changed terminal ref and one stable terminal ref")
                assertEquals(
                    1L,
                    receiver.handler.await {
                        sync_inboxQueries.countInboxBatches("space", 1).executeAsList()
                            .singleOrNull { it.status == "RECEIVED" }?.count ?: 0L
                    },
                )
            }
        }
    }

    @Test
    fun `fourth continuously changing final ref leaves follow-up discovery durable`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { receiver ->
                receiver.connect("receiver", repository)
                receiver.favorite("/local-trigger")
                var observedReads = 0
                val delayedRemoteTransport = object : SyncTransportPort by transport {
                    override suspend fun readCurrentHead(
                        repository: SyncRepository,
                        expectedSpaceId: String,
                        expectedGeneration: Long,
                    ): Result<String> {
                        observedReads++
                        if (observedReads in 1..4) {
                            val index = observedReads
                            val batch = remoteFavoriteBatch(
                                "late-batch-$index",
                                "late-device-$index",
                                "/late-$index",
                            )
                            assertEquals(SyncPublishStatus.PUBLISHED, publishRemoteBatch(transport, batch).status)
                        }
                        return transport.readCurrentHead(repository, expectedSpaceId, expectedGeneration)
                    }
                }

                val bounded = receiver.exchange(delayedRemoteTransport, secret, repository)

                assertEquals(SyncRunStatus.PARTIAL, bounded.status)
                assertEquals(1, bounded.uploaded)
                assertEquals(3, bounded.downloaded)
                val queuedBodies = receiver.handler.await {
                    sync_inboxQueries.getDiscoveredBatches("space", 1, 128).executeAsList()
                }
                assertEquals(1, queuedBodies.size)
                assertTrue(queuedBodies.single().contains("late-batch-4"))

                val resumed = receiver.exchange(delayedRemoteTransport, secret, repository)

                assertEquals(SyncRunStatus.PARTIAL, resumed.status)
                assertEquals(1, resumed.downloaded)
                assertEquals(
                    false,
                    SyncInboxStore(receiver.handler).canConfirmReceivedBatch("space", 1, "late-batch-4"),
                )
                assertTrue(
                    receiver.handler.await {
                        sync_inboxQueries.getDiscoveredBatches("space", 1, 128).executeAsList().isEmpty()
                    },
                )
            }
        }
    }

    protected fun remoteFavoriteBatch(batchId: String, actorId: String, url: String) = SyncBatch(
        protocolVersion = 1,
        spaceId = "space",
        generation = 1,
        batchId = batchId,
        events = listOf(
            SyncEventEnvelope(
                protocolVersion = 1,
                spaceId = "space",
                generation = 1,
                actorId = actorId,
                epoch = 1,
                seq = 1,
                category = SyncCategory.FAVORITE,
                effects = listOf(
                    SyncEffect(
                        effectId = "favorite",
                        objectKey = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = url),
                        field = SyncField.FAVORITE,
                        kind = SyncEffectKind.ADD,
                    ),
                ),
                origin = SyncOrigin.USER,
                batchId = batchId,
            ),
        ),
    )

    protected suspend fun publishRemoteBatch(
        transport: SyncTransportPort,
        batch: SyncBatch,
    ) = SyncBatchSyncService(transport, secret).upload(
        repository,
        transport.readSnapshot(repository, batch.spaceId, batch.generation).getOrThrow(),
        batch,
        ".mihon-sync/batches/${batch.events.single().actorId}/1/${batch.batchId}.json",
        persist = {},
    ).publish

    @Test
    fun `pending removal never blocks uploading an independent local operation`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { first ->
                open().use { second ->
                    first.connect("a", repository)
                    val item = first.favorite("/shared")
                    first.exchange(transport, secret, repository)
                    second.connect("b", repository)
                    second.exchange(transport, secret, repository)
                    first.manga.update(MangaUpdate(item.id, favorite = false, syncContext = SyncMutationContext.User))
                    first.exchange(transport, secret, repository)
                    second.favorite("/second-local")
                    val result = second.exchange(transport, secret, repository)
                    assertEquals(SyncRunStatus.PARTIAL, result.status, "problem=${result.problem}")
                    assertEquals(1, result.pending)
                    assertEquals(1, result.uploaded)
                    assertEquals(2, second.manga.getLibraryManga().size)
                    assertEquals(1, second.projector.pending("space", 1).size)
                }
            }
        }
    }

    @Test
    fun `exchange preserves receiver memo without sharing opaque source values`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { first ->
                open().use { second ->
                    first.connect("a", repository)
                    val item = first.favorite("/memo")
                    val remoteMemo = kotlinx.serialization.json.buildJsonObject {
                        put("opaque", kotlinx.serialization.json.JsonPrimitive("sender-only"))
                    }
                    first.manga.update(MangaUpdate(item.id, memo = remoteMemo))
                    first.exchange(transport, secret, repository)
                    second.connect("b", repository)
                    second.exchange(transport, secret, repository)
                    val received = second.manga.getLibraryManga().single().manga
                    assertTrue(received.memo.isEmpty())
                    val localMemo = kotlinx.serialization.json.buildJsonObject {
                        put("opaque", kotlinx.serialization.json.JsonPrimitive("receiver-only"))
                    }
                    second.manga.update(MangaUpdate(received.id, memo = localMemo))
                    first.manga.update(MangaUpdate(item.id, favorite = false, syncContext = SyncMutationContext.User))
                    first.exchange(transport, secret, repository)
                    val result = second.exchange(transport, secret, repository)
                    assertEquals(SyncRunStatus.PARTIAL, result.status, "problem=${result.problem}")
                    assertEquals(1, result.pending)
                    assertEquals(0, result.uploaded)
                    assertEquals(localMemo, second.manga.getMangaById(received.id).memo)
                    assertTrue(second.manga.getMangaById(received.id).favorite)
                }
            }
        }
    }

    @Test
    fun `network failure retains queue and a later run recovers`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { storage ->
                storage.connect("a", repository)
                storage.favorite("/retry")
                git.nextReadFailure = MockResponse(code = 500, body = "private diagnostic")
                assertEquals(SyncRunStatus.FAILED, storage.exchange(transport, secret, repository).status)
                assertEquals(1, SyncLocalJournal(storage.handler).pendingEvents("space", 1).size)
                assertEquals(1, storage.exchange(transport, secret, repository).uploaded)
            }
        }
    }

    @Test
    fun `disconnected space skips all remote access but still records local changes`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            open().use { storage ->
                storage.connect("a", repository)
                SyncLocalJournal(storage.handler).disconnect("space", 1)
                storage.favorite("/offline")
                val before = git.server.requestCount
                assertEquals(SyncRunStatus.SKIPPED, storage.exchange(git.transport(), secret, repository).status)
                assertEquals(before, git.server.requestCount)
                assertEquals(1, SyncLocalJournal(storage.handler).pendingEvents("space", 1).size)
            }
        }
    }

    protected fun database(driver: SqlDriver, create: Boolean = true): Database {
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val manga = MangaRepositoryImpl(handler, creators)
        val baseline = SyncBaselineStore(handler, bootstrap)
        val projector =
            SyncInboxProjector(handler, SyncRemoteProjectionWriter(handler, creators, creators, bootstrap, { true }))
        suspend fun seedUploadBatch(batchId: String, firstSeq: Long, count: Int) {
            // Seed only an empty batch identity; actual repository mutations create all events and outbox rows.
            handler.await {
                val header = mihon.domain.sync.SyncBatchCodec.rawEncode(
                    SyncBatch(1, "space", 1, batchId, emptyList()),
                ).encodeToByteArray().size.toLong()
                sync_journalQueries.insertBatch("space", 1, batchId, "ordered", 1, firstSeq, firstSeq, header)
            }
            repeat(count) { favorite("/$batchId-$it") }
            handler.await { sync_journalQueries.sealBatch("space", 1, batchId) }
        }
        suspend fun connect(
            actor: String,
            repository: SyncRepository,
        ) = baseline.connectAndImport("space", 1, repository, actor, 1)
        suspend fun favorite(url: String): Manga {
            val item = manga.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = url, title = url)),
            ).single()
            manga.update(MangaUpdate(item.id, favorite = true, syncContext = SyncMutationContext.User))
            return item
        }
        suspend fun exchange(transport: SyncTransportPort, secret: SyncSecret, repository: SyncRepository) =
            SyncDatabaseExchange(handler, baseline, projector, transport, secret).exchange("space", 1, repository)
        override fun close() = driver.close()
    }
}
