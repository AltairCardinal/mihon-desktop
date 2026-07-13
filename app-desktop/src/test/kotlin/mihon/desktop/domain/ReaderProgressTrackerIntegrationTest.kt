package mihon.desktop.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.util.Date

class ReaderProgressTrackerIntegrationTest {
    @Test
    fun `production tracker deduplicates retries and concurrency by exit event but accumulates distinct exits`() = runBlocking {
        val fixture = fixture()
        val tracker = ReaderProgressTracker(fixture.recorder)

        (1..8).map { async {
            tracker.track("session-1-exit", 1, 5, 10, mangaId = 10, readAt = Date(1234), sessionReadDuration = 500)
        } }.awaitAll()
        tracker.track("session-2-exit", 1, 5, 10, mangaId = 10, readAt = Date(1234), sessionReadDuration = 500)

        fixture.database.historyQueries.getHistoryByMangaId(10).executeAsOne().time_read shouldBe 1000
        fixture.database.reading_eventsQueries.countByChapter(1).executeAsOne() shouldBe 2
    }

    @Test
    fun `progress history read flag and tracker event commit together`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        driver.execute(null, "INSERT INTO mangas(_id, source, url, artist, author, description, genre, title, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, notes, is_syncing) VALUES (10, 1, '/', NULL, NULL, NULL, NULL, 'Manga', 0, NULL, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, 0, '', 0)", 0)
        driver.execute(null, "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) VALUES (1, 10, '/1', 'Chapter', NULL, 0, 0, 0, 1, 0, 0, 0)", 0)
        val recorder = RecordReadingProgress(SqlDelightReadingProgressRepository(database))

        recorder.await(ReadingProgressEvent(1, 9, 10, Date(1234), 500, trackerEvent = "finished", idempotencyKey = "reader-event-1"))

        database.chaptersQueries.getChapterById(1).executeAsOne().let {
            it.last_page_read shouldBe 9
            it.read shouldBe true
        }
        database.historyQueries.getHistoryByMangaId(10).executeAsOne().time_read shouldBe 500
        database.reading_eventsQueries.countByChapter(1).executeAsOne() shouldBe 1
        Unit
    }

    @Test
    fun `duplicate and concurrent event key mutates chapter history and tracker event once`() = runBlocking {
        val fixture = fixture()
        val event = ReadingProgressEvent(1, 5, 10, Date(1234), 500, idempotencyKey = "same-reader-event")

        (1..8).map { async { fixture.recorder.await(event) } }.awaitAll()

        fixture.database.historyQueries.getHistoryByMangaId(10).executeAsOne().time_read shouldBe 500
        fixture.database.reading_eventsQueries.countByChapter(1).executeAsOne() shouldBe 1
        Unit
    }

    @Test
    fun `different event keys accumulate reading duration and tracker events`() = runBlocking {
        val fixture = fixture()

        fixture.recorder.await(ReadingProgressEvent(1, 3, 10, Date(1234), 200, idempotencyKey = "event-a"))
        fixture.recorder.await(ReadingProgressEvent(1, 4, 10, Date(2234), 300, idempotencyKey = "event-b"))

        fixture.database.historyQueries.getHistoryByMangaId(10).executeAsOne().time_read shouldBe 500
        fixture.database.reading_eventsQueries.countByChapter(1).executeAsOne() shouldBe 2
        Unit
    }

    @Test
    fun `history failure rolls back chapter history and event`() = runBlocking {
        val fixture = fixture()
        fixture.driver.execute(null, "CREATE TRIGGER fail_history BEFORE INSERT ON history BEGIN SELECT RAISE(FAIL, 'history failed'); END", 0)

        runCatching { fixture.recorder.await(ReadingProgressEvent(1, 7, 10, Date(1234), 500, idempotencyKey = "history-failure")) }

        fixture.database.chaptersQueries.getChapterById(1).executeAsOne().last_page_read shouldBe 0
        fixture.database.reading_eventsQueries.countByChapter(1).executeAsOne() shouldBe 0
        Unit
    }

    @Test
    fun `tracker event failure rolls back chapter and history`() = runBlocking {
        val fixture = fixture()
        fixture.driver.execute(null, "CREATE TRIGGER fail_event BEFORE INSERT ON reading_events BEGIN SELECT RAISE(FAIL, 'event failed'); END", 0)

        runCatching { fixture.recorder.await(ReadingProgressEvent(1, 7, 10, Date(1234), 500, idempotencyKey = "event-failure")) }

        fixture.database.chaptersQueries.getChapterById(1).executeAsOne().last_page_read shouldBe 0
        fixture.database.historyQueries.getHistoryByMangaId(10).executeAsList().size shouldBe 0
        Unit
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        driver.execute(null, "INSERT INTO mangas(_id, source, url, artist, author, description, genre, title, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, notes, is_syncing) VALUES (10, 1, '/', NULL, NULL, NULL, NULL, 'Manga', 0, NULL, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, 0, '', 0)", 0)
        driver.execute(null, "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) VALUES (1, 10, '/1', 'Chapter', NULL, 0, 0, 0, 1, 0, 0, 0)", 0)
        return Fixture(driver, database, RecordReadingProgress(SqlDelightReadingProgressRepository(database)))
    }

    private data class Fixture(
        val driver: JdbcSqliteDriver,
        val database: Database,
        val recorder: RecordReadingProgress,
    )
}
