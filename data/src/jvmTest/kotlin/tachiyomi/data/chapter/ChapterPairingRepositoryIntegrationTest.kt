package tachiyomi.data.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.MissingChapterPairingIdentityException
import mihon.domain.reader.StaleChapterPairingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.nio.file.Files

class ChapterPairingRepositoryIntegrationTest {
    @Test
    fun `file database persists two boundaries then atomically clears only one chapter`() = runBlocking {
        val path = Files.createTempFile("chapter-pairing", ".sqlite")
        try {
            open(path.toString(), create = true).use { first ->
                seed(first)
                val repository = ChapterPairingRepositoryImpl(first)
                val saved = repository.replace(11, 10, 0, 8, setOf(4, 1))
                assertEquals(setOf(1, 4), repository.load(11, 10).record?.forcedSinglePages)
                repository.replace(12, 10, 0, 8, setOf(2))
                assertThrows(StaleChapterPairingException::class.java) {
                    runBlocking { repository.replace(11, 10, 0, 8, setOf(3)) }
                }
                assertEquals(1L, saved.revision)
            }
            open(path.toString()).use { reopened ->
                val repository = ChapterPairingRepositoryImpl(reopened)
                assertEquals(setOf(1, 4), repository.load(11, 10).record?.forcedSinglePages)
                repository.replace(11, 10, 1, 8, emptySet())
                assertNull(repository.load(11, 10).record)
                assertEquals(2L, repository.load(11, 10).revision)
                assertEquals(setOf(2), repository.load(12, 10).record?.forcedSinglePages)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `failed boundary insert rolls back prior boundaries and chapter deletion cascades`() = runBlocking {
        open(JdbcSqliteDriver.IN_MEMORY, create = true).use { handler ->
            seed(handler)
            val repository = ChapterPairingRepositoryImpl(handler)
            repository.replace(11, 10, 0, 8, setOf(1, 3))
            handler.db.run {
                // A SQL fault after the state row and first boundary insert must roll back all three changes.
                chapter_pairingsQueries.selectPairing(11).executeAsOne()
            }
            val driver = handlerDriver(handler)
            driver.execute(
                null,
                "CREATE TRIGGER pairing_fault BEFORE INSERT ON chapter_pairing_boundaries " +
                    "WHEN NEW.page_ordinal = 4 BEGIN SELECT RAISE(FAIL, 'injected'); END",
                0,
            )
            assertThrows(Exception::class.java) {
                runBlocking { repository.replace(11, 10, 1, 8, setOf(2, 4)) }
            }
            assertEquals(setOf(1, 3), repository.load(11, 10).record?.forcedSinglePages)
            driver.execute(null, "DROP TRIGGER pairing_fault", 0)
            driver.execute(null, "DELETE FROM chapters WHERE _id = 11", 0)
            assertEquals(0L, queryLong(driver, "SELECT COUNT(*) FROM chapter_pairing_boundaries"))
            assertEquals(0L, queryLong(driver, "SELECT COUNT(*) FROM chapter_pairing_revisions WHERE chapter_id=11"))
        }
    }

    @Test
    fun `clearing and reinserting never accepts a revision from an older session`() = runBlocking {
        open(JdbcSqliteDriver.IN_MEMORY, create = true).use { handler ->
            seed(handler)
            val repository = ChapterPairingRepositoryImpl(handler)
            repository.replace(11, 10, 0, 8, setOf(1))
            repository.replace(11, 10, 1, 8, emptySet())
            repository.replace(11, 10, 2, 8, setOf(3))

            assertThrows(StaleChapterPairingException::class.java) {
                runBlocking { repository.replace(11, 10, 1, 8, setOf(4)) }
            }
            assertEquals(setOf(3), repository.load(11, 10).record?.forcedSinglePages)
            assertEquals(3L, repository.load(11, 10).revision)
        }
    }

    @Test
    fun `oversized database ordinals and page count cannot wrap into a valid record`() = runBlocking {
        open(JdbcSqliteDriver.IN_MEMORY, create = true).use { handler ->
            seed(handler)
            val driver = handlerDriver(handler)
            driver.execute(
                null,
                "INSERT INTO chapter_pairings(chapter_id, format_version, page_count, revision) " +
                    "VALUES (11, 1, 4294967304, 1)",
                0,
            )
            driver.execute(null, "INSERT INTO chapter_pairing_revisions(chapter_id, revision) VALUES (11, 1)", 0)
            driver.execute(
                null,
                "INSERT INTO chapter_pairing_boundaries(chapter_id, page_ordinal) " +
                    "VALUES (11, 4294967297)",
                0,
            )

            assertEquals(false, ChapterPairingRepositoryImpl(handler).load(11, 10).record?.isValidFor(8))
        }
    }

    @Test
    fun `chapter and manga identity must match before either reading or writing`() = runBlocking {
        open(JdbcSqliteDriver.IN_MEMORY, create = true).use { handler ->
            seed(handler)
            val repository = ChapterPairingRepositoryImpl(handler)
            assertThrows(MissingChapterPairingIdentityException::class.java) {
                runBlocking { repository.load(11, 999) }
            }
            assertThrows(MissingChapterPairingIdentityException::class.java) {
                runBlocking { repository.replace(11, 999, 0, 8, setOf(1)) }
            }
            assertThrows(MissingChapterPairingIdentityException::class.java) {
                runBlocking { repository.load(0, 10) }
            }
            assertEquals(0L, queryLong(handlerDriver(handler), "SELECT COUNT(*) FROM chapter_pairing_revisions"))
        }
    }

    private val openDrivers = mutableMapOf<JvmDatabaseHandler, JdbcSqliteDriver>()

    private fun open(url: String, create: Boolean = false): JvmDatabaseHandler {
        val driver = JdbcSqliteDriver(if (url == JdbcSqliteDriver.IN_MEMORY) url else "jdbc:sqlite:$url")
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        return JvmDatabaseHandler(
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        ).also { openDrivers[it] = driver }
    }

    private fun seed(handler: JvmDatabaseHandler) {
        val driver = handlerDriver(handler)
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) VALUES (10, 1, '/', 'M', 0, 0, 0, 0, 0, 0, 0)",
            0,
        )
        listOf(11, 12).forEach { id ->
            driver.execute(
                null,
                "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES ($id, 10, '/$id', 'C', 0, 0, 0, 1, 0, 0, 0)",
                0,
            )
        }
    }

    private fun handlerDriver(handler: JvmDatabaseHandler) = checkNotNull(openDrivers[handler])

    private fun queryLong(driver: JdbcSqliteDriver, sql: String) = driver.executeQuery(null, sql, { cursor ->
        app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
    }, 0).value
}
