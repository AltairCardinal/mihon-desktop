package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.Database

class CreatorExactIdentityMigration26Test {

    @Test
    fun `migration adds exact name registry revision and recovery checkpoint`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE author_archive_creators(
                _id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                portable_key TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                normalized_name TEXT NOT NULL,
                sort_name TEXT,
                status TEXT NOT NULL,
                merged_into_creator_id INTEGER,
                needs_review INTEGER NOT NULL DEFAULT 0,
                legacy_creator_id INTEGER UNIQUE,
                created_at INTEGER NOT NULL,
                last_modified_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(portable_key, display_name, normalized_name, status, created_at, " +
                "last_modified_at) VALUES ('old', '冈本伦', '冈本伦', 'ACTIVE', 1, 1)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE author_archive_manga_links(creator_id INTEGER, role TEXT, creator_order INTEGER, " +
                "origin TEXT, source_text TEXT, confidence REAL, evidence TEXT)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE author_archive_source_work_creators(creator_id INTEGER, role TEXT, creator_order INTEGER, " +
                "origin TEXT, verification TEXT, source_text TEXT, confidence REAL, evidence TEXT)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE author_archive_canonical_creators(creator_id INTEGER, role TEXT, creator_order INTEGER, " +
                "origin TEXT, evidence TEXT)",
            0,
        )

        Database.Schema.migrate(driver, 25, 26)

        queryLong(driver, "SELECT identity_revision FROM author_archive_creators WHERE portable_key = 'old'") shouldBe
            0L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 0L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_identity_migrations") shouldBe 0L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_identity_migration_components") shouldBe 0L
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value
}
