package mihon.domain.reader.session

import mihon.domain.error.AppError
import mihon.domain.reader.ReaderTransitionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReaderChapterWindowReducerTest {

    @Test
    fun `replacing the window retains additions before releasing chapters that left it`() {
        val initial = ReaderChapterWindowReducer.reduce(
            snapshot = null,
            intent = ReaderChapterWindowIntent.Replace(
                currentChapterId = chapterId(2),
                previousChapterId = chapterId(1),
                nextChapterId = chapterId(3),
            ),
        )

        assertEquals(
            listOf(
                ReaderChapterWindowEffect.RetainChapter(chapterId(2)),
                ReaderChapterWindowEffect.RetainChapter(chapterId(1)),
                ReaderChapterWindowEffect.RetainChapter(chapterId(3)),
            ),
            initial.effects,
        )

        val shifted = ReaderChapterWindowReducer.reduce(
            initial.snapshot,
            ReaderChapterWindowIntent.Replace(
                currentChapterId = chapterId(3),
                previousChapterId = chapterId(2),
                nextChapterId = chapterId(4),
            ),
        )

        assertEquals(
            listOf(
                ReaderChapterWindowEffect.RetainChapter(chapterId(4)),
                ReaderChapterWindowEffect.ReleaseChapter(chapterId(1)),
            ),
            shifted.effects,
        )
        assertEquals(
            setOf(chapterId(2), chapterId(3), chapterId(4)),
            requireNotNull(shifted.snapshot).retainedChapterIds,
        )
    }

    @Test
    fun `prefetching an adjacent chapter requests its page list without activating it`() {
        val window = window(current = 2, previous = 1, next = 3)

        val prefetched = ReaderChapterWindowReducer.reduce(
            window,
            ReaderChapterWindowIntent.PrefetchAdjacent(ReaderTransitionDirection.NEXT),
        )

        assertSame(window, prefetched.snapshot)
        assertEquals(
            listOf(
                ReaderChapterWindowEffect.BeginPageListLoad(
                    chapterId = chapterId(3),
                    purpose = ReaderChapterLoadPurpose.PREFETCH,
                ),
            ),
            prefetched.effects,
        )
    }

    @Test
    fun `opening next chapter publishes zero-page loading before one exact activation`() {
        val window = window(current = 2, previous = 1, next = 3)
        val intent = ReaderChapterWindowIntent.OpenAdjacent(
            direction = ReaderTransitionDirection.NEXT,
            expectedCurrentChapterId = chapterId(2),
            expectedTargetChapterId = chapterId(3),
            replacementChapterId = chapterId(4),
        )

        val opened = ReaderChapterWindowReducer.reduce(window, intent)
        val beginLoad = opened.effects.filterIsInstance<ReaderChapterWindowEffect.BeginPageListLoad>().single()
        val targetLoading = beginLoad.reduceSession(ReaderSessionSnapshot.initial(beginLoad.chapterId)).snapshot

        val openedSnapshot = requireNotNull(opened.snapshot)
        assertEquals(chapterId(3), openedSnapshot.currentChapterId)
        assertEquals(chapterId(2), openedSnapshot.previousChapterId)
        assertEquals(chapterId(4), openedSnapshot.nextChapterId)
        assertEquals(ReaderChapterLoadPurpose.ACTIVATE, beginLoad.purpose)
        assertEquals(emptyList<ReaderPageSession>(), targetLoading.activeChapter.pages)
        assertInstanceOf(
            ReaderChapterLoadState.LoadingPageList::class.java,
            targetLoading.activeChapter.loadState,
        )
        assertEquals(
            ReaderChapterActivation(
                sequence = 1,
                direction = ReaderTransitionDirection.NEXT,
                fromChapterId = chapterId(2),
                toChapterId = chapterId(3),
            ),
            opened.effects.filterIsInstance<ReaderChapterWindowEffect.ActivateChapter>().single().activation,
        )

        val replayed = ReaderChapterWindowReducer.reduce(openedSnapshot, intent)
        assertSame(openedSnapshot, replayed.snapshot)
        assertEquals(emptyList<ReaderChapterWindowEffect>(), replayed.effects)
    }

    @Test
    fun `an adjacent failure changes only its own session and retry targets that chapter`() {
        val window = window(current = 2, previous = 1, next = 3)
        val current = loadedSession(chapterId(2))
        val targetLoading = ReaderSessionReducer.reduce(
            ReaderSessionSnapshot.initial(chapterId(3)),
            ReaderSessionIntent.OpenChapter(chapterId(3)),
        ).snapshot

        val failedTarget = ReaderSessionReducer.reduce(
            targetLoading,
            ReaderSessionIntent.PageListFailed(
                chapterId = chapterId(3),
                generation = targetLoading.generation,
                error = AppError.Network(),
            ),
        ).snapshot
        val retry = ReaderChapterWindowReducer.reduce(
            window,
            ReaderChapterWindowIntent.RetryChapter(chapterId(3)),
        )

        assertInstanceOf(ReaderChapterLoadState.Loaded::class.java, current.activeChapter.loadState)
        assertInstanceOf(ReaderChapterLoadState.Error::class.java, failedTarget.activeChapter.loadState)
        assertEquals(chapterId(2), current.activeChapter.id)
        assertEquals(chapterId(3), failedTarget.activeChapter.id)
        assertEquals(
            listOf(
                ReaderChapterWindowEffect.BeginPageListLoad(
                    chapterId = chapterId(3),
                    purpose = ReaderChapterLoadPurpose.RETRY,
                ),
            ),
            retry.effects,
        )
    }

    @Test
    fun `opening beyond either edge emits a boundary without changing the window`() {
        val window = window(current = 2, previous = null, next = null)

        ReaderTransitionDirection.entries.forEach { direction ->
            val result = ReaderChapterWindowReducer.reduce(
                window,
                ReaderChapterWindowIntent.OpenAdjacent(
                    direction = direction,
                    expectedCurrentChapterId = chapterId(2),
                    expectedTargetChapterId = null,
                    replacementChapterId = null,
                ),
            )

            assertSame(window, result.snapshot)
            assertEquals(listOf(ReaderChapterWindowEffect.Boundary(direction)), result.effects)
        }
    }

    @Test
    fun `closing releases every retained chapter once`() {
        val window = window(current = 2, previous = 1, next = 3)

        val closed = ReaderChapterWindowReducer.reduce(window, ReaderChapterWindowIntent.Close)

        assertEquals(null, closed.snapshot)
        assertEquals(
            listOf(
                ReaderChapterWindowEffect.ReleaseChapter(chapterId(2)),
                ReaderChapterWindowEffect.ReleaseChapter(chapterId(1)),
                ReaderChapterWindowEffect.ReleaseChapter(chapterId(3)),
            ),
            closed.effects,
        )
    }

    private fun window(
        current: Long,
        previous: Long?,
        next: Long?,
    ): ReaderChapterWindowSnapshot = requireNotNull(
        ReaderChapterWindowReducer.reduce(
            snapshot = null,
            intent = ReaderChapterWindowIntent.Replace(
                currentChapterId = chapterId(current),
                previousChapterId = previous?.let(::chapterId),
                nextChapterId = next?.let(::chapterId),
            ),
        ).snapshot,
    )

    private fun loadedSession(chapterId: ReaderChapterId): ReaderSessionSnapshot {
        val loading = ReaderSessionReducer.reduce(
            ReaderSessionSnapshot.initial(chapterId),
            ReaderSessionIntent.OpenChapter(chapterId),
        ).snapshot
        return ReaderSessionReducer.reduce(
            loading,
            ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = loading.generation,
                pages = listOf(ReaderPageDescriptor(0, "/page/0")),
            ),
        ).snapshot
    }

    private fun chapterId(value: Long) = ReaderChapterId(value)
}
