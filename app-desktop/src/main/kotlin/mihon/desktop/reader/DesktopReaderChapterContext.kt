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
)
