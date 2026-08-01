package mihon.desktop.reader

import mihon.domain.reader.ChapterListDirection
import mihon.domain.reader.ChapterNavigationResult
import mihon.domain.reader.ChapterSkipPolicy
import mihon.domain.reader.ReaderChapterEntry
import mihon.domain.reader.findAdjacentChapter
import mihon.domain.reader.markDuplicateChapters

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
    val scanlator: String? = null,
    val isFiltered: Boolean = false,
    val isDuplicate: Boolean = false,
)

fun List<ReaderChapterRef>.withDuplicateChapterFlags(currentChapterId: Long): List<ReaderChapterRef> {
    val markedById = markDuplicateChapters(
        chapters = map {
            ReaderChapterEntry(
                id = it.id,
                chapterNumber = it.chapterNumber,
                scanlator = it.scanlator,
            )
        },
        currentChapterId = currentChapterId,
    ).associateBy(ReaderChapterEntry::id)
    return map { chapter -> chapter.copy(isDuplicate = markedById.getValue(chapter.id).isDuplicate) }
}

/**
 * Computes adjacent-chapter navigation for the reader.
 *
 * [chapters] is sorted ascending by sourceOrder (newest first, same as MangaDetailScreen).
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
    val skipFilteredChapters: Boolean = false,
    val skipDuplicateChapters: Boolean = false,
) {
    val current: ReaderChapterRef
        get() = chapters[currentIndex]

    /** The newer chapter (one step toward the start of the list), or null if already at newest. */
    val nextToRead: ReaderChapterRef?
        get() = find(ChapterListDirection.NEWER)

    /** The older chapter (one step toward the end of the list), or null if already at oldest. */
    val previousRead: ReaderChapterRef?
        get() = find(ChapterListDirection.OLDER)

    fun result(direction: ChapterListDirection): ChapterNavigationResult = findAdjacentChapter(
        chapters = chapters.map {
            ReaderChapterEntry(
                id = it.id,
                isRead = it.isRead,
                isFiltered = it.isFiltered,
                isDuplicate = it.isDuplicate,
            )
        },
        currentIndex = currentIndex,
        direction = direction,
        skipPolicy = ChapterSkipPolicy(
            read = skipReadChapters,
            filtered = skipFilteredChapters,
            duplicate = skipDuplicateChapters,
        ),
    )

    private fun find(direction: ChapterListDirection): ReaderChapterRef? =
        (result(direction) as? ChapterNavigationResult.Found)?.let { chapters[it.index] }

    companion object {
        /** Returns the index of [targetId] in [chapters], defaulting to 0 if not found. */
        fun indexForId(chapters: List<ReaderChapterRef>, targetId: Long): Int =
            chapters.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
    }
}
