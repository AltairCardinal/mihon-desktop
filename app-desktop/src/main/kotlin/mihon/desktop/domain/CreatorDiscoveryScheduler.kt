package mihon.desktop.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.StoredTask
import mihon.desktop.tracking.DesktopNetworkConnectivity
import mihon.desktop.tracking.JvmDesktopNetworkConnectivity
import mihon.domain.error.AppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.NotificationEvent
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import mihon.domain.task.TaskStatus
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.service.CreatorDiscoveryResult
import tachiyomi.domain.creator.service.CreatorSourceFailure

/** Whether the current creator discovery occurrence is a global due check or a single author check. */
enum class CreatorDiscoveryRunScope {
    Due,
    Creator,
}

/**
 * Reactive creator discovery task state consumed by the author detail screen and Settings.
 */
data class CreatorDiscoveryTaskState(
    val status: TaskStatus = TaskStatus.Completed,
    val scope: CreatorDiscoveryRunScope? = null,
    val creatorId: Long? = null,
    val progress: Float? = null,
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val newCandidateCount: Int = 0,
    val errorCount: Int = 0,
    val failedUnits: List<String> = emptyList(),
    val failureMessage: String? = null,
    val leaseBusy: Boolean = false,
    val skipped: Boolean = false,
    val lastFinishedAt: Long? = null,
)

/**
 * Desktop task owner for author discovery (AA2-03).
 *
 * The scheduler owns planning (when due work exists), constraints, lifecycle, deduplicated
 * scheduling, cancellation and typed terminal states; it never performs source work itself.
 * Both due checks and manual per-author checks run the shared AA2-02 [CreatorDiscoveryService]
 * through [discoverDue]/[discoverCreator]. Library updates only request a due reevaluation via
 * [runNow] and are never the sole caller: the polling loop keeps driving due work independently.
 */
