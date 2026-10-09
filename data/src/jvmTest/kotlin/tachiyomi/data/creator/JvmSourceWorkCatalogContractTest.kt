package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler

class JvmSourceWorkCatalogContractTest : SourceWorkCatalogContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, JvmDatabaseHandler(database(driver), driver))
    }
}
