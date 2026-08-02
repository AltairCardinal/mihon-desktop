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
