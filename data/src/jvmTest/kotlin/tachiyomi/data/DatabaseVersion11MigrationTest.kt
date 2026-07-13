package tachiyomi.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DatabaseVersion11MigrationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `version 11 database migrates through download and reading tables without losing user data`() {
        val file = File(directory, "version-11.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        driver.execute(
            null,
            "CREATE TABLE categories(_id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, sort INTEGER NOT NULL, flags INTEGER NOT NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO categories VALUES (7, 'User category', 3, 5)", 0)
        driver.execute(null, "CREATE TABLE chapters(_id INTEGER NOT NULL PRIMARY KEY)", 0)
        driver.execute(null, "INSERT INTO chapters VALUES (42)", 0)
        driver.execute(null, "PRAGMA user_version = 11", 0)

        Database.Schema.migrate(driver, 11, Database.Schema.version)
        driver.execute(null, "PRAGMA user_version = ${Database.Schema.version}", 0)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )

        database.categoriesQueries.getCategory(7).executeAsOne().name shouldBe "User category"
        database.download_queueQueries.upsert(9, 1, 2, "Manga", "Chapter", "/c", "[]", "QUEUED", 0, 0, 2, null)
        database.download_queueQueries.selectAll().executeAsOne().retry_count shouldBe 2
        database.download_queueQueries.selectAll().executeAsOne().failure shouldBe null
        database.reading_eventsQueries.insertEvent("migration-event", 42, "PROGRESS", 6, 100)
        database.reading_eventsQueries.countByChapter(42).executeAsOne() shouldBe 1
        driver.close()
    }

    @Test
    fun `version 11 database resumes after download table was created by an interrupted migration`() {
        val file = File(directory, "interrupted-version-11.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        driver.execute(null, "CREATE TABLE chapters(_id INTEGER NOT NULL PRIMARY KEY)", 0)
        driver.execute(
            null,
            """CREATE TABLE download_queue(
                chapter_id INTEGER NOT NULL PRIMARY KEY, manga_id INTEGER NOT NULL, source_id INTEGER NOT NULL,
                manga_title TEXT NOT NULL, chapter_name TEXT NOT NULL, chapter_url TEXT NOT NULL,
                page_urls TEXT NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "PRAGMA user_version = 11", 0)

        Database.Schema.migrate(driver, 11, Database.Schema.version)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )

        database.download_queueQueries.upsert(9, 1, 2, "Manga", "Chapter", "/c", "[]", "QUEUED", 0, 0, 0, null)
        database.download_queueQueries.selectAll().executeAsOne().failure shouldBe null
        driver.close()
    }

    @Test
    fun `version 13 download rows migrate with null failure`() {
        val file = File(directory, "version-13.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        driver.execute(
            null,
            """CREATE TABLE download_queue(
                chapter_id INTEGER NOT NULL PRIMARY KEY, manga_id INTEGER NOT NULL, source_id INTEGER NOT NULL,
                manga_title TEXT NOT NULL, chapter_name TEXT NOT NULL, chapter_url TEXT NOT NULL,
                page_urls TEXT NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO download_queue VALUES (9, 1, 2, 'Manga', 'Chapter', '/c', '[]', 'ERROR', 0, 0, 2)",
            0,
        )

        Database.Schema.migrate(driver, 13, Database.Schema.version)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )

        database.download_queueQueries.selectAll().executeAsOne().failure shouldBe null
        driver.close()
    }
}
