package eu.kanade.tachiyomi.ui.reader.model

import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderTransitionDirection

sealed class ChapterTransition {

    abstract val from: ReaderChapter
    abstract val to: ReaderChapter?

    class Prev(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
    ) : ChapterTransition()

    class Next(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
    ) : ChapterTransition()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterTransition) return false
        if (from == other.from && to == other.to) return true
        if (from == other.to && to == other.from) return true
        return false
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + (to?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "${javaClass.simpleName}(from=${from.chapter.url}, to=${to?.chapter?.url})"
    }
}

internal fun ChapterTransition.toSharedTransitionModel(
    state: ReaderChapterState = to?.sharedStateFlow?.value ?: ReaderChapterState.Wait,
): ReaderChapterTransitionModel = ReaderChapterTransitionModel(
    direction = when (this) {
        is ChapterTransition.Prev -> ReaderTransitionDirection.PREVIOUS
        is ChapterTransition.Next -> ReaderTransitionDirection.NEXT
    },
    from = from.toSharedChapterModel(),
    to = to?.toSharedChapterModel(),
    state = state,
)

private fun ReaderChapter.toSharedChapterModel() = ReaderChapterModel(
    id = checkNotNull(chapter.id),
    url = chapter.url,
    name = chapter.name,
    chapterNumber = chapter.chapter_number.toDouble(),
)
