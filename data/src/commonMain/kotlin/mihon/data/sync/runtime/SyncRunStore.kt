package mihon.data.sync.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import mihon.data.sync.http.SyncHttpBodyDirection
import mihon.data.sync.http.SyncHttpBodyObserver
import mihon.data.sync.journal.SyncImportProgress
import mihon.domain.sync.runtime.SyncTrigger
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import java.util.UUID

enum class SyncRunState {
    QUEUED,
    RUNNING,
    WAITING_NETWORK,
    WAITING_RETRY,
    WAITING_SYSTEM,
    PAUSED_USER,
    BLOCKED,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
}
enum class SyncRunPhase { CHECKING, IMPORTING, DOWNLOADING, MERGING, UPLOADING, CONFIRMING, COMPLETE }
enum class SyncRunLogStatus { ACTIVE, COMPLETED, SKIPPED, FAILED, WAITING }

data class SyncRunSnapshot(
    val runId: String,
    val spaceId: String,
    val generation: Long,
    val trigger: SyncTrigger,
    val state: SyncRunState,
    val phase: SyncRunPhase,
    val processed: Long,
    val total: Long,
    val completed: Long,
    val skipped: Long,
    val failed: Long,
    val uploaded: Long = 0,
    val downloaded: Long = 0,
    /** Monotonic claim identity within this run; ownerSession remains the fencing owner. */
    val attemptId: Long,
    /** Count of actual network failures; process recovery and successful requests do not change it. */
    val networkFailureCount: Long = 0,
    val nextRetryAt: Long,
    val lastProgressAt: Long,
    val stopReason: String?,
    val ownerSession: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val uploadedBaseline: Long = 0,
    val downloadedBaseline: Long = 0,
    val confirmedItems: Long = 0,
    val plannedItems: Long? = null,
    val pausedMillis: Long = 0,
    val pausedAt: Long? = null,
)

data class SyncRunPlanBatch(
    val direction: SyncProgressDirection,
    val batchId: String,
    val itemCount: Long,
    val confirmed: Boolean = false,
)
data class SyncRunPlan(val batches: List<SyncRunPlanBatch>) {
    val totalItems: Long get() = batches.sumOf { it.itemCount }
}

data class SyncRunLog(
    val runId: String,
    val key: String,
    val title: String,
    val detail: String,
    val status: SyncRunLogStatus,
    val createdAt: Long,
)

data class SyncRunLogEntry(
    val key: String,
    val title: String,
    val detail: String,
    val status: SyncRunLogStatus,
)

interface SyncProgressReporter {
    suspend fun phase(
        phase: SyncRunPhase,
        processed: Long,
        total: Long,
        completed: Long = processed,
        skipped: Long = 0,
        failed: Long = 0,
        state: SyncRunState = SyncRunState.RUNNING,
        reason: String? = null,
    )

    suspend fun log(key: String, title: String, detail: String, status: SyncRunLogStatus)

    suspend fun logBatch(entries: List<SyncRunLogEntry>) {
        entries.forEach { log(it.key, it.title, it.detail, it.status) }
    }

    suspend fun totals(uploaded: Long, downloaded: Long)

    suspend fun confirmed(direction: SyncProgressDirection, batchId: String, itemCount: Long) = Unit

    /** Durable intent precedes receipt; the inbox transaction decides whether it can confirm. */
    suspend fun expectDownload(batchId: String, itemCount: Long) = Unit

    suspend fun confirmReceived(): List<Pair<String, Long>> = emptyList()

    suspend fun hasUnconfirmedDownloads(): Boolean = false

    val supportsFrozenPlan: Boolean get() = false

    suspend fun plan(): SyncRunPlan? = null

    suspend fun freezePlan(batches: List<SyncRunPlanBatch>): SyncRunPlan? = null

    suspend fun hasUnfinishedPlan(): Boolean = false

    suspend fun reconcilePublishedPlan() = Unit
}

