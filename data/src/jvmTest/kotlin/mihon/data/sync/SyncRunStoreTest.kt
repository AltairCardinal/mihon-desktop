package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncRunLogEntry
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
    fun `latest run is the newer insertion when timestamps tie`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { 1_000L })
        val first = store.start("space", 1, SyncTrigger.MANUAL)
        val second = store.start("space", 1, SyncTrigger.MANUAL)

        assertEquals(second.runId, store.latest("space", 1)?.runId)
        assertEquals(second.runId, store.active("space", 1)?.runId)
        assertTrue(first.runId != second.runId)
    }

    @Test
    fun `unprojected unavailable and decision fields never confirm a received batch`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.MANUAL)
        assertTrue(store.claim(run.runId, "owner", 1))
        store.expectDownload(run.runId, "owner", "batch", 1)
        assertTrue(store.confirmReceived(run.runId, "owner").isEmpty())
        driver.execute(
            null,
            "INSERT INTO sync_inbox_batches(space_id, generation, batch_id, status, body_json) " +
                "VALUES ('space', 1, 'batch', 'RECEIVED', '{}')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_events(space_id, generation, actor_id, epoch, seq, category, origin, " +
                "batch_id, event_json, occurred_at, event_key, sync_indexed) VALUES " +
                "('space', 1, 'actor', 1, 1, 'FAVORITE', 'REMOTE', 'batch', '{}', 1, 'event', 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_event_fields(space_id, generation, event_key, object_key, field, object_json) " +
                "VALUES ('space', 1, 'event', 'object', 'FAVORITE', '{}')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_field_state(space_id, generation, object_key, field, object_json, status, dirty) " +
                "VALUES ('space', 1, 'object', 'FAVORITE', '{}', 'DIRTY', 1)",
            0,
        )
        assertTrue(store.confirmReceived(run.runId, "owner").isEmpty())
        listOf("SOURCE", "DECISION").forEach { status ->
            driver.execute(null, "UPDATE sync_field_state SET status = '$status', dirty = 0", 0)
            assertTrue(store.confirmReceived(run.runId, "owner").isEmpty())
        }
        assertEquals(0L, store.get(run.runId)?.confirmedItems)
        driver.execute(null, "UPDATE sync_field_state SET status = 'APPLIED', dirty = 0", 0)
        assertEquals(listOf("batch" to 1L), store.confirmReceived(run.runId, "owner"))
        assertTrue(store.confirmReceived(run.runId, "owner").isEmpty())
        assertEquals(1L, store.get(run.runId)?.confirmedItems)
        assertEquals(1L, store.confirmed(run.runId, "owner", SyncProgressDirection.DOWNLOAD, "batch", 1))
    }

    @Test
    fun `partial result is durable and does not block the next run`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val partial = store.start("space", 1, SyncTrigger.MANUAL)
        store.finish(partial.runId, SyncRunState.PARTIAL, "pending_decision")
        assertEquals(SyncRunState.PARTIAL, store.latest("space", 1)?.state)
        assertEquals(null, store.active("space", 1))
        assertTrue(store.start("space", 1, SyncTrigger.MANUAL).runId != partial.runId)
    }

    @Test
    fun `only projection pending partial run can be reclaimed for its receipt`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val decision = store.start("other", 1, SyncTrigger.MANUAL)
        store.finish(decision.runId, SyncRunState.PARTIAL, "pending_decision")
        assertEquals(null, store.active("other", 1))
        assertEquals(false, store.claim(decision.runId, "decision-owner", 1))
        val projection = store.start("space", 1, SyncTrigger.MANUAL)
        assertTrue(store.claim(projection.runId, "first-owner", 1))
        store.expectDownload(projection.runId, "first-owner", "dependency-batch", 1)
        store.finish(projection.runId, SyncRunState.PARTIAL, "projection_pending", "first-owner")
        assertEquals(projection.runId, store.active("space", 1)?.runId)
        assertTrue(store.claim(projection.runId, "projection-owner", 1))
        assertEquals(SyncRunState.RUNNING, store.get(projection.runId)?.state)

        val mixed = store.start("mixed", 1, SyncTrigger.MANUAL)
        assertTrue(store.claim(mixed.runId, "mixed-first-owner", 1))
        store.expectDownload(mixed.runId, "mixed-first-owner", "decision-batch", 1)
        store.finish(mixed.runId, SyncRunState.PARTIAL, "pending_decision", "mixed-first-owner")
        assertEquals(mixed.runId, store.active("mixed", 1)?.runId)
        assertTrue(store.claim(mixed.runId, "mixed-next-owner", 2))
    }

    @Test
    fun `account cooldown is durable monotonic and isolated by account`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        var now = 10_000L
        val store = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { now })
        store.extendAccountHttpNotBefore(accountId = 101, notBeforeMillis = now + 60_000)
        store.extendAccountHttpNotBefore(accountId = 101, notBeforeMillis = now + 10_000)

        val reopened = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { now })
        assertEquals(now + 60_000L, reopened.accountHttpNotBefore(101))
        assertEquals(0L, reopened.accountHttpNotBefore(202))
        now += 60_001L
        assertTrue(reopened.accountHttpNotBefore(101) <= now)
    }

    @Test
    fun `schema 27 migration creates durable runtime tables`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP INDEX IF EXISTS sync_runtime_active", 0)
        driver.execute(null, "DROP INDEX IF EXISTS sync_runtime_log_order", 0)
        driver.execute(null, "DROP INDEX IF EXISTS sync_events_by_batch_confirmation", 0)
        driver.execute(null, "DROP INDEX IF EXISTS sync_pending_upload_round", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_confirmations", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_logs", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_runs", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_http_account_gates", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_snapshot_manifest_entries", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_snapshot_manifest_batches", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_snapshot_manifests", 0)
        driver.execute(null, "ALTER TABLE sync_remote_guards DROP COLUMN revision", 0)
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
    fun `a batch of run logs is persisted and trimmed in one store operation`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver), maxLogEntries = 3)
        val run = store.start("space", 1, SyncTrigger.RECOVERY)
        store.logBatch(
            run.runId,
            listOf(
                SyncRunLogEntry("batch-1", "作品 1", "已接收", SyncRunLogStatus.COMPLETED),
                SyncRunLogEntry("batch-2", "作品 2", "已接收", SyncRunLogStatus.COMPLETED),
                SyncRunLogEntry("batch-3", "作品 3", "已接收", SyncRunLogStatus.COMPLETED),
                SyncRunLogEntry("batch-4", "作品 4", "已接收", SyncRunLogStatus.COMPLETED),
            ),
        )
        assertEquals(listOf("batch-4", "batch-3", "batch-2"), store.logs(run.runId).map { it.key })
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

        assertEquals(2L, store.get(run.runId)?.attemptId)
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
    fun `process restart advances the claim id without rewinding it`() = runBlocking {
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
        assertEquals(2L, store.get(run.runId)?.attemptId)
    }

    @Test
    fun `network failure count is owner fenced and independent from the claim id`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        val store = SyncRunStore(JvmDatabaseHandler(database, driver))
        val run = store.start("space", 1, SyncTrigger.RECOVERY)

        assertTrue(store.claim(run.runId, "owner-1", attemptId = 1))
        assertEquals(1L, store.recordNetworkFailure(run.runId, "owner-1"))
        store.finish(run.runId, SyncRunState.WAITING_RETRY, "network", ownerSession = "owner-1")

        assertTrue(store.claim(run.runId, "owner-2", attemptId = 2))
        val reclaimed = requireNotNull(store.get(run.runId))
        assertEquals(2L, reclaimed.attemptId)
        assertEquals(1L, reclaimed.networkFailureCount)
        assertTrue(runCatching { store.recordNetworkFailure(run.runId, "owner-1") }.isFailure)
        assertEquals(1L, store.get(run.runId)?.networkFailureCount)
        assertEquals(2L, store.recordNetworkFailure(run.runId, "owner-2"))
        assertEquals(2L, store.get(run.runId)?.attemptId)
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
        assertEquals(1L, store.get(run.runId)?.attemptId)
    }

    @Test
    fun `account rate limit deferral keeps claim and actual network failure counts`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ).also { Database.Schema.create(driver) }
        var now = 10_000L
        val store = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { now })
        val run = store.start("space", 1, SyncTrigger.RECOVERY)
        assertTrue(store.claim(run.runId, "owner", attemptId = 2))
        assertEquals(1L, store.recordNetworkFailure(run.runId, "owner"))
        assertTrue(store.releaseForRecovery(run.runId))

        val deadline = now + 60_000L
        assertTrue(store.deferUntilAccountHttpGate(run.runId, deadline))
        val deferred = requireNotNull(store.get(run.runId))
        assertEquals(SyncRunState.WAITING_RETRY, deferred.state)
        assertEquals("rate_limit", deferred.stopReason)
        assertEquals(deadline, deferred.nextRetryAt)
        assertEquals(2L, deferred.attemptId)
        assertEquals(1L, deferred.networkFailureCount)

        now += 1_000L
        assertTrue(store.deferUntilAccountHttpGate(run.runId, now + 5_000L))
        assertEquals(deadline, store.get(run.runId)?.nextRetryAt)
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
