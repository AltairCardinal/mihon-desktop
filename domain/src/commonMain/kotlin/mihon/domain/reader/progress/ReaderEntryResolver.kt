package mihon.domain.reader.progress

import mihon.domain.reader.session.ReaderChapterId

enum class ReaderChapterDisplayOrder {
    STORY_ASCENDING,
    STORY_DESCENDING,
}

data class ReaderEntryCandidate(
    val chapterId: ReaderChapterId,
    val isRead: Boolean,
)

/** Selects the story-earliest unfinished chapter without inferring order from list position alone. */
fun resolveReaderEntry(
    chapters: List<ReaderEntryCandidate>,
    displayOrder: ReaderChapterDisplayOrder,
): ReaderChapterId? = when (displayOrder) {
    ReaderChapterDisplayOrder.STORY_ASCENDING -> chapters.firstOrNull { !it.isRead }
    ReaderChapterDisplayOrder.STORY_DESCENDING -> chapters.lastOrNull { !it.isRead }
}?.chapterId

/** Explicit restoration/sync wins; choosing an already read chapter starts a fresh reading pass. */
fun resolveReaderChapterEntryPage(isRead: Boolean, lastPageRead: Long, restoredPage: Int? = null): Int =
    restoredPage?.coerceAtLeast(0) ?: if (isRead) 0 else lastPageRead.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
