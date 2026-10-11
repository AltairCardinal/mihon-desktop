package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

class CreatorTitleModeReadRaceTest {
    @Test
    fun `mode change between mode and baseline reads cannot baseline a metadata scan`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        try {
            verifyCreatorTitleModeReadRace(handler)
        } finally {
            handler.close()
        }
    }
}
