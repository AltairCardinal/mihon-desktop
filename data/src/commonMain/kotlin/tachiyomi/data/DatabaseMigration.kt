package tachiyomi.data

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver

/** Runs generated SQLDelight migrations as one atomic database change. */
object DatabaseMigration {
    fun migrateAtomically(driver: SqlDriver, oldVersion: Long, newVersion: Long) {
        object : TransacterImpl(driver) {}.transaction {
            Database.Schema.migrate(driver, oldVersion, newVersion)
            driver.execute(null, "PRAGMA user_version = $newVersion", 0)
        }
    }
}
