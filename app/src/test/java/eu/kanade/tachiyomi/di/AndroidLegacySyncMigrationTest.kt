package eu.kanade.tachiyomi.di

import android.app.Application
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.data.Database
import tachiyomi.data.LegacySyncSchema32Contract
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidLegacySyncMigrationTest {
    private lateinit var app: Application
    private lateinit var previous: InjektScope

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        previous = Injekt
        // Release uses Requery's Android-only native library, which cannot load in host Robolectric.
        // Keep this production callback contract on the debug JVM; verify release migration on device.
        assumeTrue("Release SQLite native driver requires Android", BuildConfig.DEBUG)
        app.deleteDatabase("tachiyomi.db")
        Injekt = InjektScope(DefaultRegistrar())
    }

    @After
    fun teardown() {
        Injekt = previous
        app.deleteDatabase("tachiyomi.db")
    }

    @Test
    fun `production Android database callback restores missing runtime family from version 32`() {
        prepareLegacyV32(hasRuntime = false)

        Injekt.importModule(AppModule(app))
        Injekt.get<SqlDriver>().use { driver ->
            LegacySyncSchema32Contract.assertMigrated(driver, hadRuntime = false)
        }
    }

    @Test
    fun `production Android callback preserves existing version 32 runtime history`() {
        prepareLegacyV32(hasRuntime = true)

        Injekt.importModule(AppModule(app))
        Injekt.get<SqlDriver>().use { driver ->
            LegacySyncSchema32Contract.assertMigrated(driver, hadRuntime = true)
        }
    }

    @Test
    fun `production Android callback rolls back runtime repair when a later migration fails`() {
        prepareLegacyV32(hasRuntime = false)
        val file = app.getDatabasePath("tachiyomi.db")
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.execute(null, "CREATE TABLE sync_snapshot_manifest_entries(unexpected TEXT)", 0)
        }

        Injekt.importModule(AppModule(app))
        assertThrows(Exception::class.java) {
            Injekt.get<SqlDriver>().use { LegacySyncSchema32Contract.assertMigrated(it, hadRuntime = false) }
        }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            LegacySyncSchema32Contract.assertRepairRolledBack(driver)
        }
    }

    @Test
    fun `production Android callback upgrades published sync version 38 and restores author snapshots`() {
        val file = app.getDatabasePath("tachiyomi.db")
        file.parentFile!!.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            Database.Schema.create(driver)
            LegacySyncSchema32Contract.removeChapterDirectoryAdditions(driver)
            driver.execute(
                null,
                "INSERT INTO author_archive_source_works(source_id, stable_source_url, title, normalized_title, " +
                    "first_seen_at, last_seen_at) VALUES (42, '/work', 'Work', 'work', 1000, 1000)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_date_quality VALUES " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', 'TRUSTED', 1, 1, 1, 0, 0, " +
                    "1000, 2000, 200, NULL, 3000)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_date_quality_samples(" +
                    "extension_package, extension_version, source_id, field_kind, work_natural_key, " +
                    "raw_value, value_at, precision, semantic_confirmed, observed_at, network_failure) VALUES " +
                    "('extension', '1.0', 42, 'WORK_PUBLISHED', '/work', '200', 200, 'DAY', 1, 1000, 0)",
                0,
            )
            driver.execute(null, "DROP TABLE sync_repair_failures", 0)
            driver.execute(null, "DROP TRIGGER sync_runtime_pause_transition", 0)
            driver.execute(null, "DROP TABLE sync_runtime_pause_clock", 0)
            driver.execute(null, "DROP TABLE chapter_pairing_boundaries", 0)
            driver.execute(null, "DROP TABLE chapter_pairings", 0)
            driver.execute(null, "DROP TABLE author_archive_presentation_exclusions", 0)
            listOf(
                "published_date_snapshot_at",
                "published_date_snapshot_basis",
                "published_date_snapshot_reason",
            ).forEach { column ->
                driver.execute(null, "ALTER TABLE author_archive_source_works DROP COLUMN $column", 0)
            }
            driver.execute(null, "PRAGMA user_version = 38", 0)
        }

        Injekt.importModule(AppModule(app))
        Injekt.get<SqlDriver>().use { driver ->
            assertEquals(Database.Schema.version, queryLong(driver, "PRAGMA user_version"))
            assertEquals(
                200L,
                queryLong(
                    driver,
                    "SELECT published_date_snapshot_at FROM author_archive_source_works " +
                        "WHERE stable_source_url = '/work'",
                ),
            )
            assertEquals(
                1L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = 'author_archive_presentation_exclusions'",
                ),
            )
        }
    }

    @Test
    fun `production Android callback upgrades main schema 41 without losing history identity`() {
        preparePublishedShape(version = 41, hasDirectory = true, hasPause = false)
        assertPublishedShapeMigrated()
    }

    @Test
    fun `production Android callback upgrades accepted feature schema 43 with chapter identity repair`() {
        preparePublishedShape(version = 43, hasDirectory = false, hasPause = true)
        assertPublishedShapeMigrated()
    }

    @Test
    fun `production Android callback rolls back accepted feature 43 when identity repair fails`() {
        preparePublishedShape(version = 43, hasDirectory = false, hasPause = true)
        val file = app.getDatabasePath("tachiyomi.db")
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.execute(null, "CREATE TABLE chapter_url_aliases(unexpected TEXT)", 0)
        }
        Injekt.importModule(AppModule(app))
        assertThrows(Exception::class.java) {
            Injekt.get<SqlDriver>().use { driver -> queryLong(driver, "PRAGMA user_version") }
        }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            assertEquals(43L, queryLong(driver, "PRAGMA user_version"))
            assertEquals(
                1L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM sync_remote_guards WHERE latest_head = 'published-head'",
                ),
            )
            assertEquals(
                0L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = 'chapter_directory_phases'",
                ),
            )
            assertEquals(
                1L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM pragma_table_info('chapter_url_aliases') WHERE name = 'unexpected'",
                ),
            )
        }
    }

    private fun preparePublishedShape(version: Int, hasDirectory: Boolean, hasPause: Boolean) {
        val file = app.getDatabasePath("tachiyomi.db")
        file.parentFile!!.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            Database.Schema.create(driver)
            driver.execute(
                null,
                "INSERT INTO sync_remote_guards(space_id, generation, repository_owner, repository_name, " +
                    "repository_branch, latest_head) VALUES ('preserved-space', 1, 'owner', 'repo', " +
                    "'main', 'published-head')",
                0,
            )
            if (!hasDirectory) LegacySyncSchema32Contract.removeChapterDirectoryAdditions(driver)
            if (!hasPause) {
                driver.execute(null, "DROP TABLE sync_repair_failures", 0)
                driver.execute(null, "DROP TRIGGER sync_runtime_pause_transition", 0)
                driver.execute(null, "DROP TABLE sync_runtime_pause_clock", 0)
            }
            driver.execute(null, "PRAGMA user_version = $version", 0)
        }
    }

    private fun assertPublishedShapeMigrated() {
        Injekt.importModule(AppModule(app))
        Injekt.get<SqlDriver>().use { driver ->
            assertEquals(Database.Schema.version, queryLong(driver, "PRAGMA user_version"))
            assertEquals(
                1L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM sync_remote_guards WHERE latest_head = 'published-head'",
                ),
            )
            listOf("chapter_url_aliases", "chapter_id_floor", "chapter_directory_phases", "sync_repair_failures")
                .forEach { table ->
                    assertEquals(
                        table,
                        1L,
                        queryLong(
                            driver,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'",
                        ),
                    )
                }
            assertEquals(
                1L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM pragma_table_info('sync_runtime_pause_clock') WHERE name = 'planned_at'",
                ),
            )
        }
    }

    private fun queryLong(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(if (cursor.next().value) requireNotNull(cursor.getLong(0)) else -1L) },
        0,
    ).value

    private fun prepareLegacyV32(hasRuntime: Boolean) {
        val file = app.getDatabasePath("tachiyomi.db")
        file.parentFile!!.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            if (hasRuntime) {
                LegacySyncSchema32Contract.prepareWithRuntime(driver)
            } else {
                LegacySyncSchema32Contract.prepareWithoutRuntime(driver)
            }
        }
    }
}
