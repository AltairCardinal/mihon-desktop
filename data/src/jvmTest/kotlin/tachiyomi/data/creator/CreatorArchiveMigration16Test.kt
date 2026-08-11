package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseMigration
import tachiyomi.domain.creator.model.CreatorArchiveV2Contract

class CreatorArchiveMigration16Test {

    @Test
    fun `v16 adds the global unread discovery index without rewriting archive data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP INDEX idx_author_archive_discoveries_unread", 0)
        driver.execute(null, "PRAGMA user_version = 16", 0)
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(_id, portable_key, display_name, normalized_name, status, " +
                "needs_review, created_at, last_modified_at) VALUES (1, 'creator', 'Creator', 'creator', " +
                "'ACTIVE', 0, 1, 1)",
            0,
        )

        DatabaseMigration.migrateAtomically(driver, 16, CreatorArchiveV2Contract.LATEST_SCHEMA_VERSION)

        queryLong(driver, "PRAGMA user_version") shouldBe 17L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators") shouldBe 1L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'idx_author_archive_discoveries_unread'",
        ) shouldBe 1L
        queryPlan(
            driver,
            "SELECT * FROM author_archive_discoveries WHERE read_state = 'UNSEEN' " +
                "ORDER BY first_discovered_at DESC LIMIT 20",
        )
            .contains("idx_author_archive_discoveries_unread") shouldBe true
        driver.close()
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryPlan(driver: JdbcSqliteDriver, sql: String): String = driver.executeQuery(
        null,
        "EXPLAIN QUERY PLAN $sql",
        { cursor ->
            val lines = mutableListOf<String>()
            while (cursor.next().value) lines += cursor.getString(3).orEmpty()
            app.cash.sqldelight.db.QueryResult.Value(lines.joinToString("\n"))
        },
        0,
    ).value
}
