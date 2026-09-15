package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.ArchiveWatchPolicy

/** Shared SQLite contract using each platform's production handler and the actual follow command. */
abstract class SyncCreatorJournalContract {
    protected abstract fun open(): Storage

    @Test
    fun `follow command journals add and remove using only the portable author identity`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val creator = storage.repository.upsertCreator("本机作者显示名", listOf("本机别名"))
            val command = SetCreatorFollow(storage.repository)
            command.await(creator.id, true)
            assertEquals(listOf(creator.id), storage.repository.getFollowedCreators().map { it.creatorId })
            val added = storage.journal.pendingEvents("space", 1).single()
            assertEquals(SyncCategory.FOLLOW, added.category)
            assertEquals(SyncOrigin.USER, added.origin)
            assertEquals(1L, added.seq)
            assertNotNull(added.batchId)
            val effect = added.effects.single()
            assertEquals(SyncObjectKey(SyncObjectType.AUTHOR, portableKey = PORTABLE_KEY), effect.objectKey)
            assertEquals(SyncField.FOLLOWING, effect.field)
            assertEquals(SyncEffectKind.ADD, effect.kind)
            assertTrue(effect.payload.isEmpty())
            command.await(creator.id, false)
            assertTrue(storage.repository.getFollowedCreators().isEmpty())
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(listOf(1L, 2L), events.map { it.seq })
            assertEquals(added, events.first())
            val removed = events.last().effects.single()
            assertEquals(effect.objectKey, removed.objectKey)
            assertEquals(SyncEffectKind.REMOVE, removed.kind)
            assertEquals(listOf(effect.ref(added)), removed.parents)
            assertTrue(removed.payload.isEmpty())
        }
    }

    @Test
    fun `failed follow outbox insert rolls back watch event batch and sequence`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val creator = storage.repository.upsertCreator("Rollback follow")
            storage.failOutbox()
            assertTrue(runCatching { SetCreatorFollow(storage.repository).await(creator.id, true) }.isFailure)
            assertTrue(storage.repository.getFollowedCreators().isEmpty())
            assertEquals(null, storage.repository.getWatchPolicy(creator.id))
            storage.assertEmptyJournal()
        }
    }

    @Test
    fun `failed unfollow outbox insert rolls back enabled state and local policy`() = runBlocking {
        open().use { storage ->
            val creator = storage.repository.upsertCreator("Rollback unfollow")
            val policy = localPolicy(creator.id)
            storage.repository.upsertWatchPolicy(policy, 50)
            storage.connect()
            storage.failOutbox()
            assertTrue(runCatching { SetCreatorFollow(storage.repository).await(creator.id, false) }.isFailure)
            assertEquals(listOf(creator.id), storage.repository.getFollowedCreators().map { it.creatorId })
            assertEquals(policy, storage.repository.getWatchPolicy(creator.id))
            storage.assertEmptyJournal()
        }
    }

    @Test
    fun `unfollow and refollow preserve local scan sources languages and policy`() = runBlocking {
        open().use { storage ->
            val creator = storage.repository.upsertCreator("Local policy")
            val policy = localPolicy(creator.id)
            storage.repository.upsertWatchPolicy(policy, 50)
            storage.connect()
            val command = SetCreatorFollow(storage.repository)
            command.await(creator.id, false)
            assertEquals(policy.copy(enabled = false), storage.repository.getWatchPolicy(creator.id))
            command.await(creator.id, true)
            assertEquals(policy, storage.repository.getWatchPolicy(creator.id))
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(listOf(SyncEffectKind.REMOVE, SyncEffectKind.ADD), events.map { it.effects.single().kind })
            assertTrue(events.all { it.effects.single().payload.isEmpty() })
        }
    }

    @Test
    fun `unconfigured user and default metadata writes remain local`() = runBlocking {
        open().use { storage ->
            val creator = storage.repository.upsertCreator("Local writes")
            SetCreatorFollow(storage.repository).await(creator.id, true)
            assertFalse(storage.repository.getFollowedCreators().isEmpty())
            storage.connect()
            storage.repository.unfollowCreator(creator.id)
            storage.repository.followCreator(creator.id, listOf(23), listOf("ja"))
            storage.repository.updateWatchCheckResult(creator.id, 500, true, null)
            assertEquals(listOf(23L), storage.repository.getFollowedCreators().single().sourceIds)
            storage.assertEmptyJournal()
        }
    }

    @Test
    fun `non user origins and local only writes never enter the outbox`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val creator = storage.repository.upsertCreator("Excluded origins")
            for (context in listOf(
                SyncMutationContext.Metadata,
                SyncMutationContext(SyncOrigin.REMOTE_SYNC),
                SyncMutationContext(SyncOrigin.BACKUP_RESTORE),
                SyncMutationContext(SyncOrigin.MIGRATION),
                SyncMutationContext.LocalOnly,
            )) {
                storage.repository.followCreator(creator.id, syncContext = context)
                assertEquals(listOf(creator.id), storage.repository.getFollowedCreators().map { it.creatorId })
                storage.repository.unfollowCreator(creator.id, syncContext = context)
                assertTrue(storage.repository.getFollowedCreators().isEmpty())
            }
            storage.assertEmptyJournal()
        }
    }

    @Test
    fun `omitted local preferences preserve values while explicit empty lists clear them`() = runBlocking {
        open().use { storage ->
            storage.connect()
            val creator = storage.repository.upsertCreator("Explicit preferences")
            val policy = localPolicy(creator.id)
            storage.repository.upsertWatchPolicy(policy, 50)
            storage.repository.followCreator(creator.id)
            assertEquals(policy, storage.repository.getWatchPolicy(creator.id))
            storage.repository.followCreator(creator.id, sourceIds = emptyList())
            assertEquals(policy.copy(sourceIds = emptySet()), storage.repository.getWatchPolicy(creator.id))
            storage.repository.followCreator(creator.id, languageTags = emptyList())
            assertEquals(
                policy.copy(sourceIds = emptySet(), readingLanguageTags = emptySet()),
                storage.repository.getWatchPolicy(creator.id),
            )
            storage.assertEmptyJournal()
        }
    }

    protected fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    protected class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        val journal = SyncLocalJournal(handler)
        val repository = CreatorRepositoryImpl(handler, clock = { 100 }, portableKeyFactory = { PORTABLE_KEY })

        suspend fun connect() = journal.connect(
            "space",
            1,
            SyncRepository("owner", "sync-data", "mihon-sync"),
            "device-a",
            1,
        )

        fun failOutbox() {
            driver.execute(
                null,
                "CREATE TRIGGER fail_creator_sync BEFORE INSERT ON sync_outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic creator outbox failure'); END;",
                0,
            )
        }

        suspend fun assertEmptyJournal() = handler.await {
            assertEquals(0L, sync_journalQueries.countEvents().executeAsOne())
            assertEquals(0L, sync_journalQueries.countBatches().executeAsOne())
            assertEquals(1L, sync_journalQueries.getActiveActor().executeAsOne().next_seq)
        }

        override fun close() = driver.close()
    }

    private fun localPolicy(creatorId: Long) = ArchiveWatchPolicy(
        creatorId = creatorId,
        enabled = true,
        periodMillis = 123_456,
        sourceIds = setOf(17, Long.MAX_VALUE - 1),
        readingLanguageTags = setOf("ja", "zh-hans"),
        includeProbable = true,
        includeUnknown = true,
        notifyProbable = true,
        notifyUnknown = false,
    )

    private companion object {
        const val PORTABLE_KEY = "4f0a61b2-8d33-4b63-87ce-fd1f1ae5e8de"
    }
}
