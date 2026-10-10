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

    @Test
    fun `canonical directory commits metadata and complete observation with preserved user state`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("canonical.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            s.chapters.update(ChapterUpdate(chapter.id, bookmark = true, lastPageRead = 7, dateFetch = 1234))
            val before = requireNotNull(s.chapters.getChapterById(chapter.id))
            val events = s.journal.pendingEvents("space", 1)
            val archive = CreatorRepositoryImpl(s.handler)
            val writer = SourceChapterCatalogWriter(s.chapters, archive, s.handler)
            val committed = writer.commitDirectory(manga, canonicalRequest(manga))
            val after = requireNotNull(s.chapters.getChapterById(chapter.id))
            assertEquals(before.id, after.id)
            assertEquals(before.bookmark, after.bookmark)
            assertEquals(before.lastPageRead, after.lastPageRead)
            assertEquals(before.dateFetch, after.dateFetch)
            assertEquals(events, s.journal.pendingEvents("space", 1))
            assertEquals("Updated metadata", s.mangas.getMangaById(manga.id).title)
            val observation = checkNotNull(
                archive.getSourceWorkCatalog(
                    tachiyomi.domain.creator.model.SourceWorkNaturalKey(manga.source, manga.url),
                    manga.id,
                ),
            )
            assertEquals(tachiyomi.domain.creator.model.ChapterCatalogCompleteness.COMPLETE, observation.completeness)
            assertEquals(3L, observation.chapterCount)
            assertFalse(committed.phase?.observationPending == true)
        }
    }

    @Test
    fun `canonical directory rejects foreign archive binding before metadata or chapter writes`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("canonical-identity.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            val other = s.mangas.insertNetworkManga(
                listOf(
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = manga.source,
                        url = "/other",
                        title = "Other",
                    ),
                ),
            ).single()
            val archive = CreatorRepositoryImpl(s.handler)
            archive.upsertSourceWork(manga.source, manga.url, other.id, "Foreign", null, null, null, null)
            val writer = SourceChapterCatalogWriter(s.chapters, archive, s.handler)
            assertThrows(Exception::class.java) {
                runBlocking { writer.commitDirectory(manga, canonicalRequest(manga)) }
            }
            assertEquals(manga, s.mangas.getMangaById(manga.id))
            assertEquals(listOf(chapter), s.chapters.getChapterByMangaId(manga.id))
            assertEquals(
                other.id,
                s.handler.await {
                    author_archiveQueries.getArchiveSourceWorkByKey(manga.source, manga.url).executeAsOne().manga_id
                },
            )
        }
    }

    @Test
    fun `canonical directory observation SQL failure rolls back metadata chapters and phase`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("canonical-observation.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            val archive = CreatorRepositoryImpl(s.handler)
            archive.upsertSourceWork(manga.source, manga.url, manga.id, manga.title, null, null, null, null)
            s.driver.execute(
                null,
                "CREATE TRIGGER reject_complete BEFORE UPDATE ON author_archive_source_works " +
                    "WHEN NEW.chapter_count_state='COMPLETE' BEGIN SELECT RAISE(ABORT, 'observation failure'); END",
                0,
            )
            val writer = SourceChapterCatalogWriter(s.chapters, archive, s.handler)
            assertThrows(Exception::class.java) {
                runBlocking { writer.commitDirectory(manga, canonicalRequest(manga)) }
            }
            assertEquals(manga, s.mangas.getMangaById(manga.id))
            assertEquals(listOf(chapter), s.chapters.getChapterByMangaId(manga.id))
            assertNull(s.chapters.pendingDirectoryPhase(manga.id))
            assertEquals(
                "UNKNOWN",
                s.handler.await {
                    author_archiveQueries.getArchiveSourceWorkByKey(
                        manga.source,
                        manga.url,
                    ).executeAsOne().chapter_count_state
                },
            )
        }
    }

    private fun canonicalRequest(manga: tachiyomi.domain.manga.model.Manga):
        tachiyomi.domain.chapter.service.ChapterDirectoryCommit {
        val chapters = remote()
        return tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
            manga.id,
            tachiyomi.domain.chapter.service.ChapterDirectoryPlan.prepare(manga, chapters),
            2000,
            mangaMetadata = tachiyomi.domain.manga.model.MangaUpdate(manga.id, title = "Updated metadata"),
            effects = tachiyomi.domain.chapter.service.ChapterDirectoryEffects(
                manga.source,
                "Fixture",
                manga.url,
                manga.title,
                "DETAIL_REFRESH",
                2000,
                dates = chapters.map { tachiyomi.domain.chapter.service.DirectoryChapterDate(it.url, it.date_upload) },
                observe = true,
            ),
        )
    }
}
