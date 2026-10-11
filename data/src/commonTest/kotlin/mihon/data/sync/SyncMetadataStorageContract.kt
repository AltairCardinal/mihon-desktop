package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.data.sync.runtime.SyncDatabaseExchange
import mihon.data.sync.runtime.SyncRunStore
import mihon.domain.reader.ReaderChapterOpeningMode
import mihon.domain.reader.ReaderPairingAdjustment
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMetadataPolicy
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterPairingRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException

private val metadataRepository = SyncRepository("owner", "sync-data", "mihon-sync")

/** Each platform runs the same repositories, journal, receiving projector and real SQLite transactions. */
abstract class SyncMetadataStorageContract {
    protected abstract fun open(path: String? = null, create: Boolean = true): Storage

    @Test
    fun `invalid layout and foreign space batches never apply partial metadata`() = runBlocking {
        open().use { source ->
            open().use { target ->
                source.connect("source")
                target.connect("target")
                val chapter = source.chapter()
                source.setCategories.awaitResult(chapter.mangaId, listOf(source.category("Isolated")))
                source.pairings.replace(chapter.id, chapter.mangaId, 0, 10, setOf(3))
                val batch = requireNotNull(SyncOutboxStore(source.handler).nextBatch("space", 1))
                val invalid = batch.copy(
                    events = batch.events.map { event ->
                        event.copy(
                            effects = event.effects.map {
                                if (it.field == SyncField.CHAPTER_PAIRING) {
                                    it.copy(
                                        payload = buildJsonObject {
                                            put("pageCount", JsonPrimitive(-1))
                                        },
                                    )
                                } else {
                                    it
                                }
                            },
                        )
                    },
                )
                assertFalse(SyncInboxStore(target.handler).ingest(invalid).accepted)
                assertFalse(SyncInboxStore(target.handler).ingest(batch.copy(spaceId = "foreign")).accepted)
                target.projector.project("space", 1)
                assertTrue(target.categories.getAll().none { it.id > 0 })
                assertEquals(null, target.mangas.getMangaByUrlAndSourceId("/manga", 42))
                assertTrue(target.journal.pendingEvents("space", 1).isEmpty())
            }
        }
    }

