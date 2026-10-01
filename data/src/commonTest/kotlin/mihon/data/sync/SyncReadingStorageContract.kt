package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
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
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.util.Date

abstract class SyncReadingStorageContract {

    @Test
    fun `shared complete directory adds renames reorders removes and inherits latest number state`() = runBlocking {
        open().use { s ->
            val original = s.seed()
            s.chapters.update(
                ChapterUpdate(original.id, chapterNumber = 2.0, bookmark = true, read = true, lastPageRead = 8),
            )
            val latest = requireNotNull(s.chapters.getChapterById(original.id))
            val manga = s.mangas.getMangaById(latest.mangaId)
            val removed = s.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/obsolete",
                        name = "Obsolete",
                        chapterNumber = 9.0,
                    ),
                ),
            ).single()
            fun raw(
                url: String,
                name: String,
                number: Float,
            ) = eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                this.url = url
                this.name = name
                chapter_number = number
            }
            val response = tachiyomi.domain.chapter.service.ChapterDirectoryPlan.prepare(
                manga,
                listOf(
                    raw("/3", "Chapter 3", 3f),
                    raw(latest.url, "Renamed chapter 2", 2f),
                    raw("/duplicate", "Chapter 2 alternate", 2f),
                ),
            )
            val result = s.chapters.syncDirectory(
                tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                    manga.id,
                    response,
                    2000,
                    markDuplicateAsRead = true,
                ),
            )
            val rows = s.chapters.getChapterByMangaId(manga.id).sortedBy { it.sourceOrder }
            assertEquals(listOf("/3", latest.url, "/duplicate"), rows.map { it.url })
            val retained = rows[1]
            assertEquals(latest.id, retained.id)
            assertEquals(8L, retained.lastPageRead)
            assertTrue(retained.read && retained.bookmark)
            assertTrue(rows[2].read)
            assertEquals(listOf("/3"), result.added.map { it.url })
            assertEquals(null, s.chapters.getChapterById(removed.id))
        }
    }

    @Test
    fun `shared full directory ambiguity does not rebind an old ID or grant an alias`() = runBlocking {
        open().use { s ->
            val old = s.seed()
            s.chapters.update(ChapterUpdate(old.id, chapterNumber = 2.0, read = true))
            val latest = requireNotNull(s.chapters.getChapterById(old.id))
            val retained = s.chapters.addAll(listOf(latest.copy(id = -1, url = "/retained"))).single()
            val result = s.chapters.syncDirectory(
                tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                    old.mangaId,
                    listOf(
                        tachiyomi.domain.chapter.service.PreparedSourceChapter(retained, 0),
                        tachiyomi.domain.chapter.service.PreparedSourceChapter(
                            latest.copy(id = -1, url = "/replacement"),
                            0,
                        ),
                    ),
                    2000,
                ),
            )
            val replacement = requireNotNull(s.chapters.getChapterByUrlAndMangaId("/replacement", old.mangaId))
            assertTrue(replacement.id != old.id && replacement.id != retained.id)
            assertTrue(replacement.read)
            assertEquals(null, s.chapters.getChapterByUrlAndMangaId(old.url, old.mangaId))
            assertTrue(result.plan.updates.none { it.before.url != it.after.url })
        }
    }
    protected abstract fun open(): Storage

    @Test
    fun `synchronized reading and explicit read state preserve extension memo`() = runBlocking {
        open().use { s ->
            val chapter = s.seed()
            val memo = kotlinx.serialization.json.buildJsonObject {
                put("opaque-source-value", kotlinx.serialization.json.JsonPrimitive("device-local"))
            }
            s.chapters.update(ChapterUpdate(chapter.id, memo = memo))
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            s.recorder.await(s.reading(chapter, 2))
            assertEquals(memo, s.chapters.getChapterById(chapter.id)!!.memo)
            s.markRead.awaitOrThrow(listOf(s.chapters.getChapterById(chapter.id)!!), true)
            assertEquals(memo, s.chapters.getChapterById(chapter.id)!!.memo)
            assertEquals(2, s.journal.pendingEvents("space", 1).size)
        }
    }

    @Test
    fun `resume selects causal reread and respects active scope`() = runBlocking {
        open().use { s ->
            val chapter = s.seed()
            assertEquals(null, s.recorder.resumePosition(chapter.mangaId))
            s.recorder.await(s.reading(chapter, 8))
            s.recorder.await(s.reading(chapter, 2).copy(readAt = Date(500)))
            val position = requireNotNull(s.recorder.resumePosition(chapter.mangaId))
            assertEquals(chapter.id, position.chapterId)
            assertEquals(2, position.pageIndex)
            s.journal.connect("other", 1, SyncRepository("owner", "other", "sync"), "other-reader", 1)
            assertEquals(null, s.recorder.resumePosition(chapter.mangaId))
        }
    }

    @Test
    fun `unique directory relink preserves accepted resume head and canonical reading event`() = runBlocking {
        open().use { s ->
            val chapter = s.seed()
            s.chapters.update(ChapterUpdate(chapter.id, chapterNumber = 2.0))
            val recognized = requireNotNull(s.chapters.getChapterById(chapter.id))
            s.recorder.await(s.reading(recognized, 6))
            val before = s.journal.pendingEvents("space", 1).single()
            s.chapters.syncDirectory(
                tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                    recognized.mangaId,
                    listOf(
                        tachiyomi.domain.chapter.service.PreparedSourceChapter(
                            recognized.copy(url = "/new-chapter"),
                            0,
                        ),
                    ),
                    2000,
                ),
            )
            val resumed = s.recorder.resumePosition(recognized.mangaId)
            assertEquals(
                recognized.id,
                resumed?.chapterId,
                "Accepted old URL resume heads must resolve after relinking",
            )
            assertEquals(6, resumed?.pageIndex)
            s.recorder.await(s.reading(requireNotNull(s.chapters.getChapterById(chapter.id)), 7))
            val after = s.journal.pendingEvents("space", 1)
            assertEquals(before, after.first(), "Sealed reading events must not be rewritten")
            assertTrue(
                after.last().effects.filter { it.objectKey.type == mihon.domain.sync.SyncObjectType.CHAPTER }
                    .all { it.objectKey.originalUrl == "/chapter" },
            )
        }
    }

    @Test
    fun `deleting a highest chapter then adding later cannot redirect an accepted reader to another chapter`() =
        runBlocking {
            open().use { s ->
                val first = s.seed()
                val removed = s.chapters.addAll(
                    listOf(first.copy(id = -1, url = "/removed", chapterNumber = 3.0)),
                ).single()
                val snapshot = s.recorder.openSession(removed.id)
                s.chapters.syncDirectory(
                    tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                        first.mangaId,
                        listOf(tachiyomi.domain.chapter.service.PreparedSourceChapter(first, 0)),
                        2000,
                    ),
                )
                s.chapters.syncDirectory(
                    tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                        first.mangaId,
                        listOf(
                            tachiyomi.domain.chapter.service.PreparedSourceChapter(first, 0),
                            tachiyomi.domain.chapter.service.PreparedSourceChapter(
                                first.copy(id = -1, url = "/later", chapterNumber = 7.0),
                                0,
                            ),
                        ),
                        3000,
                    ),
                )
                val later = s.chapters.getChapterByMangaId(first.mangaId).single { it.url == "/later" }
                runCatching { snapshot.await(s.reading(removed, 6)) }
                assertEquals(
                    0L,
                    s.chapters.getChapterById(later.id)!!.lastPageRead,
                    "A late reader of deleted ID ${removed.id} must not write newly accepted chapter ${later.id}",
                )
            }
        }

    @Test
    fun `continuation adopts the selected snapshot even after later receipt`() = runBlocking {
        open().use { s ->
            val chapter = s.seed()
            s.recorder.await(s.reading(chapter, 8))
            val initial = s.journal.pendingEvents("space", 1).single()
            val position = requireNotNull(s.recorder.resumePosition(chapter.mangaId))
            s.recorder.await(s.reading(chapter, 2))
            s.recorder.openSession(chapter.id, position.snapshot).await(s.reading(chapter, 7))
            val effect = s.journal.pendingEvents("space", 1).last().effects.single {
                it.field == SyncField.RESUME_POSITION
            }
            assertEquals(
                listOf(initial.effects.single { it.field == SyncField.RESUME_POSITION }.ref(initial)),
                effect.parents,
            )
        }
    }

    @Test
    fun `second manual chapter outbox failure rolls back every chapter and sequence`() = runBlocking {
        open().use { storage ->
            val first = storage.seed()
            val second = storage.chapters.addAll(listOf(first.copy(id = -1, url = "/second"))).single()
            val chapters = listOf(first, second)
            storage.chapters.updateAll(chapters.map { ChapterUpdate(it.id, read = true, lastPageRead = 6) })
            storage.driver.execute(
                null,
                "CREATE TRIGGER fail_second_manual BEFORE INSERT ON sync_outbox " +
                    "WHEN EXISTS (SELECT 1 FROM sync_outbox) " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic second manual outbox failure'); END;",
                0,
            )
            assertTrue(runCatching { storage.markRead.awaitOrThrow(chapters, false) }.isFailure)
            for (chapter in chapters) {
                val persisted = storage.chapters.getChapterById(chapter.id)!!
                assertTrue(persisted.read)
                assertEquals(6, persisted.lastPageRead)
            }
            assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
            storage.driver.execute(null, "DROP TRIGGER fail_second_manual", 0)
            storage.markRead.awaitOrThrow(chapters, false)
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(listOf(1L, 2L), events.map { it.seq })
            assertTrue(events.all { it.effects.single().parents.isEmpty() })
            for (chapter in chapters) {
                val persisted = storage.chapters.getChapterById(chapter.id)!!
                assertFalse(persisted.read)
                assertEquals(0, persisted.lastPageRead)
            }
        }
    }

    @Test
    fun `an active reading session does not inherit an unseen manual unread operation`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            storage.markRead.awaitOrThrow(chapter, true)
            val session = storage.recorder.openSession(chapter.id)
            storage.markRead.awaitOrThrow(chapter, false)
            session.await(storage.reading(chapter, page = 9))
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(3, events.size)
            assertEquals(
                listOf(events.first().effects.single().ref(events.first())),
                events.last().effects.single { it.field == SyncField.READ_STATUS }.parents,
            )
        }
    }

    @Test
    fun `session progress advances its own causal head while other progress remains concurrent`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            val session = storage.recorder.openSession(chapter.id)
            session.await(storage.reading(chapter, page = 1))
            storage.recorder.await(storage.reading(chapter, page = 8))
            session.await(storage.reading(chapter, page = 2))
            val events = storage.journal.pendingEvents("space", 1)
            val firstPosition = events.first().effects.single { it.field == SyncField.RESUME_POSITION }
            assertEquals(
                listOf(firstPosition.ref(events.first())),
                events.last().effects.single { it.field == SyncField.RESUME_POSITION }.parents,
            )
        }
    }

    @Test
    fun `a reader opened in another space cannot upload into the new space`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            val session = storage.recorder.openSession(chapter.id)
            storage.journal.connect("other", 1, SyncRepository("owner", "another", "sync"), "reader", 1)
            session.await(storage.reading(chapter, page = 3))
            assertEquals(3, storage.chapters.getChapterById(chapter.id)!!.lastPageRead)
            assertTrue(storage.journal.pendingEvents("other", 1).isEmpty())
        }
    }

    @Test
    fun `a finished reading produces one atomic multi effect envelope and retries do not duplicate it`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            val event = storage.reading(chapter, page = 9)
            repeat(2) { storage.recorder.await(event) }
            val recorded = storage.journal.pendingEvents("space", 1).single()
            assertEquals(SyncCategory.READING, recorded.category)
            assertEquals(
                setOf(SyncField.READ_STATUS, SyncField.RESUME_POSITION, SyncField.READING_SUMMARY),
                recorded.effects.map { it.field }.toSet(),
            )
            val read = recorded.effects.single { it.field == SyncField.READ_STATUS }
            assertEquals("/chapter", read.objectKey.originalUrl)
            assertEquals("/manga", read.objectKey.parentUrl)
            assertEquals("9223372036854775806", read.objectKey.sourceId)
            assertTrue(storage.chapters.getChapterById(chapter.id)!!.read)
        }
    }

    @Test
    fun `partial rereading updates the position without restating read status`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            storage.recorder.await(storage.reading(chapter, page = 9))
            storage.recorder.await(storage.reading(chapter, page = 2).copy(wasRead = true))
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(2, events.size)
            val reread = events.last()
            assertEquals(
                setOf(SyncField.RESUME_POSITION, SyncField.READING_SUMMARY),
                reread.effects.map { it.field }.toSet(),
            )
            assertEquals(2, storage.chapters.getChapterById(chapter.id)!!.lastPageRead)
            assertTrue(storage.chapters.getChapterById(chapter.id)!!.read)
        }
    }

    @Test
    fun `disabled history is independent of sync permission and incognito is never backfilled`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            storage.recorder.await(storage.reading(chapter, page = 3).copy(syncContext = SyncMutationContext.LocalOnly))
            assertEquals(3, storage.chapters.getChapterById(chapter.id)!!.lastPageRead)
            assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
            storage.recorder.await(storage.reading(chapter, page = 4).copy(recordHistory = false))
            val event = storage.journal.pendingEvents("space", 1).single()
            assertEquals(1, event.seq)
            assertEquals(SyncOrigin.USER, event.origin)
            assertEquals(
                "4",
                event.effects.single {
                    it.field == SyncField.RESUME_POSITION
                }.payload["pageIndex"].toString(),
            )
        }
    }

    @Test
    fun `explicit mark unread retains intent even when the local chapter is already unread`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            storage.markRead.awaitOrThrow(chapter, false)
            storage.markRead.awaitOrThrow(chapter, false)
            val events = storage.journal.pendingEvents("space", 1)
            assertEquals(2, events.size)
            assertTrue(events.all { it.effects.single().kind == SyncEffectKind.MARK_UNREAD })
            assertEquals(
                listOf(events.first().effects.single().ref(events.first())),
                events.last().effects.single().parents,
            )
        }
    }

    @Test
    fun `metadata and remote chapter updates never become manual read operations`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            for (context in listOf(SyncMutationContext.Metadata, SyncMutationContext(SyncOrigin.REMOTE_SYNC))) {
                storage.chapters.update(ChapterUpdate(chapter.id, read = true, syncContext = context))
            }
            assertTrue(storage.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `outbox failure rolls back reading progress and permits the same idempotency key to retry`() = runBlocking {
        open().use { storage ->
            val chapter = storage.seed()
            storage.driver.execute(
                null,
                "CREATE TRIGGER fail_reading BEFORE INSERT ON sync_outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic reading outbox failure'); END;",
                0,
            )
            val reading = storage.reading(chapter, page = 9)
            assertTrue(runCatching { storage.recorder.await(reading) }.isFailure)
            assertFalse(storage.chapters.getChapterById(chapter.id)!!.read)
            assertEquals(0, storage.chapters.getChapterById(chapter.id)!!.lastPageRead)
            storage.driver.execute(null, "DROP TRIGGER fail_reading", 0)
            storage.recorder.await(reading)
            assertEquals(1, storage.journal.pendingEvents("space", 1).size)
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

    protected class Storage(val driver: SqlDriver, database: Database, handler: DatabaseHandler) : AutoCloseable {
        val journal = SyncLocalJournal(handler)
        val chapters = ChapterRepositoryImpl(handler)
        val mangas = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val recorder = RecordReadingProgress(SqlDelightReadingProgressRepository(database))
        val markRead = SetChapterReadStatus(GetChaptersByMangaId(chapters), UpdateChapter(chapters))

        suspend fun seed(): Chapter {
            journal.connect("space", 1, SyncRepository("owner", "sync-data", "sync"), "reader", 1)
            val manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = Long.MAX_VALUE - 1, url = "/manga", title = "Reading")),
            ).single()
            return chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "Chapter")),
            ).single()
        }

        fun reading(chapter: Chapter, page: Int) = ReadingProgressEvent(
            chapter.id,
            page,
            10,
            Date(1_000),
            5,
            syncContext = SyncMutationContext.User,
        )

        override fun close() = driver.close()
    }
}
