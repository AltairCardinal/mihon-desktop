package tachiyomi.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Properties

/** Uses the SQL actually shipped by each branch, including APK 70's sync schema 43. */
abstract class PublishedSchema40ConvergenceContract {
    @Test
    fun `main schema 41 retains chapter identity floor and gains durable pause clocks`() {
        withFile { driver ->
            prepareSchema40(driver)
            releasedMigration(driver, "main-40")
            driver.execute(null, "DELETE FROM chapters WHERE _id=41", 0)
            driver.execute(null, "PRAGMA user_version=41", 0)
            DatabaseMigration.migrateAtomically(driver, 41, Database.Schema.version)
            assertEquals(41L, value(driver, "SELECT value FROM chapter_id_floor"))
            assertEquals(11000L, value(driver, "SELECT paused_at FROM sync_runtime_pause_clock"))
            assertEquals(1000L, value(driver, "SELECT planned_at FROM sync_runtime_pause_clock"))
            driver.execute(null, "UPDATE sync_runtime_runs SET state='RUNNING',updated_at=71000", 0)
            assertEquals(60000L, value(driver, "SELECT paused_millis FROM sync_runtime_pause_clock"))
        }
    }

    @Test
    fun `published sync schemas 41 through 43 gain chapter identity without losing pause or repair data`() {
        for (version in 41L..43L) {
            withFile { driver ->
                prepareSchema40(driver)
                releasedMigration(driver, "sync-40")
                driver.execute(null, "UPDATE sync_runtime_pause_clock SET paused_millis=7000", 0)
                if (version >= 42) releasedMigration(driver, "sync-41")
                if (version >= 43) {
                    releasedMigration(driver, "sync-42")
                    driver.execute(
                        null,
                        "INSERT INTO sync_repair_failures VALUES('space',1,'BATCH','bad','evidence','path','reason','original',0)",
                        0,
                    )
                }
                driver.execute(null, "PRAGMA user_version=$version", 0)
                DatabaseMigration.migrateAtomically(driver, version, Database.Schema.version)
                assertEquals(41L, value(driver, "SELECT value FROM chapter_id_floor"))
                assertEquals(9L, value(driver, "SELECT last_page_read FROM chapters WHERE _id=41"))
                assertEquals(7000L, value(driver, "SELECT paused_millis FROM sync_runtime_pause_clock"))
                assertEquals(11000L, value(driver, "SELECT paused_at FROM sync_runtime_pause_clock"))
                if (version >= 43) assertEquals(1L, value(driver, "SELECT COUNT(*) FROM sync_repair_failures"))
                driver.execute(null, "INSERT INTO chapter_url_aliases VALUES(41,10,'/old','/old')", 0)
                driver.execute(null, "DELETE FROM chapters WHERE _id=41", 0)
                assertEquals(0L, value(driver, "SELECT COUNT(*) FROM chapter_url_aliases"))
                assertEquals(41L, value(driver, "SELECT value FROM chapter_id_floor"))
            }
        }
    }

