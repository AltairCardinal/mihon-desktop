package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.AndroidDatabaseHandler

class AndroidSourceWorkCatalogContractTest : SourceWorkCatalogContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, AndroidDatabaseHandler(database(driver), driver))
    }
}
