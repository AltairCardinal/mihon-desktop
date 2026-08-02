package mihon.domain.reader.session

import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReaderSessionReducerTest {

    @Test
    fun `opening a chapter exposes zero pages and one page-list request`() {
        val chapterId = ReaderChapterId(7)
        val initial = ReaderSessionSnapshot.initial(chapterId)

        val reduction = ReaderSessionReducer.reduce(initial, ReaderSessionIntent.OpenChapter(chapterId))

        assertEquals(0, initial.activeChapter.pages.size)
        assertInstanceOf(ReaderChapterLoadState.Wait::class.java, initial.activeChapter.loadState)
        assertEquals(1, reduction.snapshot.generation)
        assertEquals(emptyList<ReaderPageSession>(), reduction.snapshot.activeChapter.pages)
        assertInstanceOf(ReaderChapterLoadState.LoadingPageList::class.java, reduction.snapshot.activeChapter.loadState)
        assertEquals(
            listOf(ReaderSessionEffect.LoadPageList(chapterId, generation = 1)),
            reduction.effects,
        )
    }

    @Test
    fun `page list arrives atomically with stable identities even when urls are empty`() {
        val chapterId = ReaderChapterId(7)
        val loading = ReaderSessionReducer.reduce(
            ReaderSessionSnapshot.initial(chapterId),
            ReaderSessionIntent.OpenChapter(chapterId),
        ).snapshot

        val loaded = ReaderSessionReducer.reduce(
            loading,
            ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = loading.generation,
                pages = listOf(
                    ReaderPageDescriptor(sourcePageIndex = 0, url = ""),
                    ReaderPageDescriptor(sourcePageIndex = 1, url = "/page/1", imageUrl = null),
                ),
            ),
        ).snapshot.activeChapter

        assertInstanceOf(ReaderChapterLoadState.Loaded::class.java, loaded.loadState)
        assertEquals(
            listOf(ReaderPageId(chapterId, 0), ReaderPageId(chapterId, 1)),
            loaded.pages.map(ReaderPageSession::id),
        )
        assertEquals(listOf("", "/page/1"), loaded.pages.map(ReaderPageSession::url))
        assertEquals(
            listOf(ReaderPageLoadState.Queued, ReaderPageLoadState.Queued),
            loaded.pages.map(ReaderPageSession::loadState),
        )
    }

    @Test
    fun `page state changes preserve the stable page identity`() {
        val chapterId = ReaderChapterId(7)
        val pageId = ReaderPageId(chapterId, 0)
        val loaded = loadedSession(chapterId)
        val states = listOf(
            ReaderPageLoadState.ResolvingImage,
            ReaderPageLoadState.Downloading(progressPercent = 42),
            ReaderPageLoadState.Ready,
            ReaderPageLoadState.Error(AppError.Network()),
        )

        val snapshots = states.runningFold(loaded) { snapshot, state ->
            ReaderSessionReducer.reduce(
                snapshot,
                ReaderSessionIntent.PageStateChanged(
                    pageId = pageId,
                    generation = snapshot.generation,
                    loadState = state,
                ),
            ).snapshot
        }

        assertEquals(states, snapshots.drop(1).map { it.activeChapter.pages.single().loadState })
        assertEquals(List(states.size + 1) { pageId }, snapshots.map { it.activeChapter.pages.single().id })
    }

    @Test
    fun `materialized content updates image and encoded reference without changing page identity`() {
        val chapterId = ReaderChapterId(7)
        val pageId = ReaderPageId(chapterId, 0)
        val loaded = loadedSession(chapterId)
        val encodedPageRef = EncodedPageRef("encoded-page-0")

        val materialized = ReaderSessionReducer.reduce(
            loaded,
            ReaderSessionIntent.PageContentChanged(
                pageId = pageId,
                generation = loaded.generation,
                imageUrl = "https://example.test/image/0",
                encodedPageRef = encodedPageRef,
                loadState = ReaderPageLoadState.Ready,
            ),
        ).snapshot.activeChapter.pages.single()

        assertEquals(pageId, materialized.id)
        assertEquals("https://example.test/image/0", materialized.imageUrl)
        assertEquals(encodedPageRef, materialized.encodedPageRef)
        assertEquals(ReaderPageLoadState.Ready, materialized.loadState)
    }

    @Test
    fun `replacement generation rejects stale page lists errors and page states`() {
        val chapterId = ReaderChapterId(7)
        val first = ReaderSessionReducer.reduce(
            ReaderSessionSnapshot.initial(chapterId),
            ReaderSessionIntent.OpenChapter(chapterId),
        ).snapshot
        val replacement = ReaderSessionReducer.reduce(first, ReaderSessionIntent.OpenChapter(chapterId)).snapshot

        val stalePageList = ReaderSessionReducer.reduce(
            replacement,
            ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = first.generation,
                pages = listOf(ReaderPageDescriptor(0, "/stale")),
            ),
        ).snapshot
        val staleError = ReaderSessionReducer.reduce(
            replacement,
            ReaderSessionIntent.PageListFailed(chapterId, first.generation, AppError.Network()),
        ).snapshot
        val stalePageState = ReaderSessionReducer.reduce(
            replacement,
            ReaderSessionIntent.PageStateChanged(
                ReaderPageId(chapterId, 0),
                first.generation,
                ReaderPageLoadState.Ready,
            ),
        ).snapshot

        assertSame(replacement, stalePageList)
        assertSame(replacement, staleError)
        assertSame(replacement, stalePageState)
        assertEquals(2, replacement.generation)
        assertEquals(emptyList<ReaderPageSession>(), replacement.activeChapter.pages)
    }

    @Test
    fun `the first page-list terminal result wins within one generation`() {
        val chapterId = ReaderChapterId(7)
        val loading = ReaderSessionReducer.reduce(
            ReaderSessionSnapshot.initial(chapterId),
            ReaderSessionIntent.OpenChapter(chapterId),
        ).snapshot
        val firstLoaded = ReaderSessionReducer.reduce(
            loading,
            ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = loading.generation,
                pages = listOf(ReaderPageDescriptor(0, "/first")),
            ),
        ).snapshot

        val duplicateLoaded = ReaderSessionReducer.reduce(
            firstLoaded,
            ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = loading.generation,
                pages = listOf(ReaderPageDescriptor(0, "/replacement")),
            ),
        ).snapshot
        val lateError = ReaderSessionReducer.reduce(
            firstLoaded,
            ReaderSessionIntent.PageListFailed(chapterId, loading.generation, AppError.Network()),
        ).snapshot

        assertSame(firstLoaded, duplicateLoaded)
        assertSame(firstLoaded, lateError)

        val firstError = ReaderSessionReducer.reduce(
            loading,
            ReaderSessionIntent.PageListFailed(chapterId, loading.generation, AppError.Network()),
        ).snapshot
        val lateLoaded = ReaderSessionReducer.reduce(
            firstError,
            ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = loading.generation,
                pages = listOf(ReaderPageDescriptor(0, "/late")),
            ),
        ).snapshot

        assertSame(firstError, lateLoaded)
    }

    @Test
    fun `page list rejects duplicate source identities`() {
        val chapterId = ReaderChapterId(7)
        val loading = ReaderSessionReducer.reduce(
            ReaderSessionSnapshot.initial(chapterId),
            ReaderSessionIntent.OpenChapter(chapterId),
        ).snapshot

        assertThrows(IllegalArgumentException::class.java) {
            ReaderSessionReducer.reduce(
                loading,
                ReaderSessionIntent.PageListLoaded(
                    chapterId,
                    loading.generation,
                    listOf(ReaderPageDescriptor(0, "/a"), ReaderPageDescriptor(0, "/b")),
                ),
            )
        }
    }

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
}
