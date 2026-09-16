package mihon.domain.reader

enum class ReaderChapterBoundary { NONE, LOADING, TERMINAL }

/** Existing chapters join directly; only missing neighbours are book/chapter endpoints. */
fun readerChapterBoundary(
    hasAdjacentChapter: Boolean,
    adjacentChapterLoaded: Boolean = true,
): ReaderChapterBoundary = when {
    !hasAdjacentChapter -> ReaderChapterBoundary.TERMINAL
    !adjacentChapterLoaded -> ReaderChapterBoundary.LOADING
    else -> ReaderChapterBoundary.NONE
}
