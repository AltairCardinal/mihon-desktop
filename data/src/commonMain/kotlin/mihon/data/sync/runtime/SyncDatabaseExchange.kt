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
import mihon.domain.sync.crypto.SyncSpaceMaterial
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
    private val secret: SyncSecret? = null,
    private val spaceMaterial: SyncSpaceMaterial? = null,
    private val allowImport: () -> Boolean = { true },
    private val progress: SyncProgressReporter? = null,
    private val initialUploaded: Long = 0,
    private val initialDownloaded: Long = 0,
    private val uploadedBaseline: Long = 0,
    private val downloadedBaseline: Long = 0,
) {
    suspend fun exchange(spaceId: String, generation: Long, repository: SyncRepository): SyncRunResult {
        var uploaded = 0
        var downloaded = 0
        var uploadedTotal = initialUploaded
        var downloadedTotal = initialDownloaded
        var pending = 0
        var problem: SyncRunProblem? = null
        try {
            val enabled = handler.await {
                sync_journalQueries.getSpace(spaceId, generation).executeAsOneOrNull()?.let {
                    it.active && it.exchange_enabled
                } == true
            }
            if (!enabled) return SyncRunResult(SyncRunStatus.SKIPPED)
            progress?.phase(SyncRunPhase.CHECKING, processed = 0, total = 0)
            while (allowImport()) {
                val importId = handler.await {
                    sync_importQueries.getNextPendingImport(spaceId, generation).executeAsOneOrNull()
                } ?: break
                progress?.phase(SyncRunPhase.IMPORTING, processed = 0, total = 0)
                progress?.log(importId, "首次合并", "正在写入已保存的数据", SyncRunLogStatus.ACTIVE)
                baseline.process(importId)
                progress?.log(importId, "首次合并", "已写入已保存的数据", SyncRunLogStatus.COMPLETED)
                yield()
            }
            val store = SyncInboxStore(handler)
            val service = SyncBatchSyncService(transport, secret, spaceMaterial = spaceMaterial)
            val inbox = SyncInboxExchange(store, service)
            val outbox = SyncOutboxExchange(SyncOutboxStore(handler), service)
            suspend fun reconcileTotals() {
                val published = handler.await {
                    sync_journalQueries.countPublishedEvents(spaceId, generation).executeAsOne()
                }
                val remote = handler.await {
                    sync_journalQueries.countRemoteEvents(spaceId, generation, spaceId, generation).executeAsOne()
                }
                uploadedTotal = maxOf(uploadedTotal, (published - uploadedBaseline).coerceAtLeast(0))
                downloadedTotal = maxOf(downloadedTotal, (remote - downloadedBaseline).coerceAtLeast(0))
                progress?.totals(uploadedTotal, downloadedTotal)
            }
            reconcileTotals()
            projector.retryUnavailable(spaceId, generation)
            val attempted = mutableSetOf<String>()
            while (true) {
                progress?.phase(SyncRunPhase.DOWNLOADING, downloadedTotal, 0, completed = downloadedTotal)
                val snapshot = transport.readSnapshot(repository, spaceId, generation).getOrThrow()
                store.observeSnapshot(snapshot)
                for (entry in snapshot.batches) {
                    if (!attempted.add(entry.batchId)) continue
                    val alreadyReceived = handler.await {
                        sync_inboxQueries.getReceivedBatch(spaceId, generation, entry.batchId).executeAsOneOrNull() !=
                            null ||
                            sync_journalQueries.getBatch(
                                spaceId,
                                generation,
                                entry.batchId,
                            ).executeAsOneOrNull()?.status ==
                            "PUBLISHED"
                    }
                    if (alreadyReceived) continue
                    val result = inbox.receive(snapshot, entry)
                    if (result.accepted) {
                        if (!result.duplicate) {
                            downloaded += (entry.lastSeq - entry.firstSeq + 1).toInt()
                            reconcileTotals()
                        }
                        progress?.phase(
                            SyncRunPhase.MERGING,
                            downloadedTotal,
                            snapshot.batches.sumOf { (it.lastSeq - it.firstSeq + 1).toInt() }.toLong(),
                            completed = downloadedTotal,
                        )
                        progress?.log(
                            entry.batchId,
                            "同步数据",
                            if (result.duplicate) "已确认重复数据" else "已接收远端数据",
                            SyncRunLogStatus.COMPLETED,
                        )
                    } else {
                        problem = SyncRunProblem.INVALID_DATA
                        progress?.log(entry.batchId, "同步数据", "数据无法读取", SyncRunLogStatus.FAILED)
                    }
                    yield()
                }
                reconcileTotals()
                while (projector.project(spaceId, generation) == 50) yield()
                pending = store.status(spaceId, generation).pendingDecisions.toInt()
                progress?.phase(SyncRunPhase.UPLOADING, uploadedTotal, 0, completed = uploadedTotal)
                val result = outbox.uploadNext(snapshot) ?: break
                if (result.publish.status != SyncPublishStatus.PUBLISHED) {
                    problem = SyncRunProblem.NETWORK
                    progress?.phase(
                        SyncRunPhase.UPLOADING,
                        uploadedTotal + downloadedTotal,
                        0,
                        completed = uploadedTotal + downloadedTotal,
                        state = SyncRunState.WAITING_RETRY,
                        reason = "network",
                    )
                    break
                }
                val publishedCount = handler.await {
                    sync_journalQueries.getBatch(spaceId, generation, result.publish.batchId).executeAsOne().event_count
                }.toInt()
                uploaded += publishedCount
                uploadedTotal += publishedCount
                progress?.totals(uploadedTotal, downloadedTotal)
                progress?.phase(SyncRunPhase.CONFIRMING, uploadedTotal + downloadedTotal, 0)
                progress?.log(result.publish.batchId, "本机变动", "已确认上传", SyncRunLogStatus.COMPLETED)
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
    is mihon.domain.sync.crypto.SyncCryptoException -> SyncRunProblem.INVALID_DATA
    is UnsupportedSyncSpace -> SyncRunProblem.INVALID_DATA
    is SyncSetupException -> when (problem) {
        mihon.data.sync.auth.SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE -> SyncRunProblem.REPOSITORY_NOT_PRIVATE
        mihon.data.sync.auth.SyncDiscoveryProblem.ACCOUNT_CHANGED,
        mihon.data.sync.auth.SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
        -> SyncRunProblem.AUTHORIZATION
        else -> SyncRunProblem.REMOTE_CHANGED
    }
    is GitHubAuthException -> if (failure.retryable) SyncRunProblem.NETWORK else SyncRunProblem.AUTHORIZATION
    is SyncSecureStoreException -> SyncRunProblem.STORAGE
    is SyncRemoteSnapshotRejected -> SyncRunProblem.REMOTE_CHANGED
    is SyncHttpException -> if (code == 401 || code == 403) SyncRunProblem.AUTHORIZATION else SyncRunProblem.NETWORK
    is IOException -> SyncRunProblem.NETWORK
    else -> SyncRunProblem.UNKNOWN
}
