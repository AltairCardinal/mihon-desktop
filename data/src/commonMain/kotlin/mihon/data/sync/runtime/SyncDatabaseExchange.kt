package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.inbox.SyncInboxExchange
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncOutboxExchange
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncRemoteSnapshotRejected
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncTransportPort
import tachiyomi.data.DatabaseHandler
import java.io.IOException

/** Drains durable queues in bounded transactions; confirmation decisions do not gate exchange. */
class SyncDatabaseExchange(
    private val handler: DatabaseHandler,
    private val baseline: SyncBaselineStore,
    private val projector: SyncInboxProjector,
    private val transport: SyncTransportPort,
    private val secret: SyncSecret,
    private val allowImport: () -> Boolean = { true },
) {
    suspend fun exchange(spaceId: String, generation: Long, repository: SyncRepository): SyncRunResult {
        var uploaded = 0
        var downloaded = 0
        var pending = 0
        var problem: SyncRunProblem? = null
        try {
            val enabled = handler.await {
                sync_journalQueries.getSpace(spaceId, generation).executeAsOneOrNull()?.let {
                    it.active && it.exchange_enabled
                } == true
            }
            if (!enabled) return SyncRunResult(SyncRunStatus.SKIPPED)
            while (allowImport()) {
                val importId = handler.await {
                    sync_importQueries.getNextPendingImport(spaceId, generation).executeAsOneOrNull()
                } ?: break
                baseline.process(importId)
                yield()
            }
            val store = SyncInboxStore(handler)
            val service = SyncBatchSyncService(transport, secret)
            val inbox = SyncInboxExchange(store, service)
            val outbox = SyncOutboxExchange(SyncOutboxStore(handler), service)
            projector.retryUnavailable(spaceId, generation)
            val attempted = mutableSetOf<String>()
            while (true) {
                val snapshot = transport.readSnapshot(repository, spaceId, generation).getOrThrow()
                store.observeSnapshot(snapshot)
                for (entry in snapshot.batches) {
                    if (!attempted.add(entry.batchId)) continue
                    val received = handler.await {
                        sync_inboxQueries.getReceivedBatch(spaceId, generation, entry.batchId).executeAsOneOrNull() !=
                            null ||
                            sync_journalQueries.getBatch(
                                spaceId,
                                generation,
                                entry.batchId,
                            ).executeAsOneOrNull()?.status ==
                            "PUBLISHED"
                    }
                    if (received) continue
                    val result = inbox.receive(snapshot, entry)
                    if (result.accepted) {
                        if (!result.duplicate) downloaded += (entry.lastSeq - entry.firstSeq + 1).toInt()
                    } else {
                        problem = SyncRunProblem.INVALID_DATA
                    }
                    yield()
                }
                while (projector.project(spaceId, generation) == 50) yield()
                pending = store.status(spaceId, generation).pendingDecisions.toInt()
                val result = outbox.uploadNext(snapshot) ?: break
                if (result.publish.status != SyncPublishStatus.PUBLISHED) {
                    problem = SyncRunProblem.NETWORK
                    break
                }
                uploaded += handler.await {
                    sync_journalQueries.getBatch(spaceId, generation, result.publish.batchId).executeAsOne().event_count
                }.toInt()
                yield()
                // The publisher has advanced the durable remote anchor; always obtain a fresh snapshot.
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            problem = failure.syncProblem()
        }
        return SyncRunResult(
            status = when {
                problem == null -> SyncRunStatus.SUCCESS
                uploaded > 0 || downloaded > 0 -> SyncRunStatus.PARTIAL
                else -> SyncRunStatus.FAILED
            },
            uploaded = uploaded,
            downloaded = downloaded,
            pending = pending,
            problem = problem,
        )
    }
}

internal fun Exception.syncProblem(): SyncRunProblem = when (this) {
    is GitHubAuthException -> if (failure.retryable) SyncRunProblem.NETWORK else SyncRunProblem.AUTHORIZATION
    is SyncSecureStoreException -> SyncRunProblem.STORAGE
    is SyncRemoteSnapshotRejected -> SyncRunProblem.REMOTE_CHANGED
    is SyncHttpException -> if (code == 401 || code == 403) SyncRunProblem.AUTHORIZATION else SyncRunProblem.NETWORK
    is IOException -> SyncRunProblem.NETWORK
    else -> SyncRunProblem.UNKNOWN
}
