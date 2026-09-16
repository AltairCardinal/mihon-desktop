package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler

class JvmSyncInboxStorageContractTest : SyncInboxStorageContract() {
    override fun open(path: String?, create: Boolean): Storage {
        val driver = JdbcSqliteDriver(path?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, JvmDatabaseHandler(database(driver, create), driver))
    }
}