/** One claimed run owns this volatile display stream; durable run state remains in SyncRunStore. */
class SyncLiveProgressSession(
    private val runId: String,
    private val mutable: MutableStateFlow<SyncProgressFact?>,
    private val nanos: () -> Long = System::nanoTime,
    private val millis: () -> Long = System::currentTimeMillis,
    private val canPublish: () -> Boolean = { true },
    private var confirmedBaseline: Long? = 0,
) : SyncHttpBodyObserver {
    private val timeline = SyncProgressTimeline()
    private var stage = SyncProgressStage.PREPARING
    private var direction = SyncProgressDirection.UPLOAD
    private var scope = "$runId:initial"
    private var totalItems: Long? = null
    private var lastEmissionMillis = Long.MIN_VALUE

    fun scope(part: String): String = "$runId:$part"

    init {
        timeline.begin("$runId:initial", stage, direction, null, atNanos = nanos())
    }

    @Synchronized
    fun activate() = emit(force = true)

    @Synchronized
    fun begin(
        scope: String,
        stage: SyncProgressStage,
        direction: SyncProgressDirection,
        totalItems: Long? = null,
        additionalWork: Boolean = false,
    ) {
        // Display observations must never change the result of the safe exchange.
        try {
            require(scope.startsWith("$runId:"))
            val changedScope = this.scope != scope
            val changed = changedScope || this.stage != stage || this.direction != direction ||
                (totalItems != null && this.totalItems != totalItems)
            timeline.begin(scope, stage, direction, totalItems, atNanos = nanos(), additionalWork = additionalWork)
            this.scope = scope
            this.stage = stage
            this.direction = direction
            if (changedScope) this.totalItems = null
            if (totalItems != null) this.totalItems = totalItems
            emit(force = changed)
        } catch (_: RuntimeException) {
            // The durable reporter and transactions remain authoritative.
        }
    }

    @Synchronized
    fun batch(stage: SyncProgressStage, batchKey: String, itemCount: Long) {
        try {
            when (stage) {
                SyncProgressStage.PREPARING -> timeline.prepareBatch(batchKey, itemCount, nanos())
                SyncProgressStage.TRANSFERRING -> timeline.transferBatch(batchKey, itemCount, nanos())
                SyncProgressStage.CONFIRMING -> timeline.confirmBatch(batchKey, itemCount, nanos())
            }
            emit(force = false)
        } catch (_: RuntimeException) {
            // Progress observation cannot fail the batch.
        }
    }

    @Synchronized
    fun receivedBatch(batchKey: String, itemCount: Long) {
        try {
            timeline.receivedBatch(batchKey, itemCount, nanos())
            emit(force = false)
        } catch (_: RuntimeException) {
            // The durable inbox transaction remains authoritative.
        }
    }

    @Synchronized
    fun checkedFields(count: Int, sourceUnavailable: Int) {
        try {
            timeline.checkedFields(count, sourceUnavailable, nanos())
            emit(force = false)
        } catch (_: RuntimeException) {
            // The committed projection result remains authoritative.
        }
    }

    @Synchronized
    fun projectionStarted() {
        try {
            timeline.projectionStarted(nanos())
            emit(force = true)
        } catch (_: RuntimeException) {
            // Projection work remains independent of display observations.
        }
    }

    @Synchronized
    fun importPage(importId: String, progress: SyncImportProgress) {
        val lastId = progress.lastCommittedEntryId ?: return
        try {
            timeline.importPage(importId, "$importId:$lastId", progress.total, progress.committedCount, nanos())
            emit(force = false)
        } catch (_: RuntimeException) {
            // Import transactions must remain independent of display observations.
        }
    }

    @Synchronized
    fun hold(value: SyncProgressHold) {
        try {
            timeline.setHold(value, nanos())
            emit(force = true)
        } catch (_: RuntimeException) {
            // A bad observation must not prevent a safe pause or retry.
        }
    }

    @Synchronized
    fun snapshot(): SyncProgressFact = fact().also { if (canPublish()) mutable.value = it }

    @Synchronized
    override fun onBodyBytes(direction: SyncHttpBodyDirection, requestId: Long, bytes: Long, total: Long?) {
        onBodyWorkBytes(direction, requestId, bytes, total, null)
    }

    @Synchronized
    override fun onBodyWorkBytes(
        direction: SyncHttpBodyDirection,
        requestId: Long,
        bytes: Long,
        total: Long?,
        workKey: String?,
    ) {
        if (!canPublish()) return
        if (stage != SyncProgressStage.TRANSFERRING) return
        try {
            val attemptKey = "${direction.name}:$requestId"
            if (workKey == null) {
                timeline.networkProgress(attemptKey, bytes, nanos(), total)
            } else {
                timeline.bodyProgress("${direction.name}:$workKey", attemptKey, bytes, nanos(), total)
            }
            emit(force = false)
        } catch (_: RuntimeException) {
            // HTTP body observation cannot fail the request.
        }
    }

    @Synchronized
    override fun onBodyComplete(direction: SyncHttpBodyDirection, requestId: Long, successful: Boolean) {
        if (!canPublish()) return
        try {
            timeline.finishBody("${direction.name}:$requestId", nanos())
            emit(force = false)
        } catch (_: RuntimeException) {
            // HTTP completion remains independent of display observations.
        }
    }

    private fun emit(force: Boolean) {
        if (!canPublish()) return
        val now = millis()
        if (force || lastEmissionMillis == Long.MIN_VALUE || now - lastEmissionMillis >= 250L) {
            lastEmissionMillis = now
            mutable.value = fact()
        }
    }

    private fun fact(): SyncProgressFact {
        val snapshot = timeline.snapshot(nanos())
        return snapshot.copy(confirmedThisRun = confirmedBaseline?.plus(snapshot.confirmedThisRun ?: 0L))
    }

    @Synchronized
    fun completeConfirmed(total: Long) {
        confirmedBaseline = (total - (timeline.snapshot(nanos()).confirmedThisRun ?: 0L)).coerceAtLeast(0L)
        emit(force = true)
    }
}

