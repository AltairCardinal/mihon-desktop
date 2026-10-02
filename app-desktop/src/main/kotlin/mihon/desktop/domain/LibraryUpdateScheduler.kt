package mihon.desktop.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.LibraryUnitStatus
import mihon.desktop.task.LibraryUpdateContext
import mihon.desktop.task.LibraryUpdateScope
import mihon.desktop.task.LibraryUpdateTrigger
import mihon.desktop.task.LibraryUpdateUnit
import mihon.desktop.task.StoredTask
import mihon.domain.error.AppError
import mihon.domain.error.toStoredAppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.NotificationEvent
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import mihon.domain.task.TaskStatus
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.selectLibraryMangaForUpdate
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class LibraryUpdateScheduler(
    private val appPreferences: DesktopAppPreferences,
    private val updateChecker: LibraryUpdateChecker?,
    private val getLibraryManga: GetLibraryManga?,
    private val sourceManager: SourceManager?,
    private val creatorDiscoveryScheduler: CreatorDiscoveryScheduler? = null,
    private val taskScheduler: DesktopTaskScheduler? = null,
    private val taskNotifier: DesktopSystemNotifier? = null,
    scope: CoroutineScope? = null,
    private val libraryProvider: (suspend () -> List<LibraryManga>)? = null,
    private val updateManga: (suspend (Manga) -> LibraryUpdateChecker.UpdateResult)? = null,
    private val autoDownload: (suspend (Manga, List<Chapter>) -> Unit)? = null,
    private val categoryPolicy: mihon.desktop.settings.DesktopLibraryCategoryPolicy? = null,
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences? = null,
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
    private val getManga: tachiyomi.domain.manga.interactor.GetManga? = null,
    private val deviceConditions: mihon.desktop.platform.DesktopDeviceConditions? = null,
) {
    private val scope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null
    private var initialRecoveryJob: Job? = null
    private var updateJob: Job? = null
    private val stoppingJobs = mutableSetOf<Job>()
    private val updateLock = Any()
    private val launchFailure = MutableStateFlow<String?>(null)
    val observations: Flow<LibraryUpdateObservation> = combine(
        taskScheduler?.observe(LIBRARY_UPDATE_TASK.id) ?: flowOf(null),
        launchFailure,
    ) { task, failure -> LibraryUpdateObservation(task, failure) }

    fun lastLaunchFailure(): String? = launchFailure.value

    val isRunning: Boolean get() = schedulerJob?.isActive == true

    fun start(): Job = synchronized(updateLock) {
        if (schedulerJob?.isActive == true) return@synchronized requireNotNull(initialRecoveryJob)
        val registered = taskSnapshot()
        val needsInitialRecovery =
            registered?.status in setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed)
        initialRecoveryJob = if (needsInitialRecovery) {
            scope.launch { resumeUpdate().join() }
        } else {
            Job().apply { complete() }
        }
        schedulerJob = scope.launch {
            if (needsInitialRecovery) initialRecoveryJob?.join()
            while (true) {
                delay(CHECK_INTERVAL_MS)
                val intervalMs = libraryPreferences?.autoUpdateInterval()?.get()?.toLong()?.times(3_600_000L)
                    ?: appPreferences.libraryUpdateInterval.get().toMillis()
                if (intervalMs <= 0) continue
                val now = clock.millis()
                val lastCheck = libraryPreferences?.lastUpdatedTimestamp()?.get()
                    ?: taskSnapshot()?.libraryUpdate?.takeUnless {
                        it.scope == LibraryUpdateScope.SINGLE
                    }?.checkStartedAt
                    ?: 0L
                if (lastCheck == 0L || (now >= lastCheck && now - lastCheck >= intervalMs)) {
                    requireNotNull(launchUpdate(null, resume = false, trigger = LibraryUpdateTrigger.SCHEDULED)).join()
                }
            }
        }
        requireNotNull(initialRecoveryJob)
    }

    fun runNow(categoryId: Long? = null): Job = requireNotNull(launchUpdate(categoryId, resume = false))

    fun runSingle(mangaId: Long): Job = requireNotNull(launchUpdate(null, resume = false, singleMangaId = mangaId))

    fun tryRunNow(categoryId: Long? = null): Job? =
        launchUpdate(categoryId, resume = false, rejectBusy = true)

    fun tryRunSingle(mangaId: Long): Job? =
        launchUpdate(null, resume = false, singleMangaId = mangaId, rejectBusy = true)

    fun acceptNow(categoryId: Long? = null): AcceptedLibraryUpdate? = acceptUpdate(categoryId, null)

    fun acceptSingle(mangaId: Long): AcceptedLibraryUpdate? = acceptUpdate(null, mangaId)

    private fun acceptUpdate(categoryId: Long?, mangaId: Long?): AcceptedLibraryUpdate? {
        val result = kotlinx.coroutines.CompletableDeferred<LibraryUpdateObservation>()
        val job = launchUpdate(
            categoryId,
            resume = false,
            singleMangaId = mangaId,
            rejectBusy = true,
            onFinished = { result.complete(it) },
        ) ?: return null
        job.invokeOnCompletion { cause ->
            if (!result.isCompleted) {
                result.complete(
                    LibraryUpdateObservation(null, requestCancelled = cause is CancellationException),
                )
            }
        }
        return AcceptedLibraryUpdate(job, result)
    }

    fun resumeUpdate(): Job = requireNotNull(launchUpdate(null, resume = true))

    fun retryFailed(): Job = requireNotNull(launchUpdate(null, resume = false, failedOnly = true))

    private fun launchUpdate(
        categoryId: Long?,
        resume: Boolean,
        failedOnly: Boolean = false,
        trigger: LibraryUpdateTrigger = LibraryUpdateTrigger.MANUAL,
        singleMangaId: Long? = null,
        rejectBusy: Boolean = false,
        onFinished: ((LibraryUpdateObservation) -> Unit)? = null,
    ): Job? =
        synchronized(updateLock) {
            val previous = updateJob?.takeIf { it.isActive }
            if (previous != null) {
                val waiting = taskSnapshot()?.libraryUpdate?.waitingForDevice?.isNotEmpty() == true
                if (!waiting || trigger != LibraryUpdateTrigger.MANUAL || resume) {
                    return@synchronized if (rejectBusy) null else previous
                }
                taskScheduler?.cancelRunning(LIBRARY_UPDATE_TASK.id)
                if (stoppingJobs.add(previous)) {
                    previous.invokeOnCompletion { synchronized(updateLock) { stoppingJobs.remove(previous) } }
                }
                previous.cancel()
            }
            scope.launch(start = CoroutineStart.LAZY) {
                var started = false
                try {
                    previous?.join()
                    started = true
                    runLibraryUpdate(categoryId, resume, failedOnly, trigger, singleMangaId, onFinished)
                } finally {
                    if (!started) onFinished?.invoke(LibraryUpdateObservation(null, requestCancelled = true))
                }
            }.also {
                updateJob = it
                it.start()
            }
        }

    fun stop() {
        val jobs = synchronized(updateLock) { detachJobs() }
        jobs.forEach(Job::cancel)
    }

    suspend fun stopAndJoin() {
        val jobs = synchronized(updateLock) {
            (detachJobs() + stoppingJobs).distinct()
        }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        synchronized(updateLock) { stoppingJobs.removeAll(jobs.toSet()) }
    }

    private fun detachJobs(): List<Job> =
        listOfNotNull(initialRecoveryJob, updateJob, schedulerJob).distinct().also { jobs ->
            jobs.filter(stoppingJobs::add).forEach { job ->
                job.invokeOnCompletion { synchronized(updateLock) { stoppingJobs.remove(job) } }
            }
            initialRecoveryJob = null
            updateJob = null
            schedulerJob = null
        }

    fun cancelUpdate(): Boolean = synchronized(updateLock) {
        val cancelled = taskScheduler?.cancelRunning(LIBRARY_UPDATE_TASK.id) == true
        if (cancelled) {
            updateJob?.cancel()
            taskNotifier?.notify(NotificationEvent.Cancelled(LIBRARY_UPDATE_TASK.id, "Library update cancelled"))
        }
        cancelled
    }

    fun taskSnapshot(): StoredTask? = taskScheduler?.snapshot(LIBRARY_UPDATE_TASK.id)

    fun currentUpdateJob(): Job? = synchronized(updateLock) { updateJob?.takeIf(Job::isActive) }

    private fun parseCategoryIds(raw: String) = raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    private suspend fun runLibraryUpdate(
        categoryId: Long?,
        resume: Boolean,
        failedOnly: Boolean,
        trigger: LibraryUpdateTrigger,
        singleMangaId: Long?,
        onFinished: ((LibraryUpdateObservation) -> Unit)? = null,
    ) {
        var cancellationObserved = false
        var acceptedOccurrence: String? = null
        launchFailure.value = null
        try {
            var previous = taskSnapshot()
            if (!resume && previous?.libraryUpdate != null) {
                updateChecker?.recoverTaskReceipts(previous.task.idempotencyKey, previous.workset)
                previous = taskSnapshot()
            }
            if (failedOnly && previous?.libraryUpdate?.units?.none { it.status == LibraryUnitStatus.FAILED } == true &&
                (
                    previous.status == TaskStatus.Cancelled ||
                        previous.libraryUpdate.units.any { it.status == LibraryUnitStatus.UNPROCESSED }
                    )
            ) {
                return
            }
            val restore = resume || (
                failedOnly && previous?.libraryUpdate?.units?.none {
                    it.status == LibraryUnitStatus.FAILED
                } == true
                )
            val task = if (restore) {
                requireNotNull(previous).task
            } else {
                LIBRARY_UPDATE_TASK.copy(idempotencyKey = "library-update:${java.util.UUID.randomUUID()}")
            }
            if (restore) acceptedOccurrence = task.idempotencyKey
            val initial = if (restore || failedOnly) {
                val original = requireNotNull(previous?.libraryUpdate) {
                    "This older update has no fixed scope. Start a new library refresh."
                }
                check(
                    requireNotNull(previous).worksetInitialized && previous.workset == original.units.map {
                        it.mangaId
                    },
                )
                if (failedOnly &&
                    !restore
                ) {
                    original.copy(
                        units = original.units.filter { it.status == LibraryUnitStatus.FAILED }
                            .map {
                                it.copy(
                                    status = LibraryUnitStatus.UNPROCESSED,
                                    message = null,
                                    failure = null,
                                    sourceUnavailable = false,
                                    localSource = false,
                                    skipReason = null,
                                )
                            },
                    )
                } else {
                    original
                }
            } else {
                LibraryUpdateContext(
                    trigger,
                    if (singleMangaId !=
                        null
                    ) {
                        LibraryUpdateScope.SINGLE
                    } else if (categoryId ==
                        null
                    ) {
                        LibraryUpdateScope.ALL
                    } else {
                        LibraryUpdateScope.CATEGORY
                    },
                    categoryId = categoryId,
                    units = emptyList(),
                    singleMangaId = singleMangaId,
                )
            }
            if (restore) {
                taskScheduler?.reopen(task.id)
            } else {
                taskScheduler?.beginLibraryUpdate(task, initial, initialized = failedOnly)
            }
            acceptedOccurrence = task.idempotencyKey
            taskScheduler?.start(task.id)
            val single = initial.scope == LibraryUpdateScope.SINGLE
            val targetId = if (single) requireNotNull(initial.singleMangaId) else null
            val target = targetId?.let { requireNotNull(getManga).awaitOrThrow(it) }
            val allManga = if (single) {
                emptyList()
            } else {
                libraryProvider?.invoke()
                    ?: requireNotNull(getLibraryManga).await()
            }
            val byId = allManga.associateBy { it.manga.id }
            val mangasById = if (single) {
                listOfNotNull(target).associateBy(Manga::id)
            } else {
                byId.mapValues {
                    it.value.manga
                }
            }
            var context = if (restore || failedOnly) {
                initial
            } else {
                val policy = if (!single && categoryId == null) categoryPolicy?.snapshot() else null
                val filtered = selectLibraryMangaForUpdate(
                    library = allManga,
                    categoryId = categoryId,
                    includeCategories = if (categoryId != null) {
                        emptySet()
                    } else {
                        policy?.included ?: parseCategoryIds(appPreferences.updateCategoryIncludes.get())
                    },
                    excludeCategories = if (categoryId != null) {
                        emptySet()
                    } else {
                        policy?.excluded ?: parseCategoryIds(appPreferences.updateCategoryExcludes.get())
                    },
                )
                val now = java.time.ZonedDateTime.now(clock)
                val window = tachiyomi.domain.manga.interactor.FetchInterval.window(now)
                initial.copy(
                    units = (
                        if (single) {
                            listOf(
                                requireNotNull(target) {
                                    "The current manga is unavailable"
                                },
                            )
                        } else {
                            filtered.map {
                                it.manga
                            }
                        }
                        ).map { LibraryUpdateUnit(it.id, it.source, it.url, title = it.title) },
                    restrictions = libraryPreferences?.autoUpdateMangaRestrictions()?.get().orEmpty(),
                    deviceRestrictions = libraryPreferences?.autoUpdateDeviceRestrictions()?.get().orEmpty(),
                    fetchWindowUpperBound = window.second,
                    checkStartedAt = clock.millis(),
                )
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!restore && !failedOnly) {
                synchronized(updateLock) {
                    val initialized = taskScheduler?.initializeLibraryUpdate(task.id, task.idempotencyKey, context)
                    if (taskScheduler?.isCancelled(task.id) == true) throw CancellationException()
                    check(initialized != false) { "The original library occurrence cannot begin checking" }
                    if (!single && context.trigger != LibraryUpdateTrigger.SCHEDULED) {
                        libraryPreferences?.lastUpdatedTimestamp()?.set(requireNotNull(context.checkStartedAt))
                    }
                }
            }
            if (restore) {
                updateChecker?.recoverTaskReceipts(task.idempotencyKey, context.units.map { it.mangaId })
                context = taskSnapshot()?.libraryUpdate ?: context
            }
            val failures = mutableListOf<AppError>()
            val failedUnits = mutableListOf<AppError.FailedUnit>()
            var periodicTimestampWritten = false
            for (unit in context.units) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (taskScheduler?.isCancelled(task.id) == true) throw CancellationException()
                if (unit.status in setOf(LibraryUnitStatus.SUCCESS, LibraryUnitStatus.SKIPPED)) continue
                val entry = mangasById[unit.mangaId]
                if (entry == null || (!single && !entry.favorite) || entry.source != unit.sourceId ||
                    entry.url != unit.mangaUrl
                ) {
                    taskScheduler?.recordLibraryUnit(
                        task.id,
                        task.idempotencyKey,
                        unit.copy(
                            status = LibraryUnitStatus.SKIPPED,
                            message = "The original library item is unavailable",
                            failure = null,
                            sourceUnavailable = false,
                        ),
                    )
                    continue
                }
                val local = !single && entry.source == 0L
                val reason = if (single) {
                    null
                } else {
                    tachiyomi.domain.library.service.libraryUpdateSkipReason(
                        requireNotNull(byId[unit.mangaId]),
                        context.restrictions,
                        context.fetchWindowUpperBound,
                    )
                }
                if (local || reason != null) {
                    taskScheduler?.recordLibraryUnit(
                        task.id,
                        task.idempotencyKey,
                        unit.copy(
                            status = LibraryUnitStatus.SKIPPED,
                            skipReason = reason,
                            localSource = local,
                            failure = null,
                            sourceUnavailable = false,
                        ),
                    )
                    continue
                }
                awaitDeviceConditions(task, context, bypass = failedOnly)
                if (!single && context.trigger == LibraryUpdateTrigger.SCHEDULED && !periodicTimestampWritten) {
                    val startedAt = taskSnapshot()?.libraryUpdate?.periodicCheckStartedAt ?: clock.millis()
                    recordDeviceBoundary(task, emptyMap(), startedAt)
                    if (taskScheduler?.isCancelled(task.id) == true) throw CancellationException()
                    libraryPreferences?.lastUpdatedTimestamp()?.let { if (it.get() != startedAt) it.set(startedAt) }
                    periodicTimestampWritten = true
                }
                val completed = taskSnapshot()?.completedUnitIds?.size ?: 0
                taskNotifier?.notify(
                    NotificationEvent.Progress(
                        task.id,
                        "Updating library",
                        if (context.units.isEmpty()) null else completed.toFloat() / context.units.size,
                    ),
                )
                try {
                    val result = update(
                        entry,
                        taskScheduler?.let {
                            tachiyomi.domain.chapter.service.DirectoryTaskReceipt(task.idempotencyKey, unit.mangaId)
                        },
                        single,
                    )
                    val error = result.sourceError ?: result.error?.let { AppError.Unknown(IllegalStateException(it)) }
                    if (error != null) throw LibraryUnitUpdateException(error)
                    autoDownload?.invoke(entry, result.newChapters)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (taskScheduler?.isCancelled(task.id) == true) throw CancellationException()
                    val checkpointed = taskScheduler?.recordLibraryUnit(
                        task.id,
                        task.idempotencyKey,
                        unit.copy(
                            status = LibraryUnitStatus.SUCCESS,
                            newChapterCount = result.newChapterCount,
                            failure = null,
                            sourceUnavailable = false,
                            localSource = false,
                            skipReason = null,
                        ),
                    )
                    check(checkpointed != false) { "The original library occurrence no longer accepts this result" }
                    result.receiptPhase?.let { requireNotNull(updateChecker).acknowledgeTaskReceipt(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val appError = (error as? LibraryUnitUpdateException)?.error ?: AppError.Unknown(error)
                    failures += appError
                    failedUnits += AppError.FailedUnit("manga:${unit.mangaId}", appError)
                    if (taskSnapshot()?.completedUnitIds?.contains(unit.mangaId) != true) {
                        taskScheduler?.recordLibraryUnit(
                            task.id,
                            task.idempotencyKey,
                            unit.copy(
                                status = LibraryUnitStatus.FAILED,
                                failure = appError.toStoredAppError(),
                                sourceUnavailable =
                                unit.sourceId != 0L && sourceManager != null &&
                                    sourceManager.get(unit.sourceId) == null,
                                localSource = entry.source == 0L,
                            ),
                        )
                    }
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (taskScheduler?.isCancelled(task.id) == true) throw CancellationException()
            if (failures.isEmpty()) {
                if (!single && context.trigger == LibraryUpdateTrigger.SCHEDULED &&
                    taskSnapshot()?.libraryUpdate?.periodicCheckStartedAt == null
                ) {
                    val startedAt = clock.millis()
                    recordDeviceBoundary(task, emptyMap(), startedAt)
                    libraryPreferences?.lastUpdatedTimestamp()?.set(startedAt)
                }
                if (taskScheduler?.complete(task.id) == true) {
                    val count = taskSnapshot()?.libraryUpdate?.units?.sumOf { it.newChapterCount } ?: 0
                    taskNotifier?.notify(
                        NotificationEvent.Success(task.id, "Library updated", "$count new chapters found"),
                    )
                }
                creatorDiscoveryScheduler?.runNow()
            } else if (taskScheduler?.fail(task.id, AppError.PartialFailure(failures, failedUnits)) == true) {
                taskNotifier?.notify(
                    NotificationEvent.Failure(
                        task.id,
                        "Library update partially failed",
                        if (failedUnits.size == taskSnapshot()?.failedUnits?.size) {
                            "${taskSnapshot()?.failedUnits?.size ?: 0} items can be retried"
                        } else {
                            "Some completed items need recovery. Resume this update."
                        },
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            cancellationObserved = true
            throw cancelled
        } catch (error: Exception) {
            val existing = taskSnapshot()
            if (acceptedOccurrence != null && existing?.task?.idempotencyKey == acceptedOccurrence &&
                existing.status !in setOf(TaskStatus.Completed, TaskStatus.Cancelled)
            ) {
                try {
                    if (existing.status == TaskStatus.Pending) taskScheduler?.start(LIBRARY_UPDATE_TASK.id)
                    taskScheduler?.fail(LIBRARY_UPDATE_TASK.id, AppError.Unknown(error))
                } catch (storageFailure: Exception) {
                    error.addSuppressed(storageFailure)
                }
            }
            launchFailure.value = error.message ?: "Library update failed"
            taskNotifier?.notify(
                NotificationEvent.Failure(
                    LIBRARY_UPDATE_TASK.id,
                    "Library update failed",
                    "The update could not be started or saved. Retry from Library.",
                ),
            )
        } finally {
            val owned = taskSnapshot()?.takeIf {
                acceptedOccurrence != null &&
                    it.task.idempotencyKey == acceptedOccurrence
            }
            onFinished?.invoke(LibraryUpdateObservation(owned, lastLaunchFailure(), cancellationObserved))
        }
    }

    private class LibraryUnitUpdateException(val error: AppError) : IllegalStateException(error.toString())

    private suspend fun awaitDeviceConditions(
        task: BackgroundTask,
        context: LibraryUpdateContext,
        bypass: Boolean,
    ) {
        if (bypass || context.trigger != LibraryUpdateTrigger.SCHEDULED) return
        val port = deviceConditions ?: return
        val selected = port.supported.filterTo(mutableSetOf()) { it.preferenceKey in context.deviceRestrictions }
        if (selected.isEmpty()) return
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (taskScheduler?.isCancelled(task.id) == true) throw CancellationException()
            val status = port.query()
            val blocked = status.blocking(selected).associate { it.preferenceKey to status.state(it).name }
            recordDeviceBoundary(task, blocked)
            if (blocked.isEmpty()) return
            delay(CHECK_INTERVAL_MS)
        }
    }

    private fun recordDeviceBoundary(task: BackgroundTask, waiting: Map<String, String>, checkingAt: Long? = null) {
        val scheduler = taskScheduler ?: return
        scheduler.recordLibraryDeviceBoundary(task.id, task.idempotencyKey, waiting, checkingAt)
        val actual = scheduler.snapshot(task.id)
        check(
            actual?.status == TaskStatus.Running && actual.task.idempotencyKey == task.idempotencyKey &&
                actual.worksetInitialized && actual.libraryUpdate?.waitingForDevice == waiting &&
                (checkingAt == null || actual.libraryUpdate.periodicCheckStartedAt == checkingAt),
        ) { "The original library occurrence no longer accepts its device state" }
    }

    private suspend fun update(
        manga: Manga,
        receipt: tachiyomi.domain.chapter.service.DirectoryTaskReceipt?,
        single: Boolean,
    ): LibraryUpdateChecker.UpdateResult {
        updateManga?.let { return it(manga) }
        val source = if (single &&
            manga.source == 0L
        ) {
            localDirectorySource()
        } else {
            requireNotNull(sourceManager).get(manga.source)
                ?: return LibraryUpdateChecker.UpdateResult(0, error = "Source unavailable")
        }
        return requireNotNull(
            updateChecker,
        ).checkForUpdates(
            manga,
            source,
            origin = if (single) "DETAIL_REFRESH" else "LIBRARY_UPDATE",
            taskReceipt = receipt,
        )
    }

    /** Only adapts the existing file discovery for the current persisted detail item. */
    private fun localDirectorySource(): eu.kanade.tachiyomi.source.Source = object : eu.kanade.tachiyomi.source.Source {
        override val id = 0L
        override val name = "Local source"
        override fun toString() = name
        override suspend fun getMangaDetails(manga: eu.kanade.tachiyomi.source.model.SManga) = manga
        override suspend fun getChapterList(
            manga: eu.kanade.tachiyomi.source.model.SManga,
        ): List<eu.kanade.tachiyomi.source.model.SChapter> {
            val original = java.io.File(manga.url)
            val path = if (original.isAbsolute) {
                original
            } else {
                java.io.File(
                    appPreferences.localSourceRootDir.get(),
                    manga.url,
                )
            }
            check(path.exists() && path.canRead()) { "The local manga path is unavailable" }
            check(
                if (path.isDirectory) {
                    path.listFiles() != null
                } else {
                    path.extension.lowercase() in
                        setOf("cbz", "zip", "cbr", "rar")
                },
            ) {
                "The local chapter directory cannot be read"
            }
            return mihon.desktop.source.LocalSourceReader.discoverChapters(path).map { local ->
                eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                    url = local.file.absolutePath
                    name = local.name
                    date_upload = local.file.lastModified()
                }
            }
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        val LIBRARY_UPDATE_TASK =
            BackgroundTask("library-update", "library-update:scheduled", setOf(TaskConstraint.NetworkConnected))
    }
}

val TaskStatus.isTerminal: Boolean
    get() = this in setOf(TaskStatus.Completed, TaskStatus.Failed, TaskStatus.Cancelled)

/** The task is durable authority; launchFailure describes a refused request without replacing that authority. */
data class LibraryUpdateObservation(
    val task: StoredTask?,
    val launchFailure: String? = null,
    val requestCancelled: Boolean = false,
)

class AcceptedLibraryUpdate(val job: Job, private val result: kotlinx.coroutines.Deferred<LibraryUpdateObservation>) {
    suspend fun awaitCompletion(): LibraryUpdateObservation {
        job.join()
        return result.await()
    }
}
