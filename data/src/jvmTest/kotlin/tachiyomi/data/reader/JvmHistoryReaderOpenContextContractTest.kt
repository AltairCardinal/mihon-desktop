package tachiyomi.data.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.JvmDatabaseHandler
import java.io.File

class JvmHistoryReaderOpenContextContractTest : HistoryReaderOpenContextContract() {
    override fun open(file: File): HistoryDataStorage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val database = historyTestDatabase(driver)
        return HistoryDataStorage(driver, database, JvmDatabaseHandler(database, driver))
    }
}
