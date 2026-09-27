package eu.kanade.tachiyomi.di

import android.app.Application
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        app.deleteDatabase("tachiyomi.db")
        previous = Injekt
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
            assertEquals(40L, queryLong(driver, "PRAGMA user_version"))
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
