package mihon.desktop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager

/**
 * Periodically checks for new chapters in the library.
 *
 * Uses a polling loop that checks every minute whether enough time has elapsed
 * since the last run. This means the interval setting takes effect immediately
 * on the next polling tick rather than requiring a restart.
 */
class LibraryUpdateScheduler(
    private val appPreferences: DesktopAppPreferences,
    private val updateChecker: LibraryUpdateChecker,
    private val getLibraryManga: GetLibraryManga,
    private val sourceManager: SourceManager,
    scope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null

    /** Whether the background scheduler is currently active. */
    val isRunning: Boolean get() = schedulerJob?.isActive == true

    /** Starts the background scheduler. Safe to call multiple times. */
    fun start() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            var lastRun = 0L  // 0 means "never run"
            while (true) {
                delay(CHECK_INTERVAL_MS)
                val intervalMs = appPreferences.libraryUpdateInterval.get().toMillis()
                if (intervalMs <= 0) continue  // auto-update is disabled
                val now = System.currentTimeMillis()
                if (lastRun == 0L || now - lastRun >= intervalMs) {
                    lastRun = now
                    runLibraryUpdate()
                }
            }
        }
    }

    /** Stops the background scheduler. */
    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    private suspend fun runLibraryUpdate() {
        try {
            val allManga = getLibraryManga.await()
            val sources = sourceManager.getCatalogueSources()
            for (manga in allManga) {
                val source = sources.find { it.id == manga.manga.source } ?: continue
                runCatching { updateChecker.checkForUpdates(manga.manga, source) }
            }
        } catch (_: Exception) {
            // Silently ignore scheduler errors to keep the loop alive.
        }
    }

    companion object {
        /** How often the scheduler checks whether an update is due. */
        const val CHECK_INTERVAL_MS = 60_000L
    }
}
