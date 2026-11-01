package mihon.domain.reader.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult

data class ReaderPageMaterializeWork<T>(
    val request: ReaderScheduledRequest,
    val fetchRequest: ReaderPageFetchRequest,
    val fetchPort: ReaderPageFetchPort,
    val binding: T,
) {
    init {
        require(fetchRequest.pageId == request.pageId) { "Fetch and scheduled page IDs must match" }
        require(fetchRequest.generation == request.generation) { "Fetch and scheduled generations must match" }
    }
}

sealed interface ReaderPageMaterializeCompletion {
    data class Completed(val result: ReaderPageMaterializeResult) : ReaderPageMaterializeCompletion
    data class Cancelled(val cause: CancellationException) : ReaderPageMaterializeCompletion
    data class Failed(val cause: Throwable) : ReaderPageMaterializeCompletion
}

interface ReaderPageMaterializeRunnerPort<T> {
    fun pollNext(): ReaderPageMaterializeWork<T>?

    fun accepts(work: ReaderPageMaterializeWork<T>): Boolean

    suspend fun prepare(work: ReaderPageMaterializeWork<T>): Boolean = true

    fun publish(
        work: ReaderPageMaterializeWork<T>,
        event: ReaderPageMaterializeEvent,
    ): Boolean

    fun complete(
        work: ReaderPageMaterializeWork<T>,
        completion: ReaderPageMaterializeCompletion,
    )
}

data class ReaderPageMaterializeRunnerSnapshot(
    val activeRequestKeys: Set<ReaderRequestKey>,
    val isClosed: Boolean,
)

/**
 * Shared coroutine owner for scheduled page materialization.
 *
 * Platforms bind opaque page/context state through [ReaderPageMaterializeRunnerPort]. Priority,
 * cancellation, stale physical-I/O bounds, and late publication rejection remain centralized here
 * and in [ReaderRequestScheduler].
 */