private class StoreProgressReporter(
    private val store: SyncRunStore,
    private val runId: String,
    private val ownerSession: String,
) : SyncProgressReporter {
    override val supportsFrozenPlan: Boolean get() = true

    override suspend fun plan(): SyncRunPlan? = store.plan(runId)

    override suspend fun freezePlan(batches: List<SyncRunPlanBatch>): SyncRunPlan =
        store.freezePlan(runId, ownerSession, batches)

    override suspend fun hasUnfinishedPlan(): Boolean = store.hasUnfinishedPlan(runId, ownerSession)

    override suspend fun reconcilePublishedPlan() = store.reconcilePublishedPlan(runId, ownerSession)

    override suspend fun phase(
        phase: SyncRunPhase,
        processed: Long,
        total: Long,
        completed: Long,
        skipped: Long,
        failed: Long,
        state: SyncRunState,
        reason: String?,
    ) = store.progress(
        runId,
        phase,
        processed,
        total,
        completed,
        skipped,
        failed,
        state = state,
        reason = reason,
        ownerSession = ownerSession,
    )

    override suspend fun log(key: String, title: String, detail: String, status: SyncRunLogStatus) =
        store.log(runId, key, title, detail, status, ownerSession)

    override suspend fun logBatch(entries: List<SyncRunLogEntry>) =
        store.logBatch(runId, entries, ownerSession)

    override suspend fun totals(uploaded: Long, downloaded: Long) =
        store.totals(runId, uploaded, downloaded, ownerSession)

    override suspend fun confirmed(direction: SyncProgressDirection, batchId: String, itemCount: Long) {
        store.confirmed(runId, ownerSession, direction, batchId, itemCount)
    }

    override suspend fun expectDownload(batchId: String, itemCount: Long) {
        store.expectDownload(runId, ownerSession, batchId, itemCount)
    }

    override suspend fun confirmReceived(): List<Pair<String, Long>> =
        store.confirmReceived(runId, ownerSession)

    override suspend fun hasUnconfirmedDownloads(): Boolean =
        store.hasUnconfirmedDownloads(runId, ownerSession)
}

