package mihon.desktop.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.progress.ReaderProgressEffect
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderSessionCore
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderSessionIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `one session opens at zero pages then materializes visible pages and settled progress`() = runTest {
        val pageListGate = CompletableDeferred<Unit>()
        val progress = mutableListOf<Pair<DesktopReaderChapterContext, ReaderProgressEffect>>()
        val core = core(initialChapterId = 1L)
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core,
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory {
                ReaderChapterContentPort {
                    pageListGate.await()
                    listOf(
                        ReaderPageDescriptor(0, url = "/page/0", imageUrl = "https://example.test/0"),
                        ReaderPageDescriptor(1, url = "/page/1", imageUrl = "https://example.test/1"),
                    )
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor ->
                readyPort(descriptor)
            },
            progressPort = DesktopReaderProgressPort { chapter, effect -> progress += chapter to effect },
            parentScope = this,
        )

        session.start()

        assertSame(core, session.core)
        assertTrue(session.state.value.snapshot.activeChapter.pages.isEmpty())
        assertInstanceOf(
            ReaderChapterLoadState.LoadingPageList::class.java,
            session.state.value.snapshot.activeChapter.loadState,
        )

        pageListGate.complete(Unit)
        advanceUntilIdle()
        val loaded = session.state.value.snapshot.activeChapter
        assertEquals(listOf(0, 1), loaded.pages.map { it.id.sourcePageIndex })
        assertTrue(loaded.pages.all { it.loadState == ReaderPageLoadState.Queued })

        val visible = loaded.pages.first().id
        session.settleViewport(setOf(visible), visible)
        advanceUntilIdle()

        val materialized = session.state.value.snapshot.activeChapter.pages
        assertEquals(ReaderPageLoadState.Ready, materialized.first().loadState)
        assertEquals(EncodedPageRef("encoded:1:0"), materialized.first().encodedPageRef)
        assertEquals(visible, materialized.first().id)
        assertEquals(1, progress.size)
        assertEquals(0, progress.single().second.lastPageRead)
        session.close()
    }

    @Test
    fun `settling the last page then paging backward never makes the chapter unread`() = runTest {
        val progress = mutableListOf<ReaderProgressEffect>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-monotonic")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory {
                ReaderChapterContentPort {
                    List(3) { index ->
                        ReaderPageDescriptor(index, url = "/page/$index", imageUrl = "https://example.test/$index")
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect },
            parentScope = this,
        )
        session.start()
        advanceUntilIdle()
        val pages = session.state.value.snapshot.activeChapter.pages

        try {
            session.settleViewport(setOf(pages[2].id), pages[2].id)
            session.settleViewport(setOf(pages[0].id), pages[0].id)
            advanceUntilIdle()

            assertEquals(listOf(2, 0), progress.map(ReaderProgressEffect::lastPageRead))
            assertTrue(progress.all(ReaderProgressEffect::isRead))
            assertTrue(progress.last().wasRead)
        } finally {
            session.close()
        }
    }

    @Test
    fun `closing immediately after settlement lets the final progress write finish`() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val writeCompleted = CompletableDeferred<Unit>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-close-flush")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory {
                ReaderChapterContentPort {
                    listOf(ReaderPageDescriptor(0, url = "/page/0", imageUrl = "https://example.test/0"))
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, _ ->
                writeStarted.complete(Unit)
                allowWrite.await()
                writeCompleted.complete(Unit)
            },
            parentScope = this,
        )
        session.start()
        advanceUntilIdle()
        val page = session.state.value.snapshot.activeChapter.pages.single().id

        session.settleViewport(setOf(page), page)
        writeStarted.await()
        session.close()
        allowWrite.complete(Unit)
        advanceUntilIdle()

        assertTrue(writeCompleted.isCompleted)
    }

    @Test
    fun `adjacent activation reuses core publishes zero-page loading and retry preserves page identity`() = runTest {
        val targetGate = CompletableDeferred<Unit>()
        var targetAttempts = 0
        val core = core(initialChapterId = 1L)
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core,
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 2L) targetGate.await()
                    listOf(ReaderPageDescriptor(0, url = "/${chapter.chapterId}/0", imageUrl = "image"))
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: mihon.domain.reader.materialize.ReaderPageFetchRequest) =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef {
                        if (chapter.chapterId == 2L && targetAttempts++ == 0) {
                            throw AppErrorException(AppError.Network())
                        }
                        return EncodedPageRef("encoded:${chapter.chapterId}:${descriptor.sourcePageIndex}")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
        )
        session.start()
        advanceUntilIdle()
        val originalCore = session.core

        session.activate(context(2L))

        assertSame(originalCore, session.core)
        assertEquals(ReaderChapterId(2L), session.state.value.snapshot.activeChapter.id)
        assertTrue(session.state.value.snapshot.activeChapter.pages.isEmpty())
        assertInstanceOf(
            ReaderChapterLoadState.LoadingPageList::class.java,
            session.state.value.snapshot.activeChapter.loadState,
        )

        targetGate.complete(Unit)
        advanceUntilIdle()
        val pageId = session.state.value.snapshot.activeChapter.pages.single().id
        session.settleViewport(setOf(pageId), pageId)
        advanceUntilIdle()
        assertInstanceOf(
            ReaderPageLoadState.Error::class.java,
            session.state.value.snapshot.activeChapter.pages.single().loadState,
        )

        session.retryPage(pageId)
        advanceUntilIdle()

        val retried = session.state.value.snapshot.activeChapter.pages.single()
        assertEquals(pageId, retried.id)
        assertEquals(ReaderPageLoadState.Ready, retried.loadState)
        assertEquals(EncodedPageRef("encoded:2:0"), retried.encodedPageRef)
        session.close()
    }

    @Test
    fun `full next chapter waits for every current page then materializes all encoded pages without progress`() = runTest {
        val releaseLastCurrentPage = CompletableDeferred<Unit>()
        val nextPageLists = mutableListOf<Long>()
        val nextPageFetches = mutableListOf<Int>()
        val progress = mutableListOf<ReaderProgressEffect>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-full-next")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 2L) nextPageLists += chapter.chapterId
                    List(if (chapter.chapterId == 1L) 6 else 3) { index ->
                        ReaderPageDescriptor(index, url = "/${chapter.chapterId}/$index", imageUrl = "image:$index")
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: mihon.domain.reader.materialize.ReaderPageFetchRequest) =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef {
                        if (chapter.chapterId == 1L && descriptor.sourcePageIndex == 5) {
                            releaseLastCurrentPage.await()
                        }
                        if (chapter.chapterId == 2L) nextPageFetches += descriptor.sourcePageIndex
                        return EncodedPageRef("encoded:${chapter.chapterId}:${descriptor.sourcePageIndex}")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )
        session.start()
        session.updateNextChapter(context(2L), firstViewportPageCount = 2)
        advanceUntilIdle()
        val first = session.state.value.snapshot.activeChapter.pages.first().id

        session.settleViewport(setOf(first), first)
        advanceUntilIdle()

        assertTrue(nextPageLists.isEmpty())
        assertTrue(nextPageFetches.isEmpty())
        assertEquals(1, progress.size)

        releaseLastCurrentPage.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(2L), nextPageLists)
        assertEquals(listOf(0, 1, 2), nextPageFetches)
        assertEquals(1, progress.size)

        session.activate(context(2L))
        advanceUntilIdle()
        val activatedPages = session.state.value.snapshot.activeChapter.pages
        assertTrue(activatedPages.all { it.loadState == ReaderPageLoadState.Ready })
        session.settleViewport(setOf(activatedPages.first().id), activatedPages.first().id)
        advanceUntilIdle()

        assertEquals(listOf(2L), nextPageLists)
        assertEquals(listOf(0, 1, 2), nextPageFetches)
        assertEquals(2, progress.size)
        session.close()
    }

    @Test
    fun `first viewport mode materializes only its bounded next chapter prefix`() = runTest {
        val nextPageFetches = mutableListOf<Int>()
        val session = readyCurrentSession(
            directory = "encoded-first-viewport",
            mode = NextChapterPrefetchMode.FIRST_VIEWPORT,
            nextPageFetches = nextPageFetches,
            parentScope = this,
        )
        session.start()
        session.updateNextChapter(context(2L), firstViewportPageCount = 2)
        advanceUntilIdle()

        assertEquals(listOf(0, 1), nextPageFetches)
        session.close()
    }

    @Test
    fun `off mode keeps last five page-list preload but never fetches adjacent images`() = runTest {
        var nextPageListLoads = 0
        val nextPageFetches = mutableListOf<Int>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-off")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 2L) nextPageListLoads++
                    List(if (chapter.chapterId == 1L) 10 else 3) { index -> readyDescriptor(chapter.chapterId, index) }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                if (chapter.chapterId == 2L) nextPageFetches += descriptor.sourcePageIndex
                readyPort(descriptor)
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.OFF,
        )
        session.start()
        session.updateNextChapter(context(2L), firstViewportPageCount = 2)
        advanceUntilIdle()
        val pages = session.state.value.snapshot.activeChapter.pages

        session.settleViewport(setOf(pages[0].id), pages[0].id)
        advanceUntilIdle()
        assertEquals(0, nextPageListLoads)

        session.settleViewport(setOf(pages[5].id), pages[5].id)
        advanceUntilIdle()

        assertEquals(1, nextPageListLoads)
        assertTrue(nextPageFetches.isEmpty())
        session.close()
    }

    @Test
    fun `switching off cancels a policy-only next chapter page-list request`() = runTest {
        val nextPageListStarted = CompletableDeferred<Unit>()
        val nextPageListCancelled = CompletableDeferred<Unit>()
        val nextPageFetches = mutableListOf<Int>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-switch-off")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 2L) {
                        nextPageListStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            nextPageListCancelled.complete(Unit)
                        }
                    }
                    listOf(readyDescriptor(chapter.chapterId, 0))
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                if (chapter.chapterId == 2L) nextPageFetches += descriptor.sourcePageIndex
                readyPort(descriptor)
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )
        session.start()
        session.updateNextChapter(context(2L), firstViewportPageCount = 1)
        nextPageListStarted.await()

        session.setNextChapterPrefetchMode(NextChapterPrefetchMode.OFF)
        nextPageListCancelled.await()
        advanceUntilIdle()

        assertTrue(nextPageFetches.isEmpty())
        assertEquals(NextChapterPrefetchMode.OFF, session.currentNextChapterPrefetchMode)
        session.close()
    }

    @Test
    fun `non cooperative page cancellation keeps physical image requests within policy plus one stale request`() = runTest {
        val startedPages = mutableListOf<Int>()
        val releasePages = List(3) { CompletableDeferred<Unit>() }
        val pageStarted = List(3) { CompletableDeferred<Unit>() }
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-physical-page-bound")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory {
                ReaderChapterContentPort {
                    List(3) { index -> ReaderPageDescriptor(index, url = "/1/$index", imageUrl = "image:$index") }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) = requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        val index = descriptor.sourcePageIndex
                        startedPages += index
                        pageStarted[index].complete(Unit)
                        withContext(NonCancellable) { releasePages[index].await() }
                        return EncodedPageRef("encoded:1:$index")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
        )
        session.start()
        try {
            runCurrent()
            val pages = session.state.value.snapshot.activeChapter.pages

            session.settleViewport(setOf(pages[0].id), pages[0].id)
            pageStarted[0].await()
            session.settleViewport(setOf(pages[1].id), pages[1].id)
            pageStarted[1].await()
            session.settleViewport(setOf(pages[2].id), pages[2].id)
            runCurrent()

            assertEquals(listOf(0, 1), startedPages)

            releasePages[0].complete(Unit)
            pageStarted[2].await()
            assertEquals(listOf(0, 1, 2), startedPages)
        } finally {
            releasePages.forEach { it.complete(Unit) }
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `non cooperative target switches keep physical chapter requests within policy plus one stale request`() = runTest {
        val startedChapters = mutableListOf<Long>()
        val releaseChapters = (2L..4L).associateWith { CompletableDeferred<Unit>() }
        val chapterStarted = (2L..4L).associateWith { CompletableDeferred<Unit>() }
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-physical-chapter-bound")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 1L) {
                        listOf(readyDescriptor(1L, 0))
                    } else {
                        startedChapters += chapter.chapterId
                        chapterStarted.getValue(chapter.chapterId).complete(Unit)
                        withContext(NonCancellable) { releaseChapters.getValue(chapter.chapterId).await() }
                        listOf(readyDescriptor(chapter.chapterId, 0))
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )
        session.start()
        try {
            runCurrent()

            session.updateNextChapter(context(2L), firstViewportPageCount = 1)
            chapterStarted.getValue(2L).await()
            session.updateNextChapter(context(3L), firstViewportPageCount = 1)
            chapterStarted.getValue(3L).await()
            session.updateNextChapter(context(4L), firstViewportPageCount = 1)
            runCurrent()

            assertEquals(listOf(2L, 3L), startedChapters)

            releaseChapters.getValue(2L).complete(Unit)
            chapterStarted.getValue(4L).await()
            assertEquals(listOf(2L, 3L, 4L), startedChapters)
        } finally {
            releaseChapters.values.forEach { it.complete(Unit) }
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `late storage failure from an old target cannot cancel the new target prefetch`() = runTest {
        val oldFailurePublished = CompletableDeferred<Unit>()
        val allowOldFailureReturn = CompletableDeferred<Unit>()
        val newPageStarted = CompletableDeferred<Unit>()
        val allowNewPageReturn = CompletableDeferred<Unit>()
        var newPageAttempts = 0
        val progress = mutableListOf<ReaderProgressEffect>()
        val storageError = AppError.Storage(IllegalStateException("old quota"))
        val executor = object : ReaderMaterializeExecutor {
            override suspend fun materializeChapter(
                request: ReaderChapterContentRequest,
                port: ReaderChapterContentPort,
            ): ReaderChapterMaterializeResult = CanonicalReaderMaterializeExecutor.materializeChapter(request, port)

            override suspend fun materializePage(
                request: ReaderPageFetchRequest,
                port: ReaderPageFetchPort,
                forceRefresh: Boolean,
                publish: (ReaderPageMaterializeEvent) -> Boolean,
            ): ReaderPageMaterializeResult {
                if (request.pageId.chapterId == ReaderChapterId(2L)) {
                    assertTrue(publish(ReaderPageMaterializeEvent.Failed(storageError)))
                    oldFailurePublished.complete(Unit)
                    try {
                        withContext(NonCancellable) { allowOldFailureReturn.await() }
                    } catch (_: CancellationException) {
                        // Deliberately emulate an I/O adapter that returns a terminal result after cancellation.
                    }
                    return ReaderPageMaterializeResult.Failed(storageError)
                }
                if (request.pageId.chapterId == ReaderChapterId(3L)) {
                    newPageAttempts++
                    if (newPageAttempts == 1) {
                        newPageStarted.complete(Unit)
                        try {
                            withContext(NonCancellable) { allowNewPageReturn.await() }
                        } catch (_: CancellationException) {
                            // Preserve the stale-result publication attempt for the deterministic interleaving.
                        }
                    }
                }
                return CanonicalReaderMaterializeExecutor.materializePage(request, port, forceRefresh, publish)
            }
        }
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-stale-storage")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 1L) {
                        listOf(readyDescriptor(1L, 0))
                    } else {
                        listOf(ReaderPageDescriptor(0, url = "/${chapter.chapterId}/0", imageUrl = "image:0"))
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect },
            parentScope = this,
            materializeExecutor = executor,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )
        session.start()
        try {
            runCurrent()
            session.updateNextChapter(context(2L), firstViewportPageCount = 1)
            oldFailurePublished.await()

            session.updateNextChapter(context(3L), firstViewportPageCount = 1)
            newPageStarted.await()
            allowOldFailureReturn.complete(Unit)
            runCurrent()
            allowNewPageReturn.complete(Unit)
            advanceUntilIdle()

            session.activate(context(3L))
            advanceUntilIdle()
            val firstPage = session.state.value.snapshot.activeChapter.pages.single().id
            session.settleViewport(setOf(firstPage), firstPage)
            advanceUntilIdle()

            assertEquals(1, newPageAttempts)
            assertEquals(ReaderPageLoadState.Ready, session.state.value.snapshot.activeChapter.pages.single().loadState)
            assertEquals(1, progress.size)
        } finally {
            allowOldFailureReturn.complete(Unit)
            allowNewPageReturn.complete(Unit)
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `activating a prefetched chapter cancels P4 and retries its visible page as P0`() = runTest {
        val prefetchStarted = CompletableDeferred<Unit>()
        val prefetchCancelled = CompletableDeferred<Unit>()
        var targetFirstPageAttempts = 0
        val progress = mutableListOf<ReaderProgressEffect>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-prefetch-activation")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    List(if (chapter.chapterId == 1L) 1 else 2) { index ->
                        if (chapter.chapterId == 1L) readyDescriptor(1L, index) else ReaderPageDescriptor(
                            index,
                            url = "/2/$index",
                            imageUrl = "image:$index",
                        )
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: mihon.domain.reader.materialize.ReaderPageFetchRequest) =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef {
                        if (chapter.chapterId == 2L && descriptor.sourcePageIndex == 0 && ++targetFirstPageAttempts == 1) {
                            prefetchStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                prefetchCancelled.complete(Unit)
                            }
                        }
                        return EncodedPageRef("encoded:${chapter.chapterId}:${descriptor.sourcePageIndex}")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )
        session.start()
        session.updateNextChapter(context(2L), firstViewportPageCount = 1)
        prefetchStarted.await()

        session.activate(context(2L))

        assertTrue(session.state.value.snapshot.activeChapter.pages.isEmpty())
        prefetchCancelled.await()
        advanceUntilIdle()
        val firstPage = session.state.value.snapshot.activeChapter.pages.first().id
        session.settleViewport(setOf(firstPage), firstPage)
        advanceUntilIdle()

        assertEquals(2, targetFirstPageAttempts)
        assertEquals(ReaderPageLoadState.Ready, session.state.value.snapshot.activeChapter.pages.first().loadState)
        assertEquals(1, progress.size)
        session.close()
    }

    @Test
    fun `adjacent storage failure stops the remaining background chapter without changing active state`() = runTest {
        val nextPageFetches = mutableListOf<Int>()
        val encodedStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-quota"), maxBytes = 3)
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = encodedStore,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
                ReaderChapterContentPort {
                    List(if (chapter.chapterId == 1L) 1 else 3) { index ->
                        if (chapter.chapterId == 1L) readyDescriptor(1L, index) else ReaderPageDescriptor(
                            index,
                            url = "/2/$index",
                            imageUrl = "image:$index",
                        )
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: mihon.domain.reader.materialize.ReaderPageFetchRequest) =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(
                        request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                    ): EncodedPageRef {
                        if (chapter.chapterId == 2L) {
                            nextPageFetches += descriptor.sourcePageIndex
                            val ref = encodedStore.cacheRef(request.pageId, requireNotNull(request.imageUrl))
                            return when (val result = encodedStore.store(ref) {
                                encodedStore.destinationFile(ref).writeBytes(byteArrayOf(1, 2, 3, 4))
                                4L
                            }) {
                                is EncodedPageStoreWriteResult.Stored -> result.entry.ref
                                is EncodedPageStoreWriteResult.RejectedQuota -> throw AppErrorException(
                                    AppError.Storage(IllegalStateException("quota")),
                                )
                            }
                        }
                        return EncodedPageRef("encoded:${chapter.chapterId}:${descriptor.sourcePageIndex}")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> error("prefetch must not write progress") },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )
        session.start()
        session.updateNextChapter(context(2L), firstViewportPageCount = 2)
        advanceUntilIdle()

        assertEquals(listOf(0), nextPageFetches)
        assertEquals(0L, encodedStore.diagnostics().usedBytes)
        assertEquals(ReaderChapterId(1L), session.state.value.snapshot.activeChapter.id)
        assertTrue(session.state.value.snapshot.activeChapter.pages.all { it.loadState == ReaderPageLoadState.Ready })
        session.close()
    }

    private fun readyCurrentSession(
        directory: String,
        mode: NextChapterPrefetchMode,
        nextPageFetches: MutableList<Int>,
        parentScope: kotlinx.coroutines.CoroutineScope,
    ) = DesktopReaderSession(
        initialContext = context(1L),
        core = core(initialChapterId = 1L),
        encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve(directory)),
        chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter ->
            ReaderChapterContentPort {
                List(if (chapter.chapterId == 1L) 1 else 4) { index ->
                    if (chapter.chapterId == 1L) readyDescriptor(1L, index) else ReaderPageDescriptor(
                        index,
                        url = "/2/$index",
                        imageUrl = "image:$index",
                    )
                }
            }
        },
        pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
            if (chapter.chapterId == 2L) nextPageFetches += descriptor.sourcePageIndex
            readyPort(descriptor)
        },
        progressPort = DesktopReaderProgressPort { _, _ -> error("prefetch must not write progress") },
        parentScope = parentScope,
        initialNextChapterPrefetchMode = mode,
    )

    private fun readyDescriptor(chapterId: Long, index: Int) = ReaderPageDescriptor(
        sourcePageIndex = index,
        url = "/$chapterId/$index",
        imageUrl = "image:$index",
        encodedPageRef = EncodedPageRef("existing:$chapterId:$index"),
        initialLoadState = ReaderPageLoadState.Ready,
    )

    private fun readyPort(descriptor: ReaderPageDescriptor) = object : ReaderPageFetchPort {
        override suspend fun resolveImageUrl(request: mihon.domain.reader.materialize.ReaderPageFetchRequest) =
            requireNotNull(request.imageUrl)

        override suspend fun findEncodedPage(
            request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
        ): EncodedPageRef? = null

        override suspend fun fetchEncodedPage(
            request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
        ) = EncodedPageRef("encoded:${request.pageId.chapterId.value}:${descriptor.sourcePageIndex}")
    }

    private fun core(initialChapterId: Long) = ReaderSessionCore(
        initialChapterId = ReaderChapterId(initialChapterId),
        sessionId = "desktop-session-test",
        requestScheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 1, nearbyBackward = 0, maxConcurrentRequests = 1),
        ),
    )

    private fun context(chapterId: Long) = DesktopReaderChapterContext(
        chapterId = chapterId,
        sourceId = 42L,
        chapterUrl = "/chapter/$chapterId",
        mangaTitle = "Manga",
        chapterTitle = "Chapter $chapterId",
        chapterNumber = chapterId.toDouble(),
        chapterIndex = if (chapterId == 1L) 1 else 0,
        initialPage = 0,
        wasRead = false,
    )
}
