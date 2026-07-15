package mihon.domain.extension.service

import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, InstallFlight>()

    fun install(request: ExtensionInstallRequest): Flow<ExtensionInstallState> = flow {
        val packageName = request.artifact.packageName
        val flight = acquireFlight(request)
        flight.job.start()
        try {
            flight.events
                .takeWhile { it !is InstallEvent.Complete }
                .collect { emit((it as InstallEvent.State).value) }
        } finally {
            releaseFlight(packageName, flight)
        }
    }

    private suspend fun acquireFlight(request: ExtensionInstallRequest): InstallFlight = mutex.withLock {
        val packageName = request.artifact.packageName
        val flight = inFlight[packageName] ?: InstallFlight().also { created ->
            created.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runInstall(request, created.events)
                } finally {
                    finishFlight(packageName, created)
                }
            }
            inFlight[packageName] = created
        }
        flight.subscribers++
        flight
    }

    private suspend fun releaseFlight(packageName: String, flight: InstallFlight) {
        val cancel = mutex.withLock {
            flight.subscribers--
            if (flight.subscribers == 0 && flight.job.isActive) {
                if (inFlight[packageName] === flight) inFlight.remove(packageName)
                true
            } else {
                false
            }
        }
        if (cancel) flight.job.cancel()
    }

    private suspend fun finishFlight(packageName: String, flight: InstallFlight) = withContext(NonCancellable) {
        flight.events.emit(InstallEvent.Complete)
        mutex.withLock {
            if (inFlight[packageName] === flight) inFlight.remove(packageName)
        }
    }

    private suspend fun runInstall(
        request: ExtensionInstallRequest,
        events: MutableSharedFlow<InstallEvent>,
    ) {
        var prepared: PreparedExtensionInstallToken? = null
        var rollback: ExtensionInstallRollbackToken? = null
        var cleanupAttempted = false
        try {
            events.state(ExtensionInstallState.Preparing)
            prepared = port.prepare(request)
            events.state(ExtensionInstallState.Validating)
            port.validate(prepared)
            events.state(ExtensionInstallState.Committing)
            rollback = port.commit(prepared)
            events.state(ExtensionInstallState.Reloading)
            port.reload(request.artifact.packageName)
            cleanupAttempted = true
            port.cleanup(prepared)
            events.state(ExtensionInstallState.Installed(request.artifact))
        } catch (failure: Throwable) {
            val error = if (rollback == null) {
                failure.toAppError()
            } else {
                withContext(NonCancellable) {
                    rollbackAndRestore(request.artifact.packageName, rollback, failure.toAppError(), events)
                }
            }
            if (failure is CancellationException) throw failure
            events.state(ExtensionInstallState.Failed(error))
        } finally {
            if (prepared != null && !cleanupAttempted) {
                withContext(NonCancellable) {
                    runCatching { port.cleanup(prepared) }
                }
            }
        }
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
            if (rollbackFailure is CancellationException) throw rollbackFailure
            return rollbackFailure.toAppError()
        }

        events.state(ExtensionInstallState.RestoringRuntime)
        return try {
            port.reload(packageName)
            triggeringError
        } catch (restoreFailure: Throwable) {
            if (restoreFailure is CancellationException) throw restoreFailure
            AppError.PartialFailure(
                failures = listOf(triggeringError, restoreFailure.toAppError()),
                cause = restoreFailure,
            )
        }
    }
}

private class InstallFlight {
    val events = MutableSharedFlow<InstallEvent>(replay = 16)
    lateinit var job: Job
    var subscribers: Int = 0
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
