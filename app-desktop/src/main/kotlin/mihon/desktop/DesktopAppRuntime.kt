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
import mihon.desktop.source.LocalSourceScanService

interface DesktopRuntimeService {
    fun start()
    fun stop()
}

class DesktopAppRuntime(
    private val libraryUpdateScheduler: DesktopRuntimeService,
    private val localSourceScanService: DesktopRuntimeService,
    private val autoBackupScheduler: DesktopRuntimeService,
    private val startupCleanup: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var startupJob: Job? = null
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
    }

    fun stop() {
        if (!isRunning) return
        startupJob?.cancel()
        startupJob = null
        autoBackupScheduler.stop()
        localSourceScanService.stop()
        libraryUpdateScheduler.stop()
        isRunning = false
    }

    fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        fun create(
            libraryUpdateScheduler: LibraryUpdateScheduler,
            localSourceScanService: LocalSourceScanService,
            autoBackupScheduler: AutoBackupScheduler,
            readerModeMemoryCleaner: ReaderModeMemoryCleaner,
        ): DesktopAppRuntime {
            return DesktopAppRuntime(
                libraryUpdateScheduler = libraryUpdateScheduler.asRuntimeService(),
                localSourceScanService = localSourceScanService.asRuntimeService(),
                autoBackupScheduler = autoBackupScheduler.asRuntimeService(),
                startupCleanup = { readerModeMemoryCleaner.clearNonFavoriteManga() },
            )
        }
    }
}

private fun LibraryUpdateScheduler.asRuntimeService(): DesktopRuntimeService =
    object : DesktopRuntimeService {
        override fun start() = this@asRuntimeService.start()
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
