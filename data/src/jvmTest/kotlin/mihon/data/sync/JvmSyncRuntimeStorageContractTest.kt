package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncOutboxExchange
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.runtime.syncProblem
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncRemoteDataInvalid
import mihon.data.sync.transport.SyncUploadReconciliationUnavailable
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncPublishFailureClass
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.JvmDatabaseHandler
import java.nio.file.Files
import java.nio.file.Path

class JvmSyncRuntimeStorageContractTest : SyncRuntimeStorageContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, JvmDatabaseHandler(database(driver), driver))
    }

    @Test
    fun `catch-up discovery remains durable when the file database is reopened`() = runBlocking {
        val databasePath = Files.createTempFile("mihon-sync-catch-up-", ".db").toString()
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            var storage = open(databasePath, create = true)
            try {
                storage.connect("receiver", repository)
                storage.favorite("/file-local-trigger")
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
                                "file-late-batch-$index",
                                "file-late-device-$index",
                                "/file-late-$index",
                            )
                            assertEquals(SyncPublishStatus.PUBLISHED, publishRemoteBatch(transport, batch).status)
                        }
                        return transport.readCurrentHead(repository, expectedSpaceId, expectedGeneration)
                    }
                }

                val bounded = storage.exchange(delayedRemoteTransport, secret, repository)
                assertEquals(SyncRunStatus.PARTIAL, bounded.status)
                assertEquals(1, bounded.uploaded)
                assertEquals(3, bounded.downloaded)
                assertTrue(
                    storage.handler.await {
                        sync_inboxQueries.getDiscoveredBatches("space", 1, 128).executeAsList()
                            .single().contains("file-late-batch-4")
                    },
                )

                storage.close()
                storage = open(databasePath, create = false)
                val resumed = storage.exchange(delayedRemoteTransport, secret, repository)
                assertEquals(SyncRunStatus.PARTIAL, resumed.status)
                assertEquals(1, resumed.downloaded)
                assertEquals(
                    false,
                    SyncInboxStore(storage.handler).canConfirmReceivedBatch("space", 1, "file-late-batch-4"),
                )
                assertTrue(
                    storage.handler.await {
                        sync_inboxQueries.getDiscoveredBatches("space", 1, 128).executeAsList().isEmpty()
                    },
                )
            } finally {
                storage.close()
                Files.deleteIfExists(Path.of(databasePath))
            }
        }
    }

    @Test
    fun `restored backup reuses original upload before later actor batches`() = runBlocking {
        replayBackup("later")
    }

    @Test
    fun `restored backup rejects different authenticated content for same id`() = runBlocking {
        replayBackup("conflict")
    }

    @Test
    fun `restored backup requires current ref confirmation`() = runBlocking {
        replayBackup("historical")
    }

    @Test
    fun `readonly restored backup reuses original bytes without Git writes`() = runBlocking {
        replayBackup("later", allowPublish = false)
    }

    @Test
    fun `readonly missing batch never writes Git with or without prepared artifact`() = runBlocking {
        for (saved in listOf(false, true)) {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                open().use { storage ->
                    storage.connect("readonly-actor", repository)
                    storage.favorite("/readonly-unpublished")
                    val store = SyncOutboxStore(storage.handler)
                    store.freezeRound("space", 1)
                    val batch = store.nextBatch("space", 1)!!
                    val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                    val service = SyncBatchSyncService(transport, secret)
                    if (saved) {
                        val path = ".mihon-sync/batches/readonly-actor/1/${batch.batchId}.json"
                        store.savePrepared(batch, service.prepare(snapshot, batch, path))
                    }
                    val writes = git.pathWrites.mapValues { it.value.toList() }
                    val refUpdates = git.forceFlags.size
                    assertThrows(SyncUploadReconciliationUnavailable::class.java) {
                        runBlocking {
                            SyncOutboxExchange(store, service).uploadNext(snapshot, allowPublish = false)
                        }
                    }
                    assertEquals(writes, git.pathWrites.mapValues { it.value.toList() })
                    assertEquals(refUpdates, git.forceFlags.size)
                    assertTrue(store.nextBatch("space", 1) != null)
                }
            }
        }
    }

    @Test
    fun `readonly confirmation preserves blob rate limit after successful current ref read`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            open().use { storage ->
                storage.connect("confirmation-actor", repository)
                storage.favorite("/confirmation-network")
                val store = SyncOutboxStore(storage.handler)
                store.freezeRound("space", 1)
                val service = SyncBatchSyncService(transport, secret)
                val uploaded = SyncOutboxExchange(store, service)
                    .uploadNext(transport.readSnapshot(repository, "space", 1).getOrThrow())!!
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val blob = snapshot.tree.entries.single { it.path == uploaded.artifact.encryptedBatch.path }.sha
                val previous = git.server.dispatcher
                val reads = java.util.concurrent.atomic.AtomicInteger()
                git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath.endsWith("/git/blobs/$blob") && reads.incrementAndGet() >= 2) {
                            return MockResponse(code = 429, headers = headersOf("Retry-After", "120"))
                        }
                        return previous.dispatch(request)
                    }
                }
                val writes = git.pathWrites.mapValues { it.value.toList() }
                val result = service.confirmPrepared(repository, snapshot, uploaded.artifact)
                assertEquals(SyncPublishFailureClass.RATE_LIMITED, result.publish.failureClass)
                assertEquals(120_000L, result.publish.retryAfterMillis)
                assertEquals(SyncPublishStatus.UNCONFIRMED, result.publish.status)
                assertEquals(writes, git.pathWrites.mapValues { it.value.toList() })
            }
        }
    }

    private suspend fun replayBackup(condition: String, allowPublish: Boolean = true) {
        val path = Files.createTempFile("sync-backup-replay-", ".db")
        val backup = Files.createTempFile("sync-backup-before-upload-", ".db")
        var storage = open(path.toString(), create = true)
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                storage.connect("backup-actor", repository)
                val service = SyncBatchSyncService(transport, secret)
                var store = SyncOutboxStore(storage.handler)
                storage.favorite("/before-backup-target")
                store.freezeRound("space", 1)
                assertEquals(
                    SyncPublishStatus.PUBLISHED,
                    SyncOutboxExchange(store, service).uploadNext(
                        transport.readSnapshot(repository, "space", 1).getOrThrow(),
                    )!!.publish.status,
                )
                storage.favorite("/backup-target")
                store.freezeRound("space", 1)
                val batch = store.nextBatch("space", 1)!!
                assertNull(store.prepared(batch))
                storage.close()
                Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                storage = open(path.toString(), create = false)
                store = SyncOutboxStore(storage.handler)
                val before = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val original = if (condition == "conflict") {
                    val changed = batch.copy(events = batch.events.map { it.copy(occurredAt = it.occurredAt + 1) })
                    service.upload(
                        repository,
                        before,
                        changed,
                        ".mihon-sync/batches/backup-actor/1/${batch.batchId}.json",
                        persist = {},
                    )
                } else {
                    SyncOutboxExchange(store, service).uploadNext(before)!!
                }
                assertEquals(SyncPublishStatus.PUBLISHED, original.publish.status)
                if (condition == "later") {
                    storage.favorite("/after-backup-target")
                    store.freezeRound("space", 1)
                    assertEquals(
                        SyncPublishStatus.PUBLISHED,
                        SyncOutboxExchange(store, service).uploadNext(
                            transport.readSnapshot(repository, "space", 1).getOrThrow(),
                        )!!.publish.status,
                    )
                }
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                storage.close()
                Files.copy(backup, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                storage = open(path.toString(), create = false)
                store = SyncOutboxStore(storage.handler)
                assertNull(store.prepared(batch))
                if (condition == "historical") git.removeFile("mihon-sync", original.artifact.encryptedBatch.path)
                val writes = git.pathWrites.mapValues { it.value.toList() }
                val refUpdates = git.forceFlags.size
                val exchange = SyncOutboxExchange(store, SyncBatchSyncService(git.transport(), secret))
                if (condition == "conflict") {
                    val failure = assertThrows(SyncRemoteDataInvalid::class.java) {
                        runBlocking { exchange.uploadNext(snapshot, allowPublish = allowPublish) }
                    }
                    assertEquals(SyncRunProblem.INVALID_DATA, failure.syncProblem())
                    assertTrue(store.nextBatch("space", 1) != null)
                } else {
                    val recovered = exchange.uploadNext(snapshot, allowPublish = allowPublish)!!
                    if (condition == "historical") {
                        assertTrue(recovered.publish.status != SyncPublishStatus.PUBLISHED)
                        assertTrue(store.nextBatch("space", 1) != null)
                        assertTrue(store.prepared(batch) != null)
                        val readOnlyRetry = exchange.uploadNext(snapshot, allowPublish = false)!!
                        assertTrue(readOnlyRetry.publish.status != SyncPublishStatus.PUBLISHED)
                    } else {
                        assertEquals(SyncPublishStatus.PUBLISHED, recovered.publish.status)
                        assertEquals(original.artifact.encryptedBatch, recovered.artifact.encryptedBatch)
                        assertEquals(original.artifact.indexCiphertext, recovered.artifact.indexCiphertext)
                        assertEquals(original.artifact.previousIndexPath, recovered.artifact.previousIndexPath)
                        assertEquals(original.artifact.previousLastSeq, recovered.artifact.previousLastSeq)
                        assertNull(store.nextBatch("space", 1))
                        assertNull(exchange.uploadNext(transport.readSnapshot(repository, "space", 1).getOrThrow()))
                    }
                }
                assertEquals(writes, git.pathWrites.mapValues { it.value.toList() })
                assertEquals(refUpdates, git.forceFlags.size)
            }
        } finally {
            storage.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(backup)
        }
    }

    private fun open(path: String, create: Boolean): Storage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return Storage(driver, JvmDatabaseHandler(database(driver, create), driver))
    }
}
