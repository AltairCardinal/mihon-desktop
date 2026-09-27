package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.projection.SyncProjectionUnavailableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.JvmDatabaseHandler

class JvmSyncRemoteProjectionContractTest : SyncRemoteProjectionContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, JvmDatabaseHandler(database(driver), driver))
    }

    @Test
    fun `unavailable fallback skips a field revised by a concurrent retry`() = runBlocking {
        open().use { storage ->
            storage.prepare()
            storage.driver.execute(
                null,
                "INSERT INTO sync_field_state(space_id, generation, object_key, field, object_json) " +
                    "VALUES ('space', 1, 'race-field', 'FAVORITE', '{}')",
                0,
            )
            val projector = SyncInboxProjector(storage.handler, storage.writer)
            suspend fun field() = storage.handler.await {
                sync_inboxQueries.getFieldState("space", 1, "race-field", "FAVORITE").executeAsOne()
            }
            val stale = field()
            storage.handler.await(inTransaction = true) {
                sync_inboxQueries.markFieldDirty("space", 1, "race-field", "FAVORITE", "{}")
            }
            assertFalse(projector.recordUnavailableIfCurrent(stale, SyncProjectionUnavailableReason.SOURCE))
            assertTrue(field().dirty)
            assertEquals("DIRTY", field().status)
            assertEquals(stale.revision + 1, field().revision)

            val current = field()
            assertTrue(projector.recordUnavailableIfCurrent(current, SyncProjectionUnavailableReason.SOURCE))
            assertFalse(field().dirty)
            assertEquals("SOURCE", field().status)
            assertFalse(projector.recordUnavailableIfCurrent(current, SyncProjectionUnavailableReason.SOURCE))
        }
    }
}
