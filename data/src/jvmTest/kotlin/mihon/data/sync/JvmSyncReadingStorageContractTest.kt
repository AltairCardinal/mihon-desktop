package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler

class JvmSyncReadingStorageContractTest : SyncReadingStorageContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = database(driver)
        return Storage(driver, database, JvmDatabaseHandler(database, driver))
    }
}
