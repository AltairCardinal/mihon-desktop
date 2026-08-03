package mihon.desktop.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderPageFetchPort
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
