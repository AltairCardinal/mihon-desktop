package tachiyomi.domain.chapter

/** The action for a primary click on a chapter row. */
enum class ChapterItemClickAction {
    READ,
    SELECT,
    DESELECT,
}

/**
 * Keeps chapter-row clicks aligned across Android and Desktop.
 *
 * A selected row always toggles off before considering the active-selection branch.
 */
fun chapterItemClickAction(
    chapterSelected: Boolean,
    anyChapterSelected: Boolean,
): ChapterItemClickAction = when {
    chapterSelected -> ChapterItemClickAction.DESELECT
    anyChapterSelected -> ChapterItemClickAction.SELECT
    else -> ChapterItemClickAction.READ
}
