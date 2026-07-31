package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
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
import tachiyomi.domain.chapter.model.Chapter
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
    private val discoverCreators: (suspend () -> Unit)? = null,
    private val autoDownload: (suspend (Manga, List<Chapter>) -> Unit)? = null,
) {
    private val scope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null
    private var initialRecoveryJob: Job? = null
    private var updateJob: Job? = null
    private val updateLock = Any()
    val isRunning: Boolean get() = schedulerJob?.isActive == true

    fun start(): Job = synchronized(updateLock) {
        if (schedulerJob?.isActive == true) return@synchronized requireNotNull(initialRecoveryJob)
        val registered = taskScheduler?.register(LIBRARY_UPDATE_TASK)
        val needsInitialRecovery = registered?.status in setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed)
        initialRecoveryJob = if (needsInitialRecovery) {
            scope.launch { runNow().join() }
        } else {
            Job().apply { complete() }
        }
        schedulerJob = scope.launch {
            var lastRun = 0L
            if (needsInitialRecovery) {
                initialRecoveryJob?.join()
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
        requireNotNull(initialRecoveryJob)
    }

    fun runNow(): Job = synchronized(updateLock) {
        updateJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val existing = taskSnapshot()
        val task = if (existing?.status in setOf(TaskStatus.Completed, TaskStatus.Failed, TaskStatus.Cancelled)) {
            LIBRARY_UPDATE_TASK.copy(idempotencyKey = "library-update:${System.nanoTime()}")
        } else {
            LIBRARY_UPDATE_TASK
        }
        taskScheduler?.register(task)
        taskScheduler?.start(LIBRARY_UPDATE_TASK.id)
        scope.launch(start = CoroutineStart.LAZY) { runLibraryUpdate() }.also {
            updateJob = it
            it.start()
        }
    }

    fun stop() {
        initialRecoveryJob?.cancel()
        updateJob?.cancel()
        schedulerJob?.cancel()
        initialRecoveryJob = null
        updateJob = null
        schedulerJob = null
    }

    suspend fun stopAndJoin() {
        val jobs = synchronized(updateLock) {
            listOfNotNull(initialRecoveryJob, updateJob, schedulerJob).distinct().also {
                initialRecoveryJob = null
                updateJob = null
                schedulerJob = null
            }
        }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    fun cancelUpdate(): Boolean {
        val cancelled = taskScheduler?.cancelRunning(LIBRARY_UPDATE_TASK.id) == true
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
            taskScheduler?.setWorkset(LIBRARY_UPDATE_TASK.id, filtered.map { it.manga.id })
            val snapshot = taskSnapshot()
            val completedIds = snapshot?.completedUnitIds.orEmpty()
            val stableIds = if (snapshot?.worksetInitialized == true) snapshot.workset else filtered.map { it.manga.id }
            val totalUnits = stableIds.size
            val byId = filtered.associateBy { it.manga.id }
            val remaining = stableIds.filterNot(completedIds::contains).mapNotNull(byId::get)
            var completed = completedIds.size
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
                        if (totalUnits == 0) null else completed.toFloat() / totalUnits,
                    ),
                )
                val result = runCatching { update(entry.manga) }
                result.onSuccess { updateResult ->
                    if (updateResult.error == null && updateResult.sourceError == null) {
                        autoDownload?.invoke(entry.manga, updateResult.newChapters)
                        newChapters += updateResult.newChapterCount
                        completed++
                        taskScheduler?.completeUnit(
                            LIBRARY_UPDATE_TASK.id,
                            entry.manga.id,
                            TaskCheckpoint(entry.manga.id.toString(), completed, completed.toFloat() / totalUnits),
                        )
                    } else {
                        val error = updateResult.sourceError
                            ?: AppError.Unknown(IllegalStateException(requireNotNull(updateResult.error)))
                        failures += error
                        failedUnits += AppError.FailedUnit("manga:${entry.manga.id}", error)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    val appError = AppError.Unknown(error)
                    failures += appError
                    failedUnits += AppError.FailedUnit("manga:${entry.manga.id}", appError)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (taskScheduler?.isCancelled(LIBRARY_UPDATE_TASK.id) == true) throw CancellationException()
                if (failures.isNotEmpty()) break
            }
            if (failures.isEmpty()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (taskScheduler?.isCancelled(LIBRARY_UPDATE_TASK.id) == true) throw CancellationException()
                if (taskScheduler?.complete(LIBRARY_UPDATE_TASK.id) == true) {
                    taskNotifier?.notify(NotificationEvent.Success(LIBRARY_UPDATE_TASK.id, "Library updated", "$newChapters new chapters found"))
                }
                runCatching {
                    discoverCreators?.invoke() ?: sourceManager?.getCatalogueSources()?.let { sources ->
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
                }
            } else {
                val error = AppError.PartialFailure(failures, failedUnits)
                if (taskScheduler?.fail(LIBRARY_UPDATE_TASK.id, error) == true) {
                    taskNotifier?.notify(
                        NotificationEvent.Failure(
                            LIBRARY_UPDATE_TASK.id,
                            "Library update partially failed",
                            "${failures.size} items can be retried",
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val appError = AppError.Unknown(error)
            if (taskScheduler?.fail(LIBRARY_UPDATE_TASK.id, appError) == true) {
                taskNotifier?.notify(
                    NotificationEvent.Failure(
                        LIBRARY_UPDATE_TASK.id,
                        "Library update failed",
                        "Retry from Library",
                    ),
                )
            }
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
