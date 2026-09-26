package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.data.sync.runtime.SyncFailureLogStatus
import mihon.data.sync.runtime.SyncFailureReportStore
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRunStore
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
import okio.Path.Companion.toPath
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

class SyncFailureReportStoreTest {
    @Test
    fun `report includes every failed field and invalid event in selected pending run`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            val handler = JvmDatabaseHandler(database, driver)
            val runs = SyncRunStore(handler, clock = { 2_000L })
            val selected = runs.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(runs.claim(selected.runId, "selected-owner", 1))
            runs.expectDownload(selected.runId, "selected-owner", "pending", 522)
            val other = runs.start("other-space", 2, SyncTrigger.MANUAL)
            assertTrue(runs.claim(other.runId, "other-owner", 1))
            runs.expectDownload(other.runId, "other-owner", "pending", 1)
            repeat(520) { index -> insertFailedField(driver, "space", 1, "pending", index) }
            insertFailedField(driver, "other-space", 2, "pending", 700)
            insertInvalidEvent(driver, "space", 1, "pending", 800)
            runs.finish(selected.runId, SyncRunState.PARTIAL, "projection_pending", "selected-owner")
            runs.finish(other.runId, SyncRunState.PARTIAL, "projection_pending", "other-owner")

            val directory = Files.createTempDirectory("mihon-sync-failure-report-")
            try {
                val report = SyncFailureReportStore(handler, directory.toString().toPath())
                    .generate(requireNotNull(runs.get(selected.runId))) as SyncFailureLogStatus.Ready
                assertEquals(521L, report.failedEntries)
                val text = Files.readString(java.nio.file.Path.of(report.path))
                assertTrue(text.contains("漫画 0"))
                assertTrue(text.contains("漫画 519"))
                assertTrue(text.contains("/manga-519"))
                assertTrue(text.contains("SOURCE"))
                assertTrue(text.contains("DESCRIPTION"))
                assertTrue(text.contains("IDENTITY"))
                assertTrue(text.contains("bad-signature"))
                assertTrue(text.contains("/invalid-800"))
                assertTrue(text.contains("字段 / Field: FAVORITE"))
                assertFalse(text.contains("/manga-700"))
                assertEquals(
                    report,
                    SyncFailureReportStore(handler, directory.toString().toPath())
                        .generate(requireNotNull(runs.get(selected.runId))),
                )
            } finally {
                directory.toFile().deleteRecursively()
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `resolved failures disappear and disk errors do not alter run outcome`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            val handler = JvmDatabaseHandler(database, driver)
            val runs = SyncRunStore(handler, clock = { 2_000L })
            val run = runs.start("space", 1, SyncTrigger.MANUAL)
            assertTrue(runs.claim(run.runId, "owner", 1))
            runs.expectDownload(run.runId, "owner", "pending", 1)
            insertFailedField(driver, "space", 1, "pending", 1)
            runs.finish(run.runId, SyncRunState.PARTIAL, "projection_pending", "owner")
            val regularFile = Files.createTempFile("mihon-report-not-directory-", ".txt")
            try {
                val result = SyncFailureReportStore(handler, regularFile.toString().toPath())
                    .generate(requireNotNull(runs.get(run.runId)))
                assertTrue(result is SyncFailureLogStatus.SaveFailed)
                assertEquals(1L, result?.failedEntries)
                assertEquals(SyncRunState.PARTIAL, runs.get(run.runId)?.state)
            } finally {
                Files.deleteIfExists(regularFile)
            }
            val directory = Files.createTempDirectory("mihon-report-resolved-")
            try {
                val store = SyncFailureReportStore(handler, directory.toString().toPath())
                assertTrue(store.generate(requireNotNull(runs.get(run.runId))) is SyncFailureLogStatus.Ready)
                driver.execute(null, "UPDATE sync_field_state SET status='APPLIED' WHERE space_id='space'", 0)
                assertEquals(null, store.generate(requireNotNull(runs.get(run.runId))))
            } finally {
                directory.toFile().deleteRecursively()
            }
        } finally {
            driver.close()
        }
    }

    private fun insertFailedField(
        driver: JdbcSqliteDriver,
        spaceId: String,
        generation: Long,
        batchId: String,
        index: Int,
    ) {
        val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = "/manga-$index")
        val event = SyncEventEnvelope(
            1,
            spaceId,
            generation,
            "actor",
            1,
            index.toLong() + 1,
            SyncCategory.FAVORITE,
            listOf(SyncEffect("favorite", key, SyncField.FAVORITE, SyncEffectKind.ADD)),
            SyncOrigin.USER,
            batchId = batchId,
        )
        val eventKey = event.eventId.stableKey
        val status = when (index) {
            518 -> "DESCRIPTION"
            519 -> "IDENTITY"
            else -> "SOURCE"
        }
        driver.execute(
            null,
            "INSERT INTO sync_events(space_id, generation, actor_id, epoch, seq, category, origin, batch_id, " +
                "event_json, occurred_at, event_key, sync_indexed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            12,
        ) {
            bindString(0, spaceId)
            bindLong(1, generation)
            bindString(2, "actor")
            bindLong(3, 1)
            bindLong(4, index.toLong() + 1)
            bindString(5, "FAVORITE")
            bindString(6, "REMOTE")
            bindString(7, batchId)
            bindString(8, Json.encodeToString(event))
            bindLong(9, 1)
            bindString(10, eventKey)
            bindLong(11, 1)
        }
        driver.execute(
            null,
            "INSERT INTO sync_event_fields(space_id, generation, event_key, object_key, field, object_json) " +
                "VALUES (?, ?, ?, ?, 'FAVORITE', ?)",
            5,
        ) {
            bindString(0, spaceId)
            bindLong(1, generation)
            bindString(2, eventKey)
            bindString(3, key.stableKey)
            bindString(4, Json.encodeToString(key))
        }
        driver.execute(
            null,
            "INSERT INTO sync_field_state(space_id, generation, object_key, field, object_json, status, dirty) " +
                "VALUES (?, ?, ?, 'FAVORITE', ?, ?, 0)",
            5,
        ) {
            bindString(0, spaceId)
            bindLong(1, generation)
            bindString(2, key.stableKey)
            bindString(3, Json.encodeToString(key))
            bindString(4, status)
        }
        driver.execute(
            null,
            "INSERT INTO sync_descriptions(space_id, generation, object_key, description_json) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, spaceId)
            bindLong(1, generation)
            bindString(2, key.stableKey)
            bindString(3, Json.encodeToString(SyncObjectDescriptor(key, "漫画 $index")))
        }
    }

    private fun insertInvalidEvent(
        driver: JdbcSqliteDriver,
        spaceId: String,
        generation: Long,
        batchId: String,
        index: Int,
    ) {
        val eventKey = "invalid:$index"
        val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = "/invalid-$index")
        val event = SyncEventEnvelope(
            1, spaceId, generation, "invalid", 1, index.toLong(), SyncCategory.FAVORITE,
            listOf(SyncEffect("favorite", key, SyncField.FAVORITE, SyncEffectKind.ADD)),
            SyncOrigin.USER, batchId = batchId,
        )
        driver.execute(
            null,
            "INSERT INTO sync_events(space_id, generation, actor_id, epoch, seq, category, origin, batch_id, " +
                "event_json, occurred_at, event_key, sync_indexed) " +
                "VALUES ('$spaceId', $generation, 'invalid', 1, $index, 'FAVORITE', 'REMOTE', " +
                "'$batchId', ?, 1, '$eventKey', 1)",
            1,
        ) { bindString(0, Json.encodeToString(event)) }
        driver.execute(
            null,
            "INSERT INTO sync_invalid_events(space_id, generation, event_key, reason) " +
                "VALUES ('$spaceId', $generation, '$eventKey', 'bad-signature')",
            0,
        )
    }
}
