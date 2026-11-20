package mihon.data.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.data.sync.SyncRuntimeStorageContract.Storage
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.runtime.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncLocalProjectionRecoveryTest {
    private fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, JvmDatabaseHandler(database(driver), driver))
    }

    private fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    @Test
    fun `real SQLite busy rolls back local probe and retries original receipt once`() = runBlocking {
        val path = Files.createTempFile("sync-local-busy-", ".db")
        val base = JdbcSqliteDriver("jdbc:sqlite:$path")
        val locker = java.sql.DriverManager.getConnection("jdbc:sqlite:$path")
        val hook = RecoveryDriver(base)
        val storage = Storage(hook, JvmDatabaseHandler(database(hook), hook))
        try {
            base.execute(null, "PRAGMA busy_timeout=0", 0)
            locker.createStatement().use { it.execute("PRAGMA busy_timeout=0") }
            SyncOnboardingFixture(storage).use { setup ->
                val runtime = prepare(setup)
                val run = runtime.runStore.latest("space", 1)!!
                val requests = setup.git.server.requestCount
                locker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    assertTrue(runtime.resumeIfNeeded())
                    assertEquals(SyncRunState.BLOCKED, runtime.runStore.get(run.runId)!!.state)
                    assertTrue(runtime.recoveryDelayMillis() >= 900_000L)
                } finally {
                    locker.createStatement().use { it.execute("ROLLBACK") }
                }
                setup.now += 900_000L
                var held = false
                hook.beforeDirtyRead = {
                    hook.beforeDirtyRead = null
                    locker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                    held = true
                }
                hook.beforeRuntimeWrite = {
                    if (held) {
                        locker.createStatement().use { it.execute("ROLLBACK") }
                        held = false
                    }
                }
                assertTrue(runtime.resumeIfNeeded())
                val waiting = runtime.runStore.get(run.runId)!!
                assertEquals(SyncRunState.WAITING_RETRY, waiting.state)
                assertEquals("STORAGE", waiting.stopReason)
                assertFalse(runtime.localOperation.value!!.running)
                assertTrue(runtime.localOperation.value!!.fraction < 1.0)
                assertFalse(runtime.localOperation.value!!.durableSuccess)
                assertEquals(0L, waiting.confirmedItems)
                assertTrue(waiting.nextRetryAt >= setup.now + 900_000L)
                assertTrue(
                    storage.handler.await {
                        sync_inboxQueries.getDirtyFields("space", 1, 50).executeAsList().isNotEmpty()
                    },
                )
                setup.now = waiting.nextRetryAt
                assertTrue(runtime.resumeIfNeeded())
                assertEquals(SyncRunState.SUCCEEDED, runtime.runStore.get(run.runId)!!.state)
                assertEquals(1L, runtime.runStore.get(run.runId)!!.confirmedItems)
                assertEquals(1.0, runtime.localOperation.value!!.fraction)
                assertTrue(runtime.localOperation.value!!.durableSuccess)
                assertFalse(runtime.localOperation.value!!.running)
                assertFalse(runtime.resumeIfNeeded())
                assertEquals(requests, setup.git.server.requestCount)
            }
        } finally {
            locker.close()
            storage.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `pause and cancel join local probe before another field transaction`() = runBlocking {
        for (cancel in listOf(false, true)) {
            val base = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val hook = RecoveryDriver(base)
            Storage(hook, JvmDatabaseHandler(database(hook), hook)).use { storage ->
                SyncOnboardingFixture(storage).use { setup ->
                    val runtime = prepare(setup)
                    val run = runtime.runStore.latest("space", 1)!!
                    val requests = setup.git.server.requestCount
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    hook.beforeDirtyRead = {
                        hook.beforeDirtyRead = null
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    val recovery = async(Dispatchers.IO) { runtime.resumeIfNeeded() }
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        val secondRecovery = async(Dispatchers.IO) { runtime.resumeIfNeeded() }
                        val stopping = launch(start = CoroutineStart.UNDISPATCHED) {
                            if (cancel) runtime.cancelSync() else runtime.pauseSync()
                        }
                        release.countDown()
                        stopping.join()
                        recovery.await()
                        assertFalse(secondRecovery.await())
                        assertEquals(
                            if (cancel) SyncRunState.CANCELLED else SyncRunState.PAUSED_USER,
                            runtime.runStore.get(run.runId)!!.state,
                        )
                        assertEquals(0L, runtime.runStore.get(run.runId)!!.confirmedItems)
                        assertFalse(runtime.localOperation.value!!.running)
                        assertTrue(runtime.localOperation.value!!.fraction < 1.0)
                        assertTrue(
                            storage.handler.await {
                                sync_inboxQueries.getDirtyFields("space", 1, 50).executeAsList().isNotEmpty()
                            },
                        )
                        assertFalse(runtime.resumeIfNeeded())
                        assertEquals(requests, setup.git.server.requestCount)
                    } finally {
                        release.countDown()
                        recovery.cancel()
                    }
                }
            }
        }
    }

    @Test
    fun `interrupted local probe retains local authority and disabled exchange stays untouched`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                val runtime = prepare(setup)
                val run = runtime.runStore.latest("space", 1)!!
                val requests = setup.git.server.requestCount
                assertTrue(runtime.runStore.claimLocalProjectionRecovery(run.runId, "crashed-local-probe"))
                assertEquals("local_projection", runtime.runStore.get(run.runId)!!.stopReason)
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                val reopened = setup.runtime()
                assertFalse(reopened.hasResumableRun())
                assertFalse(reopened.isRecoveryDue())
                assertFalse(reopened.resumeIfNeeded())
                assertEquals("crashed-local-probe", runtime.runStore.get(run.runId)!!.ownerSession)
                storage.handler.await { sync_journalQueries.activateSpace("space", 1) }
                assertTrue(reopened.resumeIfNeeded())
                assertEquals(SyncRunState.SUCCEEDED, runtime.runStore.get(run.runId)!!.state)
                assertEquals(1L, runtime.runStore.get(run.runId)!!.confirmedItems)
                assertEquals(requests, setup.git.server.requestCount)
            }
        }
    }

    private suspend fun prepare(setup: SyncOnboardingFixture): SyncRuntime {
        setup.existing("")
        setup.authorize()
        setup.begin()
        val runtime = setup.runtime
        val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/local-probe")
        val event = SyncEventEnvelope(
            1, "space", 1, "remote", 1, 1, SyncCategory.FAVORITE,
            listOf(SyncEffect("favorite", key, SyncField.FAVORITE, SyncEffectKind.ADD)),
            SyncOrigin.USER, batchId = "local-probe",
        )
        assertTrue(
            SyncInboxStore(setup.storage.handler).ingest(
                SyncBatch(
                    1,
                    "space",
                    1,
                    "local-probe",
                    listOf(event),
                    listOf(SyncObjectDescriptor(key, "probe")),
                ),
            ).accepted,
        )
        val run = runtime.runStore.start("space", 1, SyncTrigger.MANUAL)
        assertTrue(runtime.runStore.claim(run.runId, "previous", 1))
        runtime.runStore.expectDownload(run.runId, "previous", "local-probe", 1)
        runtime.runStore.freezePlan(run.runId, "previous", emptyList())
        runtime.runStore.progress(run.runId, SyncRunPhase.MERGING, 0, 1, ownerSession = "previous")
        runtime.runStore.finish(run.runId, SyncRunState.BLOCKED, "UNKNOWN", "previous")
        return runtime
    }
}

private class RecoveryDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var beforeDirtyRead: (() -> Unit)? = null
    var beforeRuntimeWrite: (() -> Unit)? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.contains("INDEXED BY sync_dirty_fields")) beforeDirtyRead?.invoke()
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (sql.startsWith("UPDATE sync_runtime_runs")) beforeRuntimeWrite?.invoke()
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
