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

    private val lifecycleLock = Any()
    private val queueStateLock = Any()
    private var stopped = false
    private var workerJob: Job? = null
    private val activeJobs = mutableSetOf<Job>()
    private val generationSequence = AtomicLong()
    private val recoveredItems = store?.recover()?.map { it.toItem() } ?: emptyList()
    private val queueGenerations = recoveredItems.associate { it.chapterId to nextGeneration() }.toMutableMap()
    private val resolvedDownloadIdentities = mutableMapOf<DownloadAttemptKey, DownloadChapterIdentity>()
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
        if (provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName)) return@synchronized
        // Clean up any leftover _tmp directory from a previous attempt
        provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        queueGenerations[item.chapterId] = nextGeneration()
        _queue.value = current + item
        persistQueue()
    }

    override fun enqueue(entry: DownloadQueueEntry) = enqueue(entry.toItem())

    /** Remove a queued item by chapter ID and clean up its _tmp directory. */
    override fun cancel(chapterId: Long): Boolean = synchronized(queueStateLock) {
        val item = _queue.value.find { it.chapterId == chapterId }
        val generation = queueGenerations[chapterId]
        if (
            item == null ||
            generation == null ||
            stateMachine.transition(item.toEntry(0), DownloadQueueStatus.CANCELLED) == null
        ) {
            return@synchronized false
        }
        _queue.value = _queue.value.filterNot { it.chapterId == chapterId }
        queueGenerations.remove(chapterId)
        // Clean up _tmp directory if one exists
        provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        resolvedDownloadIdentities.remove(DownloadAttemptKey(chapterId, generation))?.let(provider::cleanupTmpDir)
        persistQueue()
        true
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
        _queue.value = _queue.value.map { item ->
            if (item.chapterId != chapterId) item else stateMachine.transition(item.toEntry(0), target)?.toItem()
                ?.let { transitioned -> if (target == DownloadQueueStatus.QUEUED) transitioned.copy(failure = null, retryCount = 0) else transitioned }
                ?.also { changed = true } ?: item
        }
        if (changed) {
            if (target == DownloadQueueStatus.QUEUED) {
                resolvedDownloadIdentities.remove(DownloadAttemptKey(chapterId, currentGeneration))
                queueGenerations[chapterId] = nextGeneration()
                _failures.update { it - chapterId }
            }
            persistQueue()
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
                    if (stopped || !setStatus(attempt, DownloadStatus.DOWNLOADING)) {
                        job.cancel()
                        null
                    } else {
                        activeJobs += job
                        job.invokeOnCompletion { synchronized(lifecycleLock) { activeJobs -= job } }
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
            withCurrentAttempt(attempt) {
                tmpDir.mkdirs()
                // Clean up any leftover .tmp files from previous partial downloads
                tmpDir.listFiles()
                    ?.filter { it.extension == "tmp" }
                    ?.forEach { it.delete() }
                Unit
            } ?: return false

            urls.forEachIndexed { index, url ->
                if (!isCurrentAttempt(attempt)) return false

                val baseName = "%03d".format(index + 1)
                val tmpFile = File(tmpDir, "$baseName.tmp")

                // Skip if this page was already fully downloaded in a previous attempt
                val alreadyDownloaded = withCurrentAttempt(attempt) {
                    tmpDir.listFiles()
                        ?.any {
                            it.nameWithoutExtension == baseName &&
                                it.extension != "tmp" &&
                                provider.isValidDownloadedImage(it)
                        }
                        ?: false
                } ?: return false

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
                            withCurrentAttempt(attempt) {
                                finalFile.delete()
                                fileOperations.writePage(tmpFile, bytes)
                                fileOperations.renamePage(tmpFile, finalFile)
                                provider.isValidDownloadedImage(finalFile)
                            } ?: return false
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            lastError = error
                            false
                        }
                        if (!pageDownloaded) {
                            withCurrentAttempt(attempt) {
                                tmpFile.delete()
                                finalFile.delete()
                            } ?: return false
                            val wait = stateMachine.retryDelayMillis(retryAttempt++) ?: break
                            if (!updateRetryCount(attempt, retryAttempt)) return false
                            retryDelay(wait)
                        }
                    }
                    if (!pageDownloaded) {
                        recordFailure(attempt, lastError.toAppError())
                        withCurrentAttempt(attempt) {
                            tmpFile.delete()
                            finalFile.delete()
                        } ?: return false
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
            var renamed = false
            var renameAttempt = 0
            var renameError: Throwable? = null
            while (!renamed) {
                renamed = try {
                    withCurrentAttempt(attempt) {
                        finalDir.deleteRecursively()
                        fileOperations.renameChapter(tmpDir, finalDir)
                    } ?: return false
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

            try {
                withCurrentAttempt(attempt) {
                    if (prefs?.downloadAsCbz?.get() == true) {
                        val cbzFile = CbzCreator.defaultOutputFile(finalDir)
                        val packed = CbzCreator.create(finalDir, cbzFile)
                        if (packed) {
                            // Remove individual image files — CBZ replaces them
                            finalDir.deleteRecursively()
                        }
                    }
                    completeAttempt(attempt)
                } ?: false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordFailure(attempt, error.toAppError())
                false
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

    private inline fun <T : Any> withCurrentAttempt(attempt: DownloadAttempt, block: () -> T): T? =
        synchronized(queueStateLock) {
            if (isCurrentAttemptLocked(attempt)) block() else null
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
        resolvedDownloadIdentities.remove(attempt.key)
        queueGenerations.remove(attempt.item.chapterId)
        _queue.value = _queue.value.filterNot { it.chapterId == attempt.item.chapterId }
        _failures.update { it - attempt.item.chapterId }
        persistQueue()
        true
    }

    private fun setStatus(attempt: DownloadAttempt, status: DownloadStatus): Boolean = synchronized(queueStateLock) {
        transitionLocked(
            chapterId = attempt.item.chapterId,
            target = DownloadQueueStatus.valueOf(status.name.replace("DONE", "COMPLETED")),
            expectedGeneration = attempt.generation,
        )
    }

    private fun persistQueue() = store?.replaceAll(_queue.value.mapIndexed { index, item -> item.toEntry(index.toLong()) })

    private fun updateAttempt(
        attempt: DownloadAttempt,
        transform: (DownloadItem) -> DownloadItem,
    ): Boolean = synchronized(queueStateLock) {
        if (!isCurrentAttemptLocked(attempt)) return@synchronized false
        _queue.value = _queue.value.map { item ->
            if (item.chapterId == attempt.item.chapterId) transform(item) else item
        }
        persistQueue()
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
        synchronized(queueStateLock) {
            if (!isCurrentAttemptLocked(attempt)) return@synchronized
            var changed = false
            _queue.value = _queue.value.map { item ->
                if (item.chapterId == chapterId && item.status != DownloadStatus.CANCELLED) {
                    changed = true
                    item.copy(failure = error)
                } else {
                    item
                }
            }
            if (changed) {
                _failures.update { it + (chapterId to error) }
                persistQueue()
                notifier?.notify(
                    NotificationEvent.Failure("download:$chapterId:${attempt.generation}", "下载失败", error.notificationMessage()),
                )
            }
            Unit
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
