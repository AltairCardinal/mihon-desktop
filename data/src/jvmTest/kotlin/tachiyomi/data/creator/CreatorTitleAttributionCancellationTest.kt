package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import java.util.UUID
import java.util.prefs.Preferences

class CreatorTitleAttributionCancellationTest {
    @Test
    fun `current manga commit survives cancellation before caller resume`() = verify(rebuild = false)

    @Test
    fun `rebuild progress reflects a committed batch when caller is cancelled`() = verify(rebuild = true)

    private fun verify(rebuild: Boolean) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val node = Preferences.userRoot().node("/mihon-tests/title-cancel-${UUID.randomUUID()}")
        try {
            verifyCreatorTitleCommitCancellation(
                handler,
                driver,
                CreatorDiscoveryPreferences(DesktopPreferenceStore(node)),
                rebuild,
            )
        } finally {
            handler.close()
            node.removeNode()
        }
    }
}