class CreatorDiscoveryScheduler(
    private val taskScheduler: DesktopTaskScheduler,
    private val discoverDue: suspend () -> CreatorDiscoveryResult,
    private val discoverCreator: suspend (Long) -> CreatorDiscoveryResult,
    private val taskNotifier: DesktopSystemNotifier? = null,
    private val hasDueWork: suspend () -> Boolean = { false },
    private val connectivity: DesktopNetworkConnectivity = JvmDesktopNetworkConnectivity,
    private val clock: () -> Long = { System.currentTimeMillis() },
    scope: CoroutineScope? = null,
) {
    private val scope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val updateLock = Any()
    private val mutableState = MutableStateFlow(CreatorDiscoveryTaskState())
    val state: StateFlow<CreatorDiscoveryTaskState> = mutableState.asStateFlow()

    private var schedulerJob: Job? = null
    private var initialRecoveryJob: Job? = null
    private var updateJob: Job? = null
    private val stoppingJobs = mutableSetOf<Job>()

    val isRunning: Boolean get() = schedulerJob?.isActive == true

    /**
     * Starts the periodic due loop and recovers an interrupted pending/running/failed occurrence
     * (checkpoint/host restart). Returns the initial recovery job.
     */
    fun start(): Job = synchronized(updateLock) {
        if (schedulerJob?.isActive == true) return@synchronized requireNotNull(initialRecoveryJob)
        val existing = taskSnapshot()
        val needsRecovery = existing != null && existing.status in recoverableStatuses
        initialRecoveryJob = if (needsRecovery) {
            scope.launch { resume(requireNotNull(existing)).join() }
        } else {
            Job().apply { complete() }
        }
        schedulerJob = scope.launch {
            if (needsRecovery) initialRecoveryJob?.join()
            while (true) {
                delay(CHECK_INTERVAL_MS)
                if (!connectivity.isConnected()) continue
                val pending = taskSnapshot()?.takeIf { it.status in recoverableStatuses }
                when {
                    pending != null -> resume(pending).join()
                    hasDueWork() -> runNow().join()
                }
            }
        }
        requireNotNull(initialRecoveryJob)
    }

    /** Re-evaluates due author work now (deduplicated). Also used by library update completion. */
    fun runNow(): Job = schedule(CreatorDiscoveryRunScope.Due, null)

    /** Settings changes use the same due-work query as periodic scheduling. */
    suspend fun runIfDue(): Job? = if (hasDueWork()) runNow() else null

    /** Manual per-author check for the author detail screen; merges into a running occurrence. */
    fun runForCreator(creatorId: Long): Job = schedule(CreatorDiscoveryRunScope.Creator, creatorId)

    private fun schedule(runScope: CreatorDiscoveryRunScope, creatorId: Long?): Job = synchronized(updateLock) {
        updateJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val task = nextTask(creatorId)
        taskScheduler.register(task)
        if (!connectivity.isConnected()) {
            mutableState.value = CreatorDiscoveryTaskState(
                status = TaskStatus.Pending,
                scope = runScope,
                creatorId = creatorId,
            )
            return@synchronized completedJob()
        }
        taskScheduler.start(CREATOR_DISCOVERY_TASK.id)
        mutableState.value = CreatorDiscoveryTaskState(
            status = TaskStatus.Running,
            scope = runScope,
            creatorId = creatorId,
        )
        scope.launch(start = CoroutineStart.LAZY) {
            runDiscovery(runScope, creatorId)
        }.also {
            updateJob = it
            it.start()
        }
    }

    fun cancel(): Boolean {
        val cancelled = when (taskSnapshot()?.status) {
            TaskStatus.Pending, TaskStatus.Failed -> taskScheduler.cancel(CREATOR_DISCOVERY_TASK.id)
            TaskStatus.Running -> taskScheduler.cancelRunning(CREATOR_DISCOVERY_TASK.id)
            else -> false
        }
        if (cancelled) {
            updateJob?.cancel()
            taskNotifier?.notify(
                NotificationEvent.Cancelled(CREATOR_DISCOVERY_TASK.id, "Author discovery cancelled"),
            )
        }
        return cancelled
    }

    fun stop() {
        val jobs = synchronized(updateLock) { detachJobs() }
        jobs.forEach(Job::cancel)
    }

    suspend fun stopAndJoin() {
        val jobs = synchronized(updateLock) {
            (detachJobs() + stoppingJobs).distinct()
        }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        synchronized(updateLock) { stoppingJobs.removeAll(jobs.toSet()) }
    }

    private fun detachJobs(): List<Job> =
        listOfNotNull(initialRecoveryJob, updateJob, schedulerJob).distinct().also { jobs ->
            jobs.filter(stoppingJobs::add).forEach { job ->
                job.invokeOnCompletion { synchronized(updateLock) { stoppingJobs.remove(job) } }
            }
            initialRecoveryJob = null
            updateJob = null
            schedulerJob = null
        }

    fun taskSnapshot(): StoredTask? = taskScheduler.snapshot(CREATOR_DISCOVERY_TASK.id)

    private fun resume(stored: StoredTask): Job {
        val creatorId = stored.task.checkpoint?.cursor
            ?.takeIf { it.startsWith(MANUAL_CURSOR_PREFIX) }
            ?.removePrefix(MANUAL_CURSOR_PREFIX)
            ?.toLongOrNull()
        return if (creatorId == null) runNow() else runForCreator(creatorId)
    }

    private fun nextTask(manualCreatorId: Long?): BackgroundTask {
        val existing = taskSnapshot()
        val terminal = existing?.status in terminalStatuses
        val idempotencyKey = when {
            manualCreatorId != null && terminal -> "creator-discovery:manual:$manualCreatorId:${clock()}"
            manualCreatorId != null -> "creator-discovery:manual:$manualCreatorId"
            terminal -> "creator-discovery:${clock()}"
            else -> CREATOR_DISCOVERY_TASK.idempotencyKey
        }
        return CREATOR_DISCOVERY_TASK.copy(
            idempotencyKey = idempotencyKey,
            checkpoint = TaskCheckpoint(
                cursor = manualCreatorId?.let { "$MANUAL_CURSOR_PREFIX$it" } ?: DUE_CURSOR,
            ),
        )
    }

    private suspend fun runDiscovery(scope: CreatorDiscoveryRunScope, creatorId: Long?) {
        try {
            val result = if (creatorId == null) discoverDue() else discoverCreator(creatorId)
            finish(result, scope, creatorId)
        } catch (cancelled: CancellationException) {
            taskScheduler.cancel(CREATOR_DISCOVERY_TASK.id)
            mutableState.value = CreatorDiscoveryTaskState(
                status = TaskStatus.Cancelled,
                scope = scope,
                creatorId = creatorId,
                lastFinishedAt = clock(),
            )
            throw cancelled
        } catch (error: Exception) {
            val appError = AppError.Unknown(error)
            taskScheduler.fail(CREATOR_DISCOVERY_TASK.id, appError)
            mutableState.value = CreatorDiscoveryTaskState(
                status = TaskStatus.Failed,
                scope = scope,
                creatorId = creatorId,
                failureMessage = error.safeMessage(),
                lastFinishedAt = clock(),
            )
        }
    }

    private suspend fun finish(
        result: CreatorDiscoveryResult,
        scope: CreatorDiscoveryRunScope,
        creatorId: Long?,
    ) {
        if (result.runState == DiscoveryRunState.CANCELLED) {
            taskScheduler.cancel(CREATOR_DISCOVERY_TASK.id)
            mutableState.value = CreatorDiscoveryTaskState(
                status = TaskStatus.Cancelled,
                scope = scope,
                creatorId = creatorId,
                lastFinishedAt = clock(),
            )
            return
        }
        val failedSources = result.sourceResults.filter { it.failure != null }
        val failedUnits = failedSources.map { source ->
            AppError.FailedUnit("source:${source.sourceId}", requireNotNull(source.failure).toAppError())
        }
        val completed = failedUnits.isEmpty() && result.runState !in setOf(
            DiscoveryRunState.PARTIAL,
            DiscoveryRunState.FAILED,
        )
        if (completed) {
            taskScheduler.complete(CREATOR_DISCOVERY_TASK.id)
            taskNotifier?.notify(
                NotificationEvent.Success(
                    CREATOR_DISCOVERY_TASK.id,
                    "Author discovery finished",
                    "${result.newCandidateCount} new candidate(s)",
                ),
            )
        } else {
            val failedCount = failedUnits.size.takeIf { it > 0 } ?: result.errorCount
            val message = "$failedCount of ${result.totalSources} sources failed"
            val failure = if (failedUnits.isEmpty()) {
                AppError.Unknown(IllegalStateException(message))
            } else {
                AppError.PartialFailure(
                    failures = failedUnits.map { it.error },
                    failedUnits = failedUnits,
                    cause = IllegalStateException(message),
                )
            }
            taskScheduler.fail(
                CREATOR_DISCOVERY_TASK.id,
                failure,
            )
            taskNotifier?.notify(
                NotificationEvent.Failure(
                    CREATOR_DISCOVERY_TASK.id,
                    "Author discovery partially failed",
                    "$message; retry from Settings or the author page",
                ),
            )
        }
        mutableState.value = CreatorDiscoveryTaskState(
            status = if (completed) TaskStatus.Completed else TaskStatus.Failed,
            scope = scope,
            creatorId = creatorId,
            progress = if (result.totalSources > 0) {
                result.completedSources.toFloat() / result.totalSources
            } else {
                null
            },
            completedSources = result.completedSources,
            totalSources = result.totalSources,
            newCandidateCount = result.newCandidateCount,
            errorCount = maxOf(result.errorCount, failedUnits.size),
            failedUnits = failedUnits.map { it.unitId },
            failureMessage = if (completed) null else "${failedUnits.size.takeIf { it > 0 } ?: result.errorCount} of ${result.totalSources} sources failed",
            leaseBusy = result.leaseBusy,
            skipped = result.skipped,
            lastFinishedAt = clock(),
        )
    }

    private fun CreatorSourceFailure.toAppError(): AppError = when (this) {
        is CreatorSourceFailure.AuthenticationRequired -> AppError.Authentication()
        is CreatorSourceFailure.RateLimited -> AppError.RateLimited(retryAfterSeconds = retryAfterMillis?.div(1_000))
        is CreatorSourceFailure.Http -> AppError.Server(statusCode)
        CreatorSourceFailure.Timeout -> AppError.Network()
        is CreatorSourceFailure.Network -> AppError.Network(safeMessage?.let(::IllegalStateException))
        is CreatorSourceFailure.MalformedResponse -> AppError.MalformedData(safeMessage?.let(::IllegalStateException))
        CreatorSourceFailure.MissingSource -> AppError.Unknown()
        CreatorSourceFailure.UnsupportedCapability -> AppError.Unknown()
    }

    private fun Throwable.safeMessage(): String = message ?: "unexpected discovery failure"

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        val CREATOR_DISCOVERY_TASK = BackgroundTask(
            id = "creator-discovery",
            idempotencyKey = "creator-discovery:scheduled",
            constraints = setOf(TaskConstraint.NetworkConnected),
        )

        private val terminalStatuses = setOf(
            TaskStatus.Completed,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
        )

        private val recoverableStatuses = setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed)
        private const val MANUAL_CURSOR_PREFIX = "creator:"
        private const val DUE_CURSOR = "due"

        private fun completedJob() = Job().apply { complete() }
    }
}
