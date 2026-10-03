package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler

class JvmSyncSpaceSwitchContractTest : SyncSpaceSwitchContract() {
    override fun open(): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database(driver), driver))
    }
}
