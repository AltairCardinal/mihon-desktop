package tachiyomi.data.extension

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler

class JvmSourceUpdateMemoPersistenceIntegrationTest : SourceUpdateMemoPersistenceIntegrationTest() {
    override fun open(path: String, create: Boolean): Storage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return Storage(driver, JvmDatabaseHandler(database(driver, create), driver))
    }
}
