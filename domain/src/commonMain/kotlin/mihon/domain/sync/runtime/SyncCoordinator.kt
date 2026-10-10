package mihon.domain.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@kotlinx.serialization.Serializable
enum class SyncTrigger { MANUAL, STARTUP, PERIODIC, RECOVERY }

@kotlinx.serialization.Serializable
enum class SyncRunStatus { SUCCESS, PARTIAL, FAILED, SKIPPED }

@kotlinx.serialization.Serializable
enum class SyncNetworkFailurePhase { DNS, CONNECT, PROXY_HANDSHAKE, TLS, TIMEOUT, HTTP_RESPONSE, HTTP_BODY, UNKNOWN }

@kotlinx.serialization.Serializable
enum class SyncRunProblem {
    AUTHORIZATION,
    SPACE_UNAVAILABLE,
    NETWORK,
    STORAGE,
    REMOTE_CHANGED,
    INVALID_DATA,
    REPOSITORY_NOT_PRIVATE,
    UNKNOWN,
}

@kotlinx.serialization.Serializable
data class SyncRunResult(
    val status: SyncRunStatus,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val pending: Int = 0,
    val problem: SyncRunProblem? = null,
    /** Server-advised delay for the next automatic attempt, when available. */
    val retryAfterMillis: Long? = null,
    val networkPhase: SyncNetworkFailurePhase? = null,
    val httpStatus: Int? = null,
)

fun interface SyncRunPort {
    suspend fun exchange(trigger: SyncTrigger): SyncRunResult
}

data class SyncActivity(
    val running: Boolean = false,
    val trigger: SyncTrigger? = null,
    val completion: Long = 0,
    val result: SyncRunResult? = null,
)

/** One coordinator per application, shared by foreground and scheduled callers. */
class SyncCoordinator(private val port: SyncRunPort) {
    private class Flight(val owner: Job, val trigger: SyncTrigger) {
        val completion = CompletableDeferred<SyncRunResult>()
        var pending: SyncTrigger? = null
    }

    private val mutex = Mutex()
    private var flight: Flight? = null
    private val mutableActivity = MutableStateFlow(SyncActivity())
    val activity: StateFlow<SyncActivity> = mutableActivity

    suspend fun synchronize(trigger: SyncTrigger): SyncRunResult = coroutineScope {
        val owner = currentCoroutineContext().job
        val current = mutex.withLock {
            flight?.also { existing ->
                val duplicateAutomatic = trigger in AUTOMATIC_TRIGGERS &&
                    existing.trigger in AUTOMATIC_TRIGGERS &&
                    existing.pending == null
                if (!duplicateAutomatic && existing.pending != SyncTrigger.MANUAL && existing.pending != trigger) {
                    existing.pending = trigger
                }
            } ?: Flight(owner, trigger).also {
                flight = it
                mutableActivity.value = mutableActivity.value.copy(running = true, trigger = trigger)
            }
        }
        if (current.owner != owner) return@coroutineScope current.completion.await()
        try {
            var next = trigger
            while (true) {
                val result = try {
                    port.exchange(next)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SyncRunResult(SyncRunStatus.FAILED, problem = SyncRunProblem.UNKNOWN)
                }
                val pending = mutex.withLock {
                    val pending = current.pending.takeIf { result.status == SyncRunStatus.SUCCESS }
                    current.pending = null
                    if (pending == null) {
                        flight = null
                        mutableActivity.value = SyncActivity(
                            completion = mutableActivity.value.completion + 1,
                            result = result,
                        )
                        current.completion.complete(result)
                    } else {
                        mutableActivity.value = mutableActivity.value.copy(trigger = pending)
                    }
                    pending
                }
                if (pending == null) return@coroutineScope result
                next = pending
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (flight === current) {
                        flight = null
                        mutableActivity.value = mutableActivity.value.copy(running = false, trigger = null)
                        current.completion.cancel()
                    }
                }
            }
        }
    }

    suspend fun cancelAndJoin() {
        mutex.withLock { flight?.owner }?.cancelAndJoin()
    }

    companion object {
        private val AUTOMATIC_TRIGGERS = setOf(SyncTrigger.PERIODIC, SyncTrigger.RECOVERY)
    }
}
