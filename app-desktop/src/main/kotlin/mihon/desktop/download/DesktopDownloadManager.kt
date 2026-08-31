package mihon.desktop.download

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.download.DownloadQueueStateMachine
import mihon.domain.download.DownloadRepository
import mihon.domain.error.AppError
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.desktop.domain.DesktopSystemNotifier
import mihon.domain.task.NotificationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.update
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.Response
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.data.download.PersistentDownloadStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the chapter download queue.
 *
 * Follows Android Mihon's download lifecycle:
 * 1. Downloads go into a `_tmp` directory
 * 2. On success, `_tmp` is renamed to the final directory
 * 3. On cancel/error, `_tmp` is cleaned up
 * 4. Only final directories (without `_tmp`) are considered "downloaded"
 */
class DesktopDownloadManager(
    private val provider: DesktopDownloadProvider,
    private val networkHelper: NetworkHelper? = null, // null = inject lazily at runtime
    private val downloadPreferences: DesktopDownloadPreferences? = null, // null = inject lazily
    /** Injectable scope for testing. Production uses Dispatchers.IO. */
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val store: PersistentDownloadStore? = null,
    private val stateMachine: DownloadQueueStateMachine = DownloadQueueStateMachine(),
    private val httpClient: OkHttpClient? = null,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val chapterPackager: (File, File) -> Boolean = CbzCreator::create,
    private val artifactCleaner: (File) -> Boolean = { artifact -> artifact.deleteRecursively() },
    private val queuePersister: ((List<DownloadQueueEntry>) -> Unit)? = null,
    private val retirementAwaitObserver: (Long) -> Unit = {},
    private val retirementCleanupAwaitObserver: (Long) -> Unit = {},
    private val retirementCleanupFinishedObserver: (Long, Boolean) -> Unit = { _, _ -> },
    private val fileOperations: DownloadFileOperations = DefaultDownloadFileOperations,
    private val taskNotifier: DesktopSystemNotifier? = null,
    private val sourceResolver: (Long) -> CatalogueSource? = { sourceId ->
        Injekt.get<SourceManager>().getCatalogueSources().find { it.id == sourceId }
    },
    private val sourceCallTimeoutMs: Long = 30_000L,
    private val downloadIdentityResolver: suspend (DownloadItem) -> DownloadChapterIdentity? = { null },
) : DownloadRepository, DesktopDownloadQueuePort {
    private data class DownloadAttempt(
        val item: DownloadItem,
        val generation: Long,
    ) {
        val key = DownloadAttemptKey(item.chapterId, generation)
    }

    private data class DownloadAttemptKey(
        val chapterId: Long,
        val generation: Long,
    )

    private data class ChapterRetirement(
        val key: DownloadAttemptKey,
        val item: DownloadItem,
        val artifacts: List<File>,
        val producerDone: CompletableDeferred<Unit>,
        val completion: CompletableDeferred<RetirementCleanupResult> = CompletableDeferred(),
    )

    private data class RetirementCleanupResult(
        val success: Boolean,
        val error: Throwable? = null,
    )

    private data class CancelResult(
        val retirement: ChapterRetirement,
        val startCleanup: Boolean,
    )

    private val lifecycleLock = Any()
    private val queueStateLock = Any()
    private var stopped = false
    private var workerJob: Job? = null
    private val activeJobs = mutableSetOf<Job>()
    private val generationSequence = AtomicLong()
    private val recoveredItems = store?.recover()?.map { it.toItem() } ?: emptyList()
    private val queueGenerations = recoveredItems.associate { it.chapterId to nextGeneration() }.toMutableMap()
    private val resolvedDownloadIdentities = mutableMapOf<DownloadAttemptKey, DownloadChapterIdentity>()
    private val activeProducers = mutableMapOf<DownloadAttemptKey, CompletableDeferred<Unit>>()
    private val retirementsByChapter = mutableMapOf<Long, ChapterRetirement>()
    private val _queue = MutableStateFlow(recoveredItems)
    override val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()
    private val _failures = MutableStateFlow(recoveredItems.mapNotNull { item -> item.failure?.let { item.chapterId to it } }.toMap())
    val failures: StateFlow<Map<Long, AppError>> = _failures.asStateFlow()
    internal val activeJobCount: Int
        get() = workerScope.coroutineContext[Job]?.children?.count() ?: 0
    override val queueEntries = queue.map { items -> items.mapIndexed { index, item -> item.toEntry(index.toLong()) } }

    private val _isPaused = MutableStateFlow(false)
    /** True when downloads are paused by the user. */
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    /** Add a chapter to the download queue (no-op if already queued or downloaded). */
    fun enqueue(item: DownloadItem) = synchronized(queueStateLock) {
        val current = _queue.value
        if (current.any { it.chapterId == item.chapterId }) return@synchronized
        val hasRetiringAttempt = retirementsByChapter.containsKey(item.chapterId)
        if (!hasRetiringAttempt && provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName)) {
            return@synchronized
        }
        // A retirement owns the shared paths until its cleanup gate opens. Fresh attempts retain
        // the historical eager cleanup behavior when no old producer can still touch the paths.
        if (!hasRetiringAttempt) {
            provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        }
        queueGenerations[item.chapterId] = nextGeneration()
        _queue.value = current + item
        persistQueue()
    }

    override fun enqueue(entry: DownloadQueueEntry) = enqueue(entry.toItem())

    /** Remove a queued item by chapter ID and clean up its _tmp directory. */
    override fun cancel(chapterId: Long): Boolean {
        val result = synchronized(queueStateLock) {
            val item = _queue.value.find { it.chapterId == chapterId }
            val generation = queueGenerations[chapterId]
            if (
                item == null ||
                generation == null ||
                stateMachine.transition(item.toEntry(0), DownloadQueueStatus.CANCELLED) == null
            ) {
                null
            } else {
                val nextQueue = _queue.value.filterNot { it.chapterId == chapterId }
                try {
                    persistQueue(nextQueue)
                } catch (_: Exception) {
                    return@synchronized null
                }
                val key = DownloadAttemptKey(chapterId, generation)
                val identity = resolvedDownloadIdentities.remove(key)
                val existingRetirement = retirementsByChapter[chapterId]
                val retirement = if (existingRetirement?.completion?.isCompleted == false) {
                    existingRetirement
                } else {
                    ChapterRetirement(
                        key = key,
                        item = item,
                        artifacts = (existingRetirement?.artifacts.orEmpty() + retirementArtifacts(item, identity))
                            .distinctBy(File::getAbsolutePath),
                        producerDone = activeProducers[key] ?: completedProducerSignal(),
                    ).also { retirementsByChapter[chapterId] = it }
                }
                _queue.value = nextQueue
                queueGenerations.remove(chapterId)
                _failures.update { it - chapterId }
                CancelResult(
                    retirement = retirement,
                    startCleanup = retirement !== existingRetirement,
                )
            }
        } ?: return false

        // Cancellation commits queue state promptly. Disk cleanup is serialized behind the old
        // producer and never runs on the caller/UI thread.
        if (result.startCleanup) launchRetirementCleanup(result.retirement)
        return true
    }

    /** Cancel and clear the entire queue, cleaning up all _tmp directories. */
    fun cancelAll() {
        _queue.value.map { it.chapterId }.forEach(::cancel)
    }

    /** Remove all items with ERROR status and clean up their _tmp directories. */
    fun clearErrors() {
        _queue.value.filter { it.status == DownloadStatus.ERROR }.map { it.chapterId }.forEach(::cancel)
    }

    /** Reset all ERROR items back to QUEUED so they will be retried. */
    fun retryErrors() {
        _queue.value.filter { it.status == DownloadStatus.ERROR }.forEach { retry(it.chapterId) }
    }

    /** Reset a single ERROR item back to QUEUED. */
    fun retryItem(chapterId: Long) { retry(chapterId) }

    override fun retry(chapterId: Long): Boolean = transition(chapterId, DownloadQueueStatus.QUEUED)

    override fun transition(chapterId: Long, target: DownloadQueueStatus): Boolean = synchronized(queueStateLock) {
        transitionLocked(chapterId, target, expectedGeneration = null)
    }

    private fun transitionLocked(
        chapterId: Long,
        target: DownloadQueueStatus,
        expectedGeneration: Long?,
    ): Boolean {
        val currentGeneration = queueGenerations[chapterId] ?: return false
        if (expectedGeneration != null && currentGeneration != expectedGeneration) return false
        var changed = false
        val nextQueue = _queue.value.map { item ->
            if (item.chapterId != chapterId) item else stateMachine.transition(item.toEntry(0), target)?.toItem()
                ?.let { transitioned -> if (target == DownloadQueueStatus.QUEUED) transitioned.copy(failure = null, retryCount = 0) else transitioned }
                ?.also { changed = true } ?: item
        }
        if (changed) {
            try {
                persistQueue(nextQueue)
            } catch (error: Exception) {
                // A persistent storage outage must not leave an already-finished producer stuck
                // as DOWNLOADING for the rest of the process. ERROR remains retryable and recovery
                // normalizes any older persisted DOWNLOADING row after restart.
                if (target != DownloadQueueStatus.ERROR) throw error
            }
            _queue.value = nextQueue
            if (target == DownloadQueueStatus.QUEUED) {
                resolvedDownloadIdentities.remove(DownloadAttemptKey(chapterId, currentGeneration))
                queueGenerations[chapterId] = nextGeneration()
                _failures.update { it - chapterId }
            }
        }
        return changed
    }

    override fun recover(): List<DownloadQueueEntry> = synchronized(queueStateLock) {
        stateMachine.recover(
            _queue.value.mapIndexed { index, item -> item.toEntry(index.toLong()) },
        )
    }

    /**
     * Move a queue item from [from] index to [to] index.
     * DOWNLOADING items are included in the index space so drag handles
     * feel contiguous. Does nothing if indices are equal or out of bounds.
     */
    fun reorderItem(from: Int, to: Int) = synchronized(queueStateLock) {
        if (from == to) return@synchronized
        _queue.value = _queue.value.let { items ->
            if (from !in items.indices || to !in items.indices) return@let items
            val sourceId = items[from].sourceId
            if (items[to].sourceId != sourceId) return@let items
            val sourceIndices = items.indices.filter { items[it].sourceId == sourceId }
            val fromInSource = sourceIndices.indexOf(from)
            val toInSource = sourceIndices.indexOf(to)
            if (fromInSource < 0 || toInSource < 0) return@let items
            val reorderedSource = sourceIndices.map(items::get).toMutableList().apply {
                add(toInSource, removeAt(fromInSource))
            }
            val mutable = items.toMutableList()
            sourceIndices.forEachIndexed { index, queueIndex -> mutable[queueIndex] = reorderedSource[index] }
            mutable
        }
        persistQueue()
    }

    /** Sort each source group by a key selector while preserving source group order. */
    fun <R : Comparable<R>> sortQueue(selector: (DownloadItem) -> R) = synchronized(queueStateLock) {
        _queue.value = _queue.value.let { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.sortedBy(selector)
            }
        }
        persistQueue()
    }

    /** Sort each source group by a comparator while preserving source group order. */
    fun sortQueue(comparator: Comparator<DownloadItem>) = synchronized(queueStateLock) {
        _queue.value = _queue.value.let { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.sortedWith(comparator)
            }
        }
        persistQueue()
    }

    /** Reverse items inside each source group while preserving source group order. */
    fun reverseQueue() = synchronized(queueStateLock) {
        _queue.value = _queue.value.let { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.reversed()
            }
        }
        persistQueue()
    }

    /** Pause the download worker (no new downloads will start). */
    fun pauseAll() { _isPaused.value = true }

    /** Resume the download worker. */
    fun resumeAll() { _isPaused.value = false }

    /** Delete the on-disk files for a downloaded chapter. */
    fun deleteDownload(sourceId: Long, mangaTitle: String, chapterName: String) {
        provider.deleteChapterDownload(sourceId, mangaTitle, chapterName)
    }

    fun deleteDownload(sourceId: Long, identity: DownloadChapterIdentity) {
        provider.deleteChapterDownload(sourceId, identity)
    }

    /** Delegates to [DesktopDownloadProvider]. */
    override fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean =
        provider.isChapterDownloaded(sourceId, mangaTitle, chapterName)

    override fun isDownloaded(sourceId: Long, identity: DownloadChapterIdentity): Boolean =
        provider.isChapterDownloaded(sourceId, identity)

    /**
     * Start the background worker. Call once at app startup.
     *
     * Watches the queue StateFlow. Whenever the queue transitions from
     * "no QUEUED items" → "has QUEUED items", triggers [drainQueue].
     *
     * Returns the [Job] so callers (e.g. tests) can cancel it when done.
     * In production the job runs for the lifetime of the process.
     */
    fun start(): Job = synchronized(lifecycleLock) {
        workerJob?.let { return@synchronized it }
        if (stopped) return@synchronized Job().apply { cancel() }
        workerScope.launch(start = CoroutineStart.LAZY) {
            _queue
                .map { items -> items.any { it.status == DownloadStatus.QUEUED } }
                .distinctUntilChanged()
                .filter { hasQueued -> hasQueued }
                .collect { drainQueue() }
        }.also { job ->
            workerJob = job
            job.invokeOnCompletion { synchronized(lifecycleLock) { if (workerJob === job) workerJob = null } }
            job.start()
        }
    }

    /** Stops the worker and active downloads without waiting for cancellation cleanup. */
    fun stop() {
        val jobs = synchronized(lifecycleLock) {
            stopped = true
            (listOfNotNull(workerJob) + activeJobs).distinct()
        }
        jobs.forEach { it.cancel() }
    }

    /** Stops the worker and every active download, waiting until no old task can mutate the queue. */
    suspend fun stopAndJoin() {
        val jobs = snapshotJobsForStop()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    private fun snapshotJobsForStop(): List<Job> = synchronized(lifecycleLock) {
        stopped = true
        (listOfNotNull(workerJob) + activeJobs).distinct().also {
            workerJob = null
            activeJobs.clear()
        }
    }

    /** Process every QUEUED item until none remain, respecting parallel download limit. */
    private suspend fun drainQueue() {
        val limit = (downloadPreferences ?: runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull())
            ?.parallelDownloadLimit?.get()?.coerceIn(1, 5) ?: 1
        while (true) {
            val batch = synchronized(queueStateLock) {
                stateMachine.schedule(
                    _queue.value.mapIndexed { index, queued -> queued.toEntry(index.toLong()) },
                    limit = limit,
                ).mapNotNull { entry ->
                    queueGenerations[entry.chapterId]?.let { generation ->
                        DownloadAttempt(entry.toItem(), generation)
                    }
                }
            }
            if (batch.isEmpty()) break
            val jobs = batch.mapNotNull { attempt ->
                val job = workerScope.launch(start = CoroutineStart.LAZY) {
                    val success = downloadChapter(attempt)
                    if (!success && !isStopped()) {
                        setStatus(attempt, DownloadStatus.ERROR)
                    }
                }
                synchronized(lifecycleLock) {
                    if (stopped || !startAttempt(attempt)) {
                        job.cancel()
                        null
                    } else {
                        activeJobs += job
                        job.invokeOnCompletion {
                            finishProducer(attempt)
                            synchronized(lifecycleLock) { activeJobs -= job }
                        }
                        job.start()
                        job
                    }
                }
            }
            jobs.forEach { it.join() }
        }
    }

    /**
     * Downloads a chapter using the _tmp directory pattern (mirrors Android Downloader):
     * 1. Create/reuse _tmp directory
     * 2. Download each page as {page}.tmp, then rename to {page}.{ext}
     * 3. On success, rename _tmp dir to final dir
     * 4. On failure, _tmp dir remains (will be cleaned on cancel/retry)
     */
    private suspend fun downloadChapter(attempt: DownloadAttempt): Boolean {
        val item = attempt.item
        return try {
            if (!awaitRetirement(attempt)) return false
            val downloadIdentity = downloadIdentityResolver(item)
            if (downloadIdentity != null && !registerIdentity(attempt, downloadIdentity)) return false
            if (!isCurrentAttempt(attempt)) return false
            if (downloadIdentity != null && provider.isChapterDownloaded(item.sourceId, downloadIdentity)) {
                return completeAttempt(attempt)
            }
            val client = httpClient
                ?: networkHelper?.clientForSource(item.sourceId)
                ?: Injekt.get<NetworkHelper>().clientForSource(item.sourceId)
            // Resolve page URLs if not pre-provided
            val urls = when {
                item.pageUrls.isNotEmpty() -> item.pageUrls
                item.chapterUrl.isNotBlank() -> {
                    val source = sourceResolver(item.sourceId) ?: return fail(
                        attempt,
                        AppError.Unknown(IllegalStateException("Source ${item.sourceId} is unavailable")),
                    )
                    val sChapter = SChapter.create().apply {
                        url = item.chapterUrl
                        name = item.chapterName
                    }
                    val pagesResult = safeSourceCall(timeoutMs = sourceCallTimeoutMs) { source.getPageList(sChapter) }
                    when (pagesResult) {
                        is SourceCallResult.Success -> pagesResult.value.mapNotNull { it.imageUrl }
                        is SourceCallResult.Timeout -> return fail(attempt, pagesResult.error)
                        is SourceCallResult.Error -> return fail(
                            attempt,
                            pagesResult.error,
                        )
                    }
                }
                else -> return fail(
                    attempt,
                    AppError.MalformedData(IllegalArgumentException("Chapter URL is missing")),
                )
            }
            if (urls.isEmpty()) return fail(
                attempt,
                AppError.MalformedData(IllegalStateException("Source returned no downloadable pages")),
            )

            // Update queue item with resolved URL count so progress display is accurate
            if (!updateAttempt(attempt) { it.copy(pageUrls = urls) }) return false

            // Use _tmp directory for in-progress download (Android pattern)
            val tmpDir = downloadIdentity?.let(provider::canonicalChapterTmpDir)
                ?: provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
            if (!isCurrentAttempt(attempt)) return false
            tmpDir.mkdirs()
            // Clean up any leftover .tmp files from previous partial downloads.
            // No replacement worker can start until this attempt has returned.
            tmpDir.listFiles()
                ?.filter { it.extension == "tmp" }
                ?.forEach { it.delete() }
            if (!isCurrentAttempt(attempt)) {
                tmpDir.deleteRecursively()
                return false
            }

            urls.forEachIndexed { index, url ->
                if (!isCurrentAttempt(attempt)) return false

                val baseName = "%03d".format(index + 1)
                val attemptTmpFile = File(tmpDir, "$baseName.${attempt.generation}.tmp")

                // Skip if this page was already fully downloaded in a previous attempt
                val alreadyDownloaded = tmpDir.listFiles()
                    ?.any {
                        it.nameWithoutExtension == baseName &&
                            it.extension != "tmp" &&
                            provider.isValidDownloadedImage(it)
                    }
                    ?: false
                if (!isCurrentAttempt(attempt)) {
                    tmpDir.deleteRecursively()
                    return false
                }

                if (!alreadyDownloaded) {
                    // Download to .tmp file first
                    val finalFile = File(tmpDir, "$baseName.${extensionFromUrl(url)}")
                    var pageDownloaded = false
                    var retryAttempt = 0
                    var lastError: Throwable? = null
                    while (!pageDownloaded) {
                        pageDownloaded = try {
                            val bytes = fileOperations.execute(client, url).use { response ->
                                val bytes = fileOperations.readBody(response)
                                if (!isCurrentAttempt(attempt)) return false
                                bytes
                            }
                            fileOperations.writePage(attemptTmpFile, bytes)
                            if (!isCurrentAttempt(attempt)) {
                                tmpDir.deleteRecursively()
                                return false
                            }
                            finalFile.delete()
                            fileOperations.renamePage(attemptTmpFile, finalFile)
                            val valid = provider.isValidDownloadedImage(finalFile)
                            if (!isCurrentAttempt(attempt)) {
                                tmpDir.deleteRecursively()
                                return false
                            }
                            valid
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            lastError = error
                            false
                        }
                        if (!pageDownloaded) {
                            attemptTmpFile.delete()
                            finalFile.delete()
                            if (!isCurrentAttempt(attempt)) {
                                tmpDir.deleteRecursively()
                                return false
                            }
                            val wait = stateMachine.retryDelayMillis(retryAttempt++) ?: break
                            if (!updateRetryCount(attempt, retryAttempt)) return false
                            retryDelay(wait)
                        }
                    }
                    if (!pageDownloaded) {
                        recordFailure(attempt, lastError.toAppError())
                        attemptTmpFile.delete()
                        finalFile.delete()
                        if (!isCurrentAttempt(attempt)) {
                            tmpDir.deleteRecursively()
                            return false
                        }
                        return false
                    }
                }

                if (!updateAttempt(attempt) { it.copy(progress = index + 1, retryCount = 0) }) return false
                clearFailure(attempt)
            }

            // All pages downloaded — rename _tmp to final directory
            val finalDir = downloadIdentity?.let(provider::canonicalChapterDownloadDir)
                ?: provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
            val prefs = downloadPreferences ?: runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull()
            if (!isCurrentAttempt(attempt)) return false
            run {
                var renamed = false
                var renameAttempt = 0
                var renameError: Throwable? = null
                while (!renamed) {
                    renamed = try {
                        if (!isCurrentAttempt(attempt)) return false
                        finalDir.deleteRecursively()
                        if (!isCurrentAttempt(attempt)) return false
                        fileOperations.renameChapter(tmpDir, finalDir)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        renameError = error
                        false
                    }
                    if (!renamed) {
                        val wait = stateMachine.retryDelayMillis(renameAttempt++) ?: break
                        if (!updateRetryCount(attempt, renameAttempt)) return false
                        retryDelay(wait)
                    }
                }
                if (!renamed) {
                    recordFailure(attempt, AppError.Storage(renameError))
                    return false
                }

                if (!isCurrentAttempt(attempt)) return false
                try {
                    if (prefs?.downloadAsCbz?.get() == true) {
                        val cbzFile = CbzCreator.defaultOutputFile(finalDir)
                        val packed = chapterPackager(finalDir, cbzFile)
                        if (packed) {
                            // Remove individual image files — CBZ replaces them
                            finalDir.deleteRecursively()
                        }
                    }
                    if (!isCurrentAttempt(attempt)) return false
                    completeAttempt(attempt)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    recordFailure(attempt, error.toAppError())
                    false
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordFailure(attempt, error.toAppError())
            // _tmp directory remains on disk but won't be counted as "downloaded"
            false
        }
    }

    private fun isStopped(): Boolean = synchronized(lifecycleLock) { stopped }

    private fun nextGeneration(): Long = generationSequence.incrementAndGet()

    private fun isCurrentAttempt(attempt: DownloadAttempt): Boolean = synchronized(queueStateLock) {
        isCurrentAttemptLocked(attempt)
    }

    private fun isCurrentAttemptLocked(attempt: DownloadAttempt): Boolean =
        queueGenerations[attempt.item.chapterId] == attempt.generation &&
            _queue.value.any { it.chapterId == attempt.item.chapterId }

    private fun launchRetirementCleanup(retirement: ChapterRetirement) {
        val job = workerScope.launch(start = CoroutineStart.LAZY) {
            retirementCleanupAwaitObserver(retirement.item.chapterId)
            retirement.producerDone.await()
            settleRetirement(retirement)
        }
        val shouldStart = synchronized(lifecycleLock) {
            if (stopped) {
                false
            } else {
                activeJobs += job
                job.invokeOnCompletion { synchronized(lifecycleLock) { activeJobs -= job } }
                true
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
            retirement.completion.complete(
                RetirementCleanupResult(
                    success = false,
                    error = CancellationException("Download manager is stopped"),
                ),
            )
        }
    }

    private suspend fun awaitRetirement(attempt: DownloadAttempt): Boolean {
        val retirement = synchronized(queueStateLock) { retirementsByChapter[attempt.item.chapterId] }
            ?: return true
        retirementAwaitObserver(attempt.item.chapterId)
        val initialResult = retirement.completion.await()
        if (!isCurrentAttempt(attempt)) return false
        val result = if (initialResult.success) {
            initialResult
        } else {
            cleanupRetirementArtifacts(retirement)
        }
        if (result.success) {
            synchronized(queueStateLock) {
                if (retirementsByChapter[attempt.item.chapterId] === retirement) {
                    retirementsByChapter.remove(attempt.item.chapterId)
                }
            }
            return isCurrentAttempt(attempt)
        }
        recordFailure(attempt, AppError.Storage(result.error))
        return false
    }

    private fun settleRetirement(retirement: ChapterRetirement) {
        val result = cleanupRetirementArtifacts(retirement)
        if (result.success) {
            synchronized(queueStateLock) {
                if (retirementsByChapter[retirement.item.chapterId] === retirement) {
                    retirementsByChapter.remove(retirement.item.chapterId)
                }
            }
        }
        retirement.completion.complete(result)
        retirementCleanupFinishedObserver(retirement.item.chapterId, result.success)
    }

    private fun retirementArtifacts(
        item: DownloadItem,
        identity: DownloadChapterIdentity?,
    ): List<File> {
        val legacyFinal = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
        return buildList {
            add(provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName))
            add(legacyFinal)
            add(CbzCreator.defaultOutputFile(legacyFinal))
            identity?.let {
                val canonicalFinal = provider.canonicalChapterDownloadDir(identity)
                add(provider.canonicalChapterTmpDir(identity))
                add(canonicalFinal)
                add(CbzCreator.defaultOutputFile(canonicalFinal))
            }
        }.distinctBy(File::getAbsolutePath)
    }

    private fun cleanupRetirementArtifacts(retirement: ChapterRetirement): RetirementCleanupResult {
        var cleanupError: Throwable? = null
        retirement.artifacts.forEach { artifact ->
            if (!artifact.exists()) return@forEach
            val removed = try {
                artifactCleaner(artifact) && !artifact.exists()
            } catch (error: Exception) {
                cleanupError = cleanupError ?: error
                false
            }
            if (!removed && cleanupError == null) {
                cleanupError = java.io.IOException("Unable to remove retired download artifact: ${artifact.absolutePath}")
            }
        }
        return RetirementCleanupResult(success = cleanupError == null, error = cleanupError)
    }

    private fun registerIdentity(
        attempt: DownloadAttempt,
        identity: DownloadChapterIdentity,
    ): Boolean = synchronized(queueStateLock) {
        if (!isCurrentAttemptLocked(attempt)) return@synchronized false
        resolvedDownloadIdentities[attempt.key] = identity
        true
    }

    private fun completeAttempt(attempt: DownloadAttempt): Boolean = synchronized(queueStateLock) {
        if (!isCurrentAttemptLocked(attempt)) {
            resolvedDownloadIdentities.remove(attempt.key)
            return@synchronized false
        }
        val item = _queue.value.first { it.chapterId == attempt.item.chapterId }
        if (stateMachine.transition(item.toEntry(0), DownloadQueueStatus.COMPLETED) == null) {
            return@synchronized false
        }
        val nextQueue = _queue.value.filterNot { it.chapterId == attempt.item.chapterId }
        // Persist first: a storage failure must leave the current generation and its published
        // artifact available for an explicit retry instead of committing a partial completion.
        persistQueue(nextQueue)
        resolvedDownloadIdentities.remove(attempt.key)
        queueGenerations.remove(attempt.item.chapterId)
        _queue.value = nextQueue
        _failures.update { it - attempt.item.chapterId }
        true
    }

    private fun setStatus(attempt: DownloadAttempt, status: DownloadStatus): Boolean = synchronized(queueStateLock) {
        transitionLocked(
            chapterId = attempt.item.chapterId,
            target = DownloadQueueStatus.valueOf(status.name.replace("DONE", "COMPLETED")),
            expectedGeneration = attempt.generation,
        )
    }

    private fun startAttempt(attempt: DownloadAttempt): Boolean = synchronized(queueStateLock) {
        if (
            !transitionLocked(
                chapterId = attempt.item.chapterId,
                target = DownloadQueueStatus.DOWNLOADING,
                expectedGeneration = attempt.generation,
            )
        ) {
            return@synchronized false
        }
        activeProducers[attempt.key] = CompletableDeferred()
        true
    }

    private fun finishProducer(attempt: DownloadAttempt) {
        val completion = synchronized(queueStateLock) { activeProducers.remove(attempt.key) }
        completion?.complete(Unit)
    }

    private fun completedProducerSignal() = CompletableDeferred<Unit>().apply { complete(Unit) }

    private fun persistQueue(items: List<DownloadItem> = _queue.value) {
        val entries = items.mapIndexed { index, item -> item.toEntry(index.toLong()) }
        queuePersister?.invoke(entries) ?: store?.replaceAll(entries)
    }

    private fun updateAttempt(
        attempt: DownloadAttempt,
        transform: (DownloadItem) -> DownloadItem,
    ): Boolean = synchronized(queueStateLock) {
        if (!isCurrentAttemptLocked(attempt)) return@synchronized false
        val nextQueue = _queue.value.map { item ->
            if (item.chapterId == attempt.item.chapterId) transform(item) else item
        }
        persistQueue(nextQueue)
        _queue.value = nextQueue
        true
    }

    private fun updateRetryCount(attempt: DownloadAttempt, retryCount: Int): Boolean =
        updateAttempt(attempt) { it.copy(retryCount = retryCount) }

    private fun clearFailure(attempt: DownloadAttempt) = synchronized(queueStateLock) {
        if (isCurrentAttemptLocked(attempt)) _failures.update { it - attempt.item.chapterId }
    }

    private fun recordFailure(attempt: DownloadAttempt, error: AppError) {
        val chapterId = attempt.item.chapterId
        val notifier = taskNotifier ?: runCatching { Injekt.get<DesktopSystemNotifier>() }.getOrNull()
        val shouldNotify = synchronized(queueStateLock) {
            if (!isCurrentAttemptLocked(attempt)) return@synchronized false
            var changed = false
            val nextQueue = _queue.value.map { item ->
                if (item.chapterId == chapterId && item.status != DownloadStatus.CANCELLED) {
                    changed = true
                    item.copy(failure = error)
                } else {
                    item
                }
            }
            if (changed) {
                // Reporting the storage error itself must remain visible even when the same
                // persistence backend is unavailable.
                runCatching { persistQueue(nextQueue) }
                _queue.value = nextQueue
                _failures.update { it + (chapterId to error) }
            }
            changed
        }
        if (shouldNotify) {
            notifier?.notify(
                NotificationEvent.Failure("download:$chapterId:${attempt.generation}", "下载失败", error.notificationMessage()),
            )
        }
    }

    private fun fail(attempt: DownloadAttempt, error: AppError): Boolean {
        recordFailure(attempt, error)
        return false
    }

    private fun Throwable?.toAppError(): AppError {
        val error = this ?: return AppError.Unknown()
        if (error is DownloadHttpException) return when (error.statusCode) {
            401, 403 -> AppError.Authentication(error)
            429 -> AppError.RateLimited(error.retryAfterSeconds, error)
            in 500..599 -> AppError.Server(error.statusCode, error)
            else -> AppError.Network(error)
        }
        return when (error) {
            is NetworkDownloadException -> AppError.Network(error.cause ?: error)
            is java.nio.file.AccessDeniedException, is SecurityException -> AppError.Permission(error)
            is java.io.IOException -> AppError.Storage(error)
            else -> AppError.Unknown(error)
        }
    }

    private fun DownloadItem.toEntry(position: Long) = DownloadQueueEntry(
        chapterId = chapterId,
        mangaId = mangaId,
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = chapterName,
        chapterUrl = chapterUrl,
        pageUrls = pageUrls,
        status = DownloadQueueStatus.valueOf(status.name.replace("DONE", "COMPLETED")),
        progress = progress,
        position = position,
        retryCount = retryCount,
        failure = failure,
    )

    private fun DownloadQueueEntry.toItem() = DownloadItem(
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = chapterName,
        chapterId = chapterId,
        mangaId = mangaId,
        chapterUrl = chapterUrl,
        pageUrls = pageUrls,
        status = DownloadStatus.valueOf(status.name.replace("COMPLETED", "DONE")),
        progress = progress,
        retryCount = retryCount,
        failure = failure,
    )

    private fun extensionFromUrl(url: String): String {
        val ext = url.substringAfterLast('/').substringAfterLast('.').substringBefore('?').lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "avif" -> ext
            else -> "jpg"
        }
    }
}

private fun AppError.notificationMessage(): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Authentication -> "服务器拒绝访问（HTTP 403），请检查登录或源设置后重试"
    is AppError.RateLimited -> retryAfterSeconds?.let { "请求过于频繁，请在 ${it} 秒后重试" } ?: "请求过于频繁，请稍后重试"
    is AppError.Server -> "服务器错误（HTTP $statusCode），请稍后重试"
    is AppError.Permission -> "没有写入权限，请检查下载路径后重试"
    is AppError.Storage -> "磁盘空间不足或无法写入，请检查下载路径后重试"
    else -> "下载失败，请重试"
}

