package mihon.desktop.test.http

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class DownloadTestRow(
    val chapterId: Long,
    val sourceId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val status: DownloadStatus,
    val progress: Int,
    val failure: StoredAppError?,
)

@Serializable
data class DownloadTestSnapshot(
    val paused: Boolean,
    val rows: List<DownloadTestRow>,
)

@Serializable
enum class DownloadTestFailureCode {
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    ROW_NOT_FOUND,
    OPERATION_REJECTED,
    PARTIAL_FAILURE,
    OWNER_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class DownloadTestActionResult(
    val success: Boolean,
    val snapshot: DownloadTestSnapshot,
    val failureCode: DownloadTestFailureCode? = null,
)

class DownloadTestModeController(
    private val manager: DesktopDownloadManager,
) {
    private var closed = false

    fun snapshot() = DownloadTestSnapshot(
        paused = manager.isPaused.value,
        rows = manager.queue.value.map { item ->
            DownloadTestRow(
                chapterId = item.chapterId,
                sourceId = item.sourceId,
                mangaTitle = item.mangaTitle,
                chapterName = item.chapterName,
                status = item.status,
                progress = item.progress,
                failure = item.failure?.toStoredAppError(),
            )
        },
    )

    fun execute(
        action: String,
        params: Map<String, String>,
    ): DownloadTestActionResult {
        if (closed) return failure(DownloadTestFailureCode.OWNER_CLOSED)
        val before = snapshot()
        val failureCode = try {
            when (action) {
                "downloads_pause_all" -> null.also { manager.pauseAll() }
                "downloads_resume_all" -> null.also { manager.resumeAll() }
                "downloads_cancel" -> cancel(params)
                "downloads_cancel_all" -> null.also { manager.cancelAll() }
                "downloads_clear_errors" -> null.also { manager.clearErrors() }
                "downloads_retry_errors" -> null.also { manager.retryErrors() }
                "downloads_reorder" -> reorder(params)
                "downloads_sort" -> sort(params)
                "downloads_reverse" -> null.also { manager.reverseQueue() }
                else -> DownloadTestFailureCode.UNSUPPORTED_ACTION
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(
                if (snapshot() != before) {
                    DownloadTestFailureCode.PARTIAL_FAILURE
                } else {
                    DownloadTestFailureCode.OPERATION_REJECTED
                },
            )
        }
        return failureCode?.let(::failure) ?: DownloadTestActionResult(true, snapshot())
    }

    fun close() {
        closed = true
        DownloadTestModeBridge.clear(this)
    }

    private fun cancel(params: Map<String, String>): DownloadTestFailureCode? {
        val rawChapterId = params["chapterId"] ?: return if ("index" in params) {
            DownloadTestFailureCode.INVALID_PARAMETER
        } else {
            DownloadTestFailureCode.MISSING_PARAMETER
        }
        val chapterId = rawChapterId.toLongOrNull() ?: return DownloadTestFailureCode.INVALID_PARAMETER
        if (manager.queue.value.none { it.chapterId == chapterId }) return DownloadTestFailureCode.ROW_NOT_FOUND
        return if (manager.cancel(chapterId)) null else DownloadTestFailureCode.OPERATION_REJECTED
    }

    private fun reorder(params: Map<String, String>): DownloadTestFailureCode? {
        val from = params["from"]?.toIntOrNull() ?: return DownloadTestFailureCode.MISSING_PARAMETER
        val to = params["to"]?.toIntOrNull() ?: return DownloadTestFailureCode.MISSING_PARAMETER
        val before = manager.queue.value.map(DownloadItem::chapterId)
        if (from !in before.indices || to !in before.indices) return DownloadTestFailureCode.ROW_NOT_FOUND
        manager.reorderItem(from, to)
        return if (from != to && manager.queue.value.map(DownloadItem::chapterId) == before) {
            DownloadTestFailureCode.OPERATION_REJECTED
        } else {
            null
        }
    }

    private fun sort(params: Map<String, String>): DownloadTestFailureCode? {
        when (params["by"] ?: "date_added") {
            "date_added" -> return DownloadTestFailureCode.INVALID_PARAMETER
            "chapter_id" -> manager.sortQueue(DownloadItem::chapterId)
            "chapter_name" -> manager.sortQueue(DownloadItem::chapterName)
            "manga_title" -> manager.sortQueue(DownloadItem::mangaTitle)
            else -> return DownloadTestFailureCode.INVALID_PARAMETER
        }
        return null
    }

    private fun failure(code: DownloadTestFailureCode) = DownloadTestActionResult(false, snapshot(), code)
}

object DownloadTestModeBridge {
    private val value = AtomicReference<DownloadTestModeController?>()
    val controller: DownloadTestModeController? get() = value.get()
    fun install(controller: DownloadTestModeController) { value.set(controller) }
    fun clear(expected: DownloadTestModeController): Boolean = value.compareAndSet(expected, null)
}
