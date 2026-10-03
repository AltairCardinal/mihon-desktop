package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.AndroidDatabaseHandler

class AndroidSyncSpaceSwitchContractTest : SyncSpaceSwitchContract() {
    override fun open(): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return SyncRuntimeStorageContract.Storage(driver, AndroidDatabaseHandler(database(driver), driver))
    }
}