class SyncRunStore(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLogEntries: Int = 500,
) {
    suspend fun plan(runId: String): SyncRunPlan? = handler.await {
        if (sync_runtimeQueries.getRuntimePlanMarker(runId).executeAsOneOrNull() == null) return@await null
        readPlan(runId)
    }

    private fun Database.readPlan(runId: String): SyncRunPlan = SyncRunPlan(
        sync_runtimeQueries.getRuntimePlanMembers(runId).executeAsList().map {
            SyncRunPlanBatch(
                SyncProgressDirection.valueOf(it.direction),
                it.batch_id,
                it.item_count,
                it.status == "CONFIRMED",
            )
        },
    )

    suspend fun freezePlan(runId: String, ownerSession: String, batches: List<SyncRunPlanBatch>): SyncRunPlan =
        handler.await(inTransaction = true) {
            val run = sync_runtimeQueries.getRuntimeRun(runId).executeAsOne()
            require(run.owner_session == ownerSession) { "sync plan owner changed" }
            require(
                sync_journalQueries.getSpace(run.space_id, run.generation).executeAsOneOrNull()?.let {
                    it.active && it.exchange_enabled
                } == true,
            ) { "sync plan space is no longer active" }
            if (sync_runtimeQueries.getRuntimePlanMarker(runId).executeAsOneOrNull() == null) {
                require(batches.map { it.direction to it.batchId }.distinct().size == batches.size)
                batches.forEach { batch ->
                    require(batch.itemCount > 0 && batch.batchId.isNotBlank())
                    val previous = sync_runtimeQueries.getRuntimeConfirmation(
                        runId,
                        batch.direction.name,
                        batch.batchId,
                    ).executeAsOneOrNull()
                    if (previous == null) {
                        sync_runtimeQueries.insertRuntimeConfirmation(
                            runId,
                            batch.direction.name,
                            batch.batchId,
                            batch.itemCount,
                            "PLANNED",
                        )
                    } else {
                        require(previous.item_count == batch.itemCount)
                    }
                }
                sync_runtimeQueries.insertRuntimeConfirmation(runId, "PLAN", "round", 0, "PLANNED")
                // Publish the fixed denominator through the existing run observation stream.
                sync_runtimeQueries.addRuntimeConfirmedItemsOwned(0, clock(), runId, ownerSession)
            }
            readPlan(runId)
        }

    /** Only the outbox's committed safe acknowledgement can recover a missing run confirmation. */
    suspend fun reconcilePublishedPlan(runId: String, ownerSession: String) = handler.await(inTransaction = true) {
        val run = sync_runtimeQueries.getRuntimeRun(runId).executeAsOne()
        require(run.owner_session == ownerSession) { "sync plan owner changed" }
        if (sync_runtimeQueries.getRuntimePlanMarker(runId).executeAsOneOrNull() == null) return@await
        sync_runtimeQueries.getRuntimePlanMembers(runId).executeAsList()
            .filter { it.direction == "UPLOAD" && it.status == "PLANNED" }
            .forEach { member ->
                val batch = sync_journalQueries.getBatch(run.space_id, run.generation, member.batch_id)
                    .executeAsOneOrNull()
                if (batch?.status == "PUBLISHED") {
                    require(batch.event_count == member.item_count) { "published plan batch count changed" }
                    sync_runtimeQueries.markRuntimeConfirmation(runId, member.direction, member.batch_id)
                    sync_runtimeQueries.addRuntimeConfirmedItemsOwned(member.item_count, clock(), runId, ownerSession)
                }
            }
    }

    suspend fun hasPlannedWork(runId: String): Boolean = handler.await {
        sync_runtimeQueries.hasPlannedRuntimeWork(runId).executeAsOneOrNull() != null
    }

    suspend fun hasUnfinishedPlan(runId: String, ownerSession: String): Boolean = handler.await {
        require(sync_runtimeQueries.getRuntimeRun(runId).executeAsOne().owner_session == ownerSession)
        sync_runtimeQueries.hasUnfinishedRuntimePlan(runId).executeAsOneOrNull() != null
    }

    suspend fun accountHttpNotBefore(accountId: Long): Long {
        require(accountId > 0L) { "GitHub account id must be positive" }
        return handler.await {
            sync_http_gateQueries.getAccountHttpNotBefore(accountId).executeAsOneOrNull() ?: 0L
        }
    }

    /** Atomically extends the shared account cooldown without allowing a shorter response to reduce it. */
    suspend fun extendAccountHttpNotBefore(accountId: Long, notBeforeMillis: Long): Long {
        require(accountId > 0L) { "GitHub account id must be positive" }
        require(notBeforeMillis >= 0L) { "GitHub account cooldown must be non-negative" }
        return handler.await(inTransaction = true) {
            sync_http_gateQueries.extendAccountHttpNotBefore(accountId, notBeforeMillis, clock())
            sync_http_gateQueries.getAccountHttpNotBefore(accountId).executeAsOneOrNull() ?: 0L
        }
    }

    suspend fun start(spaceId: String, generation: Long, trigger: SyncTrigger): SyncRunSnapshot {
        val now = clock()
        val runId = UUID.randomUUID().toString()
        handler.await(inTransaction = true) {
            val uploadedBaseline = sync_journalQueries.countPublishedEvents(spaceId, generation).executeAsOne()
            val downloadedBaseline = sync_journalQueries.countRemoteEvents(spaceId, generation, spaceId, generation)
                .executeAsOne()
            sync_runtimeQueries.insertRuntimeRun(
                runId,
                spaceId,
                generation,
                trigger.name,
                SyncRunState.QUEUED.name,
                SyncRunPhase.CHECKING.name,
                uploadedBaseline,
                downloadedBaseline,
                now,
                now,
                now,
            )
        }
        return requireNotNull(get(runId))
    }

    suspend fun get(runId: String): SyncRunSnapshot? = handler.await {
        sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()?.let {
            it.toSnapshot(
                sync_runtimeQueries.getRuntimePlannedItems(it.run_id).executeAsOneOrNull(),
                sync_runtimeQueries.getRuntimePauseClock(it.run_id).executeAsOneOrNull(),
            )
        }
    }

    suspend fun terminalSummary(runId: String): SyncTerminalSummary? = handler.await {
        val run = sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull() ?: return@await null
        val pending = sync_runtimeQueries.getPendingRuntimeReceipts(runId).executeAsList()
        SyncTerminalSummary(
            runId = runId,
            pendingDownloadBatches = pending.size.toLong(),
            pendingDownloadEvents = pending.sumOf { it.item_count },
            sourceUnavailableFields = if (pending.isEmpty()) {
                0L
            } else {
                sync_runtimeQueries.countSourceFieldsInPendingRuntimeReceipts(
                    spaceId = run.space_id,
                    generation = run.generation,
                    runId = runId,
                ).executeAsOne()
            },
        )
    }

    suspend fun active(spaceId: String, generation: Long): SyncRunSnapshot? = handler.await {
        sync_runtimeQueries.getActiveRuntimeRun(spaceId, generation).executeAsOneOrNull()?.let {
            it.toSnapshot(
                sync_runtimeQueries.getRuntimePlannedItems(it.run_id).executeAsOneOrNull(),
                sync_runtimeQueries.getRuntimePauseClock(it.run_id).executeAsOneOrNull(),
            )
        }
    }

    suspend fun latest(spaceId: String, generation: Long): SyncRunSnapshot? = handler.await {
        sync_runtimeQueries.getLatestRuntimeRun(spaceId, generation).executeAsOneOrNull()?.let {
            it.toSnapshot(
                sync_runtimeQueries.getRuntimePlannedItems(it.run_id).executeAsOneOrNull(),
                sync_runtimeQueries.getRuntimePauseClock(it.run_id).executeAsOneOrNull(),
            )
        }
    }

    suspend fun progress(
        runId: String,
        phase: SyncRunPhase,
        processed: Long,
        total: Long,
        completed: Long = processed,
        skipped: Long = 0,
        failed: Long = 0,
        attemptId: Long = 0,
        nextRetryAt: Long = 0,
        state: SyncRunState = SyncRunState.RUNNING,
        reason: String? = null,
        ownerSession: String? = null,
    ) {
        val now = clock()
        handler.await {
            val previous = if (ownerSession == null) {
                null
            } else {
                sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()
                    ?.takeIf { it.owner_session == ownerSession }
            }
            val samePhase = previous?.phase == phase.name
            val durableProcessed = if (samePhase) previous!!.processed.coerceAtLeast(processed) else processed
            val durableTotal = if (samePhase) previous!!.total.coerceAtLeast(total) else total
            val durableCompleted = if (samePhase) previous!!.completed.coerceAtLeast(completed) else completed
            val durableSkipped = if (samePhase) previous!!.skipped.coerceAtLeast(skipped) else skipped
            val durableFailed = if (samePhase) previous!!.failed.coerceAtLeast(failed) else failed
            val durableAttemptId = previous?.attempt_id?.coerceAtLeast(attemptId) ?: attemptId
            if (ownerSession == null) {
                sync_runtimeQueries.updateRuntimeProgress(
                    state.name,
                    phase.name,
                    durableProcessed,
                    durableTotal,
                    durableCompleted,
                    durableSkipped,
                    durableFailed,
                    durableAttemptId,
                    nextRetryAt,
                    now,
                    reason,
                    null,
                    now,
                    runId,
                )
            } else {
                sync_runtimeQueries.updateRuntimeProgressOwned(
                    state.name,
                    phase.name,
                    durableProcessed,
                    durableTotal,
                    durableCompleted,
                    durableSkipped,
                    durableFailed,
                    durableAttemptId,
                    nextRetryAt,
                    now,
                    reason,
                    ownerSession,
                    now,
                    runId,
                    ownerSession,
                )
            }
        }
    }

    suspend fun totals(runId: String, uploaded: Long, downloaded: Long, ownerSession: String? = null) {
        handler.await {
            if (ownerSession == null) {
                sync_runtimeQueries.updateRuntimeTotals(uploaded, downloaded, clock(), runId)
            } else {
                sync_runtimeQueries.updateRuntimeTotalsOwned(uploaded, downloaded, clock(), runId, ownerSession)
            }
        }
    }

    suspend fun confirmed(
        runId: String,
        ownerSession: String,
        direction: SyncProgressDirection,
        batchId: String,
        itemCount: Long,
    ): Long = handler.await(inTransaction = true) {
        require(itemCount >= 0 && batchId.isNotBlank())
        val run = sync_runtimeQueries.getRuntimeRun(runId).executeAsOne()
        require(run.owner_session == ownerSession) { "sync confirmation owner changed" }
        val previous = sync_runtimeQueries.getRuntimeConfirmation(runId, direction.name, batchId).executeAsOneOrNull()
        val hasPlan = sync_runtimeQueries.getRuntimePlanMarker(runId).executeAsOneOrNull() != null
        require(!hasPlan || previous != null) { "batch is outside the frozen sync plan" }
        if (previous == null) {
            sync_runtimeQueries.insertRuntimeConfirmation(runId, direction.name, batchId, itemCount, "CONFIRMED")
            sync_runtimeQueries.addRuntimeConfirmedItemsOwned(itemCount, clock(), runId, ownerSession)
        } else {
            require(previous.item_count == itemCount) { "confirmed batch count changed" }
            if (previous.status == "PENDING" ||
                (previous.status == "PLANNED" && direction == SyncProgressDirection.UPLOAD)
            ) {
                sync_runtimeQueries.markRuntimeConfirmation(runId, direction.name, batchId)
                sync_runtimeQueries.addRuntimeConfirmedItemsOwned(itemCount, clock(), runId, ownerSession)
            }
        }
        sync_runtimeQueries.getRuntimeRun(runId).executeAsOne().confirmed_items
    }

    suspend fun expectDownload(runId: String, ownerSession: String, batchId: String, itemCount: Long) {
        handler.await(inTransaction = true) {
            require(itemCount > 0 && batchId.isNotBlank())
            require(sync_runtimeQueries.getRuntimeRun(runId).executeAsOne().owner_session == ownerSession)
            val previous = sync_runtimeQueries.getRuntimeConfirmation(
                runId,
                SyncProgressDirection.DOWNLOAD.name,
                batchId,
            ).executeAsOneOrNull()
            require(sync_runtimeQueries.getRuntimePlanMarker(runId).executeAsOneOrNull() == null || previous != null) {
                "download is outside the frozen sync plan"
            }
            if (previous == null) {
                sync_runtimeQueries.insertRuntimeConfirmation(
                    runId,
                    SyncProgressDirection.DOWNLOAD.name,
                    batchId,
                    itemCount,
                    "PENDING",
                )
            } else {
                require(previous.item_count == itemCount)
                sync_runtimeQueries.expectPlannedRuntimeDownload(runId, batchId)
            }
        }
    }

    suspend fun confirmReceived(runId: String, ownerSession: String): List<Pair<String, Long>> =
        handler.await(inTransaction = true) {
            val run = sync_runtimeQueries.getRuntimeRun(runId).executeAsOne()
            require(run.owner_session == ownerSession)
            val pending = sync_runtimeQueries.getPendingRuntimeReceipts(runId).executeAsList()
            val received = pending.filter { receipt ->
                sync_inboxQueries.canConfirmReceivedBatch(run.space_id, run.generation, receipt.batch_id)
                    .executeAsOneOrNull() != null
            }
            received.forEach { receipt ->
                sync_runtimeQueries.markRuntimeConfirmation(runId, receipt.direction, receipt.batch_id)
                sync_runtimeQueries.addRuntimeConfirmedItemsOwned(receipt.item_count, clock(), runId, ownerSession)
            }
            received.map { it.batch_id to it.item_count }
        }

    suspend fun hasUnconfirmedDownloads(runId: String, ownerSession: String): Boolean = handler.await {
        require(sync_runtimeQueries.getRuntimeRun(runId).executeAsOne().owner_session == ownerSession)
        sync_runtimeQueries.hasPendingRuntimeReceipt(runId).executeAsOneOrNull() != null
    }

    suspend fun claim(runId: String, ownerSession: String, attemptId: Long): Boolean = handler.await(
        inTransaction = true,
    ) {
        sync_runtimeQueries.claimRuntimeRun(attemptId, ownerSession, clock(), runId, ownerSession)
        sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()?.let {
            it.state == SyncRunState.RUNNING.name && it.owner_session == ownerSession && it.attempt_id == attemptId
        } == true
    }

    suspend fun recordNetworkFailure(runId: String, ownerSession: String): Long =
        handler.await(inTransaction = true) {
            sync_runtimeQueries.incrementRuntimeNetworkFailureOwned(clock(), runId, ownerSession)
            sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()
                ?.takeIf { it.owner_session == ownerSession }
                ?.network_failure_count
                ?: error("sync run owner changed before network failure was persisted")
        }

    suspend fun releaseForRecovery(runId: String): Boolean = handler.await(inTransaction = true) {
        sync_runtimeQueries.releaseRuntimeRunForRecovery("process_restart", clock(), runId)
        sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()?.let {
            it.state == SyncRunState.WAITING_SYSTEM.name && it.owner_session == null
        } == true
    }

    suspend fun deferUntilAccountHttpGate(runId: String, notBeforeMillis: Long): Boolean =
        handler.await(inTransaction = true) {
            sync_runtimeQueries.deferRuntimeRunUntilAccountGate(notBeforeMillis, clock(), runId)
            sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()?.let {
                it.state == SyncRunState.WAITING_RETRY.name &&
                    it.stop_reason == "rate_limit" &&
                    it.next_retry_at >= notBeforeMillis &&
                    it.owner_session == null
            } == true
        }

    suspend fun pause(runId: String) = state(runId, SyncRunState.PAUSED_USER, "user")

    suspend fun cancel(runId: String) = state(runId, SyncRunState.CANCELLED, "user")

    suspend fun resumeIfAllowed(runId: String): Boolean {
        val before = get(runId)
        if (before?.state != SyncRunState.PAUSED_USER) return false
        handler.await { sync_runtimeQueries.resumeRuntimeRun(clock(), runId) }
        return get(runId)?.state == SyncRunState.RUNNING
    }

    suspend fun finish(
        runId: String,
        state: SyncRunState,
        reason: String? = null,
        ownerSession: String? = null,
    ) {
        val unfinished = state == SyncRunState.SUCCEEDED && handler.await {
            sync_runtimeQueries.hasUnfinishedRuntimePlan(runId).executeAsOneOrNull() != null
        }
        state(
            runId,
            if (unfinished) SyncRunState.PARTIAL else state,
            if (unfinished) "planned_work_pending" else reason,
            ownerSession,
        )
    }

    fun reporter(runId: String, ownerSession: String): SyncProgressReporter =
        StoreProgressReporter(this, runId, ownerSession)

    suspend fun log(
        runId: String,
        key: String,
        title: String,
        detail: String,
        status: SyncRunLogStatus,
        ownerSession: String? = null,
    ) {
        val now = clock()
        handler.await(inTransaction = true) {
            val inserted = if (ownerSession == null) {
                sync_runtimeQueries.insertRuntimeLog(runId, key, title.take(200), detail.take(500), status.name, now)
                    .value
            } else {
                sync_runtimeQueries.insertRuntimeLogOwned(
                    runId,
                    key,
                    title.take(200),
                    detail.take(500),
                    status.name,
                    now,
                    runId,
                    ownerSession,
                ).value
            }
            if (inserted > 0) {
                sync_runtimeQueries.trimRuntimeLogs(runId, runId, maxLogEntries.coerceAtLeast(1).toLong())
            }
        }
    }

    suspend fun logBatch(
        runId: String,
        entries: List<SyncRunLogEntry>,
        ownerSession: String? = null,
    ) {
        if (entries.isEmpty()) return
        val now = clock()
        handler.await(inTransaction = true) {
            var inserted = 0L
            entries.forEach { entry ->
                inserted += if (ownerSession == null) {
                    sync_runtimeQueries.insertRuntimeLog(
                        runId,
                        entry.key,
                        entry.title.take(200),
                        entry.detail.take(500),
                        entry.status.name,
                        now,
                    ).value
                } else {
                    sync_runtimeQueries.insertRuntimeLogOwned(
                        runId,
                        entry.key,
                        entry.title.take(200),
                        entry.detail.take(500),
                        entry.status.name,
                        now,
                        runId,
                        ownerSession,
                    ).value
                }
            }
            if (inserted > 0) {
                sync_runtimeQueries.trimRuntimeLogs(runId, runId, maxLogEntries.coerceAtLeast(1).toLong())
            }
        }
    }

    suspend fun logs(runId: String, limit: Long = 20): List<SyncRunLog> = handler.await {
        sync_runtimeQueries.getRuntimeLogs(runId, limit.coerceIn(1, maxLogEntries.toLong())).executeAsList().map {
            SyncRunLog(it.run_id, it.log_key, it.title, it.detail, SyncRunLogStatus.valueOf(it.status), it.created_at)
        }
    }

    fun observe(runId: String): Flow<SyncRunSnapshot?> = handler.subscribeToOneOrNull {
        sync_runtimeQueries.getRuntimeRun(runId)
    }.map { it?.let { run -> get(run.run_id) } }

    private suspend fun state(
        runId: String,
        state: SyncRunState,
        reason: String?,
        ownerSession: String? = null,
    ) {
        handler.await {
            if (ownerSession == null) {
                sync_runtimeQueries.updateRuntimeState(state.name, reason, null, clock(), runId)
            } else {
                sync_runtimeQueries.updateRuntimeStateOwned(
                    state.name,
                    reason,
                    null,
                    clock(),
                    runId,
                    ownerSession,
                )
            }
        }
    }
}

private fun tachiyomi.data.Sync_runtime_runs.toSnapshot(
    planned: Long? = null,
    pauseClock: tachiyomi.data.Sync_runtime_pause_clock? = null,
) = SyncRunSnapshot(
    runId = run_id,
    spaceId = space_id,
    generation = generation,
    trigger = SyncTrigger.valueOf(trigger),
    state = SyncRunState.valueOf(state),
    phase = SyncRunPhase.valueOf(phase),
    processed = processed,
    total = total,
    completed = completed,
    skipped = skipped,
    failed = failed,
    uploaded = uploaded,
    downloaded = downloaded,
    confirmedItems = confirmed_items,
    plannedItems = planned,
    pausedMillis = pauseClock?.paused_millis ?: 0,
    pausedAt = pauseClock?.paused_at,
    uploadedBaseline = uploaded_baseline,
    downloadedBaseline = downloaded_baseline,
    attemptId = attempt_id,
    networkFailureCount = network_failure_count,
    nextRetryAt = next_retry_at,
    lastProgressAt = last_progress_at,
    stopReason = stop_reason,
    ownerSession = owner_session,
    createdAt = created_at,
    updatedAt = updated_at,
)
