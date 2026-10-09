package tachiyomi.data.reader

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.sync.SyncCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.chapter.SourceChapterCatalogWriter
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import java.io.File

abstract class NonDeletingChapterCatalogContract {
    protected abstract fun open(file: File): HistoryDataStorage

    @Test
    fun `merge preserves stable reading rows and records complete metadata atomically`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("catalog.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            s.progress.record(s.reading(chapter, 7))
            s.chapters.update(ChapterUpdate(chapter.id, bookmark = true, dateFetch = 1234))
            val before = requireNotNull(s.chapters.getChapterById(chapter.id))
            val events = s.journal.pendingEvents("space", 1)
            val archive = CreatorRepositoryImpl(s.handler)
            val writer = SourceChapterCatalogWriter(s.chapters, archive, s.handler)
            val result = writer.transaction { writer.merge(manga, remote()) }
            assertEquals(3, result.chapters.size)
            assertEquals(2, result.added.size)
            val after = requireNotNull(s.chapters.getChapterById(chapter.id))
            assertEquals(before.id, after.id)
            assertEquals(before.lastPageRead, after.lastPageRead)
            assertEquals(before.bookmark, after.bookmark)
            assertEquals(before.read, after.read)
            assertEquals(before.dateFetch, after.dateFetch)
            assertEquals(events, s.journal.pendingEvents("space", 1))
            assertFalse(writer.needsRefresh(manga))
            writer.transaction { writer.merge(manga, remote().dropLast(1)) }
            assertEquals(
                3,
                s.chapters.getChapterByMangaId(manga.id).size,
                "A source omission cannot delete persisted chapters",
            )
        }
    }

    @Test
    fun `swallowed insert failure rolls back matching fields and catalog observation`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("catalog.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            s.chapters.update(ChapterUpdate(chapter.id, sourceOrder = 9, bookmark = true, lastPageRead = 7))
            val before = requireNotNull(s.chapters.getChapterById(chapter.id))
            s.driver.execute(
                null,
                "CREATE TRIGGER reject_catalog BEFORE INSERT ON chapters WHEN NEW.url = '/first' BEGIN SELECT RAISE(ABORT, 'catalog failure'); END",
                0,
            )
            val archive = CreatorRepositoryImpl(s.handler)
            val writer = SourceChapterCatalogWriter(s.chapters, archive, s.handler)
            assertThrows(Exception::class.java) { runBlocking { writer.transaction { writer.merge(manga, remote()) } } }
            assertEquals(listOf(before), s.chapters.getChapterByMangaId(manga.id))
            val observation = s.handler.await {
                author_archiveQueries.getArchiveSourceWorkByKey(42, manga.url).executeAsOneOrNull()
            }
            assertNull(observation)
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `bootstrap waits outside transaction and late changed identity is rejected`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("catalog.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            val waiting = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            var enteredTransaction = false
            val writer = SourceChapterCatalogWriter(
                s.chapters,
                CreatorRepositoryImpl(s.handler),
                s.handler,
                object : CreatorArchiveBootstrap {
                    override suspend fun awaitReady() {
                        waiting.complete(Unit)
                        ready.await()
                    }
                },
            )
            val merge =
                async {
                    runCatching {
                        writer.transaction {
                            enteredTransaction = true
                            writer.merge(manga, remote())
                        }
                    }
                }
            withTimeout(5_000) { waiting.await() }
            assertFalse(enteredTransaction)
            // The real handler remains usable while archive bootstrap is pending.
            s.handler.await(inTransaction = true) { mangasQueries.getMangaById(manga.id).executeAsOne() }
            s.driver.execute(null, "UPDATE mangas SET source = 43, url = '/changed' WHERE _id = ${manga.id}", 0)
            ready.complete(Unit)
            assertTrue(merge.await().isFailure)
            assertEquals(listOf(chapter), s.chapters.getChapterByMangaId(manga.id))
        }
    }

    private fun remote() = listOf("/last" to 3, "/chapter" to 2, "/first" to 1).map { (url, number) ->
        SChapter.create().apply {
            this.url = url
            name = "Chapter $number"
            chapter_number = number.toFloat()
        }
    }
}
