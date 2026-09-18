package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncReceiverDecisionResult
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
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.AddCreatorAliasesRequest
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.SetCreatorDisplayNameRequest

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
            val batch = requireNotNull(SyncOutboxStore(storage.handler).nextBatch("space", 1))
            assertEquals(listOf(SyncObjectDescriptor(effect.objectKey, "本机作者显示名")), batch.objects)
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

    @Test
    fun `consumed old key add cannot revive a later cancellation but a new add can`() = runBlocking {
        open().use { first ->
            open().use { second ->
                open().use { receiver ->
                    first.connect("first")
                    second.connect("second")
                    receiver.connect("receiver")
                    val a = first.repository.upsertCreator("Exact author")
                    val b = second.repository.upsertCreator("Exact author")
                    first.repository.followCreator(a.id, syncContext = SyncMutationContext.User)
                    second.repository.followCreator(b.id, syncContext = SyncMutationContext.User)
                    val firstBatch = first.transferTo(receiver)
                    val secondBatch = second.transferTo(receiver)
                    val root = receiver.repository.getFollowedCreators().single().creatorId
                    receiver.repository.unfollowCreator(root, syncContext = SyncMutationContext.User)
                    receiver.projectAll()
                    assertTrue(receiver.repository.getFollowedCreators().isEmpty())
                    // Durable field replay must not reapply an already consumed ADD through another key.
                    val oldKey = secondBatch.events.single().effects.single().objectKey
                    receiver.handler.await {
                        sync_inboxQueries.markFieldDirty(
                            "space",
                            1,
                            oldKey.stableKey,
                            "FOLLOWING",
                            kotlinx.serialization.json.Json.encodeToString(oldKey),
                        )
                    }
                    receiver.projectAll()
                    assertTrue(
                        receiver.repository.getFollowedCreators().isEmpty(),
                        "consumed alias ADD revived cancellation",
                    )
                    assertTrue(receiver.inbox.ingest(firstBatch).duplicate)
                    assertTrue(receiver.inbox.ingest(secondBatch).duplicate)
                    second.repository.unfollowCreator(b.id, syncContext = SyncMutationContext.User)
                    second.transferTo(receiver)
                    second.repository.followCreator(b.id, syncContext = SyncMutationContext.User)
                    second.transferTo(receiver)
                    assertEquals(listOf(root), receiver.repository.getFollowedCreators().map { it.creatorId })
                }
            }
        }
    }

    @Test
    fun `concurrent remove on consumed alias cannot revive cancellation but fresh add still wins`() = runBlocking {
        open().use { sender ->
            open().use { branch ->
                open().use { receiver ->
                    sender.connect("sender")
                    receiver.connect("receiver")
                    val local = receiver.repository.upsertCreator("Shared exact name")
                    val remote = sender.repository.upsertCreator("Shared exact name")
                    sender.repository.followCreator(remote.id, syncContext = SyncMutationContext.User)
                    val add = sender.transferTo(receiver)
                    val oldKey = add.events.single().effects.single().objectKey
                    // A restored portable key can be present on a device that never received this ADD.
                    branch.connect("independent", requireNotNull(oldKey.portableKey))
                    val restored = branch.repository.upsertCreator("Shared exact name")
                    branch.repository.unfollowCreator(restored.id, syncContext = SyncMutationContext.User)
                    val remove = branch.takeBatch()
                    assertTrue(remove.events.single().effects.single().parents.isEmpty())
                    assertEquals(oldKey, remove.events.single().effects.single().objectKey)
                    receiver.repository.unfollowCreator(local.id, syncContext = SyncMutationContext.User)
                    receiver.projectAll()
                    assertTrue(receiver.repository.getFollowedCreators().isEmpty())
                    receiver.receive(remove)
                    assertTrue(
                        receiver.repository.getFollowedCreators().isEmpty(),
                        "new REMOVE changed heads but must not replay the consumed ADD",
                    )
                    assertTrue(receiver.projector.pending("space", 1).isEmpty())
                    sender.repository.followCreator(remote.id, syncContext = SyncMutationContext.User)
                    sender.transferTo(receiver)
                    assertEquals(listOf(local.id), receiver.repository.getFollowedCreators().map { it.creatorId })
                    // Existing add-wins remains valid for a receiver which has not consumed the ADD.
                    open().use { fresh ->
                        fresh.connect("fresh")
                        fresh.receive(remove)
                        fresh.receive(add)
                        assertEquals(1, fresh.repository.getFollowedCreators().size)
                    }
                }
            }
        }
    }

    @Test
    fun `out of order journal ancestry settles once without duplicate decisions`() = runBlocking {
        open().use { sender ->
            open().use { receiver ->
                sender.connect("sender")
                receiver.connect("receiver")
                val author = sender.repository.upsertCreator("Ordered author")
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                val add = sender.takeBatch()
                sender.repository.unfollowCreator(author.id, syncContext = SyncMutationContext.User)
                val remove = sender.takeBatch()
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                val readd = sender.takeBatch()
                receiver.receive(readd)
                assertEquals("DEPENDENCY", receiver.state(readd))
                assertTrue(receiver.repository.getFollowedCreators().isEmpty())
                receiver.receive(remove)
                receiver.receive(add)
                assertEquals(1, receiver.repository.getFollowedCreators().size)
                assertTrue(receiver.projector.pending("space", 1).isEmpty())
                listOf(remove, add, readd).forEach { assertTrue(receiver.inbox.ingest(it).duplicate) }
                receiver.projectAll()
                assertEquals(1, receiver.repository.getFollowedCreators().size)
            }
        }
    }

    @Test
    fun `legacy descriptor without name waits and later trusted description completes identity`() = runBlocking {
        open().use { sender ->
            open().use { receiver ->
                sender.connect("sender")
                receiver.connect("receiver")
                val author = sender.repository.upsertCreator("Exact descriptive name")
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                val missing = sender.takeBatch().copy(objects = emptyList())
                assertEquals(1, missing.protocolVersion)
                receiver.receive(missing)
                assertEquals("DESCRIPTION", receiver.state(missing))
                assertTrue(receiver.repository.getFollowedCreators().isEmpty())
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                val root = receiver.repository.getFollowedCreators().single().creatorId
                assertEquals(listOf("Exact descriptive name"), receiver.repository.getIdentitySnapshot(root).names)
                assertTrue(receiver.journal.pendingEvents("space", 1).isEmpty(), "remote application must not echo")
            }
        }
    }

    @Test
    fun `known key renamed descriptor neither changes local primary nor merges another identity`() = runBlocking {
        open().use { sender ->
            open().use { receiver ->
                sender.connect("sender")
                receiver.connect("receiver")
                val author = sender.repository.upsertCreator("Original name", listOf("Different name"))
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                val root = receiver.repository.getFollowedCreators().single().creatorId
                val unrelated = receiver.repository.upsertCreator("Different name")
                val snapshot = sender.repository.getIdentitySnapshot(author.id)
                sender.repository.setCreatorDisplayName(
                    SetCreatorDisplayNameRequest(author.id, snapshot.revision, "Different name", "rename"),
                )
                sender.repository.unfollowCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                val pending = receiver.projector.pending("space", 1).single()
                assertEquals(
                    SyncReceiverDecisionResult.APPLIED,
                    receiver.projector.decide("space", 1, pending, SyncCancellationDecision.CONFIRM),
                )
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                assertEquals(listOf(root), receiver.repository.getFollowedCreators().map { it.creatorId })
                assertEquals("Original name", receiver.repository.getIdentitySnapshot(root).displayName)
                assertEquals(listOf("Original name"), receiver.repository.getIdentitySnapshot(root).names)
                assertEquals(unrelated.id, receiver.repository.getIdentitySnapshot(unrelated.id).id)
            }
        }
    }

    @Test
    fun `local alias editing while disconnected preserves old key routing on reconnect`() = runBlocking {
        open().use { sender ->
            open().use { receiver ->
                sender.connect("sender")
                receiver.connect("receiver")
                val author = sender.repository.upsertCreator("Remote name")
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                val first = sender.transferTo(receiver)
                val old = receiver.repository.getFollowedCreators().single().creatorId
                val local = receiver.repository.upsertCreator("Local primary")
                receiver.journal.disconnect("space", 1)
                val target = receiver.repository.getIdentitySnapshot(local.id)
                val source = receiver.repository.getIdentitySnapshot(old)
                val merged = receiver.repository.addCreatorAliases(
                    AddCreatorAliasesRequest(
                        target.id,
                        target.revision,
                        mapOf(source.id to source.revision),
                        "merge-off",
                    ),
                )
                receiver.repository.setCreatorDisplayName(
                    SetCreatorDisplayNameRequest(merged.id, merged.revision, "Remote name", "primary-off"),
                )
                assertTrue(receiver.journal.pendingEvents("space", 1).isEmpty(), "name edits are device local")
                sender.repository.unfollowCreator(author.id, syncContext = SyncMutationContext.User)
                val remove = sender.takeBatch()
                assertFalse(receiver.inbox.ingest(remove).accepted)
                receiver.connect("receiver")
                receiver.receive(remove)
                val pending = receiver.projector.pending("space", 1).single()
                assertEquals(
                    SyncReceiverDecisionResult.APPLIED,
                    receiver.projector.decide("space", 1, pending, SyncCancellationDecision.CONFIRM),
                )
                assertTrue(receiver.repository.getFollowedCreators().isEmpty())
                receiver.replay(first)
                assertTrue(receiver.repository.getFollowedCreators().isEmpty())
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                assertEquals(listOf(local.id), receiver.repository.getFollowedCreators().map { it.creatorId })
                assertEquals(local.id, receiver.repository.getIdentitySnapshot(old).id)
            }
        }
    }

    @Test
    fun `pending old identity cancellation is invalidated by local intent after real merge`() = runBlocking {
        open().use { sender ->
            open().use { receiver ->
                sender.connect("sender")
                receiver.connect("receiver")
                val author = sender.repository.upsertCreator("Old identity")
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                val old = receiver.repository.getFollowedCreators().single().creatorId
                sender.repository.unfollowCreator(author.id, syncContext = SyncMutationContext.User)
                val remove = sender.transferTo(receiver)
                val pending = receiver.projector.pending("space", 1).single()
                val job = receiver.projector.startBulk("space", 1, SyncCancellationDecision.CONFIRM)
                val target = receiver.repository.upsertCreator("New root")
                receiver.repository.mergeCreatorIdentities(old, target.id)
                receiver.repository.followCreator(target.id, syncContext = SyncMutationContext.User)
                assertEquals(
                    SyncReceiverDecisionResult.INVALIDATED,
                    receiver.projector.decide("space", 1, pending, SyncCancellationDecision.CONFIRM),
                )
                receiver.replay(remove)
                assertTrue(receiver.projector.pending("space", 1).isEmpty())
                assertEquals(1L, receiver.projector.processBulk(job).outcomes["INVALIDATED"])
                assertEquals(listOf(target.id), receiver.repository.getFollowedCreators().map { it.creatorId })
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                sender.repository.unfollowCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                assertEquals(1, receiver.projector.pending("space", 1).size)
            }
        }
    }

    @Test
    fun `cyclic old key identity waits without blocking another journal author`() = runBlocking {
        open().use { sender ->
            open().use { receiver ->
                sender.connect("sender")
                receiver.connect("receiver")
                val author = sender.repository.upsertCreator("Broken identity")
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                sender.transferTo(receiver)
                val broken = receiver.repository.getFollowedCreators().single().creatorId
                receiver.driver.execute(
                    null,
                    "UPDATE author_archive_creators SET status='MERGED', merged_into_creator_id=_id WHERE _id=$broken",
                    0,
                )
                sender.repository.followCreator(author.id, syncContext = SyncMutationContext.User)
                val retry = sender.transferTo(receiver)
                assertEquals("IDENTITY", receiver.state(retry))
                val other = sender.repository.upsertCreator("Healthy identity")
                sender.repository.followCreator(other.id, syncContext = SyncMutationContext.User)
                val healthy = sender.transferTo(receiver)
                assertEquals("APPLIED", receiver.state(healthy))
                assertEquals(true, receiver.writer.localMembership(healthy.events.single().effects.single().objectKey))
                assertEquals("IDENTITY", receiver.state(retry))
            }
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
        private var keyPrefix = PORTABLE_KEY
        private var keySequence = 0
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val repository = CreatorRepositoryImpl(
            handler,
            bootstrap = bootstrap,
            clock = { 100 },
            portableKeyFactory = { if (keySequence++ == 0) keyPrefix else "$keyPrefix-$keySequence" },
        )
        val inbox = SyncInboxStore(handler)
        val writer = SyncRemoteProjectionWriter(handler, repository, repository, bootstrap, { true })
        val projector = SyncInboxProjector(handler, writer)

        suspend fun projectAll() {
            repeat(30) { if (projector.project("space", 1) == 0) return }
            error("dirty fields did not settle")
        }

        suspend fun receive(batch: SyncBatch) {
            assertTrue(inbox.ingest(batch).accepted)
            projectAll()
        }

        suspend fun transferTo(receiver: Storage): SyncBatch = takeBatch().also { receiver.receive(it) }

        suspend fun takeBatch(): SyncBatch {
            val batch = requireNotNull(SyncOutboxStore(handler).nextBatch("space", 1))
            // Transport acknowledgement is outside this journal/inbox/projection contract.
            handler.await {
                sync_journalQueries.markOutboxPublished("space", 1, batch.batchId)
                sync_journalQueries.markBatchPublished("space", 1, batch.batchId)
            }
            return batch
        }

        suspend fun state(batch: SyncBatch): String = handler.await {
            val key = batch.events.single().effects.single().objectKey
            sync_inboxQueries.getFieldState("space", 1, key.stableKey, "FOLLOWING").executeAsOne().status
        }

        suspend fun replay(batch: SyncBatch) {
            val key = batch.events.single().effects.single().objectKey
            handler.await {
                sync_inboxQueries.markFieldDirty(
                    "space",
                    1,
                    key.stableKey,
                    "FOLLOWING",
                    kotlinx.serialization.json.Json.encodeToString(key),
                )
            }
            projectAll()
        }

        suspend fun connect(actor: String = "device-a", restoredKey: String? = null) {
            keyPrefix = restoredKey ?: if (actor == "device-a") PORTABLE_KEY else "$PORTABLE_KEY-$actor"
            journal.connect(
                "space",
                1,
                SyncRepository("owner", "sync-data", "mihon-sync"),
                actor,
                1,
            )
        }

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
