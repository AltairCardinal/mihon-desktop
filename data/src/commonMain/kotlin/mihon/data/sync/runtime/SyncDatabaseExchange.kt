package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import mihon.data.sync.http.NoopSyncMetrics
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.data.sync.http.SyncMetrics
import mihon.data.sync.inbox.SyncDiscoveryStore
import mihon.data.sync.inbox.SyncInboxExchange
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncOutboxExchange
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncRemoteSnapshotRejected
import mihon.data.sync.transport.SyncRemoteSnapshotStaleCandidate
import mihon.data.sync.transport.SyncSnapshotWriteOwner
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.runtime.SyncOperationStage
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncPublishFailureClass
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
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
    private val reconcileTotalsOnStart: Boolean = true,
    private val metrics: SyncMetrics = NoopSyncMetrics,
    private val snapshotOwner: SyncSnapshotWriteOwner? = null,
    private val liveProgress: SyncLiveProgressSession? = null,
    private val diagnosticContext: SyncJournalContext? = null,
    private val allowPublish: Boolean = true,
    private val onOperationProgress: (SyncOperationStage, Double) -> Unit = { _, _ -> },
) {
    private val inboxStore = SyncInboxStore(handler)
    internal val snapshotEvidenceEvaluations: Long get() = inboxStore.remoteGuard.evidenceEvaluations

    private fun operation(stage: SyncOperationStage, completed: Double = 1.0) {
        runCatching { onOperationProgress(stage, completed) }
    }

    suspend fun exchange(spaceId: String, generation: Long, repository: SyncRepository): SyncRunResult {
        var diagnosticStage = SyncJournalStage.CHECKING
        var runPlan = progress?.plan()
        val persistentPlan = progress?.supportsFrozenPlan == true
        var uploaded = 0
        var downloaded = 0
        var uploadedTotal = initialUploaded
        var downloadedTotal = initialDownloaded
        var pending = 0
        var problem: SyncRunProblem? = null
        var retryAfterMillis: Long? = null
        var networkPhase: mihon.domain.sync.runtime.SyncNetworkFailurePhase? = null
        var httpStatus: Int? = null
        var downloadScope = liveProgress?.scope("download-discovery")
        var downloadTotal: Long? = null
        var uploadScope: String? = null
        var uploadRound = 0
        var uploadRoundTotal = 0L
        var uploadRemainingExpected = 0L
        var frozenUploadBatchIds = emptyList<String>()
        var frozenUploadBatchIndex = 0
        var activeUploadBatchId: String? = null
        var uploadCount = 0L
        var uploadTransferFinished = false
        var hasUnconfirmedProjection = false
        val receivedThisExchange = mutableSetOf<String>()
        var finalHeadVerified = false
        try {
            operation(SyncOperationStage.BASELINE, 0.0)
            val enabled = handler.await {
                sync_journalQueries.getSpace(spaceId, generation).executeAsOneOrNull()?.let {
                    it.active && it.exchange_enabled
                } == true
            }
            if (!enabled) return SyncRunResult(SyncRunStatus.SKIPPED)
            if (runPlan == null && allowImport()) {
                baseline.ensureMetadataBaseline(spaceId, generation)
                baseline.ensureFavoriteDateBaseline(spaceId, generation)
            }
            diagnosticStage = SyncJournalStage.CHECKING
            diagnosticContext?.journal?.record(
                SyncJournalEventKind.PHASE,
                diagnosticStage,
                diagnosticContext.runId,
                attemptId = diagnosticContext.attemptId,
                trigger = diagnosticContext.trigger,
            )
            progress?.phase(SyncRunPhase.CHECKING, processed = 0, total = 0)
            while (runPlan == null && allowImport()) {
                val importId = handler.await {
                    sync_importQueries.getNextPendingImport(spaceId, generation).executeAsOneOrNull()
                } ?: break
                diagnosticStage = SyncJournalStage.IMPORTING
                diagnosticContext?.journal?.record(
                    SyncJournalEventKind.PHASE,
                    diagnosticStage,
                    diagnosticContext.runId,
                    attemptId = diagnosticContext.attemptId,
                    trigger = diagnosticContext.trigger,
                )
                progress?.phase(SyncRunPhase.IMPORTING, processed = 0, total = 0)
                progress?.log(importId, "首次合并", "正在写入已保存的数据", SyncRunLogStatus.ACTIVE)
                liveProgress?.begin(
                    liveProgress.scope("import:$importId"),
                    SyncProgressStage.PREPARING,
                    SyncProgressDirection.UPLOAD,
                )
                val importProgress = baseline.process(importId)
                liveProgress?.importPage(importId, importProgress)
                progress?.log(importId, "首次合并", "已写入已保存的数据", SyncRunLogStatus.COMPLETED)
                yield()
            }
            operation(SyncOperationStage.BASELINE)
            val store = inboxStore
            val discovery = SyncDiscoveryStore(handler)
            val service = SyncBatchSyncService(
                transport,
                secret,
                spaceMaterial = spaceMaterial,
                onEncryptedBatchReceived = { entry ->
                    liveProgress?.batch(
                        SyncProgressStage.TRANSFERRING,
                        entry.batchId,
                        entry.lastSeq - entry.firstSeq + 1,
                    )
                    if (downloadScope != null) {
                        liveProgress?.begin(
                            requireNotNull(downloadScope),
                            SyncProgressStage.CONFIRMING,
                            SyncProgressDirection.DOWNLOAD,
                            downloadTotal,
                        )
                    }
                },
            )
            val inbox = SyncInboxExchange(store, service)
            val outboxStore = SyncOutboxStore(handler)
            val outbox = SyncOutboxExchange(outboxStore, service)
            val githubTransport = transport as? GitHubSyncTransport
            githubTransport?.onBatchObjectsUploaded { batchId ->
                if (batchId == activeUploadBatchId) {
                    liveProgress?.batchWork(SyncBatchWork.CONFIRMING)
                    liveProgress?.batch(SyncProgressStage.TRANSFERRING, batchId, uploadCount)
                    uploadTransferFinished = true
                    liveProgress?.begin(
                        requireNotNull(uploadScope),
                        SyncProgressStage.CONFIRMING,
                        SyncProgressDirection.UPLOAD,
                        uploadRoundTotal,
                    )
                }
            }
            githubTransport?.onBatchTransferResumed { batchId ->
                if (batchId == activeUploadBatchId && uploadScope != null) {
                    liveProgress?.begin(
                        requireNotNull(uploadScope),
                        SyncProgressStage.TRANSFERRING,
                        SyncProgressDirection.UPLOAD,
                        uploadRoundTotal,
                    )
                }
            }
            githubTransport?.installSnapshotFenceProvider { candidateSpace, candidateGeneration ->
                store.remoteGuard.captureFence(candidateSpace, candidateGeneration, snapshotOwner)
            }
            suspend fun observeSnapshot(snapshot: SyncSnapshot, excludedBatchId: String? = null) {
                val fence = githubTransport?.takeSnapshotFence(snapshot)
                val admission = githubTransport?.takeWarmAdmission(snapshot)
                if (admission != null && store.confirmWarmSnapshot(snapshot, admission, fence)) return
                val manifest = githubTransport?.takeSnapshotManifest(snapshot)
                store.observeSnapshot(snapshot, discovery, manifest, setOfNotNull(excludedBatchId), fence)
            }
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
            if (reconcileTotalsOnStart) {
                metrics.recordTotalsReconciliation()
                reconcileTotals()
            } else {
                progress?.totals(uploadedTotal, downloadedTotal)
            }
            projector.retryUnavailable(spaceId, generation)
            liveProgress?.begin(
                requireNotNull(downloadScope),
                SyncProgressStage.TRANSFERRING,
                SyncProgressDirection.DOWNLOAD,
            )
            operation(SyncOperationStage.DISCOVERY, 0.0)
            var snapshot = transport.readSnapshot(repository, spaceId, generation).getOrThrow()
            if (persistentPlan) {
                observeSnapshot(snapshot)
                operation(SyncOperationStage.DISCOVERY)
                operation(SyncOperationStage.PLAN, 0.0)
                if (runPlan == null) {
                    val downloads = discovery.allPending(spaceId, generation)
                    val uploads = outboxStore.freezeRound(spaceId, generation)
                    runPlan = progress!!.freezePlan(
                        uploads.batchItems.map { (id, count) ->
                            SyncRunPlanBatch(SyncProgressDirection.UPLOAD, id, count)
                        } + downloads.map { entry ->
                            SyncRunPlanBatch(
                                SyncProgressDirection.DOWNLOAD,
                                entry.batchId,
                                entry.lastSeq - entry.firstSeq + 1,
                            )
                        },
                    )
                }
                progress!!.reconcilePublishedPlan()
                runPlan = progress.plan()
                val uploadMembers = requireNotNull(runPlan).batches.filter {
                    it.direction == SyncProgressDirection.UPLOAD && !it.confirmed
                }
                frozenUploadBatchIds = outboxStore.orderedBatchIds(
                    spaceId,
                    generation,
                    uploadMembers.map { it.batchId }.toSet(),
                )
                uploadRoundTotal = uploadMembers.sumOf { it.itemCount }
                uploadRemainingExpected = uploadRoundTotal
                uploadScope = liveProgress?.scope("upload-round-${uploadRound++}")
                val uploadOrder = frozenUploadBatchIds.withIndex().associate { it.value to it.index }
                liveProgress?.plan(
                    SyncRunPlan(
                        requireNotNull(runPlan).batches.filter { it.direction == SyncProgressDirection.DOWNLOAD } +
                            requireNotNull(runPlan).batches.filter { it.direction == SyncProgressDirection.UPLOAD }
                                .sortedBy { uploadOrder[it.batchId] ?: -1 },
                    ),
                )
            }
            operation(SyncOperationStage.PLAN)
            operation(SyncOperationStage.ITEMS, 0.0)
            suspend fun pendingDownloads() = if (persistentPlan) {
                val memberIds = requireNotNull(runPlan).batches.filter {
                    it.direction == SyncProgressDirection.DOWNLOAD && !it.confirmed
                }.map { it.batchId }.toSet()
                discovery.allPending(spaceId, generation).filter { it.batchId in memberIds }.take(128)
            } else {
                discovery.pending(spaceId, generation)
            }
            var catchUpSegments = 0
            var performedExchangeWork = false
            var downloadRound = 0
            var downloadHead: String? = null
            while (true) {
                diagnosticStage = SyncJournalStage.DOWNLOADING
                diagnosticContext?.journal?.record(
                    SyncJournalEventKind.PHASE,
                    diagnosticStage,
                    diagnosticContext.runId,
                    attemptId = diagnosticContext.attemptId,
                    trigger = diagnosticContext.trigger,
                )
                progress?.phase(SyncRunPhase.DOWNLOADING, downloadedTotal, 0, completed = downloadedTotal)
                observeSnapshot(snapshot)
                if (downloadRound == 0) liveProgress?.hold(SyncProgressHold.ACTIVE)
                val discovered = pendingDownloads()
                val newDownloadRound = downloadHead != snapshot.head
                if (newDownloadRound) {
                    downloadHead = snapshot.head
                    downloadScope = liveProgress?.scope("download-${downloadRound++}")
                    // A full discovery page may hide more work; never freeze its partial total.
                    downloadTotal = discovered.takeIf { it.size < 128 }
                        ?.sumOf { (it.lastSeq - it.firstSeq + 1).coerceAtLeast(0) }
                }
                if (discovered.isNotEmpty()) {
                    liveProgress?.begin(
                        requireNotNull(downloadScope),
                        SyncProgressStage.TRANSFERRING,
                        SyncProgressDirection.DOWNLOAD,
                        downloadTotal,
                        additionalWork = newDownloadRound && downloadRound > 1,
                    )
                }
                val acceptedBatches = mutableListOf<Pair<String, Long>>()
                if (discovered.isNotEmpty()) performedExchangeWork = true
                for (entry in discovered) {
                    liveProgress?.begin(
                        requireNotNull(downloadScope),
                        SyncProgressStage.TRANSFERRING,
                        SyncProgressDirection.DOWNLOAD,
                        downloadTotal,
                    )
                    progress?.expectDownload(entry.batchId, entry.lastSeq - entry.firstSeq + 1)
                    liveProgress?.startBatch(SyncProgressDirection.DOWNLOAD, entry.batchId, SyncBatchWork.TRANSFERRING)
                    val result = try {
                        inbox.receive(
                            snapshot,
                            entry,
                            snapshotAlreadyObserved = true,
                            onItemsWritten = { count ->
                                if (count == 1L) liveProgress?.batchWork(SyncBatchWork.WRITING)
                                liveProgress?.batchItems(count)
                            },
                        ).also {
                            if (it.accepted) liveProgress?.batchItems(entry.lastSeq - entry.firstSeq + 1)
                            liveProgress?.finishBatchWork(it.accepted)
                        }
                    } catch (failure: Exception) {
                        liveProgress?.finishBatchWork(false)
                        throw failure
                    }
                    if (result.accepted) {
                        if (!result.duplicate) {
                            liveProgress?.receivedBatch(
                                entry.batchId,
                                (entry.lastSeq - entry.firstSeq + 1).coerceAtLeast(0),
                            )
                        }
                        if (!result.duplicate) {
                            acceptedBatches += entry.batchId to (entry.lastSeq - entry.firstSeq + 1)
                            receivedThisExchange += entry.batchId
                        }
                        if (!result.duplicate) {
                            downloaded += (entry.lastSeq - entry.firstSeq + 1).toInt()
                            downloadedTotal += (entry.lastSeq - entry.firstSeq + 1).coerceAtLeast(0)
                            progress?.totals(uploadedTotal, downloadedTotal)
                        }
                        diagnosticStage = SyncJournalStage.MERGING
                        diagnosticContext?.journal?.record(
                            SyncJournalEventKind.PHASE,
                            diagnosticStage,
                            diagnosticContext.runId,
                            attemptId = diagnosticContext.attemptId,
                            trigger = diagnosticContext.trigger,
                        )
                        progress?.phase(
                            SyncRunPhase.MERGING,
                            downloadedTotal,
                            snapshot.batches.sumOf { (it.lastSeq - it.firstSeq + 1).toInt() }.toLong(),
                            completed = downloadedTotal,
                        )
                        progress.logBatch(
                            result.batch,
                            entry.batchId,
                            if (result.duplicate) "已确认重复数据" else "已接收",
                        )
                    } else {
                        problem = SyncRunProblem.INVALID_DATA
                        progress?.log(entry.batchId, "同步数据", "数据无法读取", SyncRunLogStatus.FAILED)
                    }
                    yield()
                }
                liveProgress?.begin(
                    requireNotNull(downloadScope),
                    SyncProgressStage.CONFIRMING,
                    SyncProgressDirection.DOWNLOAD,
                    downloadTotal,
                )
                while (
                    projector.project(
                        spaceId,
                        generation,
                        onStarted = {
                            liveProgress?.batchWork(SyncBatchWork.MERGING)
                            liveProgress?.projectionStarted()
                        },
                        onChecked = { count, sourceUnavailable ->
                            liveProgress?.checkedFields(count, sourceUnavailable)
                        },
                    ) == 50
                ) {
                    yield()
                }
                val newlyConfirmed = if (progress == null) {
                    acceptedBatches.filter { (batchId, _) ->
                        store.canConfirmReceivedBatch(spaceId, generation, batchId)
                    }
                } else {
                    progress.confirmReceived()
                }
                if (newlyConfirmed.isNotEmpty()) {
                    liveProgress?.begin(
                        requireNotNull(downloadScope),
                        SyncProgressStage.CONFIRMING,
                        SyncProgressDirection.DOWNLOAD,
                        downloadTotal,
                    )
                    newlyConfirmed.forEach { (batchId, count) ->
                        liveProgress?.batch(SyncProgressStage.CONFIRMING, batchId, count)
                    }
                }
                pending = store.status(spaceId, generation).pendingDecisions.toInt()
                // Discovery is paged to keep each durable query bounded. Drain every page before
                // reporting completion or switching to uploads so large snapshots converge fully.
                if (pendingDownloads().isNotEmpty()) {
                    yield()
                    continue
                }
                diagnosticStage = SyncJournalStage.UPLOADING
                diagnosticContext?.journal?.record(
                    SyncJournalEventKind.PHASE,
                    diagnosticStage,
                    diagnosticContext.runId,
                    attemptId = diagnosticContext.attemptId,
                    trigger = diagnosticContext.trigger,
                )
                progress?.phase(SyncRunPhase.UPLOADING, uploadedTotal, 0, completed = uploadedTotal)
                if (!persistentPlan && liveProgress != null && (uploadScope == null || uploadRemainingExpected == 0L)) {
                    val frozen = outboxStore.freezeRound(spaceId, generation)
                    frozenUploadBatchIds = frozen.batchIds
                    frozenUploadBatchIndex = 0
                    if (frozen.totalItems > 0) {
                        uploadScope = liveProgress.scope("upload-round-${uploadRound++}")
                        uploadRoundTotal = frozen.totalItems
                        uploadRemainingExpected = frozen.totalItems
                    }
                }
                val frozenBatchId = if (!persistentPlan && liveProgress == null) {
                    null
                } else {
                    frozenUploadBatchIds.getOrNull(frozenUploadBatchIndex)
                }
                val result = if ((persistentPlan || liveProgress != null) && frozenBatchId == null) {
                    null
                } else {
                    outbox.uploadNext(
                        snapshot,
                        ::observeSnapshot,
                        allowPublish = allowPublish,
                        frozenBatchId = frozenBatchId,
                        onItemRead = { batchId, count ->
                            if (count == 1L) {
                                liveProgress?.startBatch(SyncProgressDirection.UPLOAD, batchId, SyncBatchWork.PREPARING)
                            }
                            liveProgress?.batchItems(count)
                        },
                        onPreparingBatch = { batch ->
                            activeUploadBatchId = batch.batchId
                            uploadCount = batch.events.size.toLong()
                            uploadTransferFinished = false
                            if (uploadScope != null) {
                                liveProgress?.begin(
                                    requireNotNull(uploadScope),
                                    SyncProgressStage.PREPARING,
                                    SyncProgressDirection.UPLOAD,
                                    uploadRoundTotal,
                                    additionalWork = uploadRound > 1,
                                )
                            }
                        },
                        onPreparedBatch = { batch ->
                            liveProgress?.finishBatchWork(true)
                            liveProgress?.batchWork(SyncBatchWork.TRANSFERRING)
                            if (uploadScope != null) {
                                liveProgress?.batch(SyncProgressStage.PREPARING, batch.batchId, uploadCount)
                                liveProgress?.begin(
                                    requireNotNull(uploadScope),
                                    SyncProgressStage.TRANSFERRING,
                                    SyncProgressDirection.UPLOAD,
                                    uploadRoundTotal,
                                )
                            }
                        },
                        onConfirmedBatch = { batch ->
                            if (uploadScope != null) {
                                if (!uploadTransferFinished) {
                                    liveProgress?.batch(SyncProgressStage.TRANSFERRING, batch.batchId, uploadCount)
                                    liveProgress?.begin(
                                        requireNotNull(uploadScope),
                                        SyncProgressStage.CONFIRMING,
                                        SyncProgressDirection.UPLOAD,
                                        uploadRoundTotal,
                                    )
                                }
                            }
                            uploadRemainingExpected = (uploadRemainingExpected - uploadCount).coerceAtLeast(0)
                        },
                    )
                }
                if (result == null) {
                    // No-op runs stop after their initial snapshot. When this exchange moved data,
                    // probe only the ref first; parse a full snapshot only if another device advanced it.
                    if (!performedExchangeWork) break
                    operation(SyncOperationStage.HEAD, 0.0)
                    val finalHead = transport.readCurrentHead(repository, spaceId, generation).getOrThrow()
                    if (finalHead == snapshot.head) {
                        finalHeadVerified = true
                        operation(SyncOperationStage.HEAD)
                        break
                    }
                    snapshot = transport.readSnapshot(repository, spaceId, generation).getOrThrow()
                    observeSnapshot(snapshot)
                    if (persistentPlan) break
                    catchUpSegments++
                    if (catchUpSegments > MAX_CATCH_UP_SEGMENTS) {
                        if (discovery.pending(spaceId, generation, limit = 1).isNotEmpty()) {
                            problem = SyncRunProblem.REMOTE_CHANGED
                            diagnosticStage = SyncJournalStage.DOWNLOADING
                            diagnosticContext?.journal?.record(
                                SyncJournalEventKind.PHASE,
                                diagnosticStage,
                                diagnosticContext.runId,
                                attemptId = diagnosticContext.attemptId,
                                trigger = diagnosticContext.trigger,
                            )
                            progress?.phase(
                                SyncRunPhase.DOWNLOADING,
                                downloadedTotal,
                                0,
                                completed = downloadedTotal,
                                state = SyncRunState.BLOCKED,
                                reason = problem.name.lowercase(),
                            )
                        }
                        break
                    }
                    continue
                }
                performedExchangeWork = true
                if (result.publish.status != SyncPublishStatus.PUBLISHED) {
                    problem = when (result.publish.failureClass) {
                        SyncPublishFailureClass.AUTHORIZATION -> SyncRunProblem.AUTHORIZATION
                        SyncPublishFailureClass.INVALID_REQUEST -> SyncRunProblem.INVALID_DATA
                        SyncPublishFailureClass.CONFLICT -> SyncRunProblem.REMOTE_CHANGED
                        SyncPublishFailureClass.RATE_LIMITED,
                        SyncPublishFailureClass.NETWORK,
                        SyncPublishFailureClass.UNKNOWN,
                        null,
                        -> SyncRunProblem.NETWORK
                    }
                    retryAfterMillis = result.publish.retryAfterMillis
                    networkPhase = result.publish.networkPhase
                    httpStatus = result.publish.httpStatus
                    diagnosticStage = SyncJournalStage.UPLOADING
                    diagnosticContext?.journal?.record(
                        SyncJournalEventKind.PHASE,
                        diagnosticStage,
                        diagnosticContext.runId,
                        attemptId = diagnosticContext.attemptId,
                        trigger = diagnosticContext.trigger,
                    )
                    progress?.phase(
                        SyncRunPhase.UPLOADING,
                        uploadedTotal + downloadedTotal,
                        0,
                        completed = uploadedTotal + downloadedTotal,
                        state = if (problem ==
                            SyncRunProblem.NETWORK
                        ) {
                            SyncRunState.WAITING_RETRY
                        } else {
                            SyncRunState.BLOCKED
                        },
                        reason = problem.name.lowercase(),
                    )
                    break
                }
                if (persistentPlan || liveProgress != null) frozenUploadBatchIndex++
                val publishedCount = handler.await {
                    sync_journalQueries.getBatch(spaceId, generation, result.publish.batchId).executeAsOne().event_count
                }.toInt()
                progress?.confirmed(
                    SyncProgressDirection.UPLOAD,
                    result.publish.batchId,
                    publishedCount.toLong(),
                )
                liveProgress?.batch(SyncProgressStage.CONFIRMING, result.publish.batchId, publishedCount.toLong())
                uploaded += publishedCount
                uploadedTotal += publishedCount
                progress?.totals(uploadedTotal, downloadedTotal)
                diagnosticStage = SyncJournalStage.CONFIRMING
                diagnosticContext?.journal?.record(
                    SyncJournalEventKind.PHASE,
                    diagnosticStage,
                    diagnosticContext.runId,
                    attemptId = diagnosticContext.attemptId,
                    trigger = diagnosticContext.trigger,
                )
                progress?.phase(SyncRunPhase.CONFIRMING, uploadedTotal + downloadedTotal, 0)
                progress.logBatch(result.batch, result.publish.batchId, "已确认上传")
                yield()
                // A successful publisher already read and authenticated the live ref. Reuse it for the next pass;
                // transports that cannot return that evidence retain the conservative fresh-read fallback.
                snapshot = result.publish.confirmedSnapshot
                    ?: transport.readSnapshot(repository, spaceId, generation).getOrThrow()
            }
            if (problem == null && !finalHeadVerified) {
                // No-op and bounded-plan exits already authenticated their last snapshot. The
                // observation reuses that evidence; progress must not add network requests.
                operation(SyncOperationStage.HEAD)
            }
            operation(SyncOperationStage.INTEGRITY, 0.0)
            hasUnconfirmedProjection = if (progress == null) {
                receivedThisExchange.any { !store.canConfirmReceivedBatch(spaceId, generation, it) }
            } else {
                progress.hasUnconfirmedDownloads() || progress.hasUnfinishedPlan()
            }
            operation(SyncOperationStage.INTEGRITY)
        } catch (cancelled: CancellationException) {
            liveProgress?.finishBatchWork(false)
            diagnosticContext?.record(SyncJournalEventKind.TERMINAL, SyncJournalCode.CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            liveProgress?.finishBatchWork(false)
            diagnosticContext?.copy(stage = diagnosticStage)?.record(
                SyncJournalEventKind.EXCEPTION,
                failure = failure,
                networkPhase = (failure as? SyncHttpException)?.networkPhase,
                httpStatus = (failure as? SyncHttpException)?.code,
            )
            problem = failure.syncProblem()
            retryAfterMillis = (failure as? SyncHttpException)?.retryAfterMillis
                ?: LOCAL_DATABASE_RETRY_MILLIS.takeIf { failure.isRetryableSyncDatabaseFailure() }
            networkPhase = (failure as? SyncHttpException)?.networkPhase
            httpStatus = (failure as? SyncHttpException)?.code
        }
        liveProgress?.let { runCatching { it.snapshot() } }
        return SyncRunResult(
            status = when {
                problem == null && (pending > 0 || hasUnconfirmedProjection) -> SyncRunStatus.PARTIAL
                problem == null -> SyncRunStatus.SUCCESS
                uploaded > 0 || downloaded > 0 -> SyncRunStatus.PARTIAL
                else -> SyncRunStatus.FAILED
            },
            uploaded = uploaded,
            downloaded = downloaded,
            pending = pending,
            problem = problem,
            retryAfterMillis = retryAfterMillis,
            networkPhase = networkPhase,
            httpStatus = httpStatus,
        )
    }
}

private const val MAX_CATCH_UP_SEGMENTS = 3

private data class SyncBatchLogEntry(val key: String, val title: String, val detail: String)

private suspend fun SyncProgressReporter?.logBatch(batch: SyncBatch?, batchId: String, result: String) {
    val entries = batch?.itemLogs(result).orEmpty()
    if (entries.isEmpty()) {
        this?.log(batchId, "同步数据", result, SyncRunLogStatus.COMPLETED)
        return
    }
    this?.logBatch(
        entries.map { entry ->
            SyncRunLogEntry("$batchId:${entry.key}", entry.title, entry.detail, SyncRunLogStatus.COMPLETED)
        },
    )
}

private fun SyncBatch.itemLogs(result: String): List<SyncBatchLogEntry> {
    val descriptions = objects.associateBy { it.objectKey.stableKey }
    return events.asSequence()
        .flatMap { it.effects.asSequence() }
        .groupBy { it.objectKey.stableKey }
        .map { (stableKey, effects) ->
            val key = effects.first().objectKey
            val title = when (key.type) {
                SyncObjectType.CHAPTER -> descriptions[
                    SyncObjectKey(SyncObjectType.MANGA, sourceId = key.sourceId, originalUrl = key.parentUrl).stableKey,
                ]?.title ?: descriptions[stableKey]?.title
                else -> descriptions[stableKey]?.title
            } ?: when (key.type) {
                SyncObjectType.AUTHOR -> "作者"
                SyncObjectType.MANGA -> "漫画"
                SyncObjectType.CHAPTER -> "阅读记录"
            }
            val operation = effects.map { effect ->
                when (effect.field) {
                    SyncField.FOLLOWING -> "作者关注"
                    SyncField.FAVORITE -> "收藏"
                    SyncField.MANGA_CATEGORIES -> "作品分类"
                    SyncField.CHAPTER_PAIRING -> "双页调整"
                    else -> "阅读记录"
                }
            }.distinct().joinToString(" / ")
            SyncBatchLogEntry(stableKey, title, "$operation · $result")
        }
}

internal const val LOCAL_DATABASE_RETRY_MILLIS = 900_000L

/** Actual driver code, including SQLite extended result codes; no message-based classification. */
internal fun Throwable.syncDatabaseErrorCode(): Int? = generateSequence(this) { it.cause }.take(8)
    .filterIsInstance<java.sql.SQLException>().firstOrNull()?.errorCode

/** Driver codes are used instead of exception messages; permanent SQL/data errors stay blocked. */
internal fun Throwable.isRetryableSyncDatabaseFailure(): Boolean =
    syncDatabaseErrorCode()?.let { (it and 0xff) in setOf(5, 6) } == true ||
        generateSequence(this) { it.cause }.take(8).any {
            it.javaClass.name == "android.database.sqlite.SQLiteDatabaseLockedException" ||
                it.javaClass.name == "android.database.sqlite.SQLiteTableLockedException"
        }

internal fun Exception.syncProblem(): SyncRunProblem = when {
    isRetryableSyncDatabaseFailure() -> SyncRunProblem.STORAGE
    else -> syncNonDatabaseProblem()
}

internal val GitHubAuthException.requiresReauthorization: Boolean
    get() = !failure.retryable && when (failure.reason) {
        GitHubAuthFailureReason.REVOKED,
        GitHubAuthFailureReason.PERMISSION_DENIED,
        GitHubAuthFailureReason.ACCESS_DENIED,
        GitHubAuthFailureReason.EXPIRED,
        -> true
        GitHubAuthFailureReason.HTTP,
        GitHubAuthFailureReason.REFRESH_CLIENT_REJECTED,
        GitHubAuthFailureReason.MALFORMED_RESPONSE,
        GitHubAuthFailureReason.RATE_LIMITED,
        -> false
    }

private fun Exception.syncNonDatabaseProblem(): SyncRunProblem = when (this) {
    is mihon.data.sync.transport.SyncUploadReconciliationUnavailable -> SyncRunProblem.REMOTE_CHANGED
    is mihon.data.sync.transport.SyncRemoteDataInvalid -> SyncRunProblem.INVALID_DATA
    is mihon.data.sync.http.SyncRequiredResourceUnavailable -> SyncRunProblem.SPACE_UNAVAILABLE
    is mihon.domain.sync.crypto.SyncCryptoException -> SyncRunProblem.INVALID_DATA
    is UnsupportedSyncSpace -> SyncRunProblem.INVALID_DATA
    is SyncSetupException -> when (problem) {
        mihon.data.sync.auth.SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE -> SyncRunProblem.REPOSITORY_NOT_PRIVATE
        mihon.data.sync.auth.SyncDiscoveryProblem.ACCOUNT_CHANGED,
        mihon.data.sync.auth.SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
        -> SyncRunProblem.AUTHORIZATION
        else -> SyncRunProblem.REMOTE_CHANGED
    }
    is GitHubAuthException -> if (requiresReauthorization) SyncRunProblem.AUTHORIZATION else SyncRunProblem.NETWORK
    is SyncSecureStoreException -> SyncRunProblem.STORAGE
    is SyncRemoteSnapshotRejected -> SyncRunProblem.REMOTE_CHANGED
    is SyncRemoteSnapshotStaleCandidate -> SyncRunProblem.REMOTE_CHANGED
    is SyncHttpException -> when (failureClass) {
        SyncHttpFailureClass.AUTHORIZATION -> SyncRunProblem.AUTHORIZATION
        SyncHttpFailureClass.CONFLICT -> SyncRunProblem.REMOTE_CHANGED
        SyncHttpFailureClass.INVALID_REQUEST -> SyncRunProblem.INVALID_DATA
        SyncHttpFailureClass.NETWORK,
        SyncHttpFailureClass.RATE_LIMITED,
        SyncHttpFailureClass.SERVER,
        -> SyncRunProblem.NETWORK
        SyncHttpFailureClass.UNKNOWN -> if (!retryable && (code == 401 || code == 403)) {
            SyncRunProblem.AUTHORIZATION
        } else {
            SyncRunProblem.NETWORK
        }
    }
    is IOException -> SyncRunProblem.NETWORK
    else -> SyncRunProblem.UNKNOWN
}
