package mihon.domain.extension.suggestion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.service.ExtensionInstallInvalidated
import mihon.domain.extension.service.ExtensionInstallInvalidation
import mihon.domain.extension.service.ExtensionInstallLease
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionInstallStop

sealed interface SuggestionBatchResult {
    data object Installed : SuggestionBatchResult
    data object Busy : SuggestionBatchResult
    data object Stopped : SuggestionBatchResult
    data object Cancelled : SuggestionBatchResult
    data class Invalidated(val reason: ExtensionInstallInvalidation) : SuggestionBatchResult
    data class Failed(val error: AppError) : SuggestionBatchResult
    data class Paused(val reason: SuggestionBatchPause) : SuggestionBatchResult
}

enum class SuggestionBatchPause {
    CATALOG_CHANGED,
    INVENTORY_UNKNOWN,
    INSTALLER_CHANGED,
    PERMISSION,
    SERVICE,
    CONFIRMATION_CANCELLED,
    APP_FOREGROUND,
}

data class SuggestionBatchItem(
    val artifact: ExtensionArtifact,
    val transactionId: Long?,
    val progress: ExtensionInstallState? = null,
    val result: SuggestionBatchResult? = null,
)

data class SuggestionBatchState(
    val id: Long = 0,
    val items: List<SuggestionBatchItem> = emptyList(),
    val running: Boolean = false,
    val stopping: Boolean = false,
    val pauseReason: SuggestionBatchPause? = null,
) {
    val remaining: List<ExtensionArtifact> get() = if (stopping) {
        emptyList()
    } else {
        items.filter {
            it.needsConfirmation()
        }.map { it.artifact }
    }
    val completed: Int get() = items.count { it.result != null && (stopping || !it.needsConfirmation()) }
}

private fun SuggestionBatchItem.needsConfirmation(): Boolean = result is SuggestionBatchResult.Paused ||
    (result as? SuggestionBatchResult.Invalidated)?.reason in setOf(
        ExtensionInstallInvalidation.CATALOG_CHANGED,
        ExtensionInstallInvalidation.INVENTORY_UNKNOWN,
        ExtensionInstallInvalidation.INSTALLER_CHANGED,
    )

fun interface SuggestionBatchInstallPort {
    /** Return only after the actual transaction and cleanup finish, including cancellation. */
    suspend fun install(
        lease: ExtensionInstallLease,
        onProgress: (transactionId: Long, ExtensionInstallState) -> Unit,
    ): SuggestionBatchResult
}

