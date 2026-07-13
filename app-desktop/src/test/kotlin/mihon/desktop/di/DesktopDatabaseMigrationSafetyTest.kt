package mihon.desktop.di

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopDatabaseMigrationSafetyTest {
    @TempDir lateinit var directory: File

    @Test
    fun `database open failure preserves the original file and reports its path`() {
        val database = File(directory, "mihon.db").apply { writeText("not sqlite") }
        val original = database.readBytes()

        val failure = shouldThrow<IllegalStateException> { createDriver(database) }

        database.readBytes().contentEquals(original) shouldBe true
        failure.message.orEmpty() shouldContain database.absolutePath
        failure.message.orEmpty() shouldContain "preserved"
    }

    @Test
    fun `interrupted version 11 migration with all new objects resumes and records latest version`() {
        val database = File(directory, "mihon.db")
        JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}").use { driver ->
            driver.execute(null, "CREATE TABLE chapters(_id INTEGER NOT NULL PRIMARY KEY)", 0)
            driver.execute(null, "CREATE TABLE download_queue(chapter_id INTEGER NOT NULL PRIMARY KEY, manga_id INTEGER NOT NULL, source_id INTEGER NOT NULL, manga_title TEXT NOT NULL, chapter_name TEXT NOT NULL, chapter_url TEXT NOT NULL, page_urls TEXT NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0, failure TEXT)", 0)
            driver.execute(null, "CREATE TABLE reading_events(_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, idempotency_key TEXT NOT NULL UNIQUE, chapter_id INTEGER NOT NULL, event TEXT NOT NULL, page INTEGER NOT NULL, occurred_at INTEGER NOT NULL, FOREIGN KEY(chapter_id) REFERENCES chapters(_id) ON DELETE CASCADE)", 0)
            driver.execute(null, "PRAGMA user_version = 11", 0)
        }

        createDriver(database).close()

        JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}").use { driver ->
            queryUserVersion(driver) shouldBe Database.Schema.version.toInt()
        }
        createDriver(database).close()
    }

    private fun queryUserVersion(driver: JdbcSqliteDriver): Int = driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!!.toInt() else 0) },
        0,
    ).value
}
