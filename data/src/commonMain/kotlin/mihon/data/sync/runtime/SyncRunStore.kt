package mihon.data.sync.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mihon.domain.sync.runtime.SyncTrigger
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
    val attempt: Long,
    val nextRetryAt: Long,
    val lastProgressAt: Long,
    val stopReason: String?,
    val ownerSession: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val uploadedBaseline: Long = 0,
    val downloadedBaseline: Long = 0,
)

data class SyncRunLog(
    val runId: String,
    val key: String,
    val title: String,
    val detail: String,
    val status: SyncRunLogStatus,
    val createdAt: Long,
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

    suspend fun totals(uploaded: Long, downloaded: Long)
}

private class StoreProgressReporter(
    private val store: SyncRunStore,
    private val runId: String,
    private val ownerSession: String,
) : SyncProgressReporter {
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

    override suspend fun totals(uploaded: Long, downloaded: Long) =
        store.totals(runId, uploaded, downloaded, ownerSession)
}

class SyncRunStore(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLogEntries: Int = 500,
) {
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
        sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()?.toSnapshot()
    }

    suspend fun active(spaceId: String, generation: Long): SyncRunSnapshot? = handler.await {
        sync_runtimeQueries.getActiveRuntimeRun(spaceId, generation).executeAsOneOrNull()?.toSnapshot()
    }

    suspend fun latest(spaceId: String, generation: Long): SyncRunSnapshot? = handler.await {
        sync_runtimeQueries.getLatestRuntimeRun(spaceId, generation).executeAsOneOrNull()?.toSnapshot()
    }

    suspend fun progress(
        runId: String,
        phase: SyncRunPhase,
        processed: Long,
        total: Long,
        completed: Long = processed,
        skipped: Long = 0,
        failed: Long = 0,
        attempt: Long = 0,
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
            val durableAttempt = previous?.attempt?.coerceAtLeast(attempt) ?: attempt
            if (ownerSession == null) {
                sync_runtimeQueries.updateRuntimeProgress(
                    state.name,
                    phase.name,
                    durableProcessed,
                    durableTotal,
                    durableCompleted,
                    durableSkipped,
                    durableFailed,
                    durableAttempt,
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
                    durableAttempt,
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

    suspend fun claim(runId: String, ownerSession: String, attempt: Long): Boolean = handler.await(
        inTransaction = true,
    ) {
        sync_runtimeQueries.claimRuntimeRun(attempt, ownerSession, clock(), runId, ownerSession)
        sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()?.let {
            it.state == SyncRunState.RUNNING.name && it.owner_session == ownerSession && it.attempt == attempt
        } == true
    }

    suspend fun releaseForRecovery(runId: String): Boolean = handler.await(inTransaction = true) {
        sync_runtimeQueries.releaseRuntimeRunForRecovery("process_restart", clock(), runId)
        sync_runtimeQueries.getRuntimeRun(runId).executeAsOneOrNull()?.let {
            it.state == SyncRunState.WAITING_SYSTEM.name && it.owner_session == null
        } == true
    }

    suspend fun rewindSystemInterruptionAttempt(runId: String) {
        handler.await {
            sync_runtimeQueries.rewindRuntimeAttempt(clock(), runId)
        }
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
    ) = state(runId, state, reason, ownerSession)

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
            if (ownerSession == null) {
                sync_runtimeQueries.insertRuntimeLog(runId, key, title.take(200), detail.take(500), status.name, now)
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
                )
            }
            val keys = sync_runtimeQueries.getRuntimeLogKeys(runId).executeAsList()
            keys.dropLast(maxLogEntries.coerceAtLeast(1)).forEach { old ->
                sync_runtimeQueries.deleteRuntimeLog(runId, old)
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
    }.map { it?.toSnapshot() }

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

private fun tachiyomi.data.Sync_runtime_runs.toSnapshot() = SyncRunSnapshot(
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
    uploadedBaseline = uploaded_baseline,
    downloadedBaseline = downloaded_baseline,
    attempt = attempt,
    nextRetryAt = next_retry_at,
    lastProgressAt = last_progress_at,
    stopReason = stop_reason,
    ownerSession = owner_session,
    createdAt = created_at,
    updatedAt = updated_at,
)
