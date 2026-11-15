package tachiyomi.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SyncAttemptClockMigrationTest {
    @Test
    fun `current schema rejects missing or malformed attempt clock without changing old data`() {
        for (malformedType in listOf(false, true)) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                Database.Schema.create(driver)
                driver.execute(
                    null,
                    "INSERT INTO sync_runtime_runs(run_id,space_id,generation,trigger,state,phase," +
                        "confirmed_items,last_progress_at,created_at,updated_at) " +
                        "VALUES('old','space',1,'MANUAL','WAITING_SYSTEM','MERGING',7,1000,1000,11000)",
                    0,
                )
                driver.execute(null, "ALTER TABLE sync_runtime_pause_clock DROP COLUMN attempt_confirmed_baseline", 0)
                if (malformedType) {
                    driver.execute(
                        null,
                        "ALTER TABLE sync_runtime_pause_clock ADD COLUMN attempt_confirmed_baseline TEXT",
                        0,
                    )
                }
                driver.execute(null, "PRAGMA user_version=47", 0)
                assertThrows(IllegalArgumentException::class.java) {
                    DatabaseMigration.migrateAtomically(driver, 47, Database.Schema.version)
                }
                assertEquals(47L, value(driver, "PRAGMA user_version"))
                assertEquals(7L, value(driver, "SELECT confirmed_items FROM sync_runtime_runs"))
            }
        }
    }

    @Test
    fun `schema 46 retains old progress and leaves unknown attempt time after file migration`() {
        val file = Files.createTempFile("sync-attempt-clock", ".db")
        fun open() = JdbcSqliteDriver("jdbc:sqlite:$file")
        try {
            open().use { driver ->
                Database.Schema.create(driver)
                for (column in listOf("attempt_started_at", "attempt_confirmed_baseline", "attempt_paused_millis")) {
                    if (value(
                            driver,
                            "SELECT COUNT(*) FROM pragma_table_info('sync_runtime_pause_clock') WHERE name='$column'",
                        ) >
                        0
                    ) {
                        driver.execute(null, "ALTER TABLE sync_runtime_pause_clock DROP COLUMN $column", 0)
                    }
                }
                driver.execute(
                    null,
                    "INSERT INTO sync_runtime_runs(run_id,space_id,generation,trigger,state,phase," +
                        "confirmed_items,last_progress_at,created_at,updated_at) " +
                        "VALUES('old','space',1,'MANUAL','WAITING_SYSTEM','MERGING',7,1000,1000,11000)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO sync_runtime_pause_clock(run_id,paused_millis,planned_at) VALUES('old',5000,1000)",
                    0,
                )
                DatabaseMigration.migrateAtomically(driver, 46, Database.Schema.version)
                assertEquals(
                    3L,
                    value(
                        driver,
                        "SELECT COUNT(*) FROM pragma_table_info('sync_runtime_pause_clock') " +
                            "WHERE name IN ('attempt_started_at','attempt_confirmed_baseline','attempt_paused_millis')",
                    ),
                )
            }
            open().use { driver ->
                assertEquals(1L, value(driver, "SELECT attempt_started_at IS NULL FROM sync_runtime_pause_clock"))
                assertEquals(0L, value(driver, "SELECT attempt_confirmed_baseline FROM sync_runtime_pause_clock"))
                assertEquals(0L, value(driver, "SELECT attempt_paused_millis FROM sync_runtime_pause_clock"))
                assertEquals(5000L, value(driver, "SELECT paused_millis FROM sync_runtime_pause_clock"))
                assertEquals(1000L, value(driver, "SELECT planned_at FROM sync_runtime_pause_clock"))
                assertEquals(7L, value(driver, "SELECT confirmed_items FROM sync_runtime_runs"))
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
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
        },
        0,
    ).value
}
