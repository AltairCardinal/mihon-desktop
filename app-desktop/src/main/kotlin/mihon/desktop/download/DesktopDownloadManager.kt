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
) : DownloadRepository, DesktopDownloadQueuePort {
    private val lifecycleLock = Any()
    private var stopped = false
    private var workerJob: Job? = null
    private val activeJobs = mutableSetOf<Job>()
    private val recoveredItems = store?.recover()?.map { it.toItem() } ?: emptyList()
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
    fun enqueue(item: DownloadItem) {
        val current = _queue.value
        if (current.any { it.chapterId == item.chapterId }) return
        if (provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName)) return
        // Clean up any leftover _tmp directory from a previous attempt
        provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        _queue.value = current + item
        persistQueue()
    }

    override fun enqueue(entry: DownloadQueueEntry) = enqueue(entry.toItem())

    /** Remove a queued item by chapter ID and clean up its _tmp directory. */
    override fun cancel(chapterId: Long): Boolean {
        val item = _queue.value.find { it.chapterId == chapterId }
        if (item == null || stateMachine.transition(item.toEntry(0), DownloadQueueStatus.CANCELLED) == null) return false
        _queue.value = _queue.value.filterNot { it.chapterId == chapterId }
        persistQueue()
        // Clean up _tmp directory if one exists
        provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
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

    override fun transition(chapterId: Long, target: DownloadQueueStatus): Boolean {
        var changed = false
        _queue.update { items -> items.map { item ->
            if (item.chapterId != chapterId) item else stateMachine.transition(item.toEntry(0), target)?.toItem()
                ?.let { transitioned -> if (target == DownloadQueueStatus.QUEUED) transitioned.copy(failure = null, retryCount = 0) else transitioned }
                ?.also { changed = true } ?: item
        } }
        if (changed) {
            if (target == DownloadQueueStatus.QUEUED) _failures.update { it - chapterId }
            persistQueue()
        }
        return changed
    }

    override fun recover(): List<DownloadQueueEntry> = stateMachine.recover(
        _queue.value.mapIndexed { index, item -> item.toEntry(index.toLong()) },
    )

    /**
     * Move a queue item from [from] index to [to] index.
     * DOWNLOADING items are included in the index space so drag handles
     * feel contiguous. Does nothing if indices are equal or out of bounds.
     */
    fun reorderItem(from: Int, to: Int) {
        if (from == to) return
        _queue.update { items ->
            if (from !in items.indices || to !in items.indices) return@update items
            val sourceId = items[from].sourceId
            if (items[to].sourceId != sourceId) return@update items
            val sourceIndices = items.indices.filter { items[it].sourceId == sourceId }
            val fromInSource = sourceIndices.indexOf(from)
            val toInSource = sourceIndices.indexOf(to)
            if (fromInSource < 0 || toInSource < 0) return@update items
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
    fun <R : Comparable<R>> sortQueue(selector: (DownloadItem) -> R) {
        _queue.update { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.sortedBy(selector)
            }
        }
        persistQueue()
    }

    /** Sort each source group by a comparator while preserving source group order. */
    fun sortQueue(comparator: Comparator<DownloadItem>) {
        _queue.update { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.sortedWith(comparator)
            }
        }
        persistQueue()
    }

    /** Reverse items inside each source group while preserving source group order. */
    fun reverseQueue() {
        _queue.update { items ->
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

    /** Delegates to [DesktopDownloadProvider]. */
    override fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean =
        provider.isChapterDownloaded(sourceId, mangaTitle, chapterName)

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
            val batch = stateMachine.schedule(
                _queue.value.mapIndexed { index, queued -> queued.toEntry(index.toLong()) },
                limit = limit,
            ).map { it.toItem() }
            if (batch.isEmpty()) break
            val jobs = batch.mapNotNull { item ->
                val job = workerScope.launch(start = CoroutineStart.LAZY) {
                    val success = downloadChapter(item)
                    if (success) {
                        transition(item.chapterId, DownloadQueueStatus.COMPLETED)
                        _queue.value = _queue.value.filterNot { it.chapterId == item.chapterId }
                        persistQueue()
                    } else if (!isStopped()) {
                        setStatus(item.chapterId, DownloadStatus.ERROR)
                    }
                }
                synchronized(lifecycleLock) {
                    if (stopped) {
                        job.cancel()
                        null
                    } else {
                        setStatus(item.chapterId, DownloadStatus.DOWNLOADING)
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
    private suspend fun downloadChapter(item: DownloadItem): Boolean {
        return try {
            val client = httpClient
                ?: networkHelper?.clientForSource(item.sourceId)
                ?: Injekt.get<NetworkHelper>().clientForSource(item.sourceId)
            // Resolve page URLs if not pre-provided
            val urls = when {
                item.pageUrls.isNotEmpty() -> item.pageUrls
                item.chapterUrl.isNotBlank() -> {
                    val source = sourceResolver(item.sourceId) ?: return fail(
                        item.chapterId,
                        AppError.Unknown(IllegalStateException("Source ${item.sourceId} is unavailable")),
                    )
                    val sChapter = SChapter.create().apply {
                        url = item.chapterUrl
                        name = item.chapterName
                    }
                    val pagesResult = safeSourceCall(timeoutMs = sourceCallTimeoutMs) { source.getPageList(sChapter) }
                    when (pagesResult) {
                        is SourceCallResult.Success -> pagesResult.value.mapNotNull { it.imageUrl }
                        is SourceCallResult.Timeout -> return fail(item.chapterId, pagesResult.error)
                        is SourceCallResult.Error -> return fail(
                            item.chapterId,
                            pagesResult.error,
                        )
                    }
                }
                else -> return fail(
                    item.chapterId,
                    AppError.MalformedData(IllegalArgumentException("Chapter URL is missing")),
                )
            }
            if (urls.isEmpty()) return fail(
                item.chapterId,
                AppError.MalformedData(IllegalStateException("Source returned no downloadable pages")),
            )

            // Update queue item with resolved URL count so progress display is accurate
            _queue.value = _queue.value.map {
                if (it.chapterId == item.chapterId) it.copy(pageUrls = urls) else it
            }
            persistQueue()

            // Use _tmp directory for in-progress download (Android pattern)
            val tmpDir = provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
            tmpDir.mkdirs()

            // Clean up any leftover .tmp files from previous partial downloads
            tmpDir.listFiles()
                ?.filter { it.extension == "tmp" }
                ?.forEach { it.delete() }

            urls.forEachIndexed { index, url ->
                // Check if item was cancelled before each page — mirrors Android's CancellationException handling
                if (_queue.value.none { it.chapterId == item.chapterId }) {
                    provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
                    return false
                }

                val baseName = "%03d".format(index + 1)
                val tmpFile = File(tmpDir, "$baseName.tmp")

                // Skip if this page was already fully downloaded in a previous attempt
                val alreadyDownloaded = tmpDir.listFiles()
                    ?.any {
                        it.nameWithoutExtension == baseName &&
                            it.extension != "tmp" &&
                            provider.isValidDownloadedImage(it)
                    }
                    ?: false

                if (!alreadyDownloaded) {
                    // Download to .tmp file first
                    val finalFile = File(tmpDir, "$baseName.${extensionFromUrl(url)}")
                    finalFile.delete()
                    var pageDownloaded = false
                    var attempt = 0
                    var lastError: Throwable? = null
                    while (!pageDownloaded) {
                        pageDownloaded = try {
                            fileOperations.execute(client, url).use { response ->
                                val bytes = fileOperations.readBody(response)
                                fileOperations.writePage(tmpFile, bytes)
                            }
                            fileOperations.renamePage(tmpFile, finalFile)
                            provider.isValidDownloadedImage(finalFile)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            lastError = error
                            false
                        }
                        if (!pageDownloaded) {
                            tmpFile.delete()
                            finalFile.delete()
                            val wait = stateMachine.retryDelayMillis(attempt++) ?: break
                            updateRetryCount(item.chapterId, attempt)
                            retryDelay(wait)
                        }
                    }
                    if (!pageDownloaded) {
                        recordFailure(item.chapterId, lastError.toAppError())
                        tmpFile.delete()
                        finalFile.delete()
                        return false
                    }
                }

                _queue.value = _queue.value.map {
                    if (it.chapterId == item.chapterId) it.copy(progress = index + 1, retryCount = 0) else it
                }
                persistQueue()
                _failures.update { it - item.chapterId }
            }

            // Final cancellation check before renaming — prevents race where cancel() arrived
            // just after the last page loop iteration but before renameTmpToFinal()
            if (_queue.value.none { it.chapterId == item.chapterId }) {
                provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
                return false
            }

            // All pages downloaded — rename _tmp to final directory
            val finalDir = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
            var renamed = false
            var renameAttempt = 0
            var renameError: Throwable? = null
            while (!renamed) {
                renamed = try {
                    finalDir.deleteRecursively()
                    fileOperations.renameChapter(tmpDir, finalDir)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    renameError = error
                    false
                }
                if (!renamed) {
                    val wait = stateMachine.retryDelayMillis(renameAttempt++) ?: break
                    updateRetryCount(item.chapterId, renameAttempt)
                    retryDelay(wait)
                }
            }
            if (!renamed) recordFailure(item.chapterId, AppError.Storage(renameError))

            // Optionally package pages into a CBZ archive
            if (renamed) {
                val prefs = downloadPreferences ?: runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull()
                if (prefs?.downloadAsCbz?.get() == true) {
                    val finalDir = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
                    val cbzFile = CbzCreator.defaultOutputFile(finalDir)
                    val packed = CbzCreator.create(finalDir, cbzFile)
                    if (packed) {
                        // Remove individual image files — CBZ replaces them
                        finalDir.deleteRecursively()
                    }
                }
            }

            renamed
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordFailure(item.chapterId, error.toAppError())
            // _tmp directory remains on disk but won't be counted as "downloaded"
            false
        }
    }

    private fun isStopped(): Boolean = synchronized(lifecycleLock) { stopped }

    private fun setStatus(chapterId: Long, status: DownloadStatus) {
        transition(chapterId, DownloadQueueStatus.valueOf(status.name.replace("DONE", "COMPLETED")))
    }

    private fun persistQueue() = store?.replaceAll(_queue.value.mapIndexed { index, item -> item.toEntry(index.toLong()) })

    private fun updateRetryCount(chapterId: Long, retryCount: Int) {
        _queue.update { items -> items.map { if (it.chapterId == chapterId) it.copy(retryCount = retryCount) else it } }
        persistQueue()
    }

    private fun recordFailure(chapterId: Long, error: AppError) {
        var recorded = false
        _queue.update { items -> items.map { item ->
            if (item.chapterId == chapterId && item.status != DownloadStatus.CANCELLED) {
                recorded = true
                item.copy(failure = error)
            } else item
        } }
        if (!recorded) return
        _failures.update { it + (chapterId to error) }
        persistQueue()
        (taskNotifier ?: runCatching { Injekt.get<DesktopSystemNotifier>() }.getOrNull())?.notify(
            NotificationEvent.Failure("download:$chapterId", "下载失败", error.notificationMessage()),
        )
    }

    private fun fail(chapterId: Long, error: AppError): Boolean {
        recordFailure(chapterId, error)
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
