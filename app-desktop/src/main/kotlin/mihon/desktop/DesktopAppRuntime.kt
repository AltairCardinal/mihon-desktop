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
) {
    private var startupJob: Job? = null
    private var instanceBroker: DesktopExternalActionBroker? = null
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        startupJob = scope.launch {
            runCatching { startupCleanup() }
        }
        libraryUpdateScheduler.start()
        localSourceScanService.start()
        autoBackupScheduler.start()
        trackerSyncScheduler.start()
        batchMigrationController.start()
    }

    fun stop() {
        if (!isRunning) return
        startupJob?.cancel()
        startupJob = null
        batchMigrationController.stop()
        trackerSyncScheduler.stop()
        autoBackupScheduler.stop()
        localSourceScanService.stop()
        libraryUpdateScheduler.stop()
        isRunning = false
    }

    fun close() {
        try {
            stop()
        } finally {
            try {
                instanceBroker?.close()
                instanceBroker = null
            } finally {
                scope.cancel()
            }
        }
    }

    fun attachInstanceBroker(broker: DesktopExternalActionBroker) {
        check(instanceBroker == null || instanceBroker === broker) { "A different instance broker is already attached" }
        instanceBroker = broker
    }

    companion object {
        fun create(
            libraryUpdateScheduler: LibraryUpdateScheduler,
            localSourceScanService: LocalSourceScanService,
            autoBackupScheduler: AutoBackupScheduler,
            readerModeMemoryCleaner: ReaderModeMemoryCleaner,
            trackerSyncScheduler: DesktopRuntimeService = NoopRuntimeService,
            batchMigrationController: DesktopRuntimeService = NoopRuntimeService,
        ): DesktopAppRuntime {
            return DesktopAppRuntime(
                libraryUpdateScheduler = libraryUpdateScheduler.asRuntimeService(),
                localSourceScanService = localSourceScanService.asRuntimeService(),
                autoBackupScheduler = autoBackupScheduler.asRuntimeService(),
                trackerSyncScheduler = trackerSyncScheduler,
                batchMigrationController = batchMigrationController,
                startupCleanup = { readerModeMemoryCleaner.clearNonFavoriteManga() },
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
