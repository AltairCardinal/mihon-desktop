package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncDiscoveryStore
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitTree
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

class SyncDiscoveryStoreTest {
    @Test
    fun `discovered batches survive reopen and are removed only after receipt`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val handler = JvmDatabaseHandler(database, driver)
        handler.await(inTransaction = true) {
            sync_journalQueries.insertSpace("space", 1, "owner", "repo", "sync")
        }
        val entry =
            SyncBatchIndexEntry(
                "batch", ".mihon-sync/batches/a/1/batch.json",
                "a".repeat(
                    64,
                ),
                1, 1, "a", 1, "index", "b".repeat(64),
            )
        val snapshot = SyncSnapshot(
            SyncRepository("owner", "repo", "sync"),
            "head",
            SyncGitTree("tree", emptyList(), false),
            "space",
            1,
            listOf(entry),
        )
        SyncDiscoveryStore(handler).observe(snapshot)
        assertEquals(listOf(entry), SyncDiscoveryStore(handler).pending("space", 1))
        SyncDiscoveryStore(handler).complete("space", 1, entry.batchId)
        assertEquals(emptyList<SyncBatchIndexEntry>(), SyncDiscoveryStore(handler).pending("space", 1))
    }
}
