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
import java.sql.DriverManager

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
    fun `production Android opens published schema 42 without changing reader and pause data`() {
        preparePublishedPauseSchema(version = 42)
        Injekt.importModule(AppModule(app))
        Injekt.get<SqlDriver>().use { driver ->
            assertEquals(Database.Schema.version, queryLong(driver, "PRAGMA user_version"))
            assertEquals(15L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id = 11"))
            assertEquals(1L, queryLong(driver, "SELECT bookmark FROM chapters WHERE _id = 11"))
            assertEquals(
                23L,
                queryLong(driver, "SELECT paused_millis FROM sync_runtime_pause_clock WHERE run_id = 'r'"),
            )
            assertEquals(
                11L,
                queryLong(driver, "SELECT planned_paused_millis FROM sync_runtime_pause_clock WHERE run_id = 'r'"),
            )
            assertEquals(1000L, queryLong(driver, "SELECT planned_at FROM sync_runtime_pause_clock WHERE run_id = 'r'"))
        }
    }

    @Test
    fun `production Android upgrades schema 40 retaining reader progress and original paused plan`() {
        preparePublishedPauseSchema(version = 40)
        Injekt.importModule(AppModule(app))
        Injekt.get<SqlDriver>().use { driver ->
            assertEquals(Database.Schema.version, queryLong(driver, "PRAGMA user_version"))
            assertEquals(15L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id = 11"))
            assertEquals(7000L, queryLong(driver, "SELECT paused_at FROM sync_runtime_pause_clock WHERE run_id = 'r'"))
            assertEquals(1000L, queryLong(driver, "SELECT planned_at FROM sync_runtime_pause_clock WHERE run_id = 'r'"))
            assertEquals(
                0L,
                queryLong(driver, "SELECT planned_paused_millis FROM sync_runtime_pause_clock WHERE run_id = 'r'"),
            )
        }
    }

    @Test
    fun `fresh production Android schema preserves pause clock trigger and run cascade`() {
        Injekt.importModule(AppModule(app))
        Injekt.get<SqlDriver>().use { driver ->
            assertEquals(Database.Schema.version, queryLong(driver, "PRAGMA user_version"))
            seedPauseRun(driver, "RUNNING")
            driver.execute(
                null,
                "UPDATE sync_runtime_runs SET state = 'PAUSED_USER', updated_at = 7000 WHERE run_id = 'r'",
                0,
            )
            driver.execute(
                null,
                "UPDATE sync_runtime_runs SET state = 'RUNNING', updated_at = 9000 WHERE run_id = 'r'",
                0,
            )
            assertEquals(
                2000L,
                queryLong(driver, "SELECT paused_millis FROM sync_runtime_pause_clock WHERE run_id = 'r'"),
            )
            driver.execute(null, "DELETE FROM sync_runtime_runs WHERE run_id = 'r'", 0)
            assertEquals(0L, queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_pause_clock"))
        }
    }

    // Historical fixture uses the exact migrations shipped by the schema-42 candidate (9a6651d5d8).
    private fun preparePublishedPauseSchema(version: Int) {
        val file = app.getDatabasePath("tachiyomi.db")
        file.parentFile!!.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            Database.Schema.create(driver)
            driver.execute(null, "DROP TABLE chapter_pairing_pairs", 0)
            driver.execute(null, "ALTER TABLE chapter_pairings DROP COLUMN opening_mode", 0)
            LegacySyncSchema32Contract.removeChapterDirectoryAdditions(driver)
            driver.execute(null, "DROP TABLE sync_repair_failures", 0)
            driver.execute(null, "DROP TRIGGER IF EXISTS sync_runtime_pause_transition", 0)
            driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_pause_clock", 0)
            driver.execute(
                null,
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) " +
                    "VALUES (10, 1, '/', 'Fixture', 0, 1, 1, 0, 0, 0, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                    "chapter_number, source_order, date_fetch, date_upload) " +
                    "VALUES (11, 10, '/c', 'Fixture', 0, 1, 15, 1, 0, 0, 0)",
                0,
            )
            seedPauseRun(driver, "PAUSED_USER")
            driver.execute(
                null,
                "INSERT INTO sync_runtime_confirmations VALUES ('r', 'PLAN', 'round', 1, 'CONFIRMED')",
                0,
            )
            driver.execute(null, "PRAGMA user_version = 40", 0)
        }
        if (version == 42) {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    for (migration in listOf(40, 41)) {
                        val sql = requireNotNull(
                            javaClass.getResourceAsStream("/database/published-sync42/$migration.sql"),
                        )
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        statement.executeUpdate(sql)
                    }
                    statement.executeUpdate(
                        "UPDATE sync_runtime_pause_clock SET paused_millis = 23, planned_paused_millis = 11",
                    )
                    statement.executeUpdate("PRAGMA user_version = 42")
                }
            }
        }
    }

    private fun seedPauseRun(driver: SqlDriver, state: String) {
        driver.execute(
            null,
            "INSERT INTO sync_runtime_runs(run_id, space_id, generation, trigger, state, phase, " +
                "last_progress_at, created_at, updated_at) " +
                "VALUES ('r', 'fixture', 1, 'MANUAL', '$state', 'WAITING', 1000, 1000, 7000)",
            0,
        )
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
            driver.execute(null, "DROP TABLE chapter_pairing_pairs", 0)
            driver.execute(null, "ALTER TABLE chapter_pairings DROP COLUMN opening_mode", 0)
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

    @Test
    fun `production Android callback upgrades reader schema 43 preserving saved opening intent`() {
        preparePublishedShape(version = 43, hasDirectory = false, hasPause = true, hasReaderIntent = true)
        val file = app.getDatabasePath("tachiyomi.db")
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.execute(null, "DROP TABLE sync_repair_failures", 0)
            driver.execute(
                null,
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) " +
                    "VALUES (10, 1, '/', 'Fixture', 0, 1, 1, 0, 0, 0, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                    "chapter_number, source_order, date_fetch, date_upload) " +
                    "VALUES (11, 10, '/c', 'Fixture', 0, 0, 0, 1, 0, 0, 0)",
                0,
            )
            driver.execute(null, "INSERT INTO chapter_pairings VALUES(11,2,10,7,'FIRST_PAIR')", 0)
            driver.execute(null, "INSERT INTO chapter_pairing_pairs VALUES(11,4)", 0)
        }
        assertPublishedShapeMigrated { driver ->
            assertEquals(4L, queryLong(driver, "SELECT page_ordinal FROM chapter_pairing_pairs"))
            assertEquals(
                1L,
                queryLong(driver, "SELECT COUNT(*) FROM chapter_pairings WHERE opening_mode='FIRST_PAIR'"),
            )
        }
    }

    @Test
    fun `production Android callback upgrades main schema 44 adding reader pairing intent`() {
        preparePublishedShape(version = 44, hasDirectory = true, hasPause = true)
        assertPublishedShapeMigrated { driver ->
            assertEquals(0L, queryLong(driver, "SELECT COUNT(*) FROM chapter_pairing_pairs"))
            assertEquals(
                1L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM pragma_table_info('chapter_pairings') WHERE name='opening_mode'",
                ),
            )
        }
    }

    private fun preparePublishedShape(
        version: Int,
        hasDirectory: Boolean,
        hasPause: Boolean,
        hasReaderIntent: Boolean = false,
    ) {
        val file = app.getDatabasePath("tachiyomi.db")
        file.parentFile!!.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            Database.Schema.create(driver)
            if (!hasReaderIntent) {
                driver.execute(null, "DROP TABLE chapter_pairing_pairs", 0)
                driver.execute(null, "ALTER TABLE chapter_pairings DROP COLUMN opening_mode", 0)
            }
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

    private fun assertPublishedShapeMigrated(assertExtra: (SqlDriver) -> Unit = {}) {
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
            assertExtra(driver)
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
