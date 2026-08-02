package tachiyomi.data.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.util.Date

class SqlDelightReadingProgressRepositoryTest {

    @Test
    fun `reading a later chapter updates only that row and requires its last page to mark read`() = runTest {
        val fixture = fixture()
        val recorder = RecordReadingProgress(SqlDelightReadingProgressRepository(fixture.database))

        recorder.await(
            ReadingProgressEvent(
                chapterId = 3,
                lastPageRead = 4,
                totalPages = 10,
                readAt = Date(1_000),
                sessionReadDuration = 0,
                recordHistory = false,
                idempotencyKey = "chapter-3-partial",
            ),
        )

        assertFalse(fixture.chapter(1).read)
        assertFalse(fixture.chapter(2).read)
        assertFalse(fixture.chapter(3).read)
        assertEquals(4, fixture.chapter(3).last_page_read)

        recorder.await(
            ReadingProgressEvent(
                chapterId = 3,
                lastPageRead = 9,
                totalPages = 10,
                readAt = Date(2_000),
                sessionReadDuration = 0,
                recordHistory = false,
                idempotencyKey = "chapter-3-finished",
            ),
        )

        assertFalse(fixture.chapter(1).read)
        assertFalse(fixture.chapter(2).read)
        assertTrue(fixture.chapter(3).read)
    }

    @Test
    fun `partial progress preserves an existing read state`() = runTest {
        val fixture = fixture()
        fixture.database.chaptersQueries.update(
            mangaId = null,
            url = null,
            name = null,
            scanlator = null,
            read = true,
            bookmark = null,
            lastPageRead = 9,
            chapterNumber = null,
            sourceOrder = null,
            dateFetch = null,
            dateUpload = null,
            chapterId = 2,
            version = null,
            isSyncing = 0,
        )
        val recorder = RecordReadingProgress(SqlDelightReadingProgressRepository(fixture.database))

        recorder.await(
            ReadingProgressEvent(
                chapterId = 2,
                lastPageRead = 2,
                totalPages = 10,
                readAt = Date(3_000),
                sessionReadDuration = 0,
                wasRead = true,
                recordHistory = false,
                idempotencyKey = "chapter-2-revisit",
            ),
        )

        assertTrue(fixture.chapter(2).read)
        assertEquals(2, fixture.chapter(2).last_page_read)
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, artist, author, description, genre, title, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, notes, is_syncing) VALUES (10, 1, '/', NULL, NULL, NULL, NULL, 'Manga', 0, NULL, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, 0, '', 0)",
            0,
        )
        (1L..3L).forEach { chapterId ->
            driver.execute(
                null,
                "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) VALUES ($chapterId, 10, '/$chapterId', 'Chapter $chapterId', NULL, 0, 0, 0, $chapterId, $chapterId, 0, 0)",
                0,
            )
        }
        return Fixture(database)
    }

    private data class Fixture(val database: Database) {
        fun chapter(id: Long) = database.chaptersQueries.getChapterById(id).executeAsOne()
    }
}
