package mihon.data.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxExchange
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxExchange
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.runtime.SyncRunStore
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncRemoteSnapshotGuard
import mihon.data.sync.transport.SyncRemoteSnapshotStaleCandidate
import mihon.data.sync.transport.SyncSnapshotWriteOwner
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SyncRemoteSnapshotGuardContract {
    protected abstract fun open(path: String? = null, create: Boolean = true): Storage
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    private val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })
    private val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = "/remote")

    @Test
    fun `guard revision advances only when an accepted head changes`() = runBlocking {
        remote().use { remote ->
            open().use { storage ->
                storage.connect(repository)

                storage.guard.observe(remote.first)

                assertEquals(1L, guardRevision(storage.driver))
                storage.guard.observe(remote.first)
                assertEquals(1L, guardRevision(storage.driver))
                storage.guard.observe(remote.latest)
                assertEquals(2L, guardRevision(storage.driver))
            }
        }
    }

    @Test
    fun `stale guard revision cannot poison a newer accepted head`() = runBlocking {
        remote().use { remote ->
            open().use { storage ->
                storage.connect(repository)
                val beforeFirstRead = storage.guard.captureFence("space", 1)
                storage.guard.observe(remote.first, beforeFirstRead)

                val stale = runCatching { storage.guard.observe(remote.latest, beforeFirstRead) }.exceptionOrNull()
                assertTrue(stale is SyncRemoteSnapshotStaleCandidate)
                assertEquals(remote.first.head, guardHead(storage.driver))
                assertEquals(false, guardBlocked(storage.driver))
                assertEquals(1L, guardRevision(storage.driver))

                storage.guard.observe(remote.latest, storage.guard.captureFence("space", 1))
                assertEquals(remote.latest.head, guardHead(storage.driver))
                assertEquals(2L, guardRevision(storage.driver))
            }
        }
    }

    @Test
    fun `reclaimed run owner cannot commit a snapshot read by its stale attempt`() = runBlocking {
        remote().use { remote ->
            open().use { storage ->
                storage.connect(repository)
                val runs = SyncRunStore(storage.handler)
                val run = runs.start("space", 1, SyncTrigger.MANUAL)
                assertEquals(true, runs.claim(run.runId, "owner-a", 1))
                val oldOwner = SyncSnapshotWriteOwner(run.runId, "owner-a", 1)
                val stale = storage.guard.captureFence("space", 1, oldOwner)

                assertEquals(true, runs.releaseForRecovery(run.runId))
                assertEquals(true, runs.claim(run.runId, "owner-b", 2))
                val failure = runCatching { storage.guard.observe(remote.first, stale) }.exceptionOrNull()

                assertTrue(failure is SyncRemoteSnapshotStaleCandidate)
                assertEquals(0L, guardCount(storage.driver))
                assertEquals(2L, requireNotNull(runs.get(run.runId)).attemptId)
                assertEquals("owner-b", requireNotNull(runs.get(run.runId)).ownerSession)
            }
        }
    }

    @Test
    fun `growth and repeated heads are accepted without inventing an initial anchor`() = runBlocking {
        remote().use { remote ->
            open().use { storage ->
                storage.connect(repository)
                storage.guard.observe(remote.first)
                storage.guard.observe(remote.latest)
                storage.guard.observe(remote.latest)
                assertEquals(2, remote.latest.batches.size)
            }
            // A fresh device has no evidence that the older valid snapshot was rolled back.
            open().use { fresh ->
                fresh.connect(repository)
                fresh.guard.observe(remote.first)
            }
        }
    }

    @Test
    fun `observed immutable deletion blocks the space across database reopen and reconnect`() = runBlocking {
        val file = File.createTempFile("sync-remote-anchor-", ".db")
        try {
            remote().use { remote ->
                open(file.absolutePath).use { storage ->
                    storage.connect(repository)
                    storage.guard.observe(remote.latest)
                }
                open(file.absolutePath, false).use { storage ->
                    storage.connect(repository)
                    assertTrue(runCatching { storage.guard.observe(remote.first) }.isFailure)
                }
                open(file.absolutePath, false).use { storage ->
                    storage.connect(repository)
                    assertTrue(runCatching { storage.guard.observe(remote.latest) }.isFailure)
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `same immutable batch identity cannot be replaced by another authenticated ciphertext`() = runBlocking {
        remote().use { original ->
            remote(SyncEffectKind.REMOVE).use { variant ->
                open().use { storage ->
                    storage.connect(repository)
                    storage.guard.observe(original.latest)
                    assertTrue(runCatching { storage.guard.observe(variant.latest) }.isFailure)
                    assertTrue(runCatching { storage.guard.observe(original.latest) }.isFailure)
                }
            }
            remote().use { reencrypted ->
                open().use { storage ->
                    storage.connect(repository)
                    storage.guard.observe(original.latest)
                    assertEquals(
                        original.latest.batches.first().digestHex,
                        reencrypted.latest.batches.first().digestHex,
                    )
                    assertTrue(runCatching { storage.guard.observe(reencrypted.latest) }.isFailure)
                }
            }
        }
    }

    @Test
    fun `same head cache cannot conceal changed batch or index tree material`() = runBlocking {
        remote().use { remote ->
            for (path in listOf(remote.latest.batches.first().path, remote.latest.batches.first().indexPath)) {
                open().use { storage ->
                    storage.connect(repository)
                    storage.guard.observe(remote.latest)
                    val altered = remote.latest.copy(
                        tree = remote.latest.tree.copy(
                            entries = remote.latest.tree.entries.map {
                                if (it.path == path) it.copy(sha = "f".repeat(40)) else it
                            },
                        ),
                    )
                    assertTrue(runCatching { storage.guard.observe(altered) }.isFailure)
                    assertTrue(runCatching { storage.guard.observe(remote.latest) }.isFailure)
                }
            }
        }
    }

    @Test
    fun `wrong repository or inactive space is rejected without poisoning the correct binding`() = runBlocking {
        remote().use { remote ->
            open().use { storage ->
                storage.connect(repository)
                assertTrue(
                    runCatching {
                        storage.guard.observe(remote.latest.copy(repository = repository.copy(name = "another")))
                    }.isFailure,
                )
                assertTrue(
                    runCatching { storage.guard.observe(remote.latest.copy(spaceId = "other")) }.isFailure,
                )
                assertTrue(
                    runCatching { storage.guard.observe(remote.latest.copy(generation = 2)) }.isFailure,
                )
                storage.guard.observe(remote.latest)
            }
        }
    }

    @Test
    fun `rollback prevents preparation upload and download without new HTTP requests`() = runBlocking {
        remote().use { remote ->
            open().use { storage ->
                storage.connect(repository)
                storage.guard.observe(remote.latest)
                storage.favorite()
                val requests = remote.git.server.requestCount
                val upload = runCatching {
                    SyncOutboxExchange(SyncOutboxStore(storage.handler), remote.service)
                        .uploadNext(remote.first)
                }
                val download = runCatching {
                    SyncInboxExchange(SyncInboxStore(storage.handler), remote.service).receive(
                        remote.first,
                        remote.first.batches.single(),
                    )
                }
                assertTrue(upload.isFailure)
                assertTrue(download.isFailure)
                assertEquals(requests, remote.git.server.requestCount)
                assertEquals(1, storage.journal.pendingEvents("space", 1).size)
                assertEquals(
                    null,
                    storage.handler.await {
                        sync_journalQueries.getNextUploadBatch("space", 1).executeAsOne().prepared_upload
                    },
                )
            }
        }
    }

    @Test
    fun `failed durable anchor write prevents exchange network effects`() = runBlocking {
        remote().use { remote ->
            open().use { storage ->
                storage.connect(repository)
                storage.favorite()
                storage.driver.execute(
                    null,
                    "CREATE TRIGGER fail_guard BEFORE INSERT ON sync_remote_guards " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic anchor failure'); END",
                    0,
                )
                val requests = remote.git.server.requestCount
                val result = runCatching {
                    SyncOutboxExchange(SyncOutboxStore(storage.handler), remote.service)
                        .uploadNext(remote.latest)
                }
                assertTrue(result.isFailure)
                assertEquals(requests, remote.git.server.requestCount)
                assertEquals(1, storage.journal.pendingEvents("space", 1).size)
                storage.driver.execute(null, "DROP TRIGGER fail_guard", 0)
                storage.guard.observe(remote.latest)
            }
        }
    }

    @Test
    fun `successful publish anchors its readback before acknowledgement and survives restart`() = runBlocking {
        val file = File.createTempFile("sync-published-anchor-", ".db")
        try {
            remote().use { remote ->
                open(file.absolutePath).use { storage ->
                    storage.connect(repository)
                    storage.favorite()
                    val result = requireNotNull(
                        SyncOutboxExchange(SyncOutboxStore(storage.handler), remote.service)
                            .uploadNext(remote.latest),
                    )
                    assertEquals(SyncPublishStatus.PUBLISHED, result.publish.status)
                    assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
                    listOf(
                        result.artifact.encryptedBatch.path,
                        result.artifact.indexPath,
                        result.artifact.headPath,
                    ).forEach { remote.git.removeFile(repository.branch, it) }
                }
                val rolledBack = remote.git.transport().readSnapshot(repository, "space", 1).getOrThrow()
                assertEquals(2, rolledBack.batches.size)
                open(file.absolutePath, false).use { storage ->
                    storage.connect(repository)
                    val requests = remote.git.server.requestCount
                    assertTrue(
                        runCatching {
                            SyncOutboxExchange(SyncOutboxStore(storage.handler), remote.service)
                                .uploadNext(rolledBack)
                        }.isFailure,
                    )
                    assertEquals(requests, remote.git.server.requestCount)
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `publish observer failure preserves outbox and prevents competing ref retry writes`() = runBlocking {
        for (competition in listOf(0, 2)) {
            remote().use { remote ->
                open().use { storage ->
                    storage.connect(repository)
                    storage.guard.observe(remote.latest)
                    storage.favorite()
                    storage.driver.execute(
                        null,
                        "CREATE TRIGGER fail_readback_anchor BEFORE INSERT ON sync_remote_heads " +
                            "WHEN NEW.head_sha != '${remote.latest.head}' " +
                            "BEGIN SELECT RAISE(ABORT, 'synthetic readback anchor failure'); END",
                        0,
                    )
                    remote.git.competingWrites = competition
                    val writes = remote.git.forceFlags.size
                    assertTrue(
                        runCatching {
                            SyncOutboxExchange(SyncOutboxStore(storage.handler), remote.service)
                                .uploadNext(remote.latest)
                        }.isFailure,
                    )
                    assertEquals(writes + 1, remote.git.forceFlags.size)
                    assertEquals(1, storage.journal.pendingEvents("space", 1).size)
                    assertTrue(
                        storage.handler.await {
                            sync_journalQueries.getNextUploadBatch("space", 1).executeAsOne().prepared_upload != null
                        },
                    )
                    storage.driver.execute(null, "DROP TRIGGER fail_readback_anchor", 0)
                    val current = remote.git.transport().readSnapshot(repository, "space", 1).getOrThrow()
                    val retried = requireNotNull(
                        SyncOutboxExchange(SyncOutboxStore(storage.handler), remote.service)
                            .uploadNext(current),
                    )
                    assertEquals(SyncPublishStatus.PUBLISHED, retried.publish.status)
                    assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
                }
            }
        }
    }

    @Test
    fun `authenticated bootstrap index is immutable across otherwise valid growing heads`() = runBlocking {
        remote().use { original ->
            remote().use { replacement ->
                open().use { storage ->
                    storage.connect(repository)
                    storage.guard.observe(original.latest)
                    val path = ".mihon-sync/index/bootstrap/0/bootstrap.bin"
                    original.git.replaceFile(
                        repository.branch,
                        path,
                        requireNotNull(replacement.git.file(repository.branch, path)),
                    )
                    val changed = original.git.transport().readSnapshot(repository, "space", 1).getOrThrow()
                    assertTrue(runCatching { storage.guard.observe(changed) }.isFailure)
                }
            }
        }
    }

    private suspend fun remote(kind: SyncEffectKind = SyncEffectKind.ADD): Remote {
        val git = SyncGitSafetyContractTest().GitFixture()
        try {
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val service = SyncBatchSyncService(transport, secret)
            suspend fun publish(seq: Long): SyncSnapshot {
                val before = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val id = "remote-$seq"
                val event = SyncEventEnvelope(
                    1, "space", 1, "remote", 1, seq, SyncCategory.FAVORITE,
                    listOf(SyncEffect("favorite", key, SyncField.FAVORITE, kind)), SyncOrigin.USER, batchId = id,
                )
                val batch = SyncBatch(1, "space", 1, id, listOf(event), listOf(SyncObjectDescriptor(key, "远端漫画")))
                assertEquals(
                    SyncPublishStatus.PUBLISHED,
                    service.upload(
                        repository,
                        before,
                        batch,
                        ".mihon-sync/batches/remote/1/$id.json",
                        persist = {},
                    ).publish.status,
                )
                return transport.readSnapshot(repository, "space", 1).getOrThrow()
            }
            val first = publish(1)
            return Remote(git, service, first, publish(2))
        } catch (failure: Throwable) {
            git.close()
            throw failure
        }
    }

    private class Remote(
        val git: SyncGitSafetyContractTest.GitFixture,
        val service: SyncBatchSyncService,
        val first: SyncSnapshot,
        val latest: SyncSnapshot,
    ) : AutoCloseable {
        override fun close() = git.close()
    }

    private fun guardRevision(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT revision FROM sync_remote_guards WHERE space_id = 'space' AND generation = 1",
        parameters = 0,
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
        },
        binders = {},
    ).value

    private fun guardHead(driver: SqlDriver): String? = driver.executeQuery(
        identifier = null,
        sql = "SELECT latest_head FROM sync_remote_guards WHERE space_id = 'space' AND generation = 1",
        parameters = 0,
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
    ).value

    private fun guardBlocked(driver: SqlDriver): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT blocked FROM sync_remote_guards WHERE space_id = 'space' AND generation = 1",
        parameters = 0,
        mapper = { cursor -> QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L) },
    ).value

    private fun guardCount(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM sync_remote_guards",
        parameters = 0,
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
    ).value

    protected fun database(driver: SqlDriver, create: Boolean): Database {
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    protected class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        val journal = SyncLocalJournal(handler)
        val guard = SyncRemoteSnapshotGuard(handler)
        suspend fun connect(repository: SyncRepository) = journal.connect("space", 1, repository, "local-device", 1)
        suspend fun favorite() {
            val repository = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
            val manga = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/local", title = "本机漫画")),
            ).single()
            assertTrue(
                repository.update(MangaUpdate(manga.id, favorite = true, syncContext = SyncMutationContext.User)),
            )
        }
        override fun close() = driver.close()
    }
}