    @Test
    fun `oversized category user edit fails visibly and rolls back association and sequence`() = runBlocking {
        open().use { s ->
            s.connect("actor")
            val manga = s.manga()
            val category = s.category("x".repeat(513))
            assertTrue(
                s.setCategories.awaitResult(manga.id, listOf(category)) is SetMangaCategories.Result.InternalError,
            )
            assertTrue(s.categories.getCategoriesByMangaId(manga.id).isEmpty())
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `metadata travels through real encrypted exchange with operation logs and drained counts`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })
            open().use { source ->
                open().use { target ->
                    source.journal.connect("space", 1, repository, "source", 1)
                    target.journal.connect("space", 1, repository, "target", 1)
                    val chapter = source.chapter()
                    source.setCategories.awaitResult(chapter.mangaId, listOf(source.category("Exchange")))
                    source.pairings.replace(chapter.id, chapter.mangaId, 0, 10, setOf(3))
                    val runs = SyncRunStore(source.handler)
                    val run = runs.start("space", 1, SyncTrigger.MANUAL)
                    assertTrue(runs.claim(run.runId, "owner", 1))
                    val result = SyncDatabaseExchange(
                        source.handler,
                        source.baseline,
                        source.projector,
                        transport,
                        secret,
                        progress = runs.reporter(run.runId, "owner"),
                    ).exchange("space", 1, repository)
                    assertEquals(
                        SyncRunStatus.SUCCESS,
                        result.status,
                        "$result ${runs.logs(run.runId)}",
                    )
                    assertTrue(result.uploaded >= 2)
                    assertTrue(source.journal.pendingEvents("space", 1).isEmpty())
                    val details = runs.logs(run.runId).map { it.detail }
                    assertTrue(details.any { "作品分类" in it })
                    assertTrue(details.any { "双页调整" in it })
                    val received = SyncDatabaseExchange(
                        target.handler,
                        target.baseline,
                        target.projector,
                        transport,
                        secret,
                    ).exchange("space", 1, repository)
                    assertEquals(result.uploaded, received.downloaded)
                    val remote = requireNotNull(target.mangas.getMangaByUrlAndSourceId("/manga", 42))
                    val names = target.categories.getCategoriesByMangaId(remote.id).map { it.name }
                    assertEquals(listOf("Exchange"), names)
                    val remoteChapter = target.chapters.getChapterByMangaId(remote.id).single()
                    val receivedLayout = target.pairings.load(remoteChapter.id, remote.id).record!!
                    assertEquals(setOf(3), receivedLayout.forcedSinglePages)
                    assertTrue(target.journal.pendingEvents("space", 1).isEmpty())
                }
            }
        }
    }

    @Test
    fun `production exchange backfills completed imports before network and only once`() = runBlocking {
        open().use { s ->
            val chapter = s.chapter()
            val category = s.category("Upgrade")
            s.mangas.setMangaCategories(chapter.mangaId, listOf(category))
            s.pairings.replace(chapter.id, chapter.mangaId, 0, 10, setOf(3))
            s.connect("actor")
            s.handler.await { sync_importQueries.insertImport("legacy", "space", 1, "INITIAL_IMPORT", "actor", 1, 1) }
            val transport = mockk<SyncTransportPort>()
            val offline = Result.failure<SyncSnapshot>(IOException("offline"))
            coEvery { transport.readSnapshot(any(), any(), any()) } returns offline
            val exchange = SyncDatabaseExchange(s.handler, s.baseline, s.projector, transport)
            exchange.exchange("space", 1, metadataRepository)
            val events = s.journal.pendingEvents("space", 1)
            assertEquals(
                setOf(SyncField.MANGA_CATEGORIES, SyncField.CHAPTER_PAIRING),
                events.flatMap { it.effects }.map { it.field }.toSet(),
            )
            assertTrue(events.all { it.origin == SyncOrigin.INITIAL_IMPORT })
            exchange.exchange("space", 1, metadataRepository)
            assertEquals(events, s.journal.pendingEvents("space", 1))
        }
    }

    @Test
    fun `frozen upgrade cannot replace later user clear and marker rollback can retry`() = runBlocking {
        open().use { s ->
            val chapter = s.chapter()
            s.mangas.setMangaCategories(chapter.mangaId, listOf(s.category("Old")))
            s.pairings.replace(chapter.id, chapter.mangaId, 0, 10, setOf(3))
            s.connect("actor")
            s.handler.await { sync_importQueries.insertImport("legacy", "space", 1, "INITIAL_IMPORT", "actor", 1, 1) }
            s.driver.execute(
                null,
                "CREATE TRIGGER fail_freeze BEFORE INSERT ON sync_import_entries " +
                    "BEGIN SELECT RAISE(ABORT,'refused'); END",
                0,
            )
            assertTrue(runCatching { s.baseline.ensureMetadataBaseline("space", 1) }.isFailure)
            assertFalse(s.handler.await { sync_importQueries.hasMetadataImport("space", 1).executeAsOne() })
            s.driver.execute(null, "DROP TRIGGER fail_freeze", 0)
            s.baseline.ensureMetadataBaseline("space", 1)
            s.setCategories.awaitResult(chapter.mangaId, emptyList())
            s.pairings.replace(chapter.id, chapter.mangaId, 1, 10, emptySet())
            s.baseline.process("legacy")
            s.projector.project("space", 1)
            assertTrue(s.categories.getCategoriesByMangaId(chapter.mangaId).isEmpty())
            assertEquals(null, s.pairings.load(chapter.id, chapter.mangaId).record)
            val baselines = s.journal.pendingEvents("space", 1).filter { it.origin == SyncOrigin.INITIAL_IMPORT }
            assertEquals(2, baselines.size)
            assertTrue(baselines.flatMap { it.effects }.all { it.parents.isEmpty() })
        }
    }

    @Test
    fun `legacy format one upgrade baseline does not stale an unchanged active reader`() = runBlocking {
        open().use { s ->
            val chapter = s.chapter()
            s.pairings.replace(chapter.id, chapter.mangaId, 0, 10, setOf(3))
            s.driver.execute(null, "UPDATE chapter_pairings SET format_version=1", 0)
            val before = s.pairings.load(chapter.id, chapter.mangaId)
            val id = s.baseline.connectAndImport("space", 1, metadataRepository, "actor", 1)
            s.baseline.process(id)
            s.projector.project("space", 1)
            assertEquals(before.revision, s.pairings.load(chapter.id, chapter.mangaId).revision)
            s.pairings.replace(chapter.id, chapter.mangaId, before.revision, 10, setOf(4))
            Unit
        }
    }

    @Test
    fun `schema 45 retains pending batches and layouts through metadata migration`() = runBlocking {
        val file = File.createTempFile("sync-metadata-migration", ".db")
        try {
            open(file.absolutePath).use { s ->
                val chapter = s.chapter()
                val category = s.category("Preserved")
                s.mangas.setMangaCategories(chapter.mangaId, listOf(category))
                s.pairings.replace(chapter.id, chapter.mangaId, 0, 10, setOf(3))
                s.connect("actor")
                s.mangas.update(
                    tachiyomi.domain.manga.model.MangaUpdate(
                        chapter.mangaId,
                        favorite = true,
                        syncContext = mihon.domain.sync.SyncMutationContext.User,
                    ),
                )
                val before = s.journal.pendingEvents("space", 1)
                if (Database.Schema.version >= 46) {
                    s.driver.execute(null, "DROP TABLE sync_metadata_imports", 0)
                    s.driver.execute(null, "ALTER TABLE sync_import_entries DROP COLUMN snapshot_field", 0)
                    s.driver.execute(null, "ALTER TABLE sync_import_entries DROP COLUMN snapshot_payload", 0)
                }
                s.driver.execute(null, "PRAGMA user_version = 45", 0)
                assertEquals(46L, Database.Schema.version)
                tachiyomi.data.DatabaseMigration.migrateAtomically(s.driver, 45, Database.Schema.version)
                assertEquals(before, s.journal.pendingEvents("space", 1))
                assertEquals(setOf(3), s.pairings.load(chapter.id, chapter.mangaId).record!!.forcedSinglePages)
                val preservedNames = s.categories.getCategoriesByMangaId(chapter.mangaId).map { it.name }
                assertEquals(listOf("Preserved"), preservedNames)
            }
            open(file.absolutePath, false).use { s ->
                assertEquals(1, s.journal.pendingEvents("space", 1).size)
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `initial metadata baseline freezes categories and layout and resumes once after reopening`() = runBlocking {
        val file = File.createTempFile("sync-metadata", ".db")
        var importId = ""
        try {
            open(file.absolutePath).use { s ->
                val chapter = s.chapter()
                val category = s.category("Frozen")
                s.mangas.setMangaCategories(chapter.mangaId, listOf(category))
                s.pairings.replace(chapter.id, chapter.mangaId, 0, 10, setOf(3))
                importId = s.baseline.connectAndImport("space", 1, metadataRepository, "actor", 1)
                s.driver.execute(null, "DELETE FROM mangas", 0)
            }
            open(file.absolutePath, false).use { s ->
                assertEquals(importId, s.baseline.connectAndImport("space", 1, metadataRepository, "actor", 1))
                while (s.baseline.process(importId, 1).remaining > 0) { /* process durable pages */ }
                val events = s.journal.pendingEvents("space", 1)
                assertEquals(
                    setOf(SyncField.MANGA_CATEGORIES, SyncField.CHAPTER_PAIRING),
                    events.flatMap { it.effects }.map { it.field }.toSet(),
                )
                assertTrue(events.all { it.origin == SyncOrigin.INITIAL_IMPORT })
                assertEquals(0L, s.baseline.process(importId).remaining)
                assertEquals(events, s.journal.pendingEvents("space", 1))
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `category entry captures portable full set and receiver creates exact names without echo`() = runBlocking {
        open().use { source ->
            open().use { target ->
                source.connect("source")
                target.connect("target")
                target.manga("/padding")
                val a = source.manga()
                val b = target.manga()
                val science = source.category("科幻")
                val upper = source.category("SF")
                val existing = target.category("SF")
                target.categories.updatePartial(CategoryUpdate(existing, order = 10, flags = 7))
                target.category("sf")
                assertTrue(a.id != b.id)
                assertEquals(
                    SetMangaCategories.Result.Success,
                    source.setCategories.awaitResult(a.id, listOf(science, upper)),
                )
                val batch = requireNotNull(SyncOutboxStore(source.handler).nextBatch("space", 1))
                val effect = batch.events.single().effects.single { it.field == SyncField.MANGA_CATEGORIES }
                assertEquals(listOf("SF", "科幻"), SyncMetadataPolicy.categoryNames(effect.payload))
                assertTrue(SyncInboxStore(target.handler).ingest(batch).accepted)
                target.projector.project("space", 1)
                val categories = target.categories.getCategoriesByMangaId(b.id)
                assertEquals(setOf("SF", "科幻"), categories.map { it.name }.toSet())
                assertEquals(existing, categories.single { it.name == "SF" }.id)
                assertEquals(7L, categories.single { it.name == "SF" }.flags)
                assertEquals(10L, categories.single { it.name == "SF" }.order)
                assertEquals(73L, categories.single { it.name == "科幻" }.flags)
                assertFalse(target.mangas.getMangaById(b.id).favorite)
                assertTrue(target.journal.pendingEvents("space", 1).isEmpty())
                SyncInboxStore(target.handler).ingest(batch)
                target.projector.project("space", 1)
                assertEquals(1, target.categories.getAll().count { it.name == "科幻" })
            }
        }
    }

    @Test
    fun `category rename delete and clear publish sets while metadata stays local`() = runBlocking {
        open().use { s ->
            s.connect("source")
            val manga = s.manga()
            val category = s.category("Old")
            s.mangas.setMangaCategories(manga.id, listOf(category))
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            s.categories.updatePartial(CategoryUpdate(category, name = "New"))
            s.categories.delete(category)
            s.setCategories.awaitResult(manga.id, emptyList())
            val effects = s.journal.pendingEvents("space", 1).flatMap { it.effects }
            assertEquals(
                listOf(listOf("New"), emptyList(), emptyList()),
                effects.map {
                    SyncMetadataPolicy.categoryNames(it.payload)
                },
            )
        }
    }

    @Test
    fun `pairing save reaches remote layout and preserves local revision and CAS`() = runBlocking {
        open().use { source ->
            open().use { target ->
                source.connect("source")
                target.connect("target")
                val a = source.chapter()
                target.manga("/padding")
                val b = target.chapter()
                val old = target.pairings.load(b.id, b.mangaId)
                val adjustment = ReaderPairingAdjustment(setOf(3), 7, ReaderChapterOpeningMode.FIRST_SINGLE, setOf(5))
                val saved = source.pairings.replaceLayout(a.id, a.mangaId, 0, 10, adjustment)
                val batch = requireNotNull(SyncOutboxStore(source.handler).nextBatch("space", 1))
                assertEquals(SyncField.CHAPTER_PAIRING, batch.events.single().effects.single().field)
                assertTrue(SyncInboxStore(target.handler).ingest(batch).accepted)
                target.projector.project("space", 1)
                val received = target.pairings.load(b.id, b.mangaId)
                assertEquals(saved.record, received.record)
                assertEquals(1L, received.revision)
                assertFalse(received.record!!.isValidFor(11))
                val staleSave = runCatching {
                    target.pairings.replace(b.id, b.mangaId, old.revision, 10, setOf(2))
                }
                assertTrue(staleSave.isFailure)
                SyncInboxStore(target.handler).ingest(batch)
                target.projector.project("space", 1)
                assertEquals(received, target.pairings.load(b.id, b.mangaId))
                assertTrue(target.journal.pendingEvents("space", 1).isEmpty())
                source.projector.project("space", 1)
                assertEquals(saved, source.pairings.load(a.id, a.mangaId))
            }
        }
    }

    @Test
    fun `category journal failure rolls back business state and sequence`() = runBlocking {
        open().use { s ->
            s.connect("source")
            val manga = s.manga()
            val category = s.category("A")
            s.driver.execute(
                null,
                "CREATE TRIGGER fail_metadata BEFORE INSERT ON sync_outbox " +
                    "BEGIN SELECT RAISE(ABORT,'refused'); END",
                0,
            )
            assertTrue(
                s.setCategories.awaitResult(manga.id, listOf(category)) is SetMangaCategories.Result.InternalError,
            )
            assertTrue(s.categories.getCategoriesByMangaId(manga.id).isEmpty())
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            s.driver.execute(null, "DROP TRIGGER fail_metadata", 0)
            s.setCategories.awaitResult(manga.id, listOf(category))
            assertEquals(1L, s.journal.pendingEvents("space", 1).single().seq)
        }
    }

    @Test
    fun `receiving category association failure rolls back category creation and remains retryable`() = runBlocking {
        open().use { source ->
            open().use { target ->
                source.connect("source")
                target.connect("target")
                val manga = source.manga()
                source.setCategories.awaitResult(manga.id, listOf(source.category("New")))
                val batch = requireNotNull(SyncOutboxStore(source.handler).nextBatch("space", 1))
                assertTrue(SyncInboxStore(target.handler).ingest(batch).accepted)
                target.driver.execute(
                    null,
                    "CREATE TRIGGER fail_assignment BEFORE INSERT ON mangas_categories " +
                        "BEGIN SELECT RAISE(ABORT,'refused'); END",
                    0,
                )
                assertTrue(runCatching { target.projector.project("space", 1) }.isFailure)
                assertFalse(target.categories.getAll().any { it.name == "New" })
                target.driver.execute(null, "DROP TRIGGER fail_assignment", 0)
                target.projector.project("space", 1)
                val remote = requireNotNull(target.mangas.getMangaByUrlAndSourceId("/manga", 42))
                assertEquals(listOf("New"), target.categories.getCategoriesByMangaId(remote.id).map { it.name })
            }
        }
    }

    @Test
    fun `pairing clear preserves progress and rolls back with journal failure`() = runBlocking {
        open().use { source ->
            open().use { target ->
                source.connect("source")
                target.connect("target")
                val a = source.chapter()
                val b = target.chapter()
                source.pairings.replace(a.id, a.mangaId, 0, 10, setOf(3))
                source.driver.execute(
                    null,
                    "CREATE TRIGGER fail_pairing BEFORE INSERT ON sync_outbox " +
                        "BEGIN SELECT RAISE(ABORT,'refused'); END",
                    0,
                )
                assertTrue(runCatching { source.pairings.replace(a.id, a.mangaId, 1, 10, emptySet()) }.isFailure)
                assertEquals(setOf(3), source.pairings.load(a.id, a.mangaId).record!!.forcedSinglePages)
                source.driver.execute(null, "DROP TRIGGER fail_pairing", 0)
                source.pairings.replace(a.id, a.mangaId, 1, 10, emptySet())
                val batch = requireNotNull(SyncOutboxStore(source.handler).nextBatch("space", 1))
                assertTrue(SyncInboxStore(target.handler).ingest(batch).accepted)
                target.projector.project("space", 1)
                assertEquals(null, target.pairings.load(b.id, b.mangaId).record)
                assertEquals(0L, target.chapters.getChapterById(b.id)!!.lastPageRead)
                assertTrue(target.journal.pendingEvents("space", 1).isEmpty())
            }
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
        val categories = CategoryRepositoryImpl(handler)
        val chapters = ChapterRepositoryImpl(handler)
        val pairings = ChapterPairingRepositoryImpl(handler)
        val setCategories = SetMangaCategories(mangas)
        val writer = SyncRemoteProjectionWriter(
            handler,
            creators,
            creators,
            bootstrap,
            { true },
            defaultCategoryFlags = { 73L },
        )
        val projector = SyncInboxProjector(handler, writer)
        suspend fun connect(actor: String) = journal.connect("space", 1, metadataRepository, actor, 1)
        suspend fun manga(url: String = "/manga"): Manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = 42, url = url, title = url)),
        ).single()
        suspend fun chapter(): Chapter {
            val manga = manga()
            val chapter = Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "Chapter")
            return chapters.addAll(listOf(chapter)).single()
        }
        suspend fun category(name: String): Long {
            categories.insert(Category(0, name, 0, 0))
            return categories.getAll().single { it.name == name }.id
        }
        override fun close() = driver.close()
    }
}
