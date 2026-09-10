package mihon.desktop.reader

data class DesktopReaderChapterContext(
    val chapterId: Long,
    val sourceId: Long,
    val chapterUrl: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val chapterNumber: Double,
    val chapterIndex: Int,
    val initialPage: Int,
    val wasRead: Boolean,
    val localChapterPath: String? = null,
    val mangaId: Long = 0L,
    val scanlator: String? = null,
    /** True when the chapter is resolved from a downloaded artifact or another local source. */
    val isDownloaded: Boolean = false,
    val sourceDisplayName: String = sourceId.toString(),
    val disallowNonAsciiFilenames: Boolean = false,
)

/** Exposes the resolved content route to the mounted reader without leaking I/O adapters into UI. */
internal interface DesktopReaderChapterDownloadState {
    val chapterDownloaded: Boolean?
}
