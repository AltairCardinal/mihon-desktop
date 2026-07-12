package mihon.desktop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import mihon.domain.task.BackgroundTask
import mihon.domain.task.NotificationEvent
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.category.repository.CategoryRepository
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
    private val categoryRepository: CategoryRepository? = null,
    private val notificationService: DesktopNotificationService? = null,
    private val creatorDiscoveryService: CreatorDiscoveryService? = null,
    private val taskScheduler: DesktopTaskScheduler? = null,
    private val taskNotifier: DesktopSystemNotifier? = null,
    scope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null

    /** Whether the background scheduler is currently active. */
    val isRunning: Boolean get() = schedulerJob?.isActive == true

    /** Starts the background scheduler. Safe to call multiple times. */
    fun start() {
        if (schedulerJob?.isActive == true) return
        taskScheduler?.register(LIBRARY_UPDATE_TASK)
        schedulerJob = scope.launch {
            var lastRun = 0L  // 0 means "never run"
            if (taskScheduler?.pendingTasks()?.any { it.id == LIBRARY_UPDATE_TASK.id && it.checkpoint != null } == true) {
                runLibraryUpdate()
                lastRun = System.currentTimeMillis()
            }
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

    fun cancelUpdate(): Boolean {
        val cancelled = taskScheduler?.cancel(LIBRARY_UPDATE_TASK.id) == true
        if (cancelled) taskNotifier?.notify(NotificationEvent.Cancelled(LIBRARY_UPDATE_TASK.id, "Library update cancelled"))
        return cancelled
    }

    private fun parseCategoryIds(raw: String): Set<Long> =
        raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

    private suspend fun runLibraryUpdate() {
        try {
            val allManga = getLibraryManga.await()
            val includeCategories = parseCategoryIds(appPreferences.updateCategoryIncludes.get())
            val excludeCategories = parseCategoryIds(appPreferences.updateCategoryExcludes.get())

            val mangaIds = allManga.map { it.manga.id }
            val filteredIds = if (includeCategories.isEmpty() && excludeCategories.isEmpty()) {
                mangaIds
            } else {
                // Pre-compute category lookup (suspend calls not allowed in filter lambda)
                val catLookup = mutableMapOf<Long, List<Long>>()
                for (id in mangaIds) {
                    catLookup[id] = categoryRepository?.getCategoriesByMangaId(id)
                        ?.map { it.id } ?: emptyList()
                }
                filterMangaForUpdate(
                    mangaIds = mangaIds,
                    mangaCategoryLookup = { catLookup[it] ?: emptyList() },
                    includeCategories = includeCategories,
                    excludeCategories = excludeCategories,
                )
            }
            val filteredIdSet = filteredIds.toSet()

            val sources = sourceManager.getCatalogueSources()
            var newChapters = 0
            val failures = mutableListOf<Throwable>()
            var completed = 0
            for (manga in allManga) {
                if (manga.manga.id !in filteredIdSet) continue
                val source = sources.find { it.id == manga.manga.source } ?: continue
                runCatching {
                    val result = updateChecker.checkForUpdates(manga.manga, source)
                    newChapters += result.newChapterCount
                }.onFailure(failures::add)
                completed++
                taskScheduler?.checkpoint(
                    LIBRARY_UPDATE_TASK.id,
                    TaskCheckpoint(cursor = manga.manga.id.toString(), completedUnits = completed),
                )
            }
            if (failures.isEmpty()) {
                taskScheduler?.complete(LIBRARY_UPDATE_TASK.id)
                taskNotifier?.notify(NotificationEvent.Success(LIBRARY_UPDATE_TASK.id, "Library updated", "$newChapters new chapters found"))
            } else {
                taskNotifier?.notify(NotificationEvent.Failure(LIBRARY_UPDATE_TASK.id, "Library update partially failed", "${failures.size} items can be retried"))
            }
            if (newChapters > 0) {
                notificationService?.post(
                    DesktopNotification(
                        title = "Library updated",
                        message = "$newChapters new chapter${if (newChapters != 1) "s" else ""} found",
                    ),
                )
            }
            creatorDiscoveryService?.discoverDueWatches(sources)?.let { result ->
                if (result.newCandidateCount > 0) {
                    notificationService?.post(
                        DesktopNotification(
                            title = "Author works discovered",
                            message = "${result.newCandidateCount} new candidate${if (result.newCandidateCount != 1) "s" else ""} found",
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            // Silently ignore scheduler errors to keep the loop alive.
        }
    }

    companion object {
        /** How often the scheduler checks whether an update is due. */
        const val CHECK_INTERVAL_MS = 60_000L
        val LIBRARY_UPDATE_TASK = BackgroundTask(
            id = "library-update",
            idempotencyKey = "library-update:scheduled",
            constraints = setOf(TaskConstraint.NetworkConnected),
        )
    }
}
