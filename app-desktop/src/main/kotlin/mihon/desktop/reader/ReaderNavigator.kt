package mihon.desktop.reader

/**
 * Lightweight reference to a chapter passed into the reader.
 * Only serializable primitives so it can be a Voyager screen param.
 */
data class ReaderChapterRef(
    val id: Long,
    val url: String,
    val name: String,
)

/**
 * Computes adjacent-chapter navigation for the reader.
 *
 * [chapters] is sorted descending by sourceOrder (newest first, same as MangaDetailScreen).
 * Therefore:
 *   - "next to read" = lower index  (newer chapter, higher number)
 *   - "previous read" = higher index (older chapter, lower number)
 */
class ReaderNavigator(
    val chapters: List<ReaderChapterRef>,
    val currentIndex: Int,
) {
    val current: ReaderChapterRef
        get() = chapters[currentIndex]

    /** The newer chapter (one step toward the start of the list), or null if already at newest. */
    val nextToRead: ReaderChapterRef?
        get() = chapters.getOrNull(currentIndex - 1)

    /** The older chapter (one step toward the end of the list), or null if already at oldest. */
    val previousRead: ReaderChapterRef?
        get() = chapters.getOrNull(currentIndex + 1)

    companion object {
        /** Returns the index of [targetId] in [chapters], defaulting to 0 if not found. */
        fun indexForId(chapters: List<ReaderChapterRef>, targetId: Long): Int =
            chapters.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
    }
}
