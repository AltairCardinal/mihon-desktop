package mihon.desktop.download

/** A single download job: all pages of one chapter. */
data class DownloadItem(
    val sourceId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val chapterId: Long,
    val chapterUrl: String = "",
    /** Pre-resolved page URLs. If empty, the manager fetches them from the source at download time. */
    val pageUrls: List<String> = emptyList(),
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, ERROR, CANCELLED }
