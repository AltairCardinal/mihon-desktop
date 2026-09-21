package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncRunLogStatus
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRunStore
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

class SyncRunStoreTest {
    @Test
    fun `schema 27 migration creates durable runtime tables`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP INDEX IF EXISTS sync_runtime_active", 0)
        driver.execute(null, "DROP INDEX IF EXISTS sync_runtime_log_order", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_logs", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_runs", 0)
        driver.execute(null, "DROP TRIGGER IF EXISTS author_archive_source_work_first_seen_defaults", 0)
        driver.execute(null, "DROP TABLE IF EXISTS author_archive_source_date_quality_samples", 0)
        driver.execute(null, "DROP TABLE IF EXISTS author_archive_source_date_quality_current", 0)
        driver.execute(null, "DROP TABLE IF EXISTS author_archive_source_date_quality", 0)
        driver.execute(null, "DROP TABLE IF EXISTS author_archive_representative_work_cache", 0)
        listOf(
            "first_seen_date",
            "first_seen_zone",
            "chapter_count_state",
            "catalog_chapter_count",
            "latest_chapter_at",
        )
            .forEach { column ->
                driver.execute(null, "ALTER TABLE author_archive_source_works DROP COLUMN $column", 0)
            }
        driver.execute(null, "PRAGMA user_version = 27", 0)

        Database.Schema.migrate(driver, 27, Database.Schema.version)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        assertEquals(SyncRunState.QUEUED, store.start("space", 1, SyncTrigger.RECOVERY).state)
    }

    @Test
    fun `run intent and item logs survive reopening and preserve user pause`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val first = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = first.start("space", 1, SyncTrigger.MANUAL)
        first.progress(run.runId, SyncRunPhase.IMPORTING, processed = 2, total = 5)
        first.log(run.runId, "manga-1", "作品 A", "阅读记录 · 已合并", SyncRunLogStatus.COMPLETED)
        first.pause(run.runId)

        val reopened = SyncRunStore(JvmDatabaseHandler(database, driver))
        val restored = reopened.active("space", 1)
        assertEquals(SyncRunState.PAUSED_USER, restored?.state)
        assertEquals(2L, restored?.processed)
        assertEquals(5L, restored?.total)
        assertEquals("作品 A", reopened.logs(run.runId).single().title)
        reopened.resumeIfAllowed(run.runId)
        assertEquals(SyncRunState.RUNNING, reopened.get(run.runId)?.state)
        assertFalse(reopened.logs(run.runId).single().title.isBlank())
    }

    @Test
    fun `logs are bounded without deleting the active run`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver), maxLogEntries = 3)
        val run = store.start("space", 1, SyncTrigger.RECOVERY)
        store.progress(run.runId, SyncRunPhase.CHECKING, processed = 0, total = 0)
        repeat(5) { index -> store.log(run.runId, "key-$index", "标题 $index", "已处理", SyncRunLogStatus.COMPLETED) }
        assertEquals(3, store.logs(run.runId).size)
        assertTrue(store.get(run.runId)!!.state == SyncRunState.RUNNING)
    }

    @Test
    fun `stale owner cannot overwrite progress or finish a newer attempt`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.RECOVERY)

        assertTrue(store.claim(run.runId, "owner-a", 1))
        store.progress(run.runId, SyncRunPhase.IMPORTING, 1, 3, ownerSession = "owner-a")
        store.progress(run.runId, SyncRunPhase.IMPORTING, 0, 0, ownerSession = "owner-a")
        assertEquals(1L, store.get(run.runId)?.processed)
        assertEquals(3L, store.get(run.runId)?.total)
        store.progress(run.runId, SyncRunPhase.UPLOADING, 2, 3, ownerSession = "owner-b")
        store.log(run.runId, "stale", "旧尝试", "不应写入", SyncRunLogStatus.FAILED, ownerSession = "owner-b")
        store.finish(run.runId, SyncRunState.SUCCEEDED, ownerSession = "owner-b")

        assertEquals(SyncRunPhase.IMPORTING, store.get(run.runId)?.phase)
        assertEquals(SyncRunState.RUNNING, store.get(run.runId)?.state)
        assertTrue(store.logs(run.runId).none { it.key == "stale" })

        store.finish(run.runId, SyncRunState.SUCCEEDED, ownerSession = "owner-a")
        assertEquals(SyncRunState.SUCCEEDED, store.get(run.runId)?.state)
    }

    @Test
    fun `stage callbacks cannot reset the durable retry attempt`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.RECOVERY)
        assertTrue(store.claim(run.runId, "owner", 2))

        store.progress(
            run.runId,
            SyncRunPhase.DOWNLOADING,
            processed = 1,
            total = 2,
            ownerSession = "owner",
        )

        assertEquals(2L, store.get(run.runId)?.attempt)
    }

    @Test
    fun `changing phase starts a fresh progress segment`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.MANUAL)
        assertTrue(store.claim(run.runId, "owner", 1))

        store.progress(
            run.runId,
            SyncRunPhase.DOWNLOADING,
            processed = 80,
            total = 100,
            completed = 70,
            skipped = 5,
            failed = 5,
            ownerSession = "owner",
        )
        store.progress(
            run.runId,
            SyncRunPhase.MERGING,
            processed = 1,
            total = 3,
            completed = 1,
            ownerSession = "owner",
        )

        val snapshot = requireNotNull(store.get(run.runId))
        assertEquals(SyncRunPhase.MERGING, snapshot.phase)
        assertEquals(1L, snapshot.processed)
        assertEquals(3L, snapshot.total)
        assertEquals(1L, snapshot.completed)
        assertEquals(0L, snapshot.skipped)
        assertEquals(0L, snapshot.failed)
    }

    @Test
    fun `user pause remains authoritative when an owner is cancelled`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.MANUAL)
        assertTrue(store.claim(run.runId, "owner", 1))

        store.pause(run.runId)
        store.finish(run.runId, SyncRunState.WAITING_SYSTEM, "cancelled", ownerSession = "owner")

        assertEquals(SyncRunState.PAUSED_USER, store.get(run.runId)?.state)
    }

    @Test
    fun `process restart does not spend the automatic network attempt`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.RECOVERY)
        assertTrue(store.claim(run.runId, "owner", 2))

        assertTrue(store.releaseForRecovery(run.runId))
        assertEquals(1L, store.get(run.runId)?.attempt)
    }

    @Test
    fun `waiting retry owner can be reclaimed after a process restart`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.RECOVERY)
        assertTrue(store.claim(run.runId, "owner", 1))
        store.progress(
            run.runId,
            SyncRunPhase.UPLOADING,
            processed = 1,
            total = 2,
            state = SyncRunState.WAITING_RETRY,
            ownerSession = "owner",
        )

        assertTrue(store.releaseForRecovery(run.runId))
        assertEquals(SyncRunState.WAITING_SYSTEM, store.get(run.runId)?.state)
        assertEquals(0L, store.get(run.runId)?.attempt)
    }

    @Test
    fun `durable exchange totals keep upload and download counts separate`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.RECOVERY)
        assertTrue(store.claim(run.runId, "owner", 1))

        store.totals(run.runId, uploaded = 2, downloaded = 100, ownerSession = "owner")

        assertEquals(2L, store.get(run.runId)?.uploaded)
        assertEquals(100L, store.get(run.runId)?.downloaded)
    }
}
