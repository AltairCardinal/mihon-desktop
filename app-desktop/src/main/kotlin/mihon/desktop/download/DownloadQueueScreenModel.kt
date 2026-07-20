package mihon.desktop.download

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.source.service.SourceManager

enum class DownloadQueueOrder {
    UPLOAD_DATE_NEWEST,
    UPLOAD_DATE_OLDEST,
    CHAPTER_NUMBER_ASCENDING,
    CHAPTER_NUMBER_DESCENDING,
}

data class DownloadQueueSourceGroup(
    val sourceId: Long,
    val sourceName: String,
    val items: List<DownloadItem>,
)

data class DownloadQueueScreenState(
    val queue: List<DownloadItem>,
    val isPaused: Boolean,
    val sourceGroups: List<DownloadQueueSourceGroup>,
) {
    val hasErrors: Boolean = queue.any { it.status == DownloadStatus.ERROR }
}

class DownloadQueueScreenModel(
    private val downloadManager: DesktopDownloadManager,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
    coroutineScope: CoroutineScope? = null,
) : ScreenModel {
    private val injectedScope = coroutineScope?.let { CoroutineScope(it.coroutineContext + SupervisorJob()) }
    private val scope = injectedScope ?: screenModelScope
    val queue: StateFlow<List<DownloadItem>> = downloadManager.queue
    val isPaused: StateFlow<Boolean> = downloadManager.isPaused
    val state: StateFlow<DownloadQueueScreenState> =
        combine(queue, isPaused, ::projectState)
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = projectState(queue.value, isPaused.value),
            )

    fun reorder(fromChapterId: Long, toChapterId: Long) {
        val currentQueue = queue.value
        val fromIndex = currentQueue.indexOfFirst { it.chapterId == fromChapterId }
        val toIndex = currentQueue.indexOfFirst { it.chapterId == toChapterId }
        if (fromIndex >= 0 && toIndex >= 0) downloadManager.reorderItem(fromIndex, toIndex)
    }

    fun sort(order: DownloadQueueOrder): Job = scope.launch {
        val chapters = queue.value.associate { item ->
            item.chapterId to chapterRepository.getChapterById(item.chapterId)
        }
        downloadManager.sortQueue(
            Comparator { left, right ->
                val leftChapter = chapters[left.chapterId]
                val rightChapter = chapters[right.chapterId]
                when {
                    leftChapter == null && rightChapter == null -> 0
                    leftChapter == null -> 1
                    rightChapter == null -> -1
                    else -> when (order) {
                        DownloadQueueOrder.UPLOAD_DATE_NEWEST ->
                            rightChapter.dateUpload.compareTo(leftChapter.dateUpload)
                        DownloadQueueOrder.UPLOAD_DATE_OLDEST ->
                            leftChapter.dateUpload.compareTo(rightChapter.dateUpload)
                        DownloadQueueOrder.CHAPTER_NUMBER_ASCENDING ->
                            leftChapter.chapterNumber.compareTo(rightChapter.chapterNumber)
                        DownloadQueueOrder.CHAPTER_NUMBER_DESCENDING ->
                            rightChapter.chapterNumber.compareTo(leftChapter.chapterNumber)
                    }
                }
            },
        )
    }

    fun pauseAll() = downloadManager.pauseAll()

    fun resumeAll() = downloadManager.resumeAll()

    fun retryErrors() = downloadManager.retryErrors()

    fun clearErrors() = downloadManager.clearErrors()

    fun cancelAll() = downloadManager.cancelAll()

    fun cancel(chapterId: Long) = downloadManager.cancel(chapterId)

    fun retry(chapterId: Long) = downloadManager.retryItem(chapterId)

    override fun onDispose() {
        injectedScope?.cancel()
    }

    private fun projectState(
        queue: List<DownloadItem>,
        isPaused: Boolean,
    ): DownloadQueueScreenState = DownloadQueueScreenState(
        queue = queue,
        isPaused = isPaused,
        sourceGroups = queue.groupBy(DownloadItem::sourceId).map { (sourceId, items) ->
            DownloadQueueSourceGroup(
                sourceId = sourceId,
                sourceName = sourceManager.get(sourceId)?.name ?: "Unknown source ($sourceId)",
                items = items,
            )
        },
    )
}
