package mihon.data.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncObjectDescriptions
import mihon.domain.sync.SyncBatchCodec
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncProtocol
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.interactor.LibraryMembershipResult
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import java.io.File

/** The same commands execute against both production handlers and generated SQLite queries. */
abstract class SyncJournalStorageContract {
    protected abstract fun open(databasePath: String? = null, create: Boolean = true): Storage

    @Test
    fun `connecting persists one active actor and reconnecting cannot reset its sequence`() = runBlocking {
        open().use { storage ->
            storage.connect()
            storage.handler.await {
                val actor = sync_journalQueries.getActiveActor().executeAsOneOrNull()
                assertNotNull(actor)
                assertEquals(1, actor!!.next_seq)
                sync_journalQueries.advanceSequence("space", 1, "device-a", 1)
            }
            storage.connect()
            storage.handler.await {
                assertEquals(2, sync_journalQueries.getActiveActor().executeAsOne().next_seq)
            }
        }
    }

    @Test
    fun `generation zero accepted by the protocol records and survives reconnect`() = runBlocking {
        val file = File.createTempFile("mihon-sync-generation-", ".db")
        try {
            val event = open(file.absolutePath).use { storage ->
                storage.journal.connect("zero", 0, SyncRepository("owner", "sync-data", "sync"), "device-a", 1)
                val manga = storage.insertManga("/generation-zero")
                assertTrue(
                    UpdateLibraryMembership(storage.repository).await(manga, true) is LibraryMembershipResult.Success,
                )
                storage.journal.pendingEvents("zero", 0).single()
            }
            open(file.absolutePath, create = false).use { storage ->
                storage.journal.connect("zero", 0, SyncRepository("owner", "sync-data", "sync"), "device-a", 1)
                assertEquals(event, storage.journal.pendingEvents("zero", 0).single())
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `membership command records an immutable operation in the same database`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val manga = storage.insertManga("/favorite")
            val command = UpdateLibraryMembership(storage.repository)
            assertTrue(command.await(manga, true) is LibraryMembershipResult.Success)
            assertTrue(storage.repository.getMangaById(manga.id).favorite)
            val added = storage.journal.pendingEvents("space", 1).single()
            assertEquals(SyncCategory.FAVORITE, added.category)
            assertEquals(SyncOrigin.USER, added.origin)
            assertEquals(SyncEffectKind.ADD, added.effects.single().kind)
            assertEquals("9223372036854775806", added.effects.single().objectKey.sourceId)
            assertEquals("/favorite", added.effects.single().objectKey.originalUrl)
            assertTrue(command.await(manga.copy(favorite = true), false) is LibraryMembershipResult.Success)
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(listOf(1L, 2L), events.map { it.seq })
            assertEquals(added, events.first())
            assertEquals(listOf(added.ref("favorite")), events.last().effects.single().parents)
            assertEquals(SyncEffectKind.REMOVE, events.last().effects.single().kind)
        }
    }

    @Test
    fun `failed outbox insert rolls back favorite sequence events and open batch`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val manga = storage.insertManga("/rollback")
            storage.driver.execute(
                null,
                "CREATE TRIGGER fail_sync BEFORE INSERT ON sync_outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic outbox failure'); END;",
                0,
            )
            val result = UpdateLibraryMembership(storage.repository).await(manga, true)
            assertTrue(result is LibraryMembershipResult.Failure)
            assertFalse(storage.repository.getMangaById(manga.id).favorite)
            storage.handler.await {
                assertEquals(0, sync_journalQueries.countEvents().executeAsOne())
                assertEquals(0, sync_journalQueries.countBatches().executeAsOne())
                assertEquals(1, sync_journalQueries.getActiveActor().executeAsOne().next_seq)
            }
        }
    }

    @Test
    fun `batch update failure rolls back every favorite and journal entry`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val first = storage.insertManga("/first")
            val second = storage.insertManga("/second")
            storage.driver.execute(
                null,
                "CREATE TRIGGER fail_second_sync BEFORE INSERT ON sync_outbox WHEN NEW.seq = 2 " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic second event failure'); END;",
                0,
            )
            val result = UpdateManga(storage.repository).awaitAll(
                listOf(first, second).map {
                    MangaUpdate(it.id, favorite = true, syncContext = SyncMutationContext.User)
                },
            )
            assertFalse(result)
            assertFalse(storage.repository.getMangaById(first.id).favorite)
            assertFalse(storage.repository.getMangaById(second.id).favorite)
            assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `unconfigured and metadata remote or local only writes never create user operations`() = runBlocking {
        open().use { storage ->
            val manga = storage.insertManga("/local")
            storage.repository.update(MangaUpdate(manga.id, favorite = true, syncContext = SyncMutationContext.User))
            storage.connect()
            for (context in listOf(
                SyncMutationContext.Metadata,
                SyncMutationContext(SyncOrigin.REMOTE_SYNC),
                SyncMutationContext(SyncOrigin.BACKUP_RESTORE),
                SyncMutationContext.LocalOnly,
            )) {
                assertTrue(storage.repository.update(MangaUpdate(manga.id, favorite = false, syncContext = context)))
            }
            assertTrue(
                storage.repository.update(
                    MangaUpdate(manga.id, viewerFlags = 7, syncContext = SyncMutationContext.User),
                ),
            )
            assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `explicit same value user commands retain causality and membership batches are atomic`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val manga = storage.insertManga("/same-value")
            repeat(2) {
                storage.repository.updateMembershipsAtomically(
                    listOf(
                        LibraryMembershipUpdate(manga.id, true, 1, emptyList(), syncContext = SyncMutationContext.User),
                    ),
                )
            }
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(2, events.size)
            assertEquals(listOf(events.first().ref("favorite")), events.last().effects.single().parents)
        }
    }

    @Test
    fun `an operation from an earlier observation preserves concurrent heads for the next command`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val manga = storage.insertManga("/concurrent-heads")
            for (context in listOf(
                SyncMutationContext.User,
                SyncMutationContext.User.copy(observedHeads = emptyMap()),
                SyncMutationContext.User,
            )) {
                assertTrue(storage.repository.update(MangaUpdate(manga.id, favorite = true, syncContext = context)))
            }
            val events = storage.journal.pendingEvents("space", 1)
            assertTrue(events[1].effects.single().parents.isEmpty())
            assertEquals(
                setOf(events[0].ref("favorite"), events[1].ref("favorite")),
                events[2].effects.single().parents.toSet(),
            )
        }
    }

    @Test
    fun `restarting preserves queued bytes actor sequence and causal predecessor`() = runBlocking {
        val file = File.createTempFile("mihon-sync-journal-", ".db")
        try {
            val first = open(file.absolutePath).use { storage ->
                storage.connect()
                val manga = storage.insertManga("/restart")
                UpdateLibraryMembership(storage.repository).await(manga, true)
                storage.journal.pendingEvents("space", 1).single()
            }
            open(file.absolutePath, create = false).use { storage ->
                storage.connect()
                assertEquals(first, storage.journal.pendingEvents("space", 1).single())
                val manga = storage.repository.getFavorites().single()
                UpdateLibraryMembership(storage.repository).await(manga, false)
                val events = storage.journal.pendingEvents("space", 1)
                assertEquals(listOf(1L, 2L), events.map { it.seq })
                assertEquals(listOf(first.ref("favorite")), events.last().effects.single().parents)
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `open batches bound actual encoded bytes and event counts without rewriting event identities`() = runBlocking {
        for ((url, count) in listOf("/short" to 257, "/${"漫".repeat(1000)}" to 200)) {
            open().use { storage ->
                storage.connect()
                val manga = storage.insertManga(url)
                assertTrue(
                    UpdateManga(storage.repository).awaitAll(
                        (0 until count).map { index ->
                            MangaUpdate(manga.id, favorite = index % 2 == 0, syncContext = SyncMutationContext.User)
                        },
                    ),
                )
                val events = storage.journal.pendingEvents("space", 1) +
                    storage.journal.pendingEvents("space", 1, offset = 256)
                assertEquals(count, events.size)
                val batches = events.groupBy { requireNotNull(it.batchId) }
                assertTrue(batches.size > 1)
                for ((batchId, batchEvents) in batches) {
                    val stored = storage.handler.await {
                        sync_journalQueries.getBatch("space", 1, batchId).executeAsOne()
                    }
                    val encoded = SyncBatchCodec.encode(
                        batchEvents,
                        batchId,
                        "space",
                        generation = 1,
                        objects = SyncObjectDescriptions.decode(stored.objects_json),
                    )
                    assertEquals(encoded.encodeToByteArray().size.toLong(), stored.plaintext_bytes)
                    assertTrue(batchEvents.size <= SyncProtocol.MAX_EVENTS_PER_BATCH)
                    assertTrue(encoded.encodeToByteArray().size <= SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH)
                }
                assertEquals((1L..count.toLong()).toList(), events.map { it.seq })
            }
        }
    }

    @Test
    fun `sync migration preserves existing library and enables transactional recording`() = runBlocking {
        open().use { storage ->
            val manga = storage.insertManga("/existing-before-sync")
            storage.repository.update(MangaUpdate(manga.id, favorite = true, viewerFlags = 7))
            removeSyncJournalSchema(storage.driver)
            storage.driver.execute(null, "PRAGMA user_version = 20", 0)
            DatabaseMigration.migrateAtomically(storage.driver, 20, 25)
            storage.connect()
            val retained = storage.repository.getMangaById(manga.id)
            assertTrue(retained.favorite)
            assertEquals(7, retained.viewerFlags)
            assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
            UpdateLibraryMembership(storage.repository).await(retained, false)
            assertEquals(
                SyncEffectKind.REMOVE,
                storage.journal.pendingEvents("space", 1).single().effects.single().kind,
            )
        }
    }

    @Test
    fun `a migration collision rolls back new sync tables without destroying existing data`() = runBlocking {
        open().use { storage ->
            val manga = storage.insertManga("/migration-rollback")
            removeSyncJournalSchema(storage.driver)
            storage.driver.execute(null, "PRAGMA user_version = 20", 0)
            storage.driver.execute(null, "CREATE TABLE sync_events (unexpected INTEGER)", 0)
            val result = runCatching { DatabaseMigration.migrateAtomically(storage.driver, 20, 21) }
            assertTrue(result.isFailure)
            assertEquals(manga, storage.repository.getMangaById(manga.id))
            val spaces = storage.driver.executeQuery(
                null,
                "SELECT count(*) FROM sqlite_master WHERE name = 'sync_spaces'",
                { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0))
                },
                0,
            ).value
            assertEquals(0L, spaces)
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

    protected class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        val journal = SyncLocalJournal(handler)
        val repository = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)

        suspend fun connect() = journal.connect(
            "space",
            1,
            SyncRepository("owner", "sync-data", "mihon-sync"),
            "device-a",
            1,
        )

        suspend fun insertManga(url: String): Manga = repository.insertNetworkManga(
            listOf(Manga.create().copy(source = Long.MAX_VALUE - 1, url = url, title = url)),
        ).single()

        override fun close() = driver.close()
    }
}

/** Reconstruct the schema before synchronization when a test starts from the generated current schema. */
internal fun removeSyncJournalSchema(driver: SqlDriver) {
    listOf(
        "sync_runtime_logs", "sync_runtime_runs", "sync_restore_units", "sync_restore_runs",
        "sync_history_watermarks", "sync_history_clears",
        "sync_private_reading", "sync_import_heads", "sync_import_entries", "sync_imports",
        "sync_remote_heads", "sync_remote_objects", "sync_remote_guards",
        "sync_bulk_items", "sync_bulk_jobs", "sync_decisions", "sync_projected_history", "sync_pending_decisions",
        "sync_field_state", "sync_invalid_events", "sync_event_dependencies", "sync_event_fields",
        "sync_descriptions", "sync_inbox_batches",
    )
        .forEach { driver.execute(null, "DROP TABLE $it", 0) }
    listOf("sync_outbox", "sync_object_heads", "sync_events", "sync_batches", "sync_actors", "sync_spaces")
        .forEach { driver.execute(null, "DROP TABLE $it", 0) }
}
