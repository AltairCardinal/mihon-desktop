package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler

class JvmSyncJournalStorageContractTest : SyncJournalStorageContract() {
    override fun open(databasePath: String?, create: Boolean): Storage {
        val driver = JdbcSqliteDriver(databasePath?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, JvmDatabaseHandler(database(driver, create), driver))
    }
}
