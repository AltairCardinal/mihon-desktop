package mihon.domain.extension.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact

sealed interface ExtensionInstallState {
    data object Preparing : ExtensionInstallState
    data object Validating : ExtensionInstallState
    data object Committing : ExtensionInstallState
    data object Reloading : ExtensionInstallState
    data object RollingBack : ExtensionInstallState
    data object RestoringRuntime : ExtensionInstallState
    data class Installed(val artifact: ExtensionArtifact) : ExtensionInstallState
    data class Failed(val error: AppError) : ExtensionInstallState
}

class ExtensionInstallCoordinator(
    private val port: ExtensionInstallPort,
    private val scope: CoroutineScope,
) {
    private val flightLock = Any()
    private val inFlight = mutableMapOf<String, InstallFlight>()

    fun install(request: ExtensionInstallRequest): Flow<ExtensionInstallState> = flow {
        val flight = acquireFlight(request)
        flight.job.start()
        try {
            flight.events
                .takeWhile { it !is InstallEvent.Complete }
                .collect { emit((it as InstallEvent.State).value) }
        } finally {
            if (releaseFlight(flight)) {
                withContext(NonCancellable) {
                    flight.completion.await()
                }
            }
        }
    }

    private suspend fun acquireFlight(request: ExtensionInstallRequest): InstallFlight {
        val packageName = request.artifact.packageName
        while (true) {
            var waitForCompletion: CompletableDeferred<Unit>? = null
            val flight = synchronized(flightLock) {
                val current = inFlight[packageName]
                if (current != null && !current.acceptsSubscribers) {
                    waitForCompletion = current.completion
                    null
                } else {
                    (current ?: createFlight(request)).also { it.subscribers++ }
                }
            }
            if (flight != null) return flight
            checkNotNull(waitForCompletion).await()
        }
    }

    private fun createFlight(request: ExtensionInstallRequest): InstallFlight {
        val packageName = request.artifact.packageName
        return InstallFlight().also { created ->
            created.job = scope.launch(start = CoroutineStart.LAZY) {
                var terminalState: ExtensionInstallState? = null
                try {
                    terminalState = runInstall(request, created.events)
                } catch (failure: CancellationException) {
                    terminalState = ExtensionInstallState.Failed(failure.toAppError())
                } catch (failure: Throwable) {
                    terminalState = ExtensionInstallState.Failed(failure.toAppError())
                } finally {
                    finishFlight(packageName, created, terminalState)
                }
            }
            inFlight[packageName] = created
            created.job.invokeOnCompletion { failure ->
                val terminalState = failure?.let {
                    ExtensionInstallState.Failed(it.toAppError())
                }
                finishFlight(packageName, created, terminalState)
            }
        }
    }

    private fun releaseFlight(flight: InstallFlight): Boolean {
        var lastSubscriber = false
        val cancel = synchronized(flightLock) {
            flight.subscribers--
            lastSubscriber = flight.subscribers == 0
            (flight.subscribers == 0 && flight.acceptsSubscribers && flight.job.isActive).also {
                if (it) flight.acceptsSubscribers = false
            }
        }
        if (cancel) flight.job.cancel()
        return lastSubscriber
    }

    private fun finishFlight(
        packageName: String,
        flight: InstallFlight,
        terminalState: ExtensionInstallState?,
    ) {
        val finish = synchronized(flightLock) {
            if (flight.finished) return@synchronized false
            flight.finished = true
            flight.acceptsSubscribers = false
            if (inFlight[packageName] === flight) inFlight.remove(packageName)
            true
        }
        if (!finish) return
        terminalState?.let { flight.events.tryEmit(InstallEvent.State(it)) }
        flight.events.tryEmit(InstallEvent.Complete)
        flight.completion.complete(Unit)
    }

    private suspend fun runInstall(
        request: ExtensionInstallRequest,
        events: MutableSharedFlow<InstallEvent>,
    ): ExtensionInstallState {
        var prepared: PreparedExtensionInstallToken? = null
        var rollback: ExtensionInstallRollbackToken? = null
        var cleanupCompleted = false
        var transactionFailure: Throwable? = null
        var error: AppError? = null
        try {
            events.state(ExtensionInstallState.Preparing)
            prepared = port.prepare(request)
            events.state(ExtensionInstallState.Validating)
            rollback = port.validate(prepared)
            events.state(ExtensionInstallState.Committing)
            port.commit(prepared)
            events.state(ExtensionInstallState.Reloading)
            port.reload(request.artifact.packageName)
            port.cleanup(prepared)
            cleanupCompleted = true
        } catch (failure: Throwable) {
            transactionFailure = failure
            error = if (rollback == null) {
                failure.toAppError()
            } else {
                withContext(NonCancellable) {
                    rollbackAndRestore(request.artifact.packageName, rollback, failure.toAppError(), events)
                }
            }
        } finally {
            if (prepared != null && !cleanupCompleted) {
                val cleanupFailure = withContext(NonCancellable) {
                    runCatching { port.cleanup(prepared) }.exceptionOrNull()
                }
                if (cleanupFailure == null) {
                    cleanupCompleted = true
                } else {
                    error = error?.withSecondary(cleanupFailure.toAppError(), cleanupFailure)
                        ?: cleanupFailure.toAppError()
                }
            }
        }
        if (transactionFailure is CancellationException) throw transactionFailure
        return error?.let(ExtensionInstallState::Failed)
            ?: ExtensionInstallState.Installed(request.artifact)
    }

    private suspend fun rollbackAndRestore(
        packageName: String,
        token: ExtensionInstallRollbackToken,
        triggeringError: AppError,
        events: MutableSharedFlow<InstallEvent>,
    ): AppError {
        events.state(ExtensionInstallState.RollingBack)
        try {
            port.rollback(token)
        } catch (rollbackFailure: Throwable) {
            return rollbackFailure.toAppError()
        }

        events.state(ExtensionInstallState.RestoringRuntime)
        return try {
            port.reload(packageName)
            triggeringError
        } catch (restoreFailure: Throwable) {
            AppError.PartialFailure(
                failures = listOf(triggeringError, restoreFailure.toAppError()),
                cause = restoreFailure,
            )
        }
    }
}

private class InstallFlight {
    val events = MutableSharedFlow<InstallEvent>(replay = 16)
    val completion = CompletableDeferred<Unit>()
    lateinit var job: Job
    var subscribers: Int = 0
    var acceptsSubscribers: Boolean = true
    var finished: Boolean = false
}

private sealed interface InstallEvent {
    data class State(val value: ExtensionInstallState) : InstallEvent
    data object Complete : InstallEvent
}

private suspend fun MutableSharedFlow<InstallEvent>.state(state: ExtensionInstallState) {
    emit(InstallEvent.State(state))
}

private fun Throwable.toAppError(): AppError = when (this) {
    is ExtensionInstallFailure -> error
    is CancellationException -> AppError.Cancelled
    else -> AppError.Unknown(this)
}

private fun AppError.withSecondary(secondary: AppError, secondaryCause: Throwable): AppError.PartialFailure =
    AppError.PartialFailure(
        failures = listOf(this, secondary),
        cause = secondaryCause,
    )
