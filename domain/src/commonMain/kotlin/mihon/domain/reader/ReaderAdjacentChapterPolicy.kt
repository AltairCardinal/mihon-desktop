package mihon.domain.reader

/** Canonical adjacent-chapter work emitted by reader presentation anchors. */
sealed interface ReaderAdjacentChapterEffect {
    /** Load only the next chapter's page list; page image work is intentionally excluded. */
    data object LoadAdjacentChapterPageList : ReaderAdjacentChapterEffect
}

/** Original Mihon policy: request next-chapter metadata only within the final five pages. */
object ReaderAdjacentChapterPolicy {

    fun effectForPageAnchor(
        anchorPageIndex: Int,
        pageCount: Int,
    ): ReaderAdjacentChapterEffect? {
        if (pageCount <= 0 || anchorPageIndex !in 0 until pageCount) return null
        return ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList
            .takeIf { pageCount - anchorPageIndex <= LAST_PAGE_WINDOW_SIZE }
    }

    /** A visible chapter-transition page always needs destination metadata, independent of anchors. */
    fun transitionPageEffect(): ReaderAdjacentChapterEffect =
        ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList

    private const val LAST_PAGE_WINDOW_SIZE = 5
}
