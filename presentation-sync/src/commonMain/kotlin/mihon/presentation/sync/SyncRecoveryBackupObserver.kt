package mihon.presentation.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.data.sync.journal.BackupRestoreSync
import mihon.data.sync.journal.SyncRestoreOutcome
import mihon.data.sync.runtime.SyncRecoveryPlatformResult
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType

/** Observes the existing backup units; restoring and freezing baselines remain owned by the delegate. */
class SyncRecoveryBackupObserver(
    private val delegate: BackupRestoreSync,
    private val completed: (SyncRecoveryPlatformResult) -> Unit,
) : BackupRestoreSync {
    private val mutex = Mutex()
    private val expected = linkedSetOf<SyncObjectKey>()
    private val succeeded = linkedSetOf<SyncObjectKey>()
    private val failed = linkedSetOf<SyncObjectKey>()
    private var reportedErrors: Int? = null
    fun expectObjects(objects: List<SyncObjectKey>) {
        expected.addAll(objects)
    }
    fun errorCount(count: Int) {
        reportedErrors = count.coerceAtLeast(0)
    }
    override suspend fun begin() = delegate.begin()
    override suspend fun restoreManga(id: String?, sourceId: Long, url: String, restore: suspend () -> Unit) {
        observe(listOf(SyncObjectKey(SyncObjectType.MANGA, sourceId = sourceId.toString(), originalUrl = url))) {
            delegate.restoreManga(id, sourceId, url, restore)
        }
    }
    override suspend fun restoreAuthors(id: String?, portableKeys: List<String>, restore: suspend () -> Unit) {
        observe(portableKeys.distinct().map { SyncObjectKey(SyncObjectType.AUTHOR, portableKey = it) }) {
            delegate.restoreAuthors(id, portableKeys, restore)
        }
    }
    private suspend fun observe(objects: List<SyncObjectKey>, operation: suspend () -> Unit) {
        try {
            operation()
            mutex.withLock { succeeded.addAll(objects) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock { failed.addAll(objects) }
            throw failure
        }
    }
    override suspend fun finish(id: String?, outcome: SyncRestoreOutcome) {
        delegate.finish(id, outcome)
        val result = mutex.withLock {
            val unresolved = (expected - succeeded) + failed
            val remaining = maxOf(unresolved.size.toLong(), reportedErrors?.toLong() ?: 0)
            when {
                outcome == SyncRestoreOutcome.COMPLETED ||
                    (outcome == SyncRestoreOutcome.CANCELLED && succeeded.isNotEmpty() && remaining == 0L) ->
                    if (succeeded.isEmpty()) {
                        SyncRecoveryPlatformResult.NoChange
                    } else {
                        SyncRecoveryPlatformResult.Changed(bounded(succeeded))
                    }
                outcome == SyncRestoreOutcome.CANCELLED && succeeded.isEmpty() -> SyncRecoveryPlatformResult.Cancelled
                expected.isEmpty() -> SyncRecoveryPlatformResult.Failed("BACKUP_RESTORE_FAILED")
                else -> {
                    val sample = bounded(unresolved)
                    SyncRecoveryPlatformResult.PartialFailure(
                        bounded(succeeded),
                        sample,
                        remaining.takeIf { it > 0 },
                        objectsComplete =
                        sample.size.toLong() == remaining && remaining > 0,
                    )
                }
            }
        }
        completed(result)
    }
    private fun bounded(objects: Collection<SyncObjectKey>): List<SyncObjectKey> {
        var bytes = 0
        return objects.filter { key ->
            val size = key.stableKey.encodeToByteArray().size
            if (bytes + size > 1024) {
                false
            } else {
                bytes += size
                true
            }
        }.take(16)
    }
}
