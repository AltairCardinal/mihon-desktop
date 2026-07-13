package mihon.desktop.download

import mihon.domain.error.AppError

/** A single download job: all pages of one chapter. */
data class DownloadItem(
    val sourceId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val chapterId: Long,
    val mangaId: Long = 0,
    val chapterUrl: String = "",
    /** Pre-resolved page URLs. If empty, the manager fetches them from the source at download time. */
    val pageUrls: List<String> = emptyList(),
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val retryCount: Int = 0,
    val failure: AppError? = null,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, ERROR, CANCELLED }
