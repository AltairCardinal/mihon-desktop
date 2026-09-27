package tachiyomi.data.creator

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseMigration

class SourceDateSnapshotMigration31Test {

    @Test
    fun `v31 migration recovers earliest trusted publication after extension upgrade`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            listOf(
                "published_date_snapshot_at",
                "published_date_snapshot_basis",
                "published_date_snapshot_reason",
            ).forEach { driver.execute(null, "ALTER TABLE author_archive_source_works DROP COLUMN $it", 0) }
            driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN uploaded", 0)
            driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN downloaded", 0)
            driver.execute(null, "PRAGMA user_version = 31", 0)
            driver.execute(
                null,
                "INSERT INTO author_archive_source_works(source_id, stable_source_url, title, normalized_title, " +
                    "first_seen_at, last_seen_at) VALUES (42, '/work', 'Work', 'work', 1000, 1000)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_works(source_id, stable_source_url, title, normalized_title, " +
                    "first_seen_at, last_seen_at) VALUES (42, '/coarse', 'Coarse', 'coarse', 1000, 1000)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_date_quality VALUES " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', 'TRUSTED', 1, 2, 1, 0, 0, " +
                    "1000, 2000, 200, NULL, 3000)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_date_quality VALUES " +
                    "('extension', '2.0', 42, 'WORK_PUBLISHED', 'UNKNOWN', 1, 0, 0, 0, 0, " +
                    "3000, 3000, NULL, 'insufficient stable history', 3000)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_date_quality_current VALUES " +
                    "(42, 'WORK_PUBLISHED', 'extension', '2.0', 3000)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_date_quality_samples(" +
                    "extension_package, extension_version, source_id, field_kind, work_natural_key, " +
                    "raw_value, value_at, precision, semantic_confirmed, observed_at, network_failure) VALUES " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', '/work', '100', 100, 'YEAR', 1, 1000, 0), " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', '/work', '200', 200, 'DAY', 1, 1000, 0), " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', '/work', '300', 300, 'DAY', 1, 2000, 0), " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', '/coarse', '50', 50, 'YEAR', 1, 1000, 0), " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', '/coarse', '75', 75, 'MONTH', 1, 2000, 0)",
                0,
            )

            DatabaseMigration.migrateAtomically(driver, 31, 32)

            scalar(driver, "PRAGMA user_version") shouldBe 32L
            scalar(
                driver,
                "SELECT published_date_snapshot_at FROM author_archive_source_works " +
                    "WHERE stable_source_url = '/work'",
            ) shouldBe 200L
            scalar(
                driver,
                "SELECT published_date_snapshot_at FROM author_archive_source_works " +
                    "WHERE stable_source_url = '/coarse'",
            ) shouldBe null
            text(
                driver,
                "SELECT published_date_snapshot_basis FROM author_archive_source_works " +
                    "WHERE stable_source_url = '/work'",
            ) shouldBe
                "extension@1.0"
            text(
                driver,
                "SELECT published_date_snapshot_reason FROM author_archive_source_works " +
                    "WHERE stable_source_url = '/work'",
            ) shouldBe
                "extension_version_changed"
        } finally {
            driver.close()
        }
    }

    private fun scalar(driver: JdbcSqliteDriver, sql: String): Long? = driver.executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        },
        0,
    ).value

    private fun text(driver: JdbcSqliteDriver, sql: String): String? = driver.executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        },
        0,
    ).value
}
