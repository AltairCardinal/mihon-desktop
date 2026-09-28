package mihon.desktop.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.progress.ReaderProgressEffect
import mihon.data.reader.AcceptedReaderProgressContract
import mihon.domain.reader.partial.PartialReaderPageCandidate
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderEncodedPageProvenance
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderSessionCore
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.desktop.ui.reader.presentation.DualPagedPresentation
import mihon.desktop.ui.reader.presentation.ReaderPresentationRequest
import mihon.desktop.ui.reader.presentation.resolveDualVisiblePages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderSessionIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `active page scheduling preserves the partial candidate from the chapter descriptor`() = runTest {
        val candidate = PartialReaderPageCandidate(
            attemptGeneration = 3L,
            readerOrdinal = 0,
            sourcePageIndex = 4,
            opaqueLocation = "opaque://partial/001.jpg",
            committedRevision = 5L,
        )
        var scheduledDescriptor: ReaderPageDescriptor? = null
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-partial-candidate-wiring")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort {
                    listOf(
                        ReaderPageDescriptor(
                            sourcePageIndex = 4,
                            url = "/page/first",
                            imageUrl = "https://img/first.jpg",
                            partialPageCandidate = candidate,
                        ),
                    )
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor ->
                scheduledDescriptor = descriptor
                readyPort(descriptor)
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
        )

        try {
            session.start()
            advanceUntilIdle()
            assertNull(scheduledDescriptor)
            val page = session.state.value.snapshot.activeChapter.pages.single().id

            session.settleViewport(setOf(page), page)
            advanceUntilIdle()

            assertEquals(candidate, scheduledDescriptor?.partialPageCandidate)
        } finally {
            session.close()
        }
    }

    @Test
    fun `activating an adjacent prefetched partial page preserves its decode fallback provenance`() = runTest {
        val candidate = PartialReaderPageCandidate(
            attemptGeneration = 7L,
            readerOrdinal = 0,
            sourcePageIndex = 4,
            opaqueLocation = "opaque://partial/adjacent-001.jpg",
            committedRevision = 11L,
        )
        val provenance = ReaderEncodedPageProvenance.Partial(candidate)
        val prefetchedRef = EncodedPageRef("encoded:partial:adjacent")
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-adjacent-partial-provenance")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 1L) {
                        listOf(readyDescriptor(1L, 0))
                    } else {
                        listOf(
                            ReaderPageDescriptor(
                                sourcePageIndex = 4,
                                url = "/2/4",
                                imageUrl = "https://img/2/4.jpg",
                                partialPageCandidate = candidate,
                                partialPageOrdinal = 0,
                            ),
                        )
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, _ ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef = prefetchedRef

                    override fun encodedPageProvenance(
                        request: ReaderPageFetchRequest,
                        ref: EncodedPageRef,
                    ): ReaderEncodedPageProvenance? = provenance.takeIf { chapter.chapterId == 2L }
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )

        try {
            session.start()
            advanceUntilIdle()
            val opening = session.state.value.snapshot
            val currentPage = opening.activeChapter.pages.single().id
            session.updateNextChapter(context(2L), firstViewportPageCount = 1)
            session.onFirstPagePresented(currentPage, opening.generation)
            advanceUntilIdle()

            session.activate(context(2L))
            advanceUntilIdle()

            val activated = session.state.value.snapshot.activeChapter.pages.single()
            assertEquals(ReaderPageLoadState.Ready, activated.loadState)
            assertEquals(prefetchedRef, activated.encodedPageRef)
            assertEquals(provenance, activated.encodedPageProvenance)
        } finally {
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `activating an adjacent prefetched chapter retains resolved metadata`() = runTest {
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-adjacent-metadata")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
                object : ReaderChapterContentPort, DesktopReaderChapterDownloadState {
                    override val chapterDownloaded = chapter.chapterId == 2L

                    override suspend fun loadChapterContent(request: ReaderChapterContentRequest) =
                        listOf(readyDescriptor(chapter.chapterId, 0))
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )
        val target = context(2L).copy(
            chapterTitle = "预取章节标题",
            scanlator = "预取汉化组",
            isDownloaded = false,
        )

        try {
            session.start()
            advanceUntilIdle()
            val opening = session.state.value.snapshot
            val currentPage = opening.activeChapter.pages.single().id
            session.updateNextChapter(target, firstViewportPageCount = 1)
            session.onFirstPagePresented(currentPage, opening.generation)
            advanceUntilIdle()

            session.activate(target)
            advanceUntilIdle()

            assertEquals(target.chapterId, session.state.value.context.chapterId)
            assertEquals(target.chapterTitle, session.state.value.context.chapterTitle)
            assertEquals(target.scanlator, session.state.value.context.scanlator)
            assertTrue(session.state.value.context.isDownloaded)
        } finally {
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `dual presentation settles both source pages through screen model and session progress port`() = runTest {
        val progress = mutableListOf<ReaderProgressEffect>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-dual-progress-wiring")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort {
                    List(4) { index -> readyDescriptor(chapterId = 1L, index = index) }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect },
            parentScope = this,
        )
        session.start()
        advanceUntilIdle()
        val model = ReaderScreenModel(
            initialSessionState = session.state.value,
            onViewportSettled = session::settleViewport,
        )
        val presentation = DualPagedPresentation.present(
            ReaderPresentationRequest(
                chapter = session.state.value.snapshot.activeChapter,
                direction = ReaderDirection.LTR,
            ),
        )

        try {
            val pair = presentation.displayUnits.single { unit ->
                unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.toSet() == setOf(1, 2)
            }
            val pairViewport = presentation.resolveDualVisiblePages(pair.id)
            model.settleDualPage(pairViewport)
            advanceUntilIdle()

            assertEquals(setOf(1, 2), pairViewport.pageIds.mapTo(linkedSetOf(), ReaderPageId::sourcePageIndex))
            assertEquals(2, progress.single().lastPageRead)
            assertEquals(false, progress.single().isRead)

            val last = presentation.displayUnits.single { unit ->
                unit.slots.any { it.page?.id?.sourcePageIndex == 3 }
            }
            model.settleDualPage(presentation.resolveDualVisiblePages(last.id))
            advanceUntilIdle()

            assertEquals(listOf(2, 3), progress.map(ReaderProgressEffect::lastPageRead))
            assertTrue(progress.last().isRead)
        } finally {
            session.close()
        }
    }

    @Test
    fun `one session opens at zero pages then materializes visible pages and settled progress`() = runTest {
        val pageListGate = CompletableDeferred<Unit>()
        val progress = mutableListOf<Pair<DesktopReaderChapterContext, ReaderProgressEffect>>()
        val core = core(initialChapterId = 1L)
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core,
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
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
    fun `Desktop production session satisfies the shared accepted progress contract`() = runTest {
        val progress = mutableListOf<ReaderProgressEffect>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-shared-progress-contract")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort {
                    List(5) { index ->
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
            AcceptedReaderProgressContract.verifyCompletionBackTurnAndClose(
                object : AcceptedReaderProgressContract.Adapter {
                    override suspend fun settle(page: Int) {
                        session.settleViewport(setOf(pages[page].id), pages[page].id)
                        advanceUntilIdle()
                    }

                    override suspend fun closeAndDrain() {
                        session.close()
                        advanceUntilIdle()
                    }

                    override fun committed() = progress.map { effect ->
                        AcceptedReaderProgressContract.Observation(
                            effect.lastPageRead,
                            effect.totalPages,
                            effect.wasRead,
                            effect.isRead,
                            effect.idempotencyKey,
                        )
                    }
                },
            )
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
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

        AcceptedReaderProgressContract.verifyPendingWriteSurvivesClose(
            object : AcceptedReaderProgressContract.PendingCloseAdapter {
                override suspend fun accept() {
                    session.settleViewport(setOf(page), page)
                }

                override suspend fun awaitWriterStarted() {
                    writeStarted.await()
                }

                override suspend fun close() {
                    session.close()
                }

                override suspend fun releaseWrite() {
                    allowWrite.complete(Unit)
                }

                override suspend fun drain() {
                    advanceUntilIdle()
                }

                override fun committedCount(): Int = if (writeCompleted.isCompleted) 1 else 0
            },
        )
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 2L) targetGate.await()
                    listOf(ReaderPageDescriptor(0, url = "/${chapter.chapterId}/0", imageUrl = "image"))
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: mihon.domain.reader.materialize.ReaderPageFetchRequest) =
                        request.imageUrl ?: "image"

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
    fun `canonical adjacent metadata uses anchor before first presentation without fetching images`() = runTest {
        var nextPageListLoads = 0
        val nextPageFetches = mutableListOf<Int>()
        val progress = mutableListOf<ReaderProgressEffect>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-anchor-metadata")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 2L) nextPageListLoads++
                    List(if (chapter.chapterId == 1L) 10 else 3) { index ->
                        if (chapter.chapterId == 1L) {
                            readyDescriptor(chapter.chapterId, index)
                        } else {
                            ReaderPageDescriptor(index, url = "/2/$index", imageUrl = "image:$index")
                        }
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                if (chapter.chapterId == 2L) nextPageFetches += descriptor.sourcePageIndex
                readyPort(descriptor)
            },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.OFF,
        )

        try {
            session.start()
            session.updateNextChapter(context(2L), firstViewportPageCount = 2)
            advanceUntilIdle()
            val snapshot = session.state.value.snapshot
            val pages = snapshot.activeChapter.pages
            val dualViewport = setOf(pages[4].id, pages[5].id)

            session.settleViewport(dualViewport, anchorPageId = pages[4].id)
            advanceUntilIdle()

            assertEquals(0, nextPageListLoads, "Anchor index 4 still has six pages remaining")
            assertTrue(nextPageFetches.isEmpty())
            assertEquals(1, progress.size)

            session.settleViewport(dualViewport, anchorPageId = pages[5].id)
            advanceUntilIdle()

            assertEquals(1, nextPageListLoads, "Anchor index 5 loads metadata without waiting for first presentation")
            assertTrue(nextPageFetches.isEmpty(), "Canonical metadata must remain page-list-only in OFF mode")
            assertEquals(2, progress.size)

            repeat(2) { session.onFirstPagePresented(pages[5].id, snapshot.generation) }
            advanceUntilIdle()

            assertEquals(1, nextPageListLoads)
            assertEquals(2, progress.size, "Repeated presentation must not add progress side effects")
        } finally {
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `opt in decorator waits for current generation first presentation and shared scheduler idle`() = runTest {
        val currentReadyPublished = CompletableDeferred<Unit>()
        val allowCurrentTerminal = CompletableDeferred<Unit>()
        val nextPageLists = mutableListOf<Long>()
        val nextPageFetches = mutableListOf<Int>()
        val progress = mutableListOf<ReaderProgressEffect>()
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
                if (request.pageId.chapterId == ReaderChapterId(1L)) {
                    val imageUrl = requireNotNull(request.imageUrl)
                    val encodedRef = EncodedPageRef("encoded:1:0")
                    check(publish(ReaderPageMaterializeEvent.Ready(imageUrl, encodedRef)))
                    currentReadyPublished.complete(Unit)
                    allowCurrentTerminal.await()
                    return ReaderPageMaterializeResult.Ready(imageUrl, encodedRef)
                }
                return CanonicalReaderMaterializeExecutor.materializePage(request, port, forceRefresh, publish)
            }
        }
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-first-presented-idle")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 2L) nextPageLists += chapter.chapterId
                    List(if (chapter.chapterId == 1L) 10 else 2) { index ->
                        if (chapter.chapterId == 1L && index > 0) {
                            readyDescriptor(chapter.chapterId, index)
                        } else {
                            ReaderPageDescriptor(
                                index,
                                url = "/${chapter.chapterId}/$index",
                                imageUrl = "image:$index",
                            )
                        }
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        if (chapter.chapterId == 2L) nextPageFetches += descriptor.sourcePageIndex
                        return EncodedPageRef("encoded:${chapter.chapterId}:${descriptor.sourcePageIndex}")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect },
            parentScope = this,
            materializeExecutor = executor,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )

        try {
            session.start()
            advanceUntilIdle()
            val opening = session.state.value.snapshot
            val currentPage = opening.activeChapter.pages.first().id
            session.updateNextChapter(context(2L), firstViewportPageCount = 1)
            session.settleViewport(setOf(currentPage), currentPage)
            currentReadyPublished.await()

            assertTrue(session.core.schedulerSnapshot().activeRequests.isNotEmpty())
            assertEquals(ReaderPageLoadState.Ready, session.state.value.snapshot.activeChapter.pages.first().loadState)
            assertTrue(nextPageLists.isEmpty(), "Explicit prefetch must not open a page list before presentation")
            assertTrue(nextPageFetches.isEmpty())

            session.onFirstPagePresented(currentPage, opening.generation)
            runCurrent()

            assertTrue(nextPageLists.isEmpty(), "A Ready event is not scheduler idle until its request completes")
            assertTrue(nextPageFetches.isEmpty())
            assertEquals(1, progress.size)

            allowCurrentTerminal.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(2L), nextPageLists)
            assertEquals(listOf(0, 1), nextPageFetches)
            assertEquals(1, progress.size, "P4 encoded materialization must not write reading progress")
        } finally {
            allowCurrentTerminal.complete(Unit)
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `stale generation target and repeated first presentation cannot release old adjacent work`() = runTest {
        val adjacentPageLists = mutableListOf<Long>()
        val adjacentPageFetches = mutableListOf<Pair<Long, Int>>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-stale-first-presented")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
                ReaderChapterContentPort {
                    if (chapter.chapterId != 1L) adjacentPageLists += chapter.chapterId
                    listOf(
                        if (chapter.chapterId == 1L) {
                            readyDescriptor(chapter.chapterId, 0)
                        } else {
                            ReaderPageDescriptor(0, url = "/${chapter.chapterId}/0", imageUrl = "image:0")
                        },
                    )
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { chapter, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        adjacentPageFetches += chapter.chapterId to descriptor.sourcePageIndex
                        return EncodedPageRef("encoded:${chapter.chapterId}:${descriptor.sourcePageIndex}")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> error("Adjacent work must not write reading progress") },
            parentScope = this,
            initialNextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
        )

        try {
            session.start()
            advanceUntilIdle()
            val firstSnapshot = session.state.value.snapshot
            val firstPage = firstSnapshot.activeChapter.pages.single().id

            session.updateNextChapter(context(2L), firstViewportPageCount = 1)
            session.updateNextChapter(context(3L), firstViewportPageCount = 1)
            runCurrent()
            assertTrue(adjacentPageLists.isEmpty(), "Target switches before first presentation must remain I/O-free")

            repeat(2) { session.onFirstPagePresented(firstPage, firstSnapshot.generation + 1L) }
            runCurrent()
            assertTrue(adjacentPageLists.isEmpty(), "A stale generation must not release the current target")

            repeat(2) { session.onFirstPagePresented(firstPage, firstSnapshot.generation) }
            advanceUntilIdle()

            assertEquals(listOf(3L), adjacentPageLists)
            assertEquals(listOf(3L to 0), adjacentPageFetches)

            session.activate(context(3L))
            advanceUntilIdle()
            val secondSnapshot = session.state.value.snapshot
            val secondPage = secondSnapshot.activeChapter.pages.single().id
            session.updateNextChapter(context(4L), firstViewportPageCount = 1)
            runCurrent()
            assertEquals(listOf(3L), adjacentPageLists)

            repeat(2) { session.onFirstPagePresented(firstPage, firstSnapshot.generation) }
            runCurrent()
            assertEquals(listOf(3L), adjacentPageLists, "Old chapter presentation must not release the new target")

            repeat(2) { session.onFirstPagePresented(secondPage, secondSnapshot.generation) }
            advanceUntilIdle()

            assertEquals(listOf(3L, 4L), adjacentPageLists)
            assertEquals(listOf(3L to 0, 4L to 0), adjacentPageFetches)
        } finally {
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `full next chapter waits for bounded current windows before prefetching without progress`() = runTest {
        val releaseLastCurrentPage = CompletableDeferred<Unit>()
        var lastCurrentPageFetchStarted = false
        val nextPageLists = mutableListOf<Long>()
        val nextPageFetches = mutableListOf<Int>()
        val progress = mutableListOf<ReaderProgressEffect>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-full-next")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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
                            lastCurrentPageFetchStarted = true
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
        val currentPages = session.state.value.snapshot.activeChapter.pages
        val first = currentPages.first().id

        session.settleViewport(setOf(first), first)
        advanceUntilIdle()
        presentCurrentGeneration(session)
        runCurrent()

        assertTrue(nextPageLists.isEmpty())
        assertTrue(nextPageFetches.isEmpty())
        assertEquals(1, progress.size)

        currentPages.subList(1, currentPages.lastIndex - 1).forEachIndexed { index, page ->
            session.settleViewport(setOf(page.id), page.id)
            advanceUntilIdle()
            assertTrue(nextPageFetches.isEmpty())
            assertEquals(index + 2, progress.size)
        }

        val penultimate = currentPages[currentPages.lastIndex - 1].id
        session.settleViewport(setOf(penultimate), penultimate)
        runCurrent()

        assertEquals(listOf(2L), nextPageLists)
        assertTrue(nextPageFetches.isEmpty())
        assertTrue(lastCurrentPageFetchStarted)
        assertEquals(5, progress.size)

        releaseLastCurrentPage.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            session.state.value.snapshot.activeChapter.pages.all { page ->
                page.loadState is ReaderPageLoadState.Ready
            },
            session.state.value.snapshot.activeChapter.pages.joinToString { page ->
                "${page.id.sourcePageIndex}:${page.loadState}"
            },
        )
        assertTrue(session.core.schedulerSnapshot().pendingRequests.isEmpty())
        assertTrue(session.core.schedulerSnapshot().activeRequests.isEmpty())
        assertTrue(session.pageRunnerSnapshot().activeRequestKeys.isEmpty())
        assertEquals(listOf(2L), nextPageLists)
        assertEquals(listOf(0, 1, 2), nextPageFetches)
        assertEquals(5, progress.size)

        session.activate(context(2L))
        advanceUntilIdle()
        val activatedPages = session.state.value.snapshot.activeChapter.pages
        assertTrue(activatedPages.all { it.loadState == ReaderPageLoadState.Ready })
        session.settleViewport(setOf(activatedPages.first().id), activatedPages.first().id)
        advanceUntilIdle()

        assertEquals(listOf(2L), nextPageLists)
        assertEquals(listOf(0, 1, 2), nextPageFetches)
        assertEquals(6, progress.size)
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
        presentCurrentGeneration(session)
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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
        presentCurrentGeneration(session)
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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
        advanceUntilIdle()
        presentCurrentGeneration(session)
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
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
            assertEquals(
                setOf(2),
                session.pageRunnerSnapshot().activeRequestKeys.mapTo(mutableSetOf()) { it.pageIndex },
            )

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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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
            advanceUntilIdle()
            presentCurrentGeneration(session)

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
    fun `same chapter reactivation releases only the old lease generation`() = runTest {
        val reserved = mutableListOf<DesktopReaderChapterLeaseOwnerFixture>()
        val released = mutableListOf<DesktopReaderChapterLeaseOwnerFixture>()
        val leasePort = object : DesktopReaderChapterLeasePort {
            override fun reserveChapter(chapterId: Long, leaseGeneration: Long) {
                reserved += DesktopReaderChapterLeaseOwnerFixture(chapterId, leaseGeneration)
            }

            override fun releaseChapter(chapterId: Long, leaseGeneration: Long) {
                released += DesktopReaderChapterLeaseOwnerFixture(chapterId, leaseGeneration)
            }
        }
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-lease-generation")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort { listOf(readyDescriptor(1L, 0)) }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            chapterLeasePort = leasePort,
            parentScope = this,
        )

        session.start()
        advanceUntilIdle()
        val first = reserved.single()

        session.activate(context(1L))
        advanceUntilIdle()

        val second = reserved.last()
        assertEquals(2, reserved.size)
        assertTrue(second.leaseGeneration > first.leaseGeneration)
        assertEquals(listOf(first), released)

        session.close()
        assertEquals(listOf(first, second), released)
    }

    @Test
    fun `late concurrent same chapter reserve cannot replace the current generation`() = runTest {
        val adapter = DesktopReaderContentAdapter()
        val oldReserveStarted = CountDownLatch(1)
        val releaseOldReserve = CountDownLatch(1)
        val gatedLeasePort = object : DesktopReaderChapterLeasePort {
            override fun reserveChapter(chapterId: Long, leaseGeneration: Long) {
                if (leaseGeneration == 2L) {
                    oldReserveStarted.countDown()
                    releaseOldReserve.await()
                }
                adapter.reserveChapter(chapterId, leaseGeneration)
            }

            override fun releaseChapter(chapterId: Long, leaseGeneration: Long) {
                adapter.releaseChapter(chapterId, leaseGeneration)
            }
        }
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-concurrent-lease-generation")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort { listOf(readyDescriptor(1L, 0)) }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> readyPort(descriptor) },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            chapterLeasePort = gatedLeasePort,
            parentScope = this,
        )
        val archive = tempDir.resolve("concurrent-lease-generation.cbz")
        ZipOutputStream(archive.outputStream()).use { }

        session.start()
        advanceUntilIdle()
        val oldActivation = async(Dispatchers.Default) { session.activate(context(1L)) }
        assertTrue(oldReserveStarted.await(2, TimeUnit.SECONDS))

        session.activate(context(1L))
        releaseOldReserve.countDown()
        oldActivation.await()

        adapter.archiveDescriptors(chapterId = 1L, archive = archive, leaseGeneration = 3L)
        assertTrue(adapter.hasArchiveLease(1L))

        session.close()
        assertTrue(archive.delete())
        adapter.close()
    }

    @Test
    fun `current page write bypasses cache reconcile until first presentation`() = runTest {
        val cacheDirectory = tempDir.resolve("encoded-first-presentation")
        val seedFile = cacheDirectory.resolve("seed.encoded").also {
            it.parentFile.mkdirs()
            it.writeBytes(byteArrayOf(9))
        }
        val seedRef = EncodedPageRef(seedFile.toURI().toString())
        val encodedStore = DesktopReaderEncodedPageStore(cacheDirectory)
        val cacheGate = ControllableReaderIoGate(ReaderIoGatePoint.CACHE_SCAN)
        val events = mutableListOf<ReaderIoEvent>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = encodedStore,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort {
                    listOf(ReaderPageDescriptor(0, url = "/1/0", imageUrl = "image:0"))
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, _ ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        val ref = encodedStore.cacheRef(request.pageId, requireNotNull(request.imageUrl))
                        return when (val result = encodedStore.store(ref) {
                            encodedStore.destinationFile(ref).writeBytes(byteArrayOf(1, 2, 3, 4))
                            4L
                        }) {
                            is EncodedPageStoreWriteResult.Stored -> result.entry.ref
                            is EncodedPageStoreWriteResult.RejectedQuota -> error("fixture exceeds cache quota")
                        }
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            ioReporter = ReaderIoReporter(
                ReaderIoProbe(events::add),
                ReaderMonotonicClock { events.size.toLong() },
            ),
            ioGate = ReaderIoGate(cacheGate::await),
        )

        try {
            session.start()
            advanceUntilIdle()
            val visiblePage = session.state.value.snapshot.activeChapter.pages.single().id
            session.settleViewport(setOf(visiblePage), visiblePage)
            advanceUntilIdle()
            val snapshot = session.state.value.snapshot
            val page = snapshot.activeChapter.pages.single()

            assertEquals(ReaderPageLoadState.Ready, page.loadState)
            assertTrue(seedRef !in encodedStore.diagnostics().refs)
            assertTrue(events.none { it.type == ReaderIoEventType.CACHE_RECONCILE })

            session.onFirstPagePresented(page.id, snapshot.generation)
            cacheGate.awaitEntered()
            assertTrue(events.none { it.type == ReaderIoEventType.CACHE_RECONCILE })

            cacheGate.release()
            advanceUntilIdle()

            assertTrue(seedRef in encodedStore.diagnostics().refs)
            assertEquals(1, events.count { it.type == ReaderIoEventType.CACHE_RECONCILE })
        } finally {
            cacheGate.release()
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `cancelled noncurrent gate cannot report stale open page`() = runTest {
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val events = mutableListOf<ReaderIoEvent>()
        val fetchedPages = mutableListOf<ReaderPageId>()
        val session = DesktopReaderSession(
            initialContext = context(1L),
            core = core(initialChapterId = 1L),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-stale-gate")),
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
                ReaderChapterContentPort {
                    if (chapter.chapterId == 1L) {
                        List(2) { index ->
                            ReaderPageDescriptor(
                                sourcePageIndex = index,
                                url = "/1/$index",
                                imageUrl = "image:$index",
                                encodedPageRef = EncodedPageRef("existing:1:$index"),
                            )
                        }
                    } else {
                        listOf(readyDescriptor(chapter.chapterId, 0))
                    }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) = requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? =
                        descriptor.encodedPageRef

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        fetchedPages += request.pageId
                        return EncodedPageRef("fetched:${request.pageId.sourcePageIndex}")
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            ioReporter = ReaderIoReporter(
                ReaderIoProbe(events::add),
                ReaderMonotonicClock { events.size.toLong() },
            ),
            ioGate = ReaderIoGate { point ->
                if (point == ReaderIoGatePoint.NON_CURRENT_PAGE) {
                    gateEntered.complete(Unit)
                    withContext(NonCancellable) { releaseGate.await() }
                }
            },
        )

        try {
            session.start()
            advanceUntilIdle()
            val oldPages = session.state.value.snapshot.activeChapter.pages
            session.settleViewport(setOf(oldPages[0].id), oldPages[0].id)
            gateEntered.await()

            session.activate(context(2L))
            releaseGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(events.none { event ->
                event.type == ReaderIoEventType.OPEN_PAGE && event.pageId == oldPages[1].id
            })
            assertTrue(oldPages[1].id !in fetchedPages)
        } finally {
            releaseGate.complete(Unit)
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `fast reconcile pins active refs until their lease releases`() = runTest {
        val cacheDirectory = tempDir.resolve("encoded-fast-reconcile-pins")
        val firstFile = cacheDirectory.resolve("first.encoded").also {
            it.parentFile.mkdirs()
            it.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val secondFile = cacheDirectory.resolve("second.encoded").also {
            it.writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        val firstRef = EncodedPageRef(firstFile.toURI().toString())
        val secondRef = EncodedPageRef(secondFile.toURI().toString())
        val coordinator = DesktopReaderEncodedPageStoreCoordinator(cacheDirectory, maxBytes = 6)
        val firstStore = coordinator.openSessionStore()
        val secondStore = coordinator.openSessionStore()

        try {
            firstStore.beginSessionFast(emptySet())
            assertTrue(firstStore.contains(firstRef))
            secondStore.beginSessionFast(emptySet())
            assertTrue(secondStore.contains(secondRef))

            firstStore.reconcileSession()

            assertTrue(firstFile.isFile)
            assertTrue(secondFile.isFile)
            assertEquals(byteArrayOf(1, 2, 3, 4).toList(), firstStore.read(firstRef)?.toList())
            assertEquals(byteArrayOf(5, 6, 7, 8).toList(), secondStore.read(secondRef)?.toList())

            firstStore.endSession()

            assertFalse(firstFile.exists())
            assertTrue(secondFile.isFile)
            assertTrue(secondStore.diagnostics().usedBytes <= 6)
            val thirdRef = secondStore.cacheRef(ReaderPageId(ReaderChapterId(3L), 0), "third")
            assertInstanceOf(
                EncodedPageStoreWriteResult.Stored::class.java,
                secondStore.store(thirdRef) {
                    secondStore.destinationFile(thirdRef).writeBytes(byteArrayOf(9, 10))
                    2L
                },
            )
        } finally {
            firstStore.endSession()
            secondStore.endSession()
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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
            presentCurrentGeneration(session)
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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
        advanceUntilIdle()
        presentCurrentGeneration(session)
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
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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
        presentCurrentGeneration(session)
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
        chapterContentPortFactory = DesktopReaderChapterContentPortFactory { chapter, _ ->
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

    private fun presentCurrentGeneration(session: DesktopReaderSession, pageIndex: Int = 0) {
        val snapshot = session.state.value.snapshot
        session.onFirstPagePresented(snapshot.activeChapter.pages[pageIndex].id, snapshot.generation)
    }

    private data class DesktopReaderChapterLeaseOwnerFixture(
        val chapterId: Long,
        val leaseGeneration: Long,
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
