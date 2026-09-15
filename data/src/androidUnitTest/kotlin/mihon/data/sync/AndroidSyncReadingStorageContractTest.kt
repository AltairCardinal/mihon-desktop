package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.AndroidDatabaseHandler

class AndroidSyncReadingStorageContractTest : SyncReadingStorageContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = database(driver)
        return Storage(driver, database, AndroidDatabaseHandler(database, driver))
    }
}
