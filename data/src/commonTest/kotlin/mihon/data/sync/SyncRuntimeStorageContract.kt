package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.data.sync.runtime.SyncDatabaseExchange
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
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import mockwebserver3.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
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

                assertEquals(SyncRunStatus.SUCCESS, result.status)
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

                assertEquals(SyncRunStatus.SUCCESS, resumed.status)
                assertEquals(1, resumed.downloaded)
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
                    assertEquals(SyncRunStatus.SUCCESS, result.status, "problem=${result.problem}")
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
                    assertEquals(SyncRunStatus.SUCCESS, result.status, "problem=${result.problem}")
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
