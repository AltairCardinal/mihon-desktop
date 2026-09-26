package eu.kanade.tachiyomi.di

import android.app.Application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