class DownloadHttpException(val statusCode: Int, val retryAfterSeconds: Long? = null) : java.io.IOException("HTTP $statusCode")
class NetworkDownloadException(cause: Throwable) : java.io.IOException(cause)

interface DownloadFileOperations {
    fun execute(client: OkHttpClient, url: String): Response
    suspend fun readBody(response: Response): ByteArray
    fun writePage(tmp: File, bytes: ByteArray)
    fun renamePage(tmp: File, final: File)
    fun renameChapter(tmpDir: File, finalDir: File): Boolean
}

object DefaultDownloadFileOperations : DownloadFileOperations {
    override fun execute(client: OkHttpClient, url: String): Response = try {
        client.newCall(Request.Builder().url(url).build()).execute().also { response ->
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val status = response.code
                response.close()
                throw DownloadHttpException(status, retryAfter)
            }
        }
    } catch (error: DownloadHttpException) {
        throw error
    } catch (error: java.io.IOException) {
        throw NetworkDownloadException(error)
    }

    override suspend fun readBody(response: Response): ByteArray = runInterruptible { response.body.bytes() }

    override fun writePage(tmp: File, bytes: ByteArray) {
        tmp.outputStream().use { it.write(bytes) }
    }

    override fun renamePage(tmp: File, final: File) {
        if (tmp.length() <= 0L || !tmp.renameTo(final)) throw java.io.IOException("Unable to finalize page")
    }

    override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
        if (!tmpDir.renameTo(finalDir)) throw java.io.IOException("Unable to finalize chapter")
        return true
    }
}
