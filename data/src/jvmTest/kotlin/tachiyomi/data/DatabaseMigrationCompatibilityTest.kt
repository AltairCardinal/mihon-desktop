package tachiyomi.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class DatabaseMigrationCompatibilityTest {
    @Test
    fun `current schema reserves compatibility migration after published sync schema`() {
        Database.Schema.version shouldBe 33L
    }

    @Test
    fun `published sync schema upgrades without losing runtime data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(
            null,
            "INSERT INTO sync_runtime_runs(" +
                "run_id, space_id, generation, trigger, state, phase, last_progress_at, created_at, updated_at" +
                ") VALUES ('run-1', 'space-1', 1, 'MANUAL', 'RUNNING', 'IMPORTING', 10, 10, 10)",
            0,
        )
        driver.execute(null, "DROP TRIGGER IF EXISTS author_archive_source_work_first_seen_defaults", 0)
        dropAuthorAdditions(driver)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN uploaded", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN downloaded", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN uploaded_baseline", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN downloaded_baseline", 0)
        driver.execute(null, "PRAGMA user_version = 28", 0)

        DatabaseMigration.migrateAtomically(driver, 28, Database.Schema.version)

        queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_runs") shouldBe 1L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_representative_work_cache") shouldBe 0L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_date_quality") shouldBe 0L
        queryLong(driver, "PRAGMA user_version") shouldBe 33L
    }

    @Test
    fun `author schema 29 receives sync tables while preserving author data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(" +
                "portable_key, display_name, normalized_name, status, created_at, last_modified_at" +
                ") VALUES ('creator-1', 'Creator', 'creator', 'ACTIVE', 10, 10)",
            0,
        )
        driver.execute(null, "DROP TABLE sync_runtime_logs", 0)
        driver.execute(null, "DROP TABLE sync_runtime_runs", 0)
        listOf(
            "author_archive_source_date_quality_samples",
            "author_archive_source_date_quality_current",
            "author_archive_source_date_quality",
        ).forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
        driver.execute(null, "PRAGMA user_version = 29", 0)

        DatabaseMigration.migrateAtomically(driver, 29, Database.Schema.version)

        queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators") shouldBe 1L
        queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_runs") shouldBe 0L
        queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_logs") shouldBe 0L
        queryLong(driver, "PRAGMA user_version") shouldBe 33L
    }

    @Test
    fun `unknown same-number sync table shape fails without advancing the database`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP TABLE sync_runtime_logs", 0)
        driver.execute(null, "DROP TABLE sync_runtime_runs", 0)
        driver.execute(
            null,
            "CREATE TABLE sync_runtime_runs(run_id TEXT NOT NULL PRIMARY KEY, space_id TEXT NOT NULL)",
            0,
        )
        driver.execute(null, "PRAGMA user_version = 29", 0)

        val failure = shouldThrow<IllegalArgumentException> {
            DatabaseMigration.migrateAtomically(driver, 29, Database.Schema.version)
        }

        failure.message.orEmpty() shouldContain "sync_runtime_runs"
        queryLong(driver, "PRAGMA user_version") shouldBe 29L
    }

    private fun dropAuthorAdditions(driver: JdbcSqliteDriver) {
        listOf(
            "author_archive_source_date_quality_samples",
            "author_archive_source_date_quality_current",
            "author_archive_source_date_quality",
            "author_archive_representative_work_cache",
        ).forEach { table ->
            driver.execute(null, "DROP TABLE IF EXISTS $table", 0)
        }
        listOf(
            "first_seen_date",
            "first_seen_zone",
            "chapter_count_state",
            "catalog_chapter_count",
            "latest_chapter_at",
        ).forEach { column ->
            if (hasColumn(driver, "author_archive_source_works", column)) {
                driver.execute(null, "ALTER TABLE author_archive_source_works DROP COLUMN $column", 0)
            }
        }
    }

    private fun hasColumn(driver: JdbcSqliteDriver, table: String, column: String): Boolean = driver.executeQuery(
        null,
        "SELECT EXISTS(SELECT 1 FROM pragma_table_info(?) WHERE name = ?)",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
        },
        2,
        {
            bindString(0, table)
            bindString(1, column)
        },
    ).value

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value
}
