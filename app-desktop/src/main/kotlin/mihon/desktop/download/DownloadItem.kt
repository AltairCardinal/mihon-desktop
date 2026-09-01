package mihon.desktop.download

import mihon.domain.error.AppError
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.PartialPageTable

/** A single download job: all pages of one chapter. */
data class DownloadItem(
    val sourceId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val chapterId: Long,
    val mangaId: Long = 0,
    val chapterUrl: String = "",
    /** Legacy compatibility projection. [pageTable] is the lossless authority for new attempts. */
    val pageUrls: List<String> = emptyList(),
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val retryCount: Int = 0,
    val failure: AppError? = null,
    val pageTable: PartialPageTable = PartialPageTable.legacy(pageUrls),
    val downloadIdentity: DownloadChapterIdentity? = null,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, ERROR, CANCELLED }
