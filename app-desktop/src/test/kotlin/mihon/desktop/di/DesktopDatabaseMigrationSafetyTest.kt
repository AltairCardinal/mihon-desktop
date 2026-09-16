package mihon.desktop.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.domain.creator.model.CreatorArchivePhysicalSchema
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
    fun `fresh database enables and enforces foreign keys before returning`() {
        val database = File(directory, "mihon.db")

        createDriver(database).use { driver ->
            assertForeignKeysEnforced(driver)
        }
    }

    @Test
    fun `existing current database re-enables foreign keys on every connection`() {
        val database = File(directory, "mihon.db")
        createDriver(database).close()

        createDriver(database).use { driver ->
            queryUserVersion(driver) shouldBe Database.Schema.version.toInt()
            assertForeignKeysEnforced(driver)
        }
    }

    @Test
    fun `valid synthetic v15 upgrades atomically with the complete archive and effective foreign keys`() {
        val database = File(directory, "mihon.db")
        createSyntheticV15(database, recordedVersion = 15)

        createDriver(database).use { driver ->
            queryUserVersion(driver) shouldBe Database.Schema.version.toInt()
            queryLong(
                driver,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'author_archive_%'",
            ) shouldBe CreatorArchivePhysicalSchema.tables.size.toLong()
            queryLong(driver, "SELECT COUNT(*) FROM pragma_foreign_key_check") shouldBe 0L
            queryLong(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('extension_repos') " +
                    "WHERE name IN ('index_url', 'extension_list_url', 'contact_discord')",
            ) shouldBe 3L
            assertForeignKeysEnforced(driver)
        }
    }

    @Test
    fun `failed synthetic v15 migration preserves its version legacy rows and integrity`() {
        val database = File(directory, "mihon.db")
        createSyntheticV15(database, recordedVersion = 15) { driver ->
            driver.execute(null, "INSERT INTO creators VALUES (7, 'Sentinel', 'sentinel', 'Sentinel', '', 1, 1)", 0)
            driver.execute(null, "INSERT INTO manga_creators VALUES (404, 999, 'author', 'orphan', 1.0, 'failure')", 0)
        }

        shouldThrow<IllegalStateException> { createDriver(database) }

        JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}").use { driver ->
            queryUserVersion(driver) shouldBe 15
            queryLong(driver, "SELECT COUNT(*) FROM creators WHERE _id = 7") shouldBe 1L
            queryLong(driver, "SELECT COUNT(*) FROM manga_creators WHERE creator_id = 999") shouldBe 1L
            queryLong(
                driver,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'author_archive_%'",
            ) shouldBe 0L
            queryString(driver, "PRAGMA integrity_check") shouldBe "ok"
        }
    }

    @Test
    fun `interrupted version 11 marker with complete v15 objects resumes and records latest version`() {
        val database = File(directory, "mihon.db")
        createSyntheticV15(database, recordedVersion = 11)

        createDriver(database).close()

        JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}").use { driver ->
            queryUserVersion(driver) shouldBe Database.Schema.version.toInt()
        }
        createDriver(database).close()
    }

    private fun createSyntheticV15(
        database: File,
        recordedVersion: Int,
        seed: (JdbcSqliteDriver) -> Unit = {},
    ) {
        JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}").use { driver ->
            Database.Schema.create(driver)
            val laterSyncTables = driver.executeQuery(
                null,
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name GLOB 'sync_*'",
                { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(
                        buildList {
                            while (cursor.next().value) add(requireNotNull(cursor.getString(0)))
                        },
                    )
                },
                0,
            ).value
            laterSyncTables.forEach { table ->
                driver.execute(null, "DROP TABLE $table", 0)
            }
            driver.execute(null, "ALTER TABLE mangas DROP COLUMN memo", 0)
            driver.execute(null, "ALTER TABLE chapters DROP COLUMN memo", 0)
            // Restore the pre-v19 repository shape before exercising the actual migrations.
            listOf("index_url", "extension_list_url", "contact_discord").forEach { column ->
                driver.execute(null, "ALTER TABLE extension_repos DROP COLUMN $column", 0)
            }
            CreatorArchivePhysicalSchema.tables.asReversed().forEach { table ->
                driver.execute(null, "DROP TABLE IF EXISTS ${table.name}", 0)
            }
            driver.execute(null, "PRAGMA user_version = $recordedVersion", 0)
            seed(driver)
        }
    }

    private fun assertForeignKeysEnforced(driver: app.cash.sqldelight.db.SqlDriver) {
        queryLong(driver, "PRAGMA foreign_keys") shouldBe 1L
        shouldThrow<Exception> {
            driver.execute(
                null,
                "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                    "confidence, is_manual, created_at, last_modified_at) " +
                    "VALUES (999, 'orphan', 'orphan', 'test', 'test', 1.0, 0, 1, 1)",
                0,
            )
        }
    }

    private fun queryUserVersion(driver: app.cash.sqldelight.db.SqlDriver): Int = driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0)!!.toInt() else 0,
            )
        },
        0,
    ).value

    private fun queryLong(driver: app.cash.sqldelight.db.SqlDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryString(driver: app.cash.sqldelight.db.SqlDriver, sql: String): String? = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        0,
    ).value
}
