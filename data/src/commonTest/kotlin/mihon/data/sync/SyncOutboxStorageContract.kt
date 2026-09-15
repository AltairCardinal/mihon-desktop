package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncObjectDescriptions
import mihon.data.sync.journal.SyncOutboxExchange
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncUploadArtifactCodec
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncBatchCodec
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncCodec
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncProtocol
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DatabaseMigration
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.io.File
import java.util.Date

/** Production SQLite journal through production encryption and Git HTTP, on both platform handlers. */
abstract class SyncOutboxStorageContract {
    protected abstract fun open(path: String? = null, create: Boolean = true): Storage
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    private val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `blank source labels remain uploadable without changing stored metadata`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            val manga = storage.favorite("/blank-title", "   ")
            val batch = requireNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1))
            assertEquals("/blank-title", batch.objects.single().title)
            assertEquals("   ", storage.manga.getMangaById(manga.id).title)
        }
    }

    @Test
    fun `nonfinite source chapter number does not prevent recording an explicit read operation`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            val manga = storage.favorite("/infinite-chapter", "漫画")
            val chapters = ChapterRepositoryImpl(storage.handler)
            val chapter = chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/infinite",
                        name = "",
                        chapterNumber = Double.POSITIVE_INFINITY,
                    ),
                ),
            ).single()
            chapters.update(
                tachiyomi.domain.chapter.model.ChapterUpdate(
                    chapter.id,
                    read = true,
                    syncContext = SyncMutationContext.User,
                ),
            )
            assertTrue(requireNotNull(chapters.getChapterById(chapter.id)).read)
            val batch = requireNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1))
            assertEquals("/infinite", batch.objects.last().title)
            assertNull(batch.objects.last().chapterNumber)
            assertEquals(Double.POSITIVE_INFINITY, requireNotNull(chapters.getChapterById(chapter.id)).chapterNumber)
        }
    }

    @Test
    fun `duplicate local chapter identities do not roll back an explicit read operation`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            val manga = storage.favorite("/duplicates", "漫画")
            val chapters = ChapterRepositoryImpl(storage.handler)
            val duplicate = Chapter.create().copy(mangaId = manga.id, url = "/same-chapter", name = "同一话")
            val inserted = chapters.addAll(listOf(duplicate, duplicate))
            assertEquals(2, inserted.size)
            chapters.update(
                tachiyomi.domain.chapter.model.ChapterUpdate(
                    inserted.last().id,
                    read = true,
                    syncContext = SyncMutationContext.User,
                ),
            )
            assertTrue(requireNotNull(chapters.getChapterById(inserted.last().id)).read)
            val batch = requireNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1))
            assertEquals(listOf("漫画", "同一话"), batch.objects.map { it.title })
            assertEquals(2, batch.events.size)
        }
    }

    @Test
    fun `maximum size legacy batch survives migration and append without changing its wire bytes`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            fun event(seq: Int, padding: Int) = SyncEventEnvelope(
                1, "space", 1, "device-a", 1, seq.toLong(), SyncCategory.FAVORITE,
                listOf(
                    SyncEffect(
                        "favorite",
                        SyncObjectKey(
                            SyncObjectType.MANGA,
                            sourceId = "42",
                            originalUrl = "/" + "x".repeat(padding),
                        ),
                        SyncField.FAVORITE,
                        SyncEffectKind.ADD,
                    ),
                ),
                SyncOrigin.USER, batchId = "legacy",
            )
            fun legacyBytes(events: List<SyncEventEnvelope>): String {
                val encoded = SyncBatchCodec.rawEncode(SyncBatch(1, "space", 1, "legacy", events))
                return JsonObject((Json.parseToJsonElement(encoded) as JsonObject) - "objects").toString()
            }
            val padding = SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH -
                legacyBytes((1..128).map { event(it, 0) }).encodeToByteArray().size
            val events = (1..128).map { event(it, padding / 128 + if (it <= padding % 128) 1 else 0) }
            val oldWire = legacyBytes(events)
            assertEquals(SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH, oldWire.encodeToByteArray().size)
            storage.handler.await(inTransaction = true) {
                sync_journalQueries.insertBatch(
                    "space",
                    1,
                    "legacy",
                    "device-a",
                    1,
                    1,
                    128,
                    oldWire.encodeToByteArray().size.toLong(),
                )
                events.forEach { entry ->
                    sync_journalQueries.insertEvent(
                        "space", 1, "device-a", 1, entry.seq,
                        entry.category.name, entry.origin.name, "legacy", SyncCodec.encode(entry), 0,
                    )
                    sync_journalQueries.insertOutbox("space", 1, "device-a", 1, entry.seq, "legacy")
                    sync_journalQueries.updateBatch(
                        entry.seq,
                        oldWire.encodeToByteArray().size.toLong(),
                        "[]",
                        "space",
                        1,
                        "legacy",
                    )
                    sync_journalQueries.advanceSequence("space", 1, "device-a", 1)
                }
            }
            storage.driver.execute(null, "ALTER TABLE sync_batches DROP COLUMN objects_json", 0)
            storage.driver.execute(null, "PRAGMA user_version = 21", 0)
            DatabaseMigration.migrateAtomically(storage.driver, 21, 22)
            storage.favorite("/after-legacy", "新增收藏")
            val queue = storage.journal.pendingEvents("space", 1)
            assertEquals(129, queue.size)
            assertEquals(events, queue.take(128))
            assertNotEquals("legacy", queue.last().batchId)
            val frozen = requireNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1))
            assertEquals(oldWire, SyncBatchCodec.rawEncode(frozen))
            val engine = SyncAeadEngineFactory.create()
            val encrypted = SyncBatchEncryption.encrypt(engine, secret, frozen, path(frozen))
            assertTrue(engine.sha256(oldWire.encodeToByteArray()).contentEquals(encrypted.plaintextDigest))
            assertEquals(frozen, SyncBatchEncryption.decrypt(engine, secret, encrypted))
        }
    }

    @Test
    fun `bounded descriptions split by real UTF8 bytes and retain all operation identities`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            repeat(60) { storage.favorite("/large-$it", "漫".repeat(6000)) }
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(60, events.size)
            val batches = events.groupBy { requireNotNull(it.batchId) }
            assertTrue(batches.size > 1)
            for ((id, batchEvents) in batches) {
                val stored = storage.handler.await { sync_journalQueries.getBatch("space", 1, id).executeAsOne() }
                val descriptions = SyncObjectDescriptions.decode(stored.objects_json)
                assertEquals(batchEvents.size, descriptions.size)
                assertTrue(descriptions.all { it.title == "漫".repeat(4096) })
                val encoded = SyncBatchCodec.encode(batchEvents, id, "space", 1, objects = descriptions)
                assertEquals(encoded.encodeToByteArray().size.toLong(), stored.plaintext_bytes)
                assertTrue(stored.plaintext_bytes <= SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH)
                assertTrue(batchEvents.size <= SyncProtocol.MAX_EVENTS_PER_BATCH)
            }
            assertEquals((1L..60L).toList(), events.map { it.seq })
            assertNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1))
        }
    }

    @Test
    fun `partial reading uploads parent and chapter descriptions without device reader preferences`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            val manga = storage.manga.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 42, url = "/reader", title = "漫画"),
                ),
            ).single()
            assertTrue(storage.manga.update(MangaUpdate(manga.id, viewerFlags = 7)))
            val chapters = ChapterRepositoryImpl(storage.handler)
            val chapter = chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/episode",
                        name = "第一话",
                        chapterNumber = 1.5,
                        sourceOrder = 8,
                    ),
                ),
            ).single()
            storage.handler.await {
                SqlDelightReadingProgressRepository(this)
            }.record(
                ReadingProgressEvent(
                    chapter.id,
                    3,
                    10,
                    Date(1_000),
                    5,
                    syncContext = SyncMutationContext.User,
                ),
            )
            val batch = requireNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1))
            assertEquals(listOf("漫画", "第一话"), batch.objects.map { it.title })
            val description = batch.objects.last()
            assertEquals("/reader", description.objectKey.parentUrl)
            assertEquals("/episode", description.objectKey.originalUrl)
            assertEquals(1.5, description.chapterNumber)
            assertEquals(8, description.sourceOrder)
            assertEquals(2, batch.events.single().effects.size)
            assertEquals(7, storage.manga.getMangaById(manga.id).viewerFlags)
            val encoded = SyncBatchCodec.rawEncode(batch)
            assertTrue(!encoded.contains("viewerFlags") && !encoded.contains("readerMode"))
        }
    }

    @Test
    fun `descriptor migration preserves an existing queued operation and accepts subsequent commands`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            storage.favorite("/before", "迁移前")
            val before = storage.journal.pendingEvents("space", 1).single()
            storage.driver.execute(null, "ALTER TABLE sync_batches DROP COLUMN objects_json", 0)
            storage.driver.execute(null, "PRAGMA user_version = 21", 0)
            DatabaseMigration.migrateAtomically(storage.driver, 21, 22)
            assertEquals(before, storage.journal.pendingEvents("space", 1).single())
            val store = SyncOutboxStore(storage.handler)
            assertEquals(listOf(before), requireNotNull(store.nextBatch("space", 1)).events)
            storage.favorite("/after", "迁移后")
            assertEquals(listOf(1L, 2L), storage.journal.pendingEvents("space", 1).map { it.seq })
        }
    }

    @Test
    fun `upload uses descriptions captured with the user operation and clears only published events`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            open().use { storage ->
                storage.connect(repository)
                val manga = storage.favorite("/first", "收藏时的标题")
                assertTrue(storage.manga.update(MangaUpdate(manga.id, title = "稍后抓取的新标题")))
                val transport = git.transport()
                assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                val service = SyncBatchSyncService(transport, secret)
                val store = SyncOutboxStore(storage.handler)
                val frozen = requireNotNull(store.nextBatch("space", 1))
                assertEquals("收藏时的标题", frozen.objects.single().title)
                storage.favorite("/second", "同步中新增")
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val result = requireNotNull(SyncOutboxExchange(store, service).uploadNext(snapshot))
                assertEquals(SyncPublishStatus.PUBLISHED, result.publish.status)
                val published = transport.readSnapshot(repository, "space", 1).getOrThrow()
                assertEquals(frozen, service.receive(published, published.batches.single()).batch)
                assertEquals(listOf(2L), storage.journal.pendingEvents("space", 1).map { it.seq })
                assertEquals(2, requireNotNull(store.nextBatch("space", 1)).events.single().seq)
            }
        }
    }

    @Test
    fun `unconfirmed upload survives database restart with identical batch index and head ciphertext`() = runBlocking {
        val file = File.createTempFile("mihon-sync-outbox-", ".db")
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val original = open(file.absolutePath).use { storage ->
                    storage.connect(repository)
                    storage.favorite("/restart", "重启保留")
                    git.failReadAfterPatch = true
                    val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                    val result = requireNotNull(
                        SyncOutboxExchange(SyncOutboxStore(storage.handler), SyncBatchSyncService(transport, secret))
                            .uploadNext(snapshot),
                    )
                    assertEquals(SyncPublishStatus.UNCONFIRMED, result.publish.status)
                    assertEquals(1, storage.journal.pendingEvents("space", 1).size)
                    storage.favorite("/later", "后来新增")
                    SyncUploadArtifactCodec.encode(result.artifact)
                }
                open(file.absolutePath, false).use { storage ->
                    storage.connect(repository)
                    val store = SyncOutboxStore(storage.handler)
                    val batch = requireNotNull(store.nextBatch("space", 1))
                    assertEquals(original, SyncUploadArtifactCodec.encode(requireNotNull(store.prepared(batch))))
                    val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                    val result = requireNotNull(
                        SyncOutboxExchange(store, SyncBatchSyncService(transport, secret)).uploadNext(snapshot),
                    )
                    assertEquals(SyncPublishStatus.PUBLISHED, result.publish.status)
                    assertEquals(original, SyncUploadArtifactCodec.encode(result.artifact))
                    assertEquals(listOf(2L), storage.journal.pendingEvents("space", 1).map { it.seq })
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `failed durable artifact write prevents every network upload and retains the queue`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            open().use { storage ->
                storage.connect(repository)
                storage.favorite("/failure", "保存失败")
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                storage.driver.execute(
                    null,
                    "CREATE TRIGGER fail_artifact BEFORE UPDATE OF prepared_upload ON sync_batches " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic artifact failure'); END;",
                    0,
                )
                val requestsBefore = git.server.requestCount
                val attempt = runCatching {
                    SyncOutboxExchange(SyncOutboxStore(storage.handler), SyncBatchSyncService(transport, secret))
                        .uploadNext(snapshot)
                }
                assertTrue(attempt.isFailure)
                assertEquals(requestsBefore, git.server.requestCount)
                assertEquals(1, storage.journal.pendingEvents("space", 1).size)
                assertNull(
                    SyncOutboxStore(storage.handler).prepared(
                        requireNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1)),
                    ),
                )
            }
        }
    }

    @Test
    fun `first persisted artifact wins and invalid scope or acknowledgement cannot clear data`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            open().use { storage ->
                storage.connect(repository)
                storage.favorite("/binding", "绑定")
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val service = SyncBatchSyncService(transport, secret)
                val store = SyncOutboxStore(storage.handler)
                val batch = requireNotNull(store.nextBatch("space", 1))
                val first = service.prepare(snapshot, batch, path(batch))
                val second = service.prepare(snapshot, batch, path(batch))
                assertNotEquals(first.encryptedBatch, second.encryptedBatch)
                assertEquals(first, store.savePrepared(batch, first))
                assertEquals(first, store.savePrepared(batch, second))
                assertTrue(runCatching { store.savePrepared(batch.copy(generation = 2), first) }.isFailure)
                assertTrue(
                    runCatching {
                        store.savePrepared(batch, first.copy(repository = SyncRepository("other", "repo", "sync")))
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        store.savePrepared(
                            batch,
                            first.copy(
                                encryptedBatch = first.encryptedBatch.copy(
                                    plaintextDigest = ByteArray(32),
                                ),
                            ),
                        )
                    }.isFailure,
                )
                for (result in listOf(
                    SyncPublishResult(SyncPublishStatus.UNCONFIRMED, batch.batchId),
                    SyncPublishResult(SyncPublishStatus.PUBLISHED, "other", "a".repeat(40)),
                    SyncPublishResult(SyncPublishStatus.PUBLISHED, batch.batchId),
                )) {
                    assertTrue(runCatching { store.acknowledge(first, result) }.isFailure)
                }
                assertTrue(
                    runCatching {
                        store.acknowledge(
                            second,
                            SyncPublishResult(SyncPublishStatus.PUBLISHED, batch.batchId, "a".repeat(40)),
                        )
                    }.isFailure,
                )
                assertEquals(1, storage.journal.pendingEvents("space", 1).size)
                storage.journal.connect("other-space", 1, repository, "device-b", 1)
                assertTrue(runCatching { store.nextBatch("space", 1) }.isFailure)
                assertNull(store.nextBatch("other-space", 1))
            }
        }
    }

    private fun path(batch: SyncBatch) =
        ".mihon-sync/batches/${batch.events.first().actorId}/${batch.events.first().epoch}/${batch.batchId}.json"

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
        val manga = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        suspend fun connect(repository: SyncRepository) = journal.connect("space", 1, repository, "device-a", 1)
        suspend fun favorite(url: String, title: String): Manga {
            val item = manga.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = Long.MAX_VALUE - 1, url = url, title = title),
                ),
            ).single()
            assertTrue(manga.update(MangaUpdate(item.id, favorite = true, syncContext = SyncMutationContext.User)))
            return item
        }
        override fun close() = driver.close()
    }
}
