package mihon.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mihon.desktop.backup.AutoBackupScheduler
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.domain.ReaderModeMemoryCleaner
import mihon.desktop.platform.DesktopExternalActionBroker
import mihon.desktop.source.LocalSourceScanService
import mihon.desktop.security.DesktopAppLockLifecycle
import mihon.desktop.ui.settings.DesktopUpdateScreenModel

interface DesktopRuntimeService {
    fun start()
    fun stop()
}

class DesktopAppRuntime(
    private val libraryUpdateScheduler: DesktopRuntimeService,
    private val localSourceScanService: DesktopRuntimeService,
    private val autoBackupScheduler: DesktopRuntimeService,
    private val trackerSyncScheduler: DesktopRuntimeService = NoopRuntimeService,
    private val batchMigrationController: DesktopRuntimeService = NoopRuntimeService,
    private val startupCleanup: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    internal val appLock: DesktopAppLockLifecycle = NoopAppLockLifecycle,
    private val updateScreenModel: DesktopUpdateScreenModel? = null,
    private val closeUpdater: () -> Unit = { updateScreenModel?.close() },
    private val awaitUpdater: suspend () -> Unit = { updateScreenModel?.closeAndJoin() },
) {
    private var startupJob: Job? = null
    private var instanceBroker: DesktopExternalActionBroker? = null
    private val closeActions = mutableListOf<AutoCloseable>()
    private val runningServices = mutableListOf<DesktopRuntimeService>()
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        appLock.onApplicationStarted()
        isRunning = true
        startupJob = scope.launch {
            runCatching { startupCleanup() }
        }
        listOf(libraryUpdateScheduler, localSourceScanService, autoBackupScheduler, trackerSyncScheduler, batchMigrationController).forEach {
            it.start()
            runningServices += it
        }
    }

    fun stop() {
        if (!isRunning) return
        val failures = CleanupFailures()
        failures.attempt(appLock::onApplicationStopped)
        failures.attempt { startupJob?.cancel() }
        startupJob = null
        listOf(batchMigrationController, trackerSyncScheduler, autoBackupScheduler, localSourceScanService, libraryUpdateScheduler).forEach { service ->
            if (service in runningServices) {
                var stopped = false
                failures.attempt { service.stop(); stopped = true }
                if (stopped) runningServices -= service
            }
        }
        isRunning = runningServices.isNotEmpty()
        failures.throwIfAny()
    }

    fun close() {
        val failures = CleanupFailures()
        failures.attempt(::stop)
        failures.attempt {
            val broker = instanceBroker
            broker?.close()
            instanceBroker = null
        }
        closeActions.toList().forEach { closeAction ->
            var closed = false
            failures.attempt {
                closeAction.close()
                closed = true
            }
            if (closed) closeActions.remove(closeAction)
        }
        failures.attempt(closeUpdater)
        failures.attempt(scope::cancel)
        failures.throwIfAny()
    }

    suspend fun closeAndJoin() {
        var primaryFailure: Throwable? = null
        try {
            close()
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
        try {
            awaitClosed()
        } catch (failure: Throwable) {
            val primary = primaryFailure
            if (primary == null) primaryFailure = failure else if (failure !== primary) primary.addSuppressed(failure)
        }
        primaryFailure?.let { throw it }
    }

    suspend fun awaitClosed() {
        val failures = CleanupFailures()
        failures.attemptSuspend(awaitUpdater)
        failures.attemptSuspend { scope.coroutineContext[Job]?.join() }
        failures.throwIfAny()
    }

    fun attachInstanceBroker(broker: DesktopExternalActionBroker) {
        check(instanceBroker == null || instanceBroker === broker) { "A different instance broker is already attached" }
        instanceBroker = broker
    }

    fun attachCloseable(closeable: AutoCloseable) {
        closeActions += closeable
    }

    companion object {
        fun create(
            libraryUpdateScheduler: LibraryUpdateScheduler,
            localSourceScanService: LocalSourceScanService,
            autoBackupScheduler: AutoBackupScheduler,
            readerModeMemoryCleaner: ReaderModeMemoryCleaner,
            trackerSyncScheduler: DesktopRuntimeService = NoopRuntimeService,
            batchMigrationController: DesktopRuntimeService = NoopRuntimeService,
            appLock: DesktopAppLockLifecycle = NoopAppLockLifecycle,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            updateScreenModel: DesktopUpdateScreenModel? = null,
        ): DesktopAppRuntime {
            return DesktopAppRuntime(
                libraryUpdateScheduler = libraryUpdateScheduler.asRuntimeService(),
                localSourceScanService = localSourceScanService.asRuntimeService(),
                autoBackupScheduler = autoBackupScheduler.asRuntimeService(),
                trackerSyncScheduler = trackerSyncScheduler,
                batchMigrationController = batchMigrationController,
                startupCleanup = { readerModeMemoryCleaner.clearNonFavoriteManga() },
                scope = scope,
                appLock = appLock,
                updateScreenModel = updateScreenModel,
            )
        }
    }
}