class ReaderPageMaterializeRunner<T>(
    private val scope: CoroutineScope,
    maxConcurrentRequests: Int,
    maxStalePhysicalRequests: Int = 1,
    private val materializeExecutor: ReaderMaterializeExecutor = CanonicalReaderMaterializeExecutor,
    private val port: ReaderPageMaterializeRunnerPort<T>,
) : AutoCloseable {
    companion object {
        private val mutableProcessPhysicalWorkCount = MutableStateFlow(0)
        private val mutableProcessBlockingWorkCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())

        /** Original reader work can survive closed sessions; this is observation, not another scheduler. */
        val processPhysicalWorkCount = mutableProcessPhysicalWorkCount.asStateFlow()

        /**
         * The physical work that keeps the reader busy, counted per publisher.
         *
         * A pairing capability must not compete with the ordinary reading work, and it
         * must not wait for itself either. The request kind cannot tell the two apart:
         * `CURRENT_BACKGROUND` is the ordinary kind for the rest of the current chapter,
         * and the pairing uses it too. The request's fact permit can: it is the pairing's
         * own capability token, and it belongs to the request, so an ordinary request for
         * the same page carries none.
         *
         * The token of the permit identifies the publisher that admitted the capability.
         * The work is therefore counted under its own publisher, and a publisher only
         * counts the work of the other publishers: the pairing never waits for the work
         * it started itself, and it waits for the work of every other publisher,
         * including the pairing work of a replaced publisher.
         *
         * Ordinary reader work carries no permit and is counted under [ORDINARY_WORK_KEY],
         * so it keeps the reader busy for every publisher.
         *
         * The count is process wide, so the work of a closed session stays visible, and
         * it is released only when the work actually completes, so it survives exactly
         * as long as the real work does.
         */
        val processBlockingWorkCounts = mutableProcessBlockingWorkCounts.asStateFlow()

        /** The key of the ordinary reader work, which belongs to no publisher. */
        const val ORDINARY_WORK_KEY = Long.MIN_VALUE

        /**
         * The blocking work that belongs to a publisher other than [ownerKey].
         *
         * The pairing asks this before it admits a capability and before it samples a
         * region: it must not wait for its own work, and it must wait for the work of
         * every other publisher until that work really exits.
         */
        fun blockingWorkExcept(ownerKey: Long): Int {
            var total = 0
            for ((key, value) in mutableProcessBlockingWorkCounts.value) {
                if (key != ownerKey) total += value
            }
            return total
        }

        private fun MutableStateFlow<Map<Long, Int>>.addBlocking(key: Long, delta: Int) {
            update { counts ->
                val next = (counts[key] ?: 0) + delta
                if (next <= 0) counts - key else counts + (key to next)
            }
        }
    }

    private class ActiveWork<T>(
        val work: ReaderPageMaterializeWork<T>,
    ) {
        lateinit var job: Job
        var finalized = false
    }

    private val lock = Any()
    private val physicalRequestPermits: Semaphore
    private val activeWork = mutableMapOf<ReaderRequestKey, ActiveWork<T>>()
    private var pumpRequested = false
    private var pumping = false
    private var closed = false
    private val mutablePhysicalWorkCount = MutableStateFlow(0)

    /** Queued/running original jobs, including logically cancelled work until actual completion. */
    val physicalWorkCount = mutablePhysicalWorkCount.asStateFlow()

    init {
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be positive" }
        require(maxStalePhysicalRequests >= 0) { "maxStalePhysicalRequests must be non-negative" }
        physicalRequestPermits = Semaphore(maxConcurrentRequests + maxStalePhysicalRequests)
    }

    fun pump() {
        val startPump = synchronized(lock) {
            if (closed) return@synchronized false
            pumpRequested = true
            if (pumping) {
                false
            } else {
                pumping = true
                true
            }
        }
        if (!startPump) return

        while (true) {
            synchronized(lock) { pumpRequested = false }
            while (true) {
                val work = port.pollNext() ?: break
                val registration = ActiveWork(work)
                registration.job = scope.launch(start = CoroutineStart.LAZY) {
                    runWork(registration)
                }
                val shouldStart = synchronized(lock) {
                    if (closed) {
                        false
                    } else {
                        activeWork[work.request.jobKey] = registration
                        true
                    }
                }
                if (shouldStart) {
                    mutableProcessPhysicalWorkCount.update { it + 1 }
                    mutablePhysicalWorkCount.update { it + 1 }
                    mutableProcessBlockingWorkCounts.addBlocking(blockingWorkKey(work.request), 1)
                }
                registration.job.invokeOnCompletion { cause ->
                    try {
                        if (cause != null) {
                            finalize(
                                registration,
                                if (cause is CancellationException) {
                                    ReaderPageMaterializeCompletion.Cancelled(cause)
                                } else {
                                    ReaderPageMaterializeCompletion.Failed(cause)
                                },
                            )
                        }
                    } finally {
                        if (shouldStart) {
                            mutablePhysicalWorkCount.update { it - 1 }
                            mutableProcessPhysicalWorkCount.update { it - 1 }
                            mutableProcessBlockingWorkCounts.addBlocking(blockingWorkKey(work.request), -1)
                        }
                    }
                }
                if (shouldStart) registration.job.start() else registration.job.cancel()
            }
            val repeat = synchronized(lock) {
                if (pumpRequested) {
                    true
                } else {
                    pumping = false
                    false
                }
            }
            if (!repeat) return
        }
    }

    fun cancel(requestKeys: Set<ReaderRequestKey>) {
        val registrations = synchronized(lock) {
            requestKeys.mapNotNull(activeWork::remove)
        }
        registrations.forEach { it.job.cancel() }
        pump()
    }

    /**
     * The publisher whose busy state one request belongs to.
     *
     * A pairing capability carries the token of the publisher that admitted it, so its
     * work is counted under that publisher and never blocks that publisher itself. The
     * ordinary reader work carries no permit and belongs to no publisher, so it keeps
     * every publisher busy.
     */
    private fun blockingWorkKey(request: ReaderScheduledRequest): Long {
        val ownerKey = request.factPermit?.ownerKey ?: return ORDINARY_WORK_KEY
        return if (ownerKey == 0L) ORDINARY_WORK_KEY else ownerKey
    }

    fun snapshot(): ReaderPageMaterializeRunnerSnapshot = synchronized(lock) {
        ReaderPageMaterializeRunnerSnapshot(activeWork.keys.toSet(), closed)
    }

    /** An observation of the original binding, never a new authorization to publish or execute. */
    fun runningWork(jobKey: ReaderRequestKey): ReaderPageMaterializeWork<T>? = synchronized(lock) {
        if (closed) return@synchronized null
        activeWork[jobKey]?.takeIf { !it.finalized && it.job.isActive }?.work
    }

    override fun close() {
        val registrations = synchronized(lock) {
            if (closed) return
            closed = true
            activeWork.values.toList().also { activeWork.clear() }
        }
        registrations.forEach { it.job.cancel() }
    }

    private suspend fun runWork(registration: ActiveWork<T>) {
        val work = registration.work
        val completion = try {
            ReaderPageMaterializeCompletion.Completed(
                physicalRequestPermits.withPermit {
                    if (!accepts(registration)) {
                        ReaderPageMaterializeResult.Rejected
                    } else if (!port.prepare(work) || !accepts(registration)) {
                        ReaderPageMaterializeResult.Rejected
                    } else {
                        materializeExecutor.materializePage(
                            request = work.fetchRequest,
                            port = work.fetchPort,
                            forceRefresh = work.request.forceRefresh,
                            publish = { event -> accepts(registration) && port.publish(work, event) },
                        )
                    }
                },
            )
        } catch (error: CancellationException) {
            ReaderPageMaterializeCompletion.Cancelled(error)
        } catch (error: Throwable) {
            ReaderPageMaterializeCompletion.Failed(error)
        }

        finalize(registration, completion)

        when (completion) {
            is ReaderPageMaterializeCompletion.Cancelled -> throw completion.cause
            is ReaderPageMaterializeCompletion.Failed -> throw completion.cause
            is ReaderPageMaterializeCompletion.Completed -> Unit
        }
    }

    private fun finalize(
        registration: ActiveWork<T>,
        completion: ReaderPageMaterializeCompletion,
    ) {
        val shouldFinalize = synchronized(lock) {
            if (registration.finalized) {
                false
            } else {
                registration.finalized = true
                val jobKey = registration.work.request.jobKey
                if (activeWork[jobKey] === registration) activeWork.remove(jobKey)
                true
            }
        }
        if (!shouldFinalize) return
        try {
            port.complete(registration.work, completion)
        } finally {
            pump()
        }
    }

    private fun accepts(registration: ActiveWork<T>): Boolean = synchronized(lock) {
        !closed && !registration.finalized && activeWork[registration.work.request.jobKey] === registration
    } && port.accepts(registration.work)
}
