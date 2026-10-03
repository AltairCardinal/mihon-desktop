package tachiyomi.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Properties

class SyncPauseClockMigrationTest {
    @Test
    fun `schema 40 paused run retains its stop time through migration and file reopen`() {
        val file = Files.createTempFile("sync-pause-clock", ".db")
        fun open() = JdbcSqliteDriver("jdbc:sqlite:$file", Properties().apply { setProperty("foreign_keys", "true") })
        try {
            open().use { driver ->
                Database.Schema.create(driver)
                driver.execute(null, "DROP TRIGGER IF EXISTS sync_runtime_pause_transition", 0)
                driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_pause_clock", 0)
                driver.execute(
                    null,
                    "INSERT INTO sync_runtime_runs(run_id,space_id,generation,trigger,state,phase," +
                        "last_progress_at,created_at,updated_at) " +
                        "VALUES('old','space',1,'MANUAL','PAUSED_USER','UPLOADING',1000,1000,11000)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO sync_runtime_confirmations(run_id,direction,batch_id,item_count,status) " +
                        "VALUES('old','PLAN','round',0,'PLANNED')",
                    0,
                )
                DatabaseMigration.migrateAtomically(driver, 40, Database.Schema.version)
                assertEquals(42L, Database.Schema.version)
                assertEquals(11_000L, value(driver, "SELECT paused_at FROM sync_runtime_pause_clock"))
                assertEquals(1_000L, value(driver, "SELECT planned_at FROM sync_runtime_pause_clock"))
                assertEquals(0L, value(driver, "SELECT planned_paused_millis FROM sync_runtime_pause_clock"))
            }
            open().use { driver ->
                driver.execute(
                    null,
                    "UPDATE sync_runtime_runs SET state='RUNNING',updated_at=71000 WHERE run_id='old'",
                    0,
                )
                assertEquals(60_000L, value(driver, "SELECT paused_millis FROM sync_runtime_pause_clock"))
                assertEquals(1_000L, value(driver, "SELECT planned_at FROM sync_runtime_pause_clock"))
                assertEquals(1L, value(driver, "SELECT COUNT(*) FROM sync_runtime_runs WHERE run_id='old'"))
                assertEquals(1L, value(driver, "PRAGMA foreign_keys"))
                driver.execute(null, "DELETE FROM sync_runtime_runs WHERE run_id='old'", 0)
                assertEquals(0L, value(driver, "SELECT COUNT(*) FROM sync_runtime_pause_clock"))
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
