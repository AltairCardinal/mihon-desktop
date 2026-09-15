package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.io.File
import java.util.Date

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SyncBaselineStorageContract {
    protected abstract fun open(path: String? = null, create: Boolean = true): Storage
    private val repository = SyncRepository("owner", "sync-data", "mihon-sync")

    @Test
    fun `remote reading advances only the corresponding shareable baseline fields`() = runBlocking {
        open().use { s ->
            val manga = s.manga("/remote-public", false)
            val chapter = s.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "章节")),
            ).single()
            s.handler.await {
                val recorder = SqlDelightReadingProgressRepository(this)
                recorder.record(
                    ReadingProgressEvent(chapter.id, 9, 10, Date(1000), 0, syncContext = SyncMutationContext.User),
                )
                recorder.record(
                    ReadingProgressEvent(
                        chapter.id,
                        7,
                        10,
                        Date(5000),
                        0,
                        wasRead = true,
                        recordHistory = true,
                        syncContext = SyncMutationContext.LocalOnly,
                    ),
                )
            }
            val mangaKey = SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = manga.url)
            val chapterKey =
                SyncObjectKey(SyncObjectType.CHAPTER, sourceId = "42", originalUrl = chapter.url, parentUrl = manga.url)
            s.writer.prepare()
            s.writer.applyReadStatus(chapterKey, false) { null }
            s.writer.applyResume(mangaKey, chapterKey, 4) { null }
            s.writer.applyHistory(mangaKey, chapterKey, 2000) { null }
            s.baseline.process(s.baseline.connectAndImport("space", 1, repository, "device", 1))
            val effects = s.journal.pendingEvents("space", 1).single().effects
            assertTrue(effects.none { it.kind == SyncEffectKind.MARK_READ })
            assertEquals(
                "4",
                effects.single {
                    it.kind == SyncEffectKind.RESUME_POSITION
                }.payload["pageIndex"].toString(),
            )
            assertEquals(
                "2000",
                effects.single {
                    it.kind == SyncEffectKind.READING_SUMMARY
                }.payload["readAt"].toString(),
            )
        }
    }

    @Test
    fun `public rereading and metadata never inherit private completion`() = runBlocking {
        for (context in listOf(SyncMutationContext.User, SyncMutationContext.Metadata)) {
            open().use { s ->
                val manga = s.manga("/privacy", false)
                val chapter = s.chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "章节")),
                ).single()
                s.handler.await {
                    val recorder = SqlDelightReadingProgressRepository(this)
                    recorder.record(
                        ReadingProgressEvent(
                            chapter.id,
                            9,
                            10,
                            Date(1000),
                            0,
                            recordHistory = false,
                            syncContext = SyncMutationContext.LocalOnly,
                        ),
                    )
                    recorder.record(
                        ReadingProgressEvent(chapter.id, 3, 10, Date(2000), 0, wasRead = true, syncContext = context),
                    )
                }
                s.baseline.process(s.baseline.connectAndImport("space", 1, repository, "device", 1))
                val effects = s.journal.pendingEvents("space", 1).flatMap { it.effects }
                assertTrue(effects.none { it.kind == SyncEffectKind.MARK_READ })
                if (context == SyncMutationContext.User) {
                    assertEquals(
                        "3",
                        effects.single {
                            it.kind == SyncEffectKind.RESUME_POSITION
                        }.payload["pageIndex"].toString(),
                    )
                } else {
                    assertTrue(effects.isEmpty())
                }
                s.handler.await {
                    SqlDelightReadingProgressRepository(
                        this,
                    ).record(
                        ReadingProgressEvent(
                            chapter.id,
                            9,
                            10,
                            Date(3000),
                            0,
                            wasRead = true,
                            syncContext = SyncMutationContext.User,
                        ),
                    )
                }
                s.baseline.process(
                    s.baseline.connectAndImport("next", 1, repository.copy(name = "next-data"), "next-device", 1),
                )
                assertTrue(
                    s.journal.pendingEvents("next", 1).flatMap { it.effects }.any {
                        it.kind ==
                            SyncEffectKind.MARK_READ
                    },
                )
                s.chapters.update(
                    ChapterUpdate(chapter.id, read = false, lastPageRead = 0, syncContext = SyncMutationContext.User),
                )
                s.baseline.process(
                    s.baseline.connectAndImport("last", 1, repository.copy(name = "last-data"), "last-device", 1),
                )
                assertTrue(
                    s.journal.pendingEvents("last", 1).flatMap { it.effects }.none {
                        it.kind ==
                            SyncEffectKind.MARK_READ
                    },
                )
            }
        }
    }

    @Test
    fun `private reading preserves the previously public baseline without exposing private progress`() = runBlocking {
        open().use { s ->
            val manga = s.manga("/previous-public", false)
            val chapter = s.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "章节")),
            ).single()
            s.handler.await {
                val recorder = SqlDelightReadingProgressRepository(this)
                recorder.record(
                    ReadingProgressEvent(chapter.id, 2, 10, Date(1000), 0, syncContext = SyncMutationContext.User),
                )
                recorder.record(
                    ReadingProgressEvent(
                        chapter.id,
                        9,
                        10,
                        Date(2000),
                        0,
                        recordHistory = false,
                        syncContext = SyncMutationContext.LocalOnly,
                    ),
                )
            }
            s.baseline.process(s.baseline.connectAndImport("space", 1, repository, "device", 1))
            val effects = s.journal.pendingEvents("space", 1).single().effects
            assertTrue(effects.none { it.kind == SyncEffectKind.MARK_READ })
            assertEquals(
                "2",
                effects.single {
                    it.kind == SyncEffectKind.RESUME_POSITION
                }.payload["pageIndex"].toString(),
            )
            assertEquals(
                "1000",
                effects.single {
                    it.kind == SyncEffectKind.READING_SUMMARY
                }.payload["readAt"].toString(),
            )
        }
    }

    @Test
    fun `migration preserves frozen uploads and actor sequence before enabling initial import`() = runBlocking {
        open().use { s ->
            s.journal.connect("space", 1, repository, "device", 1)
            val manga = s.manga("/existing", false)
            s.mangas.update(
                MangaUpdate(manga.id, favorite = true, viewerFlags = 7, syncContext = SyncMutationContext.User),
            )
            val batch = requireNotNull(SyncOutboxStore(s.handler).nextBatch("space", 1))
            val before = s.journal.pendingEvents("space", 1)
            listOf(
                "sync_private_reading",
                "sync_import_heads",
                "sync_import_entries",
                "sync_imports",
                "sync_remote_heads",
                "sync_remote_objects",
                "sync_remote_guards",
            ).forEach {
                s.driver.execute(null, "DROP TABLE $it", 0)
            }
            s.driver.execute(null, "ALTER TABLE sync_spaces DROP COLUMN exchange_enabled", 0)
            s.driver.execute(null, "PRAGMA user_version = 23", 0)
            DatabaseMigration.migrateAtomically(s.driver, 23, 24)
            assertEquals(batch, SyncOutboxStore(s.handler).nextBatch("space", 1))
            val import = s.baseline.connectAndImport("space", 1, repository, "device", 1)
            val watermark = s.handler.await { sync_importQueries.getImport(import).executeAsOne() }
            assertEquals(2L, watermark.next_seq)
            assertEquals(1L, watermark.epoch)
            assertEquals("device", watermark.actor_id)
            assertEquals(before, s.journal.pendingEvents("space", 1))
            s.baseline.process(import)
            assertEquals(listOf(1L, 2L), s.journal.pendingEvents("space", 1).map { it.seq })
            assertEquals(7L, s.mangas.getMangaById(manga.id).viewerFlags)
        }
    }

    @Test
    fun `first import never backfills incognito progress and genuine later reading can sync`() = runBlocking {
        open().use { s ->
            val manga = s.manga("/private", false)
            val chapter = s.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/private-chapter", name = "章节")),
            ).single()
            val event =
                ReadingProgressEvent(
                    chapter.id,
                    9,
                    10,
                    Date(1000),
                    0,
                    recordHistory = false,
                    syncContext = SyncMutationContext.LocalOnly,
                )
            s.handler.await { SqlDelightReadingProgressRepository(this).record(event) }
            val import = s.baseline.connectAndImport("space", 1, repository, "device", 1)
            s.baseline.process(import)
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            s.handler.await {
                SqlDelightReadingProgressRepository(
                    this,
                ).record(
                    event.copy(
                        idempotencyKey = "new-reading",
                        lastPageRead = 3,
                        recordHistory = true,
                        syncContext = SyncMutationContext.User,
                    ),
                )
            }
            assertEquals(SyncOrigin.USER, s.journal.pendingEvents("space", 1).single().origin)
        }
    }

    @Test
    fun `activation and snapshot failure preserve previous space and allow a fresh retry`() = runBlocking {
        open().use { s ->
            s.journal.connect("old", 1, repository, "old-device", 1)
            s.manga("/one", true)
            s.driver.execute(
                null,
                "CREATE TRIGGER fail_freeze BEFORE INSERT ON sync_import_entries " +
                    "BEGIN SELECT RAISE(ABORT, 'injected'); END",
                0,
            )
            assertTrue(
                runCatching {
                    s.baseline.connectAndImport("new", 1, repository.copy(name = "new-data"), "new-device", 1)
                }.isFailure,
            )
            assertEquals("old", s.handler.await { sync_journalQueries.getActiveActor().executeAsOne().space_id })
            assertNull(s.handler.await { sync_journalQueries.getSpace("new", 1).executeAsOneOrNull() })
            s.driver.execute(null, "DROP TRIGGER fail_freeze", 0)
            val import = s.baseline.connectAndImport("new", 1, repository.copy(name = "new-data"), "new-device", 1)
            assertEquals(1L, s.baseline.process(import).total)
        }
    }

    @Test
    fun `many historical chapters keep bounded baseline parents and permit the next user reading`() = runBlocking {
        open().use { s ->
            val manga = s.manga("/many", false)
            val chapters = s.chapters.addAll(
                (1..120).map {
                    Chapter.create().copy(mangaId = manga.id, url = "/ch-$it", name = "话$it", lastPageRead = 2)
                },
            )
            s.handler.await {
                chapters.forEachIndexed { index, chapter ->
                    historyQueries.upsert(chapter.id, Date(index.toLong() + 1), 0)
                }
            }
            val import = s.baseline.connectAndImport("space", 1, repository, "device", 1)
            repeat(3) { s.baseline.process(import, 50) }
            val events = s.journal.pendingEvents("space", 1)
            assertEquals(120, events.size)
            assertTrue(events.all { event -> event.effects.all { it.parents.size <= 1 } })
            s.handler.await {
                SqlDelightReadingProgressRepository(
                    this,
                ).record(
                    ReadingProgressEvent(
                        chapters.first().id,
                        1,
                        10,
                        Date(2000),
                        0,
                        syncContext = SyncMutationContext.User,
                    ),
                )
            }
            val batch = requireNotNull(SyncOutboxStore(s.handler).nextBatch("space", 1))
            open().use { target ->
                target.journal.connect("space", 1, repository, "target", 1)
                assertTrue(SyncInboxStore(target.handler).ingest(batch).accepted)
                repeat(10) { target.projector.project("space", 1) }
                assertEquals(
                    120L,
                    target.handler.await {
                        historyQueries.getHistoryByMangaId(
                            mangasQueries.getMangaByUrlAndSource("/many", 42).executeAsOne()._id,
                        ).executeAsList().size.toLong()
                    },
                )
            }
        }
    }

    @Test
    fun `snapshot freezes positive data and descriptions while later user cancellation wins`() = runBlocking {
        open().use { s ->
            val manga = s.manga("/frozen", true)
            s.manga("/absent", false)
            val chapter = s.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "原章节", read = true)),
            ).single()
            val author = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = "author")
            s.writer.prepare()
            s.writer.applyMembership(author, true) { SyncObjectDescriptor(it, "原作者") }
            val import = s.baseline.connectAndImport("space", 1, repository, "device", 1)
            s.mangas.update(MangaUpdate(manga.id, title = "新标题"))
            s.chapters.update(ChapterUpdate(chapter.id, read = false, syncContext = SyncMutationContext.User))
            s.mangas.update(MangaUpdate(manga.id, favorite = false, syncContext = SyncMutationContext.User))
            val progress = s.baseline.process(import)
            assertEquals(3L, progress.total)
            assertEquals(0L, progress.remaining)
            val events = s.journal.pendingEvents("space", 1)
            val baseline = events.filter { it.origin == SyncOrigin.INITIAL_IMPORT }
            assertEquals(
                setOf(SyncCategory.FAVORITE, SyncCategory.FOLLOW, SyncCategory.READING),
                baseline.map {
                    it.category
                }.toSet(),
            )
            assertTrue(baseline.all { it.importId == import && it.effects.all { effect -> effect.parents.isEmpty() } })
            assertTrue(
                baseline.flatMap { it.effects }.none {
                    it.kind == SyncEffectKind.REMOVE ||
                        it.kind == SyncEffectKind.MARK_UNREAD
                },
            )
            assertTrue(events.none { event -> event.effects.any { it.objectKey.originalUrl == "/absent" } })
            val batch = requireNotNull(SyncOutboxStore(s.handler).nextBatch("space", 1))
            open().use { target ->
                target.journal.connect("space", 1, repository, "target", 1)
                assertTrue(SyncInboxStore(target.handler).ingest(batch).accepted)
                repeat(5) { target.projector.project("space", 1) }
                assertNotEquals(
                    true,
                    target.writer.localMembership(
                        SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = "/frozen"),
                    ),
                )
                assertEquals(true, target.writer.localMembership(author))
            }
            assertEquals(0L, s.baseline.process(import).remaining)
            assertEquals(events, s.journal.pendingEvents("space", 1))
        }
    }

    @Test
    fun `frozen descriptions survive source deletion and reopen without duplicate imports`() = runBlocking {
        val file = File.createTempFile("sync-baseline", ".db")
        var import = ""
        try {
            open(file.absolutePath).use { s ->
                s.manga("/one", true)
                s.manga("/two", true)
                import = s.baseline.connectAndImport("space", 1, repository, "device", 1)
                assertEquals(1L, s.baseline.process(import, 1).remaining)
                s.driver.execute(null, "DELETE FROM mangas", 0)
            }
            open(file.absolutePath, false).use { s ->
                assertEquals(import, s.baseline.connectAndImport("space", 1, repository, "device", 1))
                assertEquals(0L, s.baseline.process(import, 1).remaining)
                val batch = requireNotNull(SyncOutboxStore(s.handler).nextBatch("space", 1))
                assertEquals(setOf("/one", "/two"), batch.objects.map { it.title }.toSet())
                assertEquals(listOf(1L, 2L), batch.events.map { it.seq })
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `failed baseline append rolls back sequence outbox and import progress together`() = runBlocking {
        open().use { s ->
            s.manga("/one", true)
            val import = s.baseline.connectAndImport("space", 1, repository, "device", 1)
            s.driver.execute(
                null,
                "CREATE TRIGGER fail_import BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'injected'); END",
                0,
            )
            assertTrue(runCatching { s.baseline.process(import) }.isFailure)
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            s.driver.execute(null, "DROP TRIGGER fail_import", 0)
            assertEquals(0L, s.baseline.process(import).remaining)
            assertEquals(1L, s.journal.pendingEvents("space", 1).single().seq)
        }
    }

    @Test
    fun `disconnect preserves journaling and new identity never renumbers queued events`() = runBlocking {
        open().use { s ->
            s.journal.connect("space", 1, repository, "old-device", 1)
            val manga = s.manga("/one", false)
            s.mangas.update(MangaUpdate(manga.id, favorite = true, syncContext = SyncMutationContext.User))
            val old = s.journal.pendingEvents("space", 1).single()
            s.journal.disconnect("space", 1)
            assertTrue(runCatching { SyncOutboxStore(s.handler).nextBatch("space", 1) }.isFailure)
            s.mangas.update(MangaUpdate(manga.id, favorite = false, syncContext = SyncMutationContext.User))
            val identity = s.journal.renewIdentity("space", 1)
            assertNotEquals("old-device", identity.actorId)
            assertTrue(identity.epoch > 1)
            s.mangas.update(MangaUpdate(manga.id, favorite = true, syncContext = SyncMutationContext.User))
            val events = s.journal.pendingEvents("space", 1)
            assertEquals(old, events.single { it.actorId == "old-device" && it.seq == 1L })
            assertEquals(1L, events.single { it.actorId == identity.actorId }.seq)
            s.journal.connect("space", 1, repository, identity.actorId, identity.epoch)
            assertNotNull(SyncOutboxStore(s.handler).nextBatch("space", 1))
            s.journal.connect("other", 1, repository.copy(name = "other-data"), "other-device", 1)
            assertTrue(runCatching { SyncOutboxStore(s.handler).nextBatch("space", 1) }.isFailure)
            assertEquals(events, s.journal.pendingEvents("space", 1))
            assertTrue(s.journal.pendingEvents("other", 1).isEmpty())
        }
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
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val baseline = SyncBaselineStore(handler, bootstrap)
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val mangas = MangaRepositoryImpl(handler, creators)
        val chapters = ChapterRepositoryImpl(handler)
        val writer = SyncRemoteProjectionWriter(handler, creators, creators, bootstrap, { it == 42L })
        val projector = SyncInboxProjector(handler, writer)
        suspend fun manga(url: String, favorite: Boolean): Manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = 42, url = url, title = url, favorite = favorite)),
        ).single()
        override fun close() = driver.close()
    }
}
