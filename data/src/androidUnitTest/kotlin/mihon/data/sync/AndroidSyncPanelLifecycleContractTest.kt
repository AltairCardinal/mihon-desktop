package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.AndroidDatabaseHandler
import java.nio.file.Path

class AndroidSyncPanelLifecycleContractTest : SyncPanelLifecycleContract() {
    override fun open(path: Path): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return storage(driver) { db, managedDriver, dispatcher ->
            AndroidDatabaseHandler(db, managedDriver, dispatcher)
        }
    }
}
