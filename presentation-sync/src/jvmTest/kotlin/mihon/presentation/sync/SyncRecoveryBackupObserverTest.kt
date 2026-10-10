package mihon.presentation.sync

import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.NoopBackupRestoreSync
import mihon.data.sync.journal.SyncRestoreOutcome
import mihon.data.sync.runtime.SyncRecoveryPlatformResult
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncRecoveryBackupObserverTest {
    @Test fun `partial real unit result keeps successful and failed object scopes separate`() = runBlocking {
        val results = mutableListOf<SyncRecoveryPlatformResult>()
        val observer = SyncRecoveryBackupObserver(NoopBackupRestoreSync, results::add)
        val success = SyncObjectKey(SyncObjectType.MANGA, sourceId = "7", originalUrl = "/ok")
        val failure = SyncObjectKey(SyncObjectType.MANGA, sourceId = "7", originalUrl = "/failed")
        observer.expectObjects(listOf(success, failure))
        observer.restoreManga(null, 7, "/ok") { }
        runCatching { observer.restoreManga(null, 7, "/failed") { error("unit failed") } }
        observer.errorCount(1)
        observer.finish(null, SyncRestoreOutcome.PARTIAL)
        assertEquals(1, results.size, "actual partial restoration must report back to the recovery task")
        val result = results.single() as SyncRecoveryPlatformResult.PartialFailure
        assertEquals(listOf(success), result.succeededObjects)
        assertEquals(listOf(failure), result.failedObjects)
        assertEquals(1L, result.remaining)
    }

    @Test fun `large failure count preserves bounded intact object samples`() = runBlocking {
        val results = mutableListOf<SyncRecoveryPlatformResult>()
        val observer = SyncRecoveryBackupObserver(NoopBackupRestoreSync, results::add)
        val objects = (0 until 200).map { SyncObjectKey(SyncObjectType.MANGA, sourceId = "7", originalUrl = "/$it") }
        observer.expectObjects(objects)
        objects.forEach { key ->
            runCatching { observer.restoreManga(null, 7, key.originalUrl!!) { error("unit failed") } }
        }
        observer.errorCount(200)
        observer.finish(null, SyncRestoreOutcome.PARTIAL)
        assertEquals(1, results.size, "bounded reporting must retain the actual failure outcome")
        val result = results.single() as SyncRecoveryPlatformResult.PartialFailure
        assertEquals(200L, result.remaining)
        assertTrue(result.failedObjects.size in 1..16)
        assertTrue(result.failedObjects.all { it in objects })
    }
}
