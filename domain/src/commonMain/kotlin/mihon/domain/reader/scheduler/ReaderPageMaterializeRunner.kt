package mihon.domain.reader.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
                registration.job.invokeOnCompletion { cause ->
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

    fun snapshot(): ReaderPageMaterializeRunnerSnapshot = synchronized(lock) {
        ReaderPageMaterializeRunnerSnapshot(activeWork.keys.toSet(), closed)
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