/** Lives in the application scope; no persisted queue and no page-lifetime ownership. */
class ExtensionSuggestionBatchController(
    private val scope: CoroutineScope,
    private val arbiter: ExtensionInstallArbiter,
    private val port: SuggestionBatchInstallPort,
    private val eligibility: (ExtensionArtifact) -> ExtensionInstallInvalidation?,
) {
    private val mutableState = MutableStateFlow(SuggestionBatchState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var nextId = 0L
    private var runner: Job? = null
    private var reservations = emptyList<ExtensionInstallLease>()
    private var current: ExtensionInstallLease? = null

    fun start(confirmed: List<ExtensionArtifact>, expectedBatchId: Long? = null): Boolean = synchronized(lock) {
        if (expectedBatchId != null && expectedBatchId != mutableState.value.id) return@synchronized false
        if (mutableState.value.running || mutableState.value.remaining.isNotEmpty() ||
            confirmed.isEmpty()
        ) {
            return@synchronized false
        }
        val snapshot = confirmed.map { it.copy(sources = it.sources.toList()) }.distinct()
        if (snapshot.map { it.packageName }.distinct().size != snapshot.size) return@synchronized false
        val sourceIds = snapshot.flatMap { artifact -> artifact.sources.map { it.id }.distinct() }
        if (sourceIds.distinct().size != sourceIds.size) return@synchronized false
        val id = ++nextId
        val leases = snapshot.map { artifact -> arbiter.reserve(artifact) { eligibility(artifact) } }
        reservations = leases.filterNotNull()
        mutableState.value = SuggestionBatchState(
            id = id,
            items = snapshot.mapIndexed { index, artifact ->
                SuggestionBatchItem(
                    artifact,
                    leases[index]?.transactionId,
                    result = if (leases[index] ==
                        null
                    ) {
                        SuggestionBatchResult.Busy
                    } else {
                        null
                    },
                )
            },
            running = true,
        )
        runner = scope.launch(start = CoroutineStart.LAZY) { runBatch(id, leases.filterNotNull()) }.also { job ->
            job.invokeOnCompletion { finish(id) }
            job.start()
        }
        true
    }

    fun resume(confirmedRemaining: List<ExtensionArtifact>, expectedBatchId: Long? = null) = synchronized(lock) {
        if (expectedBatchId != null && expectedBatchId != mutableState.value.id) return@synchronized false
        restart(confirmedRemaining, mutableState.value.remaining)
    }

    fun retryFailed(confirmedFailed: List<ExtensionArtifact>, expectedBatchId: Long? = null) = synchronized(lock) {
        if (expectedBatchId != null && expectedBatchId != mutableState.value.id) return@synchronized false
        if (mutableState.value.remaining.isNotEmpty()) return@synchronized false
        restart(
            confirmedFailed,
            mutableState.value.items.filter {
                it.result is SuggestionBatchResult.Failed
            }.map { it.artifact },
        )
    }

    private fun restart(confirmedRemaining: List<ExtensionArtifact>, remaining: List<ExtensionArtifact>): Boolean {
        val previous = mutableState.value
        if (previous.running || remaining.isEmpty() ||
            confirmedRemaining.map { it.packageName } != remaining.map { it.packageName }
        ) {
            return false
        }
        val snapshot = confirmedRemaining.map { it.copy(sources = it.sources.toList()) }
        val installed = previous.items.filter { it.result == SuggestionBatchResult.Installed }.map { it.artifact }
        val sources = (snapshot + installed).flatMap { artifact -> artifact.sources.map { it.id }.distinct() }
        if (sources.size != sources.distinct().size) return false
        val leases = snapshot.map { artifact -> arbiter.reserve(artifact) { eligibility(artifact) } }
        reservations = leases.filterNotNull()
        val replacements = snapshot.mapIndexed { index, artifact ->
            SuggestionBatchItem(
                artifact,
                leases[index]?.transactionId,
                result = if (leases[index] ==
                    null
                ) {
                    SuggestionBatchResult.Busy
                } else {
                    null
                },
            )
        }.associateBy { it.artifact.packageName }
        mutableState.value = previous.copy(
            items = previous.items.map { replacements[it.artifact.packageName] ?: it },
            running = true,
            stopping = false,
            pauseReason = null,
        )
        runner =
            scope.launch(start = CoroutineStart.LAZY) { runBatch(previous.id, leases.filterNotNull()) }.also { job ->
                job.invokeOnCompletion { finish(previous.id) }
                job.start()
            }
        return true
    }

    fun stop() = synchronized(lock) {
        if (!mutableState.value.running) {
            mutableState.value = mutableState.value.copy(
                items = mutableState.value.items.map {
                    if (it.needsConfirmation()) it.copy(result = SuggestionBatchResult.Stopped) else it
                },
                pauseReason = null,
            )
            return@synchronized
        }
        mutableState.value = mutableState.value.copy(stopping = true)
        reservations.filter { it !== current }.forEach { lease ->
            update(mutableState.value.id, lease) { it.copy(result = it.result ?: SuggestionBatchResult.Stopped) }
            arbiter.stop(lease)
            arbiter.release(lease)
        }
        val lease = current
        if (lease == null || arbiter.stop(lease) == ExtensionInstallStop.CANCELLABLE) runner?.cancel()
    }

    private suspend fun runBatch(id: Long, leases: List<ExtensionInstallLease>) {
        for (lease in leases) {
            val proceed = synchronized(lock) {
                if (mutableState.value.id != id || mutableState.value.stopping) {
                    false
                } else {
                    current = lease
                    true
                }
            }
            if (!proceed) break
            val result = try {
                eligibility(lease.artifact)?.let { throw ExtensionInstallInvalidated(it) }
                port.install(lease) { transactionId, progress ->
                    if (transactionId == lease.transactionId) update(id, lease) { it.copy(progress = progress) }
                }
            } catch (failure: ExtensionInstallInvalidated) {
                SuggestionBatchResult.Invalidated(failure.reason)
            } catch (_: CancellationException) {
                SuggestionBatchResult.Stopped
            } catch (failure: Throwable) {
                SuggestionBatchResult.Failed(AppError.Unknown(failure))
            } finally {
                // The platform port must finish rollback/cleanup before returning or throwing.
                arbiter.release(lease)
            }
            update(id, lease) { it.copy(result = result) }
            synchronized(lock) { if (current === lease) current = null }
            val pause = when (result) {
                SuggestionBatchResult.Cancelled -> SuggestionBatchPause.CONFIRMATION_CANCELLED.takeIf {
                    mutableState.value.items.any { it.result == null }
                }
                is SuggestionBatchResult.Paused -> result.reason
                is SuggestionBatchResult.Invalidated -> when (result.reason) {
                    ExtensionInstallInvalidation.CATALOG_CHANGED -> SuggestionBatchPause.CATALOG_CHANGED
                    ExtensionInstallInvalidation.INVENTORY_UNKNOWN -> SuggestionBatchPause.INVENTORY_UNKNOWN
                    ExtensionInstallInvalidation.INSTALLER_CHANGED -> SuggestionBatchPause.INSTALLER_CHANGED
                    else -> null
                }
                else -> null
            }
            if (pause != null) {
                synchronized(lock) {
                    if (!mutableState.value.stopping) mutableState.value = mutableState.value.copy(pauseReason = pause)
                }
                leases.filter { it !== lease }.forEach { remaining ->
                    update(id, remaining) { it.copy(result = SuggestionBatchResult.Paused(pause)) }
                }
                break
            }
            if (result == SuggestionBatchResult.Stopped || result == SuggestionBatchResult.Cancelled) break
        }
    }

    private fun update(
        id: Long,
        lease: ExtensionInstallLease,
        transform: (SuggestionBatchItem) -> SuggestionBatchItem,
    ) = synchronized(lock) {
        val state = mutableState.value
        if (state.id != id) return@synchronized
        mutableState.value = state.copy(
            items = state.items.map {
                if (it.transactionId == lease.transactionId && it.result == null) transform(it) else it
            },
        )
    }

    private fun finish(id: Long) = synchronized(lock) {
        if (mutableState.value.id != id) return@synchronized
        reservations.forEach(arbiter::release)
        reservations = emptyList()
        current = null
        mutableState.value = mutableState.value.copy(
            running = false,
            items = mutableState.value.items.map { it.copy(result = it.result ?: SuggestionBatchResult.Stopped) },
        )
    }
}
