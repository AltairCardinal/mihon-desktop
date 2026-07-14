package mihon.desktop.reader

/**
 * Lightweight reference to a chapter passed into the reader.
 * Only serializable primitives so it can be a Voyager screen param.
 */
data class ReaderChapterRef(
    val id: Long,
    val url: String,
    val name: String,
    val isRead: Boolean = false,
    val chapterNumber: Double = 0.0,
)

/**
 * Computes adjacent-chapter navigation for the reader.
 *
 * [chapters] is sorted descending by sourceOrder (newest first, same as MangaDetailScreen).
 * Therefore:
 *   - "next to read" = lower index  (newer chapter, higher number)
 *   - "previous read" = higher index (older chapter, lower number)
 *
 * When [skipReadChapters] is true, already-read chapters are skipped when
 * navigating to the next/previous chapter.
 */
class ReaderNavigator(
    val chapters: List<ReaderChapterRef>,
    val currentIndex: Int,
    val skipReadChapters: Boolean = false,
) {
    val current: ReaderChapterRef
        get() = chapters[currentIndex]

    /** The newer chapter (one step toward the start of the list), or null if already at newest. */
    val nextToRead: ReaderChapterRef?
        get() {
            if (!skipReadChapters) return chapters.getOrNull(currentIndex - 1)
            for (i in (currentIndex - 1) downTo 0) {
                if (!chapters[i].isRead) return chapters[i]
            }
            return null
        }

    /** The older chapter (one step toward the end of the list), or null if already at oldest. */
    val previousRead: ReaderChapterRef?
        get() {
            if (!skipReadChapters) return chapters.getOrNull(currentIndex + 1)
            for (i in (currentIndex + 1) until chapters.size) {
                if (!chapters[i].isRead) return chapters[i]
            }
            return null
        }

    companion object {
        /** Returns the index of [targetId] in [chapters], defaulting to 0 if not found. */
        fun indexForId(chapters: List<ReaderChapterRef>, targetId: Long): Int =
            chapters.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
    }
}
