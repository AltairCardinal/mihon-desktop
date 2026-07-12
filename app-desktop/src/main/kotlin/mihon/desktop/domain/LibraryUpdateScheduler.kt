package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.StoredTask
import mihon.domain.error.AppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.NotificationEvent
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import mihon.domain.task.TaskStatus
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class LibraryUpdateScheduler(
    private val appPreferences: DesktopAppPreferences,
    private val updateChecker: LibraryUpdateChecker?,
    private val getLibraryManga: GetLibraryManga?,
    private val sourceManager: SourceManager?,
    private val categoryRepository: CategoryRepository? = null,
    private val notificationService: DesktopNotificationService? = null,
    private val creatorDiscoveryService: CreatorDiscoveryService? = null,
    private val taskScheduler: DesktopTaskScheduler? = null,
    private val taskNotifier: DesktopSystemNotifier? = null,
    scope: CoroutineScope? = null,
    private val libraryProvider: (suspend () -> List<LibraryManga>)? = null,
    private val updateManga: (suspend (Manga) -> LibraryUpdateChecker.UpdateResult)? = null,
) {
    private val scope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null
    private var updateJob: Job? = null
    val isRunning: Boolean get() = schedulerJob?.isActive == true

    fun start() {
        if (schedulerJob?.isActive == true) return
        val registered = taskScheduler?.register(LIBRARY_UPDATE_TASK)
        schedulerJob = scope.launch {
            var lastRun = 0L
            if (registered?.status in setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed)) {
                runNow().join()
                lastRun = System.currentTimeMillis()
            }
            while (true) {
                delay(CHECK_INTERVAL_MS)
                val intervalMs = appPreferences.libraryUpdateInterval.get().toMillis()
                if (intervalMs <= 0) continue
                val now = System.currentTimeMillis()
                if (lastRun == 0L || now - lastRun >= intervalMs) {
                    lastRun = now
                    runNow().join()
                }
            }
        }
    }

    fun runNow(): Job {
        updateJob?.takeIf { it.isActive }?.let { return it }
        val existing = taskSnapshot()
        val task = if (existing?.status in setOf(TaskStatus.Completed, TaskStatus.Cancelled)) {
            LIBRARY_UPDATE_TASK.copy(idempotencyKey = "library-update:${System.nanoTime()}")
        } else {
            LIBRARY_UPDATE_TASK
        }
        taskScheduler?.register(task)
        taskScheduler?.start(LIBRARY_UPDATE_TASK.id)
        return scope.launch { runLibraryUpdate() }.also { updateJob = it }
    }

    fun stop() {
        updateJob?.cancel()
        schedulerJob?.cancel()
        updateJob = null
        schedulerJob = null
    }

    fun cancelUpdate(): Boolean {
        val cancelled = taskScheduler?.cancel(LIBRARY_UPDATE_TASK.id) == true
        if (cancelled) {
            updateJob?.cancel()
            taskNotifier?.notify(NotificationEvent.Cancelled(LIBRARY_UPDATE_TASK.id, "Library update cancelled"))
        }
        return cancelled
    }

    fun taskSnapshot(): StoredTask? = taskScheduler?.snapshot(LIBRARY_UPDATE_TASK.id)

    private fun parseCategoryIds(raw: String) = raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    private suspend fun runLibraryUpdate() {
        try {
            val allManga = libraryProvider?.invoke() ?: requireNotNull(getLibraryManga).await()
            val filtered = filterLibrary(allManga)
            val checkpoint = taskSnapshot()?.task?.checkpoint
            val remaining = checkpoint?.cursor?.toLongOrNull()?.let { cursor ->
                filtered.dropWhile { it.manga.id != cursor }.drop(1)
            } ?: filtered
            var completed = checkpoint?.completedUnits ?: 0
            var newChapters = 0
            val failures = mutableListOf<AppError>()
            val failedUnits = mutableListOf<AppError.FailedUnit>()
            for (entry in remaining) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (taskScheduler?.isCancelled(LIBRARY_UPDATE_TASK.id) == true) throw CancellationException()
                taskNotifier?.notify(
                    NotificationEvent.Progress(
                        LIBRARY_UPDATE_TASK.id,
                        "Updating library",
                        if (filtered.isEmpty()) null else completed.toFloat() / filtered.size,
                    ),
                )
                val result = runCatching { update(entry.manga) }
                result.onSuccess { updateResult ->
                    if (updateResult.error == null) {
                        newChapters += updateResult.newChapterCount
                        completed++
                        taskScheduler?.checkpoint(
                            LIBRARY_UPDATE_TASK.id,
                            TaskCheckpoint(entry.manga.id.toString(), completed, completed.toFloat() / filtered.size),
                        )
                    } else {
                        val error = AppError.Unknown(IllegalStateException(updateResult.error))
                        failures += error
                        failedUnits += AppError.FailedUnit("manga:${entry.manga.id}", error)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    val appError = AppError.Unknown(error)
                    failures += appError
                    failedUnits += AppError.FailedUnit("manga:${entry.manga.id}", appError)
                }
                if (failures.isNotEmpty()) break
            }
            if (failures.isEmpty()) {
                taskScheduler?.complete(LIBRARY_UPDATE_TASK.id)
                taskNotifier?.notify(NotificationEvent.Success(LIBRARY_UPDATE_TASK.id, "Library updated", "$newChapters new chapters found"))
                sourceManager?.getCatalogueSources()?.let { sources ->
                    creatorDiscoveryService?.discoverDueWatches(sources)?.let { result ->
                        if (result.newCandidateCount > 0) {
                            notificationService?.post(
                                DesktopNotification(
                                    "Author works discovered",
                                    "${result.newCandidateCount} new candidates found",
                                ),
                            )
                        }
                    }
                }
            } else {
                val error = AppError.PartialFailure(failures, failedUnits)
                taskScheduler?.fail(LIBRARY_UPDATE_TASK.id, error)
                taskNotifier?.notify(NotificationEvent.Failure(LIBRARY_UPDATE_TASK.id, "Library update partially failed", "${failures.size} items can be retried"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val appError = AppError.Unknown(error)
            taskScheduler?.fail(LIBRARY_UPDATE_TASK.id, appError)
            taskNotifier?.notify(NotificationEvent.Failure(LIBRARY_UPDATE_TASK.id, "Library update failed", "Retry from Library"))
        }
    }

    private suspend fun filterLibrary(allManga: List<LibraryManga>): List<LibraryManga> {
        val includes = parseCategoryIds(appPreferences.updateCategoryIncludes.get())
        val excludes = parseCategoryIds(appPreferences.updateCategoryExcludes.get())
        if (includes.isEmpty() && excludes.isEmpty()) return allManga
        val ids = allManga.map { it.manga.id }
        val lookup = ids.associateWith { id -> categoryRepository?.getCategoriesByMangaId(id)?.map { it.id }.orEmpty() }
        val allowed = filterMangaForUpdate(ids, { lookup[it].orEmpty() }, includes, excludes).toSet()
        return allManga.filter { it.manga.id in allowed }
    }

    private suspend fun update(manga: Manga): LibraryUpdateChecker.UpdateResult {
        updateManga?.let { return it(manga) }
        val source: CatalogueSource = requireNotNull(sourceManager).getCatalogueSources().find { it.id == manga.source }
            ?: return LibraryUpdateChecker.UpdateResult(0, error = "Source unavailable")
        return requireNotNull(updateChecker).checkForUpdates(manga, source)
    }

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        val LIBRARY_UPDATE_TASK = BackgroundTask("library-update", "library-update:scheduled", setOf(TaskConstraint.NetworkConnected))
    }
}

val TaskStatus.isTerminal: Boolean
    get() = this in setOf(TaskStatus.Completed, TaskStatus.Failed, TaskStatus.Cancelled)
