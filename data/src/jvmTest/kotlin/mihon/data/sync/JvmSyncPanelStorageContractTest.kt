package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.JvmDatabaseHandler
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class JvmSyncPanelStorageContractTest : SyncPanelStorageContract() {
    override fun open(): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database(driver), driver))
    }

    @Test
    fun `visible countdown ticker updates time without refreshing database`() = runBlocking {
        open().use { storage ->
            val handler = CountingDatabaseHandler(storage.handler)
            val clock = AtomicLong(1_000L)
            withPanel(storage, handler = handler, clock = clock::get) { panel, _ ->
                panel.dispatch(mihon.data.sync.runtime.SyncPanelAction.Open)
                panel.awaitIdle()
                delay(1_100)
                val readsAfterOpening = handler.awaitCalls.get()
                clock.set(2_500L)

                delay(1_100)

                assertEquals(2_500L, panel.state.value.nowMillis)
                assertEquals(readsAfterOpening, handler.awaitCalls.get())
                assertTrue(readsAfterOpening > 0)
            }
        }
    }

    private class CountingDatabaseHandler(private val delegate: DatabaseHandler) : DatabaseHandler by delegate {
        val awaitCalls = AtomicInteger()

        override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
            awaitCalls.incrementAndGet()
            return delegate.await(inTransaction, block)
        }
    }
}
