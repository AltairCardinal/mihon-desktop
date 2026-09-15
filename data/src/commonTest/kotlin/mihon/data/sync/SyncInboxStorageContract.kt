package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.data.sync.inbox.SyncInboxExchange
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxExchange
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
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
import mihon.domain.sync.SyncReceiverDecisionResult
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DatabaseMigration
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
import java.io.File

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SyncInboxStorageContract {
    protected abstract fun open(path: String? = null, create: Boolean = true): Storage
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    private val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })
    private val manga = SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = "/manga")

    @Test
    fun `authenticated Git receipt survives restart without upload echo`() = runBlocking {
        val file = File.createTempFile("mihon-sync-inbox-", ".db")
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val service = SyncBatchSyncService(transport, secret)
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val batch = batch()
                val result = service.upload(
                    repository,
                    snapshot,
                    batch,
                    ".mihon-sync/batches/device-a/1/${batch.batchId}.json",
                    persist = {},
                )
                assertEquals(SyncPublishStatus.PUBLISHED, result.publish.status)
                val published = transport.readSnapshot(repository, "space", 1).getOrThrow()
                open(file.absolutePath).use { storage ->
                    storage.connect(repository)
                    val receiver = SyncInboxExchange(storage.inbox, service)
                    assertTrue(receiver.receive(published, published.batches.single()).accepted)
                    assertEquals(1, storage.inbox.status("space", 1).receivedBatches)
                    assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
                }
                open(file.absolutePath, false).use { storage ->
                    storage.connect(repository)
                    assertEquals(1, storage.inbox.status("space", 1).receivedBatches)
                    val replay = SyncInboxExchange(storage.inbox, service)
                        .receive(published, published.batches.single())
                    assertTrue(replay.accepted && replay.duplicate)
                    assertEquals(1, storage.inbox.status("space", 1).receivedBatches)
                    assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `invalid scope and incomplete event sequence cannot enter the received cursor`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            assertFalse(storage.inbox.ingest(batch().copy(spaceId = "other")).accepted)
            assertFalse(storage.inbox.ingest(batch(1, 3)).accepted)
            assertEquals(0, storage.inbox.status("space", 1).receivedBatches)
            assertTrue(storage.inbox.ingest(batch(2, 3)).accepted)
            assertEquals(1, storage.inbox.status("space", 1).receivedBatches)
        }
    }

    @Test
    fun `changed event identity is quarantined without replacing accepted data`() = runBlocking {
        open().use { storage ->
            storage.connect(repository)
            assertTrue(storage.inbox.ingest(batch()).accepted)
            val changed = batch().let {
                it.copy(
                    events = it.events.map { event ->
                        event.copy(effects = event.effects.map { effect -> effect.copy(kind = SyncEffectKind.REMOVE) })
                    },
                )
            }
            assertFalse(storage.inbox.ingest(changed).accepted)
            assertEquals(1, storage.inbox.status("space", 1).receivedBatches)
            assertEquals(1, storage.inbox.status("space", 1).rejectedBatches)
            assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    private fun batch(vararg seqs: Long = longArrayOf(1)): SyncBatch = SyncBatch(
        1,
        "space",
        1,
        "incoming",
        seqs.map { seq ->
            SyncEventEnvelope(
                1, "space", 1, "device-a", 1, seq, SyncCategory.FAVORITE,
                listOf(SyncEffect("favorite", manga, SyncField.FAVORITE, SyncEffectKind.ADD)),
                SyncOrigin.USER, batchId = "incoming",
            )
        },
        listOf(SyncObjectDescriptor(manga, "星海")),
    )

    private fun membership(
        seq: Long,
        kind: SyncEffectKind,
        parent: SyncEventEnvelope? = null,
        key: SyncObjectKey = manga,
        actor: String = "device-a",
    ): SyncBatch {
        val event = SyncEventEnvelope(
            1, "space", 1, actor, 1, seq, SyncCategory.FAVORITE,
            listOf(
                SyncEffect(
                    "favorite",
                    key,
                    SyncField.FAVORITE,
                    kind,
                    parent?.let { listOf(it.ref("favorite")) } ?: emptyList(),
                ),
            ),
            SyncOrigin.USER, batchId = "$actor-$seq",
        )
        return SyncBatch(
            1,
            "space",
            1,
            requireNotNull(event.batchId),
            listOf(event),
            listOf(SyncObjectDescriptor(key, "星海 $seq")),
        )
    }

    @Test
    fun `remote cancellation requires each receiver decision and keep survives restart`() = runBlocking {
        val file = File.createTempFile("mihon-sync-decision-", ".db")
        val add = membership(1, SyncEffectKind.ADD)
        val remove = membership(2, SyncEffectKind.REMOVE, add.events.single())
        try {
            open(file.absolutePath).use { s ->
                s.connect(repository)
                assertTrue(s.inbox.ingest(add).accepted)
                s.projectAll()
                assertEquals(true, s.writer.localMembership(manga))
                assertTrue(s.inbox.ingest(remove).accepted)
                s.projectAll()
                assertEquals(true, s.writer.localMembership(manga))
                val pending = s.projector.pending("space", 1).single()
                assertEquals(
                    SyncReceiverDecisionResult.KEPT_LOCAL,
                    s.projector.decide("space", 1, pending, SyncCancellationDecision.KEEP_LOCAL),
                )
                assertEquals(0, s.inbox.status("space", 1).pendingDecisions)
                assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            }
            open(file.absolutePath, false).use { s ->
                s.connect(repository)
                s.inbox.ingest(remove)
                s.projectAll()
                assertTrue(s.projector.pending("space", 1).isEmpty())
                assertEquals(true, s.writer.localMembership(manga))
            }
            open().use { s ->
                s.connect(repository)
                s.inbox.ingest(add)
                s.projectAll()
                s.inbox.ingest(remove)
                s.projectAll()
                val pending = s.projector.pending("space", 1).single()
                assertEquals(
                    SyncReceiverDecisionResult.APPLIED,
                    s.projector.decide("space", 1, pending, SyncCancellationDecision.CONFIRM),
                )
                assertEquals(false, s.writer.localMembership(manga))
                assertEquals(
                    SyncReceiverDecisionResult.APPLIED,
                    s.projector.decide("space", 1, pending, SyncCancellationDecision.CONFIRM),
                )
                assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `missing parent waits and later add invalidates an already displayed cancellation`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val add = membership(1, SyncEffectKind.ADD)
            val remove = membership(2, SyncEffectKind.REMOVE, add.events.single())
            s.inbox.ingest(remove)
            s.projectAll()
            assertEquals(null, s.writer.localMembership(manga))
            assertTrue(s.projector.pending("space", 1).isEmpty())
            s.inbox.ingest(add)
            s.projectAll()
            assertTrue(s.projector.pending("space", 1).isEmpty())
            // An absent library item never needs a cancellation decision.
            s.writer.applyMembership(manga, true) { SyncObjectDescriptor(it, "本地收藏") }
            val remove2 = membership(3, SyncEffectKind.REMOVE, remove.events.single())
            s.inbox.ingest(remove2)
            s.projectAll()
            val displayed = s.projector.pending("space", 1).single()
            val concurrent = membership(1, SyncEffectKind.ADD, actor = "device-c")
            s.inbox.ingest(concurrent)
            assertEquals(
                SyncReceiverDecisionResult.INVALIDATED,
                s.projector.decide("space", 1, displayed, SyncCancellationDecision.CONFIRM),
            )
            s.projectAll()
            assertEquals(true, s.writer.localMembership(manga))
            assertTrue(s.projector.pending("space", 1).isEmpty())
        }
    }

    @Test
    fun `unavailable source remains in inbox and retries without upload echo`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            s.sources.clear()
            s.inbox.ingest(membership(1, SyncEffectKind.ADD))
            s.projectAll()
            assertEquals(null, s.writer.localMembership(manga))
            assertEquals(1, s.inbox.status("space", 1).receivedBatches)
            s.sources += 42L
            s.projector.retryUnavailable("space", 1)
            s.projectAll()
            assertEquals(true, s.writer.localMembership(manga))
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `local explicit action clears pending and remains uploadable`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val add = membership(1, SyncEffectKind.ADD)
            s.inbox.ingest(add)
            s.projectAll()
            s.inbox.ingest(membership(2, SyncEffectKind.REMOVE, add.events.single()))
            s.projectAll()
            val displayed = s.projector.pending("space", 1).single()
            val local = requireNotNull(s.mangas.getMangaByUrlAndSourceId("/manga", 42))
            assertTrue(s.mangas.update(MangaUpdate(local.id, favorite = true, syncContext = SyncMutationContext.User)))
            assertEquals(0, s.inbox.status("space", 1).pendingDecisions)
            assertEquals(1, s.journal.pendingEvents("space", 1).size)
            assertEquals(
                SyncReceiverDecisionResult.INVALIDATED,
                s.projector.decide("space", 1, displayed, SyncCancellationDecision.CONFIRM),
            )
            assertEquals(true, s.writer.localMembership(manga))
        }
    }

    @Test
    fun `bulk freezes selection excludes arrivals and resumes after restart`() = runBlocking {
        val file = File.createTempFile("mihon-sync-bulk-", ".db")
        var job = ""
        try {
            open(file.absolutePath).use { s ->
                s.connect(repository)
                for (i in 1..6) {
                    val key = manga.copy(originalUrl = "/manga-$i")
                    val add = membership(i * 2L, SyncEffectKind.ADD, key = key)
                    s.inbox.ingest(add)
                    s.projectAll()
                    s.inbox.ingest(membership(i * 2L + 1, SyncEffectKind.REMOVE, add.events.single(), key))
                    s.projectAll()
                }
                val items = s.projector.pending("space", 1)
                assertEquals(6, items.size)
                job = s.projector.startBulk("space", 1, SyncCancellationDecision.CONFIRM, items.take(5).map { it.id })
                val progress = s.projector.processBulk(job, 2)
                assertEquals(5, progress.total)
                assertEquals(3, progress.queued)
                val key = items[3].objectKey
                s.inbox.ingest(membership(1, SyncEffectKind.ADD, key = key, actor = "device-c"))
                val laterKey = manga.copy(originalUrl = "/new")
                val add = membership(100, SyncEffectKind.ADD, key = laterKey)
                s.inbox.ingest(add)
                s.projectAll()
                s.inbox.ingest(membership(101, SyncEffectKind.REMOVE, add.events.single(), laterKey))
                s.projectAll()
            }
            open(file.absolutePath, false).use { s ->
                s.connect(repository)
                val progress = s.projector.processBulk(job)
                assertEquals(0, progress.queued)
                assertEquals(4, progress.outcomes["APPLIED"])
                assertEquals(1, progress.outcomes["INVALIDATED"])
                assertEquals(2, s.inbox.status("space", 1).pendingDecisions)
                assertEquals(progress, s.projector.processBulk(job))
                val all = s.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
                assertEquals(2, s.projector.processBulk(all).outcomes["KEPT_LOCAL"])
                assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `reading reconstructs chapter history and causal resume may move backward`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val chapter1 = SyncObjectKey(SyncObjectType.CHAPTER, "42", originalUrl = "/ch:一", parentUrl = "/manga")
            val chapter2 = chapter1.copy(originalUrl = "/ch:二")
            val first = reading(1, chapter1, 12)
            val second = reading(2, chapter2, 8, first.events.single())
            assertTrue(s.inbox.ingest(second).accepted)
            assertTrue(s.inbox.ingest(first).accepted)
            s.projectAll()
            assertEquals(
                setOf(1000L, 2000L),
                s.handler.await {
                    listOf(chapter1, chapter2).map { key ->
                        val chapter = sync_projectionQueries.getChapterByIdentity(key.originalUrl!!, "/manga", 42)
                            .executeAsOne()
                        assertTrue(chapter.read)
                        sync_projectionQueries.getChapterHistory(chapter._id).executeAsOne().last_read!!.time
                    }.toSet()
                },
            )
            val back = reading(3, chapter2, 2, second.events.single(), objects = false)
            s.sources.clear()
            assertTrue(s.inbox.ingest(back).accepted)
            s.projectAll()
            s.handler.await {
                val chapter = sync_projectionQueries.getChapterByIdentity(chapter2.originalUrl!!, "/manga", 42)
                    .executeAsOne()
                assertEquals(2L, chapter.last_page_read)
                assertEquals(
                    3000L,
                    sync_projectionQueries.getChapterHistory(chapter._id).executeAsOne().last_read!!.time,
                )
                assertEquals(0L, historyQueries.getReadDuration().executeAsOne())
            }
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `bulk records isolated failure without partial cancel and continues remaining items`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            for (i in 1..2) {
                val key = manga.copy(originalUrl = "/fault-$i")
                val add = membership(i * 2L, SyncEffectKind.ADD, key = key)
                s.inbox.ingest(add)
                s.projectAll()
                s.inbox.ingest(membership(i * 2L + 1, SyncEffectKind.REMOVE, add.events.single(), key))
                s.projectAll()
            }
            val first = s.projector.pending("space", 1).first()
            s.driver.execute(
                null,
                "CREATE TRIGGER fail_decision BEFORE INSERT ON sync_decisions " +
                    "WHEN NEW.binding = '${first.binding.replace("'", "''")}' " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic decision failure'); END",
                0,
            )
            val job = s.projector.startBulk("space", 1, SyncCancellationDecision.CONFIRM)
            val progress = s.projector.processBulk(job)
            assertEquals(1L, progress.outcomes["FAILED"])
            assertEquals(1L, progress.outcomes["APPLIED"])
            assertEquals(true, s.writer.localMembership(first.objectKey))
            assertEquals(1L, s.inbox.status("space", 1).pendingDecisions)
            s.driver.execute(null, "DROP TRIGGER fail_decision", 0)
            val retry = s.projector.startBulk("space", 1, SyncCancellationDecision.CONFIRM)
            assertEquals(1L, s.projector.processBulk(retry).outcomes["APPLIED"])
            assertEquals(false, s.writer.localMembership(first.objectKey))
        }
    }

    private fun reading(
        seq: Long,
        chapter: SyncObjectKey,
        page: Int,
        parent: SyncEventEnvelope? = null,
        objects: Boolean = true,
    ): SyncBatch {
        val effects = listOf(
            SyncEffect(
                "read",
                chapter,
                SyncField.READ_STATUS,
                SyncEffectKind.MARK_READ,
                parents = parent?.effects?.filter { it.objectKey == chapter && it.field == SyncField.READ_STATUS }
                    ?.map { it.ref(parent) } ?: emptyList(),
            ),
            SyncEffect(
                "resume",
                manga,
                SyncField.RESUME_POSITION,
                SyncEffectKind.RESUME_POSITION,
                parents = parent?.let { listOf(it.ref("resume")) } ?: emptyList(),
                payload = buildJsonObject {
                    put("chapterKey", chapter.stableKey)
                    put("pageIndex", page)
                },
            ),
            SyncEffect(
                "summary",
                manga,
                SyncField.READING_SUMMARY,
                SyncEffectKind.READING_SUMMARY,
                parents = parent?.let { listOf(it.ref("summary")) } ?: emptyList(),
                payload = buildJsonObject {
                    put("chapterKey", chapter.stableKey)
                    put("readAt", seq * 1000)
                },
            ),
        )
        val event = SyncEventEnvelope(
            1, "space", 1, "reader", 1, seq, SyncCategory.READING,
            effects, SyncOrigin.USER, occurredAt = seq * 1000, batchId = "reading-$seq",
        )
        return SyncBatch(
            1,
            "space",
            1,
            requireNotNull(event.batchId),
            listOf(event),
            if (objects) {
                listOf(SyncObjectDescriptor(manga, "星海"), SyncObjectDescriptor(chapter, "第 $seq 章"))
            } else {
                emptyList()
            },
        )
    }

    @Test
    fun `three databases exchange through encrypted Git without cancellation echo`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            open().use { a ->
                open().use { b ->
                    open().use { c ->
                        a.journal.connect("space", 1, repository, "device-a", 1)
                        b.journal.connect("space", 1, repository, "device-b", 1)
                        c.journal.connect("space", 1, repository, "device-c", 1)
                        val transport = git.transport()
                        transport.initialize(repository, "space", 1)
                        val service = SyncBatchSyncService(transport, secret)
                        suspend fun upload(s: Storage) {
                            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                            val sender = SyncOutboxExchange(SyncOutboxStore(s.handler), service)
                            assertEquals(SyncPublishStatus.PUBLISHED, sender.uploadNext(snapshot)?.publish?.status)
                        }
                        suspend fun receive(s: Storage) {
                            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                            snapshot.batches.forEach {
                                assertTrue(
                                    SyncInboxExchange(s.inbox, service).receive(snapshot, it).accepted,
                                )
                            }
                            s.projectAll()
                        }
                        val local = a.mangas.insertNetworkManga(
                            listOf(Manga.create().copy(source = 42, url = "/manga", title = "星海")),
                        ).single()
                        assertTrue(
                            a.mangas.update(
                                MangaUpdate(local.id, favorite = true, syncContext = SyncMutationContext.User),
                            ),
                        )
                        upload(a)
                        receive(b)
                        receive(c)
                        assertEquals(true, b.writer.localMembership(manga))
                        assertEquals(true, c.writer.localMembership(manga))
                        assertTrue(
                            a.mangas.update(
                                MangaUpdate(local.id, favorite = false, syncContext = SyncMutationContext.User),
                            ),
                        )
                        upload(a)
                        receive(b)
                        receive(c)
                        assertEquals(
                            SyncReceiverDecisionResult.APPLIED,
                            b.projector.decide(
                                "space",
                                1,
                                b.projector.pending("space", 1).single(),
                                SyncCancellationDecision.CONFIRM,
                            ),
                        )
                        assertEquals(
                            SyncReceiverDecisionResult.KEPT_LOCAL,
                            c.projector.decide(
                                "space",
                                1,
                                c.projector.pending("space", 1).single(),
                                SyncCancellationDecision.KEEP_LOCAL,
                            ),
                        )
                        receive(a)
                        receive(b)
                        receive(c)
                        assertEquals(false, a.writer.localMembership(manga))
                        assertEquals(false, b.writer.localMembership(manga))
                        assertEquals(true, c.writer.localMembership(manga))
                        listOf(a, b, c).forEach {
                            assertTrue(it.journal.pendingEvents("space", 1).isEmpty())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `one hundred twenty pending items page and finish in bounded chunks`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            for (i in 1..120) {
                val key = manga.copy(originalUrl = "/many-$i")
                val add = membership(i * 2L, SyncEffectKind.ADD, key = key)
                s.inbox.ingest(add)
                s.projectAll()
                s.inbox.ingest(membership(i * 2L + 1, SyncEffectKind.REMOVE, add.events.single(), key))
            }
            s.projectAll()
            assertEquals(120L, s.inbox.status("space", 1).pendingDecisions)
            assertEquals(100, s.projector.pending("space", 1).size)
            assertEquals(20, s.projector.pending("space", 1, offset = 100).size)
            val job = s.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
            assertEquals(70L, s.projector.processBulk(job).queued)
            assertEquals(20L, s.projector.processBulk(job).queued)
            assertEquals(0L, s.projector.processBulk(job).queued)
            assertEquals(120L, s.projector.processBulk(job).outcomes["KEPT_LOCAL"])
            assertTrue(s.projector.pending("space", 1).isEmpty())
        }
    }

    @Test
    fun `receipt failure rolls back events descriptors and cursor together`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            s.driver.execute(
                null,
                "CREATE TRIGGER fail_receipt BEFORE INSERT ON sync_inbox_batches " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic receipt failure'); END",
                0,
            )
            assertTrue(runCatching { s.inbox.ingest(membership(1, SyncEffectKind.ADD)) }.isFailure)
            assertEquals(0L, s.inbox.status("space", 1).receivedBatches)
            assertEquals(0L, s.handler.await { sync_journalQueries.countEvents().executeAsOne() })
            assertEquals(
                null,
                s.handler.await {
                    sync_inboxQueries.getDescription("space", 1, manga.stableKey).executeAsOneOrNull()
                },
            )
            s.driver.execute(null, "DROP TRIGGER fail_receipt", 0)
            s.inbox.ingest(membership(1, SyncEffectKind.ADD))
            s.projectAll()
            assertEquals(true, s.writer.localMembership(manga))
        }
    }

    @Test
    fun `migration indexes existing local envelopes and preserves their pending upload`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val local = s.mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/manga", title = "旧收藏")),
            ).single()
            assertTrue(s.mangas.update(MangaUpdate(local.id, favorite = true, syncContext = SyncMutationContext.User)))
            val original = s.journal.pendingEvents("space", 1).single()
            listOf(
                "sync_bulk_items", "sync_bulk_jobs", "sync_decisions", "sync_projected_history",
                "sync_pending_decisions", "sync_field_state", "sync_invalid_events", "sync_event_dependencies",
                "sync_event_fields", "sync_descriptions", "sync_inbox_batches",
            ).forEach { s.driver.execute(null, "DROP TABLE $it", 0) }
            s.driver.execute(null, "DROP INDEX sync_event_key_index", 0)
            s.driver.execute(null, "DROP INDEX sync_unindexed_events", 0)
            s.driver.execute(null, "ALTER TABLE sync_events DROP COLUMN event_key", 0)
            s.driver.execute(null, "ALTER TABLE sync_events DROP COLUMN sync_indexed", 0)
            s.driver.execute(null, "PRAGMA user_version = 22", 0)
            DatabaseMigration.migrateAtomically(s.driver, 22, 23)
            assertEquals(listOf(original), s.journal.pendingEvents("space", 1))
            val incoming = membership(1, SyncEffectKind.REMOVE, original, actor = "device-c")
            assertTrue(s.inbox.ingest(incoming).accepted)
            s.projectAll()
            assertEquals(1L, s.inbox.status("space", 1).pendingDecisions)
            assertEquals(listOf(original), s.journal.pendingEvents("space", 1))
            assertTrue(s.mangas.update(MangaUpdate(local.id, favorite = true, syncContext = SyncMutationContext.User)))
            assertEquals(listOf(1L, 2L), s.journal.pendingEvents("space", 1).map { it.seq })
        }
    }

    @Test
    fun `merged author local intent invalidates alias decision across restart and bulk`() = runBlocking {
        val file = File.createTempFile("mihon-sync-alias-", ".db")
        val a = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = "author-a")
        val b = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = "author-b")
        var job = ""
        try {
            open(file.absolutePath).use { s ->
                s.connect(repository)
                s.writer.prepare()
                listOf(a, b).forEach { key -> s.writer.applyMembership(key, true) { SyncObjectDescriptor(it, "作者") } }
                val ids = s.handler.await {
                    listOf(a, b).map {
                        author_archiveQueries.getArchiveCreatorIdByPortableKey(it.portableKey!!).executeAsOne()
                    }
                }
                s.driver.execute(
                    null,
                    "UPDATE author_archive_creators SET status='MERGED', " +
                        "merged_into_creator_id=${ids[1]} WHERE _id=${ids[0]}",
                    0,
                )
                val add = authorMembership(1, a, SyncEffectKind.ADD)
                val remove = authorMembership(2, a, SyncEffectKind.REMOVE, add.events.single())
                s.inbox.ingest(add)
                s.projectAll()
                s.inbox.ingest(remove)
                s.projectAll()
                val old = s.projector.pending("space", 1).single()
                job = s.projector.startBulk("space", 1, SyncCancellationDecision.CONFIRM)
                s.creators.followCreator(ids[1], syncContext = SyncMutationContext.User)
                assertEquals(
                    SyncReceiverDecisionResult.INVALIDATED,
                    s.projector.decide("space", 1, old, SyncCancellationDecision.CONFIRM),
                )
                assertEquals(true, s.writer.localMembership(b))
                assertEquals(0L, s.inbox.status("space", 1).pendingDecisions)
                // Simulate a durable projection rebuild; invalidation must outlive the visible pending row.
                s.handler.await {
                    sync_inboxQueries.markFieldDirty(
                        "space",
                        1,
                        a.stableKey,
                        SyncField.FOLLOWING.name,
                        kotlinx.serialization.json.Json.encodeToString(a),
                    )
                }
            }
            open(file.absolutePath, false).use { s ->
                s.connect(repository)
                s.projectAll()
                assertTrue(s.projector.pending("space", 1).isEmpty())
                assertEquals(1L, s.projector.processBulk(job).outcomes["INVALIDATED"])
                assertEquals(true, s.writer.localMembership(b))
                val add = authorMembership(1, a, SyncEffectKind.ADD)
                val remove = authorMembership(2, a, SyncEffectKind.REMOVE, add.events.single())
                s.inbox.ingest(authorMembership(3, a, SyncEffectKind.REMOVE, remove.events.single()))
                s.projectAll()
                assertEquals(1, s.projector.pending("space", 1).size)
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `quarantined ancestor invalidates cancellation without replacing bytes`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val add = membership(1, SyncEffectKind.ADD)
            s.inbox.ingest(add)
            s.projectAll()
            s.inbox.ingest(membership(2, SyncEffectKind.REMOVE, add.events.single()))
            s.projectAll()
            val old = s.projector.pending("space", 1).single()
            val before = s.handler.await {
                sync_inboxQueries.getEvent("space", 1, add.events.single().eventId.stableKey).executeAsOne()
            }
            assertFalse(s.inbox.ingest(membership(1, SyncEffectKind.REMOVE)).accepted)
            assertEquals(
                SyncReceiverDecisionResult.INVALIDATED,
                s.projector.decide("space", 1, old, SyncCancellationDecision.CONFIRM),
            )
            s.projectAll()
            assertEquals(true, s.writer.localMembership(manga))
            assertTrue(s.projector.pending("space", 1).isEmpty())
            assertEquals(
                before,
                s.handler.await {
                    sync_inboxQueries.getEvent("space", 1, add.events.single().eventId.stableKey).executeAsOne()
                },
            )
        }
    }

    private fun authorMembership(
        seq: Long,
        key: SyncObjectKey,
        kind: SyncEffectKind,
        parent: SyncEventEnvelope? = null,
    ): SyncBatch {
        val event = SyncEventEnvelope(
            1, "space", 1, "author-device", 1, seq, SyncCategory.FOLLOW,
            listOf(
                SyncEffect(
                    "follow",
                    key,
                    SyncField.FOLLOWING,
                    kind,
                    parents = parent?.let { listOf(it.ref("follow")) } ?: emptyList(),
                ),
            ),
            SyncOrigin.USER, batchId = "author-$seq",
        )
        return SyncBatch(
            1,
            "space",
            1,
            requireNotNull(event.batchId),
            listOf(event),
            listOf(SyncObjectDescriptor(key, "作者")),
        )
    }

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
        val inbox = SyncInboxStore(handler)
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val mangas = MangaRepositoryImpl(handler, creators)
        val sources = mutableSetOf(42L)
        val writer = SyncRemoteProjectionWriter(handler, creators, creators, bootstrap, sources::contains)
        val projector = SyncInboxProjector(handler, writer)
        suspend fun projectAll() {
            repeat(30) { if (projector.project("space", 1) == 0) return }
            error("dirty fields did not settle")
        }
        suspend fun connect(repository: SyncRepository) = journal.connect("space", 1, repository, "device-b", 1)
        override fun close() = driver.close()
    }
}
