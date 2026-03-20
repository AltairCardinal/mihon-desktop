package mihon.desktop.download

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import okhttp3.Request
import tachiyomi.domain.source.service.SourceManager
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
) {
    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

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
    }

    /** Remove a queued item by chapter ID and clean up its _tmp directory. */
    fun cancel(chapterId: Long) {
        val item = _queue.value.find { it.chapterId == chapterId }
        _queue.value = _queue.value.filterNot { it.chapterId == chapterId }
        // Clean up _tmp directory if one exists
        if (item != null) {
            provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        }
    }

    /** Cancel and clear the entire queue, cleaning up all _tmp directories. */
    fun cancelAll() {
        val items = _queue.value
        _queue.value = emptyList()
        // Clean up all _tmp directories
        items.forEach { item ->
            provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        }
    }

    /** Remove all items with ERROR status and clean up their _tmp directories. */
    fun clearErrors() {
        val errorItems = _queue.value.filter { it.status == DownloadStatus.ERROR }
        _queue.update { it.filterNot { item -> item.status == DownloadStatus.ERROR } }
        errorItems.forEach { item ->
            provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        }
    }

    /** Reset all ERROR items back to QUEUED so they will be retried. */
    fun retryErrors() {
        _queue.update { items ->
            items.map { if (it.status == DownloadStatus.ERROR) it.copy(status = DownloadStatus.QUEUED) else it }
        }
    }

    /** Reset a single ERROR item back to QUEUED. */
    fun retryItem(chapterId: Long) {
        _queue.update { items ->
            items.map {
                if (it.chapterId == chapterId && it.status == DownloadStatus.ERROR) {
                    it.copy(status = DownloadStatus.QUEUED)
                } else {
                    it
                }
            }
        }
    }

    /** Sort the queue by a key selector, keeping DOWNLOADING items first. */
    fun <R : Comparable<R>> sortQueue(selector: (DownloadItem) -> R) {
        _queue.update { items ->
            val downloading = items.filter { it.status == DownloadStatus.DOWNLOADING }
            val rest = items.filter { it.status != DownloadStatus.DOWNLOADING }.sortedBy(selector)
            downloading + rest
        }
    }

    /** Reverse the order of non-DOWNLOADING items in the queue. */
    fun reverseQueue() {
        _queue.update { items ->
            val downloading = items.filter { it.status == DownloadStatus.DOWNLOADING }
            val rest = items.filter { it.status != DownloadStatus.DOWNLOADING }.reversed()
            downloading + rest
        }
    }

    /** Pause the download worker (no new downloads will start). */
    fun pauseAll() { _isPaused.value = true }

    /** Resume the download worker. */
    fun resumeAll() { _isPaused.value = false }

    /**
     * Expose internal [setStatus] for test helpers.
     * Only intended for use in unit tests.
     */
    fun setItemStatusForTest(chapterId: Long, status: DownloadStatus) = setStatus(chapterId, status)

    /** Delete the on-disk files for a downloaded chapter. */
    fun deleteDownload(sourceId: Long, mangaTitle: String, chapterName: String) {
        provider.deleteChapterDownload(sourceId, mangaTitle, chapterName)
    }

    /** Delegates to [DesktopDownloadProvider]. */
    fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean =
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
    fun start(): Job = workerScope.launch {
            _queue
                .map { items -> items.any { it.status == DownloadStatus.QUEUED } }
                .distinctUntilChanged()
                .filter { hasQueued -> hasQueued }
                .collect { drainQueue() }
        }

    /** Process every QUEUED item until none remain. */
    private suspend fun drainQueue() {
        while (true) {
            val item = _queue.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
            setStatus(item.chapterId, DownloadStatus.DOWNLOADING)
            val success = downloadChapter(item)
            if (success) {
                _queue.value = _queue.value.filterNot { it.chapterId == item.chapterId }
            } else {
                setStatus(item.chapterId, DownloadStatus.ERROR)
            }
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
            val client = networkHelper?.client ?: Injekt.get<NetworkHelper>().client
            // Resolve page URLs if not pre-provided
            val urls = when {
                item.pageUrls.isNotEmpty() -> item.pageUrls
                item.chapterUrl.isNotBlank() -> {
                    val sourceManager = Injekt.get<SourceManager>()
                    val source = sourceManager.getCatalogueSources()
                        .find { it.id == item.sourceId } ?: return false
                    val sChapter = SChapter.create().apply {
                        url = item.chapterUrl
                        name = item.chapterName
                    }
                    source.getPageList(sChapter).mapNotNull { it.imageUrl }
                }
                else -> return false
            }
            if (urls.isEmpty()) return false

            // Update queue item with resolved URL count so progress display is accurate
            _queue.value = _queue.value.map {
                if (it.chapterId == item.chapterId) it.copy(pageUrls = urls) else it
            }

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
                    ?.any { it.nameWithoutExtension == baseName && it.extension != "tmp" }
                    ?: false

                if (!alreadyDownloaded) {
                    // Download to .tmp file first
                    val response = client.newCall(Request.Builder().url(url).build()).execute()
                    response.body.byteStream().use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Determine extension and rename from .tmp to final name
                    val ext = url.substringAfterLast('.').substringBefore('?').take(5).ifBlank { "jpg" }
                    tmpFile.renameTo(File(tmpDir, "$baseName.$ext"))
                }

                _queue.value = _queue.value.map {
                    if (it.chapterId == item.chapterId) it.copy(progress = index + 1) else it
                }
            }

            // Final cancellation check before renaming — prevents race where cancel() arrived
            // just after the last page loop iteration but before renameTmpToFinal()
            if (_queue.value.none { it.chapterId == item.chapterId }) {
                provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
                return false
            }

            // All pages downloaded — rename _tmp to final directory
            val renamed = provider.renameTmpToFinal(item.sourceId, item.mangaTitle, item.chapterName)

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
        } catch (_: Exception) {
            // _tmp directory remains on disk but won't be counted as "downloaded"
            false
        }
    }

    private fun setStatus(chapterId: Long, status: DownloadStatus) {
        _queue.value = _queue.value.map {
            if (it.chapterId == chapterId) it.copy(status = status) else it
        }
    }
}