internal sealed interface DesktopInstanceStartResult {
    data object Owner : DesktopInstanceStartResult
    data object Forwarded : DesktopInstanceStartResult
    data class Failed(val failure: DesktopExternalActionBroker.Failure) : DesktopInstanceStartResult
}

internal fun startDesktopInstance(
    broker: DesktopExternalActionBroker,
    rawAction: String?,
    reportFailure: (DesktopExternalActionBroker.Failure) -> Unit = {
        System.err.println("Desktop single-instance startup failed: ${it.name}")
    },
    startOwner: (DesktopExternalActionBroker) -> Unit,
): DesktopInstanceStartResult = when (val result = broker.startOrForward(rawAction)) {
    is DesktopExternalActionBroker.StartResult.Owner -> {
        try {
            startOwner(broker)
            DesktopInstanceStartResult.Owner
        } catch (failure: Throwable) {
            broker.close()
            throw failure
        }
    }
    DesktopExternalActionBroker.StartResult.Forwarded -> {
        broker.close()
        DesktopInstanceStartResult.Forwarded
    }
    is DesktopExternalActionBroker.StartResult.Failed -> {
        broker.close()
        reportFailure(result.failure)
        DesktopInstanceStartResult.Failed(result.failure)
    }
}

private object NoopRuntimeService : DesktopRuntimeService {
    override fun start() = Unit
    override fun stop() = Unit
}

private class CleanupFailures {
    private var primary: Throwable? = null

    fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            val first = primary
            if (first == null) {
                primary = failure
            } else if (failure !== first) {
                first.addSuppressed(failure)
            }
        }
    }

    suspend fun attemptSuspend(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            val first = primary
            if (first == null) primary = failure else if (failure !== first) first.addSuppressed(failure)
        }
    }

    fun throwIfAny() {
        primary?.let { throw it }
    }
}

private object NoopAppLockLifecycle : DesktopAppLockLifecycle {
    override fun onApplicationStarted() = Unit
    override fun onApplicationStopped() = Unit
}

private fun LibraryUpdateScheduler.asRuntimeService(): DesktopRuntimeService =
    object : DesktopRuntimeService {
        override fun start() {
            this@asRuntimeService.start()
        }
        override fun stop() = this@asRuntimeService.stop()
    }

private fun LocalSourceScanService.asRuntimeService(): DesktopRuntimeService =
    object : DesktopRuntimeService {
        override fun start() = this@asRuntimeService.start()
        override fun stop() = this@asRuntimeService.stop()
    }

private fun AutoBackupScheduler.asRuntimeService(): DesktopRuntimeService =
    object : DesktopRuntimeService {
        override fun start() = this@asRuntimeService.start()
        override fun stop() = this@asRuntimeService.stop()
    }
