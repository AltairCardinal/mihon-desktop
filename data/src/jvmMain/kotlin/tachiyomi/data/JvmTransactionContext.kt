package tachiyomi.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Runs a suspending JVM database transaction on one acquired dispatcher thread.
 *
 * JDBC transactions are thread-local. The registry keeps every active handler's transaction
 * thread available across suspensions and cross-handler nesting, while the handler mutex only
 * serializes independent outer transactions for the same database.
 */
internal suspend fun <T> JvmDatabaseHandler.withTransaction(block: suspend () -> T): T {
    val inheritedRegistry = coroutineContext[JvmTransactionRegistry] ?: JvmTransactionRegistry(emptyMap())
    inheritedRegistry.states[this]?.let { state ->
        return runTransaction(state, inheritedRegistry, block)
    }

    return transactionMutex.withLock {
        checkOpen()
        val state = createTransactionState()
        runTransaction(state, inheritedRegistry.with(this, state), block)
    }
}

internal suspend fun JvmDatabaseHandler.getCurrentDatabaseContext(): CoroutineContext {
    return coroutineContext[JvmTransactionRegistry]?.states?.get(this)?.dispatcher ?: queryDispatcher
}

private suspend fun <T> JvmDatabaseHandler.runTransaction(
    state: JvmTransactionState,
    registry: JvmTransactionRegistry,
    block: suspend () -> T,
): T {
    state.acquire()
    try {
        return withContext(state.dispatcher + registry) {
            val blockingContext = coroutineContext
            db.transactionWithResult {
                runBlocking(blockingContext) {
                    block()
                }
            }
        }
    } finally {
        state.release()
    }
}

private suspend fun JvmDatabaseHandler.createTransactionState(): JvmTransactionState {
    val controlJob = Job()
    coroutineContext[Job]?.invokeOnCompletion { controlJob.cancel() }
    val dispatcher = queryDispatcher.acquireTransactionThread(controlJob)
    return JvmTransactionState(controlJob, dispatcher)
}

private suspend fun CoroutineDispatcher.acquireTransactionThread(
    controlJob: Job,
): ContinuationInterceptor = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { controlJob.cancel() }
    try {
        dispatch(EmptyCoroutineContext) {
            runBlocking {
                continuation.resume(coroutineContext[ContinuationInterceptor]!!)
                controlJob.join()
            }
        }
    } catch (error: RejectedExecutionException) {
        continuation.cancel(IllegalStateException("Unable to acquire a thread for a JVM database transaction", error))
    }
}

private class JvmTransactionRegistry(
    val states: Map<JvmDatabaseHandler, JvmTransactionState>,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<JvmTransactionRegistry>

    override val key: CoroutineContext.Key<JvmTransactionRegistry> = JvmTransactionRegistry

    fun with(handler: JvmDatabaseHandler, state: JvmTransactionState): JvmTransactionRegistry {
        return JvmTransactionRegistry(states + (handler to state))
    }
}

private class JvmTransactionState(
    private val controlJob: Job,
    val dispatcher: ContinuationInterceptor,
) {
    private val references = AtomicInteger()

    fun acquire() {
        references.incrementAndGet()
    }

    fun release() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "JVM database transaction was released without being acquired" }
        if (remaining == 0) controlJob.cancel()
    }
}
