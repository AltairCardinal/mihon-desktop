package eu.kanade.tachiyomi.ui.reader.model

import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadPurpose
import mihon.domain.reader.session.ReaderChapterWindowEffect
import mihon.domain.reader.session.ReaderChapterWindowIntent
import mihon.domain.reader.session.ReaderChapterWindowReducer
import mihon.domain.reader.session.ReaderChapterWindowReduction
import mihon.domain.reader.session.ReaderChapterWindowSnapshot

/** Android object ownership adapter for the canonical shared chapter-window reducer. */
internal class ReaderChapterWindowOwner {

    private val retainedChapters = mutableMapOf<ReaderChapterId, ReaderChapter>()

    var snapshot: ReaderChapterWindowSnapshot? = null
        private set

    fun replace(chapters: ViewerChapters): ReaderChapterWindowReduction = dispatch(
        intent = ReaderChapterWindowIntent.Replace(
            currentChapterId = chapters.currChapter.sharedChapterId(),
            previousChapterId = chapters.prevChapter?.sharedChapterId(),
            nextChapterId = chapters.nextChapter?.sharedChapterId(),
        ),
        availableChapters = listOfNotNull(chapters.currChapter, chapters.prevChapter, chapters.nextChapter),
    )

    fun dispatch(
        intent: ReaderChapterWindowIntent,
        availableChapters: Iterable<ReaderChapter> = emptyList(),
    ): ReaderChapterWindowReduction {
        val availableById = availableChapters.associateBy(ReaderChapter::sharedChapterId)
        val reduction = ReaderChapterWindowReducer.reduce(snapshot, intent)

        reduction.effects.filterIsInstance<ReaderChapterWindowEffect.RetainChapter>().forEach { effect ->
            if (effect.chapterId !in retainedChapters) {
                val chapter = checkNotNull(availableById[effect.chapterId]) {
                    "Missing Android chapter for retained shared id ${effect.chapterId.value}"
                }
                chapter.ref()
                retainedChapters[effect.chapterId] = chapter
            }
        }

        snapshot = reduction.snapshot

        reduction.effects.filterIsInstance<ReaderChapterWindowEffect.ReleaseChapter>().forEach { effect ->
            retainedChapters.remove(effect.chapterId)?.unref()
        }
        check(snapshot?.retainedChapterIds.orEmpty() == retainedChapters.keys) {
            "Android retained chapters must match the shared chapter window"
        }
        return reduction
    }

    fun pageListEffect(
        chapter: ReaderChapter,
        purpose: ReaderChapterLoadPurpose,
    ): ReaderChapterWindowEffect.BeginPageListLoad? {
        val chapterId = chapter.sharedChapterId()
        val current = snapshot ?: return null
        val intent = when (purpose) {
            ReaderChapterLoadPurpose.RETRY -> ReaderChapterWindowIntent.RetryChapter(chapterId)
            ReaderChapterLoadPurpose.PREFETCH,
            ReaderChapterLoadPurpose.ACTIVATE,
            -> {
                val direction = current.directionTo(chapterId) ?: return null
                ReaderChapterWindowIntent.PrefetchAdjacent(direction)
            }
        }
        return ReaderChapterWindowReducer.reduce(current, intent).effects
            .filterIsInstance<ReaderChapterWindowEffect.BeginPageListLoad>()
            .singleOrNull { it.chapterId == chapterId }
            ?.copy(purpose = purpose)
    }

    fun viewerChapters(): ViewerChapters? {
        val current = snapshot ?: return null
        return ViewerChapters(
            currChapter = checkNotNull(retainedChapters[current.currentChapterId]),
            prevChapter = current.previousChapterId?.let { retainedChapters.getValue(it) },
            nextChapter = current.nextChapterId?.let { retainedChapters.getValue(it) },
        )
    }

    fun close() {
        dispatch(ReaderChapterWindowIntent.Close)
    }

    private fun ReaderChapterWindowSnapshot.directionTo(
        chapterId: ReaderChapterId,
    ): ReaderTransitionDirection? = when (chapterId) {
        previousChapterId -> ReaderTransitionDirection.PREVIOUS
        nextChapterId -> ReaderTransitionDirection.NEXT
        else -> null
    }
}

internal fun ReaderChapter.sharedChapterId(): ReaderChapterId = ReaderChapterId(checkNotNull(chapter.id))
