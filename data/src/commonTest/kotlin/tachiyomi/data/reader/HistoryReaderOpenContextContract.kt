package tachiyomi.data.reader

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.io.File
import java.util.Date

abstract class HistoryReaderOpenContextContract {
    protected abstract fun open(file: File): HistoryDataStorage

    @Test
    fun `ordinary local reading gets its real local page without a sync scope`(@TempDir directory: File) = runBlocking {
        open(directory.resolve("reader.sqlite")).use { s ->
            val (manga, chapter) = s.seed(connect = false)
            s.chapters.update(ChapterUpdate(chapter.id, lastPageRead = 4))
            val opened = requireNotNull(s.progress.openChapter(s.identity(manga, chapter)))
            assertEquals(4, opened.pageIndex)
            assertNull(opened.snapshot.scope)
            assertFalse(opened.resumedWithinChapter)
        }
    }

    @Test
    fun `selected unread chapter resumes its atomic page and snapshot`(@TempDir directory: File) = runBlocking {
        open(directory.resolve("reader.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            s.progress.record(s.reading(chapter, 7))
            val expected = requireNotNull(s.progress.resumePosition(manga.id))
            val opened = requireNotNull(s.progress.openChapter(s.identity(manga, chapter)))
            assertEquals(manga.id, opened.manga.id)
            assertEquals(chapter.id, opened.chapter.id)
            assertEquals(7, opened.pageIndex)
            assertEquals(expected.snapshot, opened.snapshot)
            assertTrue(opened.resumedWithinChapter)
            s.progress.record(s.reading(chapter, 2))
            assertEquals(7, opened.pageIndex)
            assertEquals(expected.snapshot, opened.snapshot)
        }
    }

    @Test
    fun `read target and another selected target use fresh legitimate baselines`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("reader.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            s.progress.record(s.reading(chapter, 8))
            s.chapters.update(ChapterUpdate(chapter.id, read = true))
            val read = requireNotNull(s.progress.openChapter(s.identity(manga, chapter)))
            assertEquals(0, read.pageIndex)
            assertFalse(read.resumedWithinChapter)
            assertTrue(read.chapter.read)
            assertEquals(s.progress.beginSyncSession(chapter.id), read.snapshot)
            assertNotNull(read.snapshot.scope)
            val next = s.chapters.addAll(listOf(chapter.copy(id = -1, url = "/next", lastPageRead = 3))).single()
            val opened = requireNotNull(s.progress.openChapter(s.identity(manga, next)))
            assertEquals(next.id, opened.chapter.id)
            assertEquals(3, opened.pageIndex)
            assertFalse(opened.resumedWithinChapter)
            assertEquals(s.progress.beginSyncSession(next.id), opened.snapshot)
            assertNotNull(opened.snapshot.scope)
        }
    }

    @Test
    fun `stale source work or chapter identity cannot be stitched into an open context`(
        @TempDir directory: File,
    ) = runBlocking {
        open(directory.resolve("reader.sqlite")).use { s ->
            val (manga, chapter) = s.seed()
            val target = s.identity(manga, chapter)
            assertNotNull(s.progress.openChapter(target))
            assertNull(s.progress.openChapter(target.copy(sourceId = 99)))
            assertNull(s.progress.openChapter(target.copy(mangaUrl = "/different")))
            assertNull(s.progress.openChapter(target.copy(chapterUrl = "/different")))
            s.chapters.update(ChapterUpdate(chapter.id, url = "/changed"))
            assertNull(s.progress.openChapter(target))
            s.chapters.removeChaptersWithIds(listOf(chapter.id))
            assertNull(s.progress.openChapter(target))
        }
    }
}
