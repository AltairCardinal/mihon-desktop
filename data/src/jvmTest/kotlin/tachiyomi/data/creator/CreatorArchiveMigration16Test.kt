package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseMigration
import tachiyomi.domain.creator.model.CreatorArchiveV2Contract

class CreatorArchiveMigration16Test {

    @Test
    fun `v16 through latest preserves archive data and accepts split chapter variants`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        mihon.data.sync.removeSyncJournalSchema(driver)
        // Remove additions from migrations 25 through 28 before replaying v16 onward.
        val identityObjects = driver.executeQuery(
            null,
            """SELECT type, name FROM sqlite_master
                WHERE (type = 'table' AND name GLOB 'author_archive_identity_*')
                   OR (type = 'table' AND name = 'author_archive_representative_work_cache')
                   OR (type = 'trigger' AND name GLOB 'author_archive_*_revision')
                   OR (type = 'trigger' AND name = 'author_archive_source_work_first_seen_defaults')
                ORDER BY CASE type WHEN 'trigger' THEN 0 ELSE 1 END""",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildList {
                        while (cursor.next().value) {
                            add(requireNotNull(cursor.getString(0)) to requireNotNull(cursor.getString(1)))
                        }
                    },
                )
            },
            0,
        ).value
        identityObjects.forEach { (type, name) -> driver.execute(null, "DROP $type $name", 0) }
        listOf(
            "first_seen_date",
            "first_seen_zone",
            "chapter_count_state",
            "catalog_chapter_count",
            "latest_chapter_at",
        )
            .forEach { column ->
                driver.execute(null, "ALTER TABLE author_archive_source_works DROP COLUMN $column", 0)
            }
        driver.execute(null, "ALTER TABLE author_archive_creators DROP COLUMN identity_revision", 0)
        driver.execute(null, "ALTER TABLE mangas DROP COLUMN memo", 0)
        driver.execute(null, "ALTER TABLE chapters DROP COLUMN memo", 0)
        listOf("index_url", "extension_list_url", "contact_discord").forEach { column ->
            driver.execute(null, "ALTER TABLE extension_repos DROP COLUMN $column", 0)
        }
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

        queryLong(driver, "PRAGMA user_version") shouldBe Database.Schema.version
        queryLong(
            driver,
            "SELECT COUNT(*) FROM pragma_table_info('extension_repos') " +
                "WHERE name IN ('index_url', 'extension_list_url', 'contact_discord')",
        ) shouldBe 3L
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
        driver.execute(
            null,
            "INSERT INTO author_archive_source_works(_id, source_id, stable_source_url, title, normalized_title, " +
                "first_seen_at, last_seen_at) VALUES (1, 7, '/work', 'Work', 'work', 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_chapter_variants(source_work_id, chapter_natural_key, chapter_number, " +
                "part_number, variant_type, raw_name, evidence, created_at, last_modified_at) " +
                "VALUES (1, '/chapter-1-part-2', 1, 2, 'SPLIT', 'Ch. 1 Part 2', 'part token', 1, 1)",
            0,
        )
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_chapter_variants") shouldBe 1L
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
