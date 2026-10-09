package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler
import java.nio.file.Path

class JvmSyncPanelLifecycleContractTest : SyncPanelLifecycleContract() {
    override fun open(path: Path): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return storage(driver) { db, managedDriver, dispatcher -> JvmDatabaseHandler(db, managedDriver, dispatcher) }
    }
}
