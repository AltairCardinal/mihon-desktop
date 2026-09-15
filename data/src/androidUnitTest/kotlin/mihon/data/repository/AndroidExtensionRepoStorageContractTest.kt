package mihon.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.AndroidDatabaseHandler

class AndroidExtensionRepoStorageContractTest : ExtensionRepoStorageContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, AndroidDatabaseHandler(database(driver), driver))
    }
}