    @Test
    fun `partial released family fails atomically without creating chapter objects or advancing version`() {
        withFile { driver ->
            prepareSchema40(driver)
            releasedMigration(driver, "sync-40")
            releasedMigration(driver, "sync-41")
            releasedMigration(driver, "sync-42")
            driver.execute(null, "DROP TRIGGER sync_runtime_pause_transition", 0)
            driver.execute(null, "PRAGMA user_version=43", 0)
            assertThrows(Exception::class.java) {
                DatabaseMigration.migrateAtomically(driver, 43, Database.Schema.version)
            }
            assertEquals(43L, value(driver, "PRAGMA user_version"))
            assertEquals(0L, value(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name='chapter_id_floor'"))
            assertEquals(9L, value(driver, "SELECT last_page_read FROM chapters WHERE _id=41"))
            assertEquals(11000L, value(driver, "SELECT paused_at FROM sync_runtime_pause_clock"))
        }
    }

    @Test
    fun `later migration failure rolls back repaired chapter family and preserves existing sync rows`() {
        withFile { driver ->
            prepareSchema40(driver)
            releasedMigration(driver, "sync-40")
            releasedMigration(driver, "sync-41")
            driver.execute(null, "CREATE TABLE sync_repair_failures(original TEXT)", 0)
            driver.execute(null, "INSERT INTO sync_repair_failures VALUES('retained invalid row')", 0)
            driver.execute(null, "PRAGMA user_version=42", 0)
            assertThrows(Exception::class.java) {
                DatabaseMigration.migrateAtomically(driver, 42, Database.Schema.version)
            }
            assertEquals(42L, value(driver, "PRAGMA user_version"))
            assertEquals(0L, value(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name='chapter_id_floor'"))
            assertEquals(1L, value(driver, "SELECT COUNT(*) FROM sync_repair_failures"))
            assertEquals(11000L, value(driver, "SELECT paused_at FROM sync_runtime_pause_clock"))
            assertEquals(1000L, value(driver, "SELECT planned_at FROM sync_runtime_pause_clock"))
        }
    }

    private fun prepareSchema40(driver: JdbcSqliteDriver) {
        Database.Schema.create(driver)
        listOf("insert_guard", "delete_guard", "insert", "delete").forEach { suffix ->
            driver.execute(null, "DROP TRIGGER chapter_id_floor_$suffix", 0)
        }
        listOf("chapter_url_aliases", "chapter_directory_phases", "chapter_id_floor").forEach { table ->
            driver.execute(null, "DROP TABLE $table", 0)
        }
        driver.execute(null, "DROP TABLE sync_repair_failures", 0)
        driver.execute(null, "DROP TRIGGER sync_runtime_pause_transition", 0)
        driver.execute(null, "DROP TABLE sync_runtime_pause_clock", 0)
        driver.execute(
            null,
            "INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer," +
                "chapter_flags,cover_last_modified,date_added) " +
                "VALUES(10,42,'/work','Work',0,0,0,0,0,0,0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number," +
                "source_order,date_fetch,date_upload) " +
                "VALUES(41,10,'/old','Chapter',1,1,9,1,0,0,0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_runtime_runs(run_id,space_id,generation,trigger,state,phase," +
                "last_progress_at,created_at,updated_at) " +
                "VALUES('old','space',1,'MANUAL','PAUSED_USER','UPLOADING',1000,1000,11000)",
            0,
        )
        driver.execute(null, "INSERT INTO sync_runtime_confirmations VALUES('old','PLAN','round',0,'PLANNED')", 0)
    }

    private fun releasedMigration(driver: JdbcSqliteDriver, name: String) {
        val sql = checkNotNull(javaClass.getResourceAsStream("/schema40/$name.sql"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val statement = StringBuilder()
        for (line in sql.lineSequence()) {
            if (line.trimStart().startsWith("--")) continue
            statement.appendLine(line)
            val trigger = statement.trimStart().startsWith("CREATE TRIGGER")
            if (line.trimEnd().endsWith(';') && (!trigger || line.trim() == "END;")) {
                driver.execute(null, statement.toString().trim(), 0)
                statement.clear()
            }
        }
        check(statement.isBlank())
    }

    private fun withFile(block: (JdbcSqliteDriver) -> Unit) {
        val file = Files.createTempFile("published-schema40", ".db")
        fun open() = JdbcSqliteDriver("jdbc:sqlite:$file", Properties().apply { setProperty("foreign_keys", "true") })
        try {
            val persistedQueries = listOf(
                "PRAGMA user_version",
                "SELECT paused_millis FROM sync_runtime_pause_clock",
                "SELECT COUNT(*) FROM sync_runtime_runs WHERE run_id='old'",
                "SELECT COUNT(*) FROM chapters",
                "SELECT COUNT(*) FROM sqlite_master WHERE name='chapter_id_floor'",
            )
            val expected = open().use { driver ->
                block(driver)
                persistedQueries.map { value(driver, it) }
            }
            open().use { driver ->
                assertEquals(1L, value(driver, "PRAGMA foreign_keys"))
                assertEquals(expected, persistedQueries.map { value(driver, it) })
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun value(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        },
        0,
    ).value
}
