package tachiyomi.data.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.AndroidDatabaseHandler
import java.io.File

class AndroidHistoryReaderOpenContextContractTest : HistoryReaderOpenContextContract() {
    override fun open(file: File): HistoryDataStorage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val database = historyTestDatabase(driver)
        return HistoryDataStorage(driver, database, AndroidDatabaseHandler(database, driver))
    }
}
