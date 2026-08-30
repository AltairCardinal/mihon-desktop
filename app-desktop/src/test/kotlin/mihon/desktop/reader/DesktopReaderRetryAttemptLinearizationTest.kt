package mihon.desktop.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.scheduler.ReaderRequestKind
import mihon.domain.reader.scheduler.ReaderRequestPriority
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderSessionCore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Retry linearization at the production Desktop session/materialize boundary.
 *
 * A chapter generation identifies the stable page list. A Retry therefore needs a second,
 * monotonically increasing content-attempt identity: cancellation alone cannot distinguish two
 * materialize/decode owners for the same page, chapter generation and encoded reference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderRetryAttemptLinearizationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `Retry immediately queues and clears only the failed target before forced P0 materialize`() = runTest {
        val allowFetch = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val createdDescriptors = mutableListOf<ReaderPageDescriptor>()
        val fetchRequests = mutableListOf<ReaderPageFetchRequest>()
        var cacheLookups = 0
        val session = session(
            pages = listOf(failedDescriptor(0), readyDescriptor(1)),
            parentScope = this,
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor ->
                createdDescriptors += descriptor
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String =
                        "https://example.test/fresh/${request.pageId.sourcePageIndex}"

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? {
                        cacheLookups++
                        return FAILED_REF
                    }

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        fetchRequests += request
                        fetchStarted.complete(Unit)
                        allowFetch.await()
                        return FRESH_REF
                    }
                }
            },
        )
        session.start()
        advanceUntilIdle()
        val chapterBefore = session.state.value.snapshot.activeChapter
        val targetBefore = chapterBefore.pages[0]
        val untouchedBefore = chapterBefore.pages[1]

        try {
            session.retryPage(targetBefore.id)

            val immediate = session.state.value.snapshot
            val target = immediate.activeChapter.pages[0]
            assertEquals(chapterBefore.generation, immediate.generation)
            assertEquals(ReaderPageLoadState.Queued, target.loadState)
            assertEquals(targetBefore.attemptGeneration + 1L, target.attemptGeneration)
            assertNull(target.imageUrl, "Retry must re-resolve an image URL that belonged to the failed attempt")
            assertNull(target.encodedPageRef, "Retry must detach the failed encoded ref synchronously")
            assertEquals(untouchedBefore, immediate.activeChapter.pages[1], "Retry must reset only its target page")
            assertNull(
                createdDescriptors.single().encodedPageRef,
                "the Desktop fetch adapter must never receive the failed ref for the new attempt",
            )

            runCurrent()
            fetchStarted.await()
            val active = session.core.schedulerSnapshot().activeRequests.single()
            assertEquals(ReaderRequestKind.EXPLICIT_RETRY, active.kind)
            assertEquals(ReaderRequestPriority.P0_INTERACTIVE, active.priority)
            assertTrue(active.forceRefresh)
            assertEquals(0, cacheLookups, "forced Retry must bypass the encoded-page lookup")
            assertEquals(target.attemptGeneration, fetchRequests.single().attemptGeneration)
            assertEquals("https://example.test/fresh/0", fetchRequests.single().imageUrl)

            allowFetch.complete(Unit)
            advanceUntilIdle()

            val ready = session.state.value.snapshot.activeChapter.pages[0]
            assertEquals(ReaderPageLoadState.Ready, ready.loadState)
            assertEquals(FRESH_REF, ready.encodedPageRef)
            assertEquals(target.attemptGeneration, ready.attemptGeneration)
            assertEquals(untouchedBefore, session.state.value.snapshot.activeChapter.pages[1])
        } finally {
            allowFetch.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `two same-generation Retries advance attempts and reject the noncooperative old terminal`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val allowOldTerminal = CompletableDeferred<Unit>()
        val secondPublished = CompletableDeferred<Unit>()
        val oldTerminalAccepted = CompletableDeferred<Boolean>()
        val observedAttempts = mutableListOf<Long>()
        val executor = object : ReaderMaterializeExecutor {
            override suspend fun materializeChapter(
                request: ReaderChapterContentRequest,
                port: ReaderChapterContentPort,
            ): ReaderChapterMaterializeResult =
                CanonicalReaderMaterializeExecutor.materializeChapter(request, port)

            override suspend fun materializePage(
                request: ReaderPageFetchRequest,
                port: ReaderPageFetchPort,
                forceRefresh: Boolean,
                publish: (ReaderPageMaterializeEvent) -> Boolean,
            ): ReaderPageMaterializeResult {
                assertTrue(forceRefresh)
                observedAttempts += request.attemptGeneration
                return when (request.attemptGeneration) {
                    1L -> {
                        firstStarted.complete(Unit)
                        val accepted = withContext(NonCancellable) {
                            allowOldTerminal.await()
                            publish(ReaderPageMaterializeEvent.Ready("https://example.test/old", OLD_LATE_REF))
                        }
                        oldTerminalAccepted.complete(accepted)
                        if (accepted) {
                            ReaderPageMaterializeResult.Ready("https://example.test/old", OLD_LATE_REF)
                        } else {
                            ReaderPageMaterializeResult.Rejected
                        }
                    }
                    2L -> {
                        assertTrue(
                            publish(ReaderPageMaterializeEvent.Ready("https://example.test/new", FRESH_REF)),
                        )
                        secondPublished.complete(Unit)
                        ReaderPageMaterializeResult.Ready("https://example.test/new", FRESH_REF)
                    }
                    else -> error("unexpected Retry attempt ${request.attemptGeneration}")
                }
            }
        }
        val session = session(
            pages = listOf(failedDescriptor(0)),
            parentScope = this,
            materializeExecutor = executor,
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor -> inertPort(descriptor) },
        )
        session.start()
        advanceUntilIdle()
        val chapterGeneration = session.state.value.snapshot.generation
        val pageId = session.state.value.snapshot.activeChapter.pages.single().id

        try {
            session.retryPage(pageId)
            runCurrent()
            firstStarted.await()

            session.retryPage(pageId)
            assertEquals(chapterGeneration, session.state.value.snapshot.generation)
            assertEquals(2L, session.state.value.snapshot.activeChapter.pages.single().attemptGeneration)
            runCurrent()
            secondPublished.await()

            val afterSecond = session.state.value.snapshot.activeChapter.pages.single()
            assertEquals(ReaderPageLoadState.Ready, afterSecond.loadState)
            assertEquals(FRESH_REF, afterSecond.encodedPageRef)
            assertEquals(2L, afterSecond.attemptGeneration)

            allowOldTerminal.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L), observedAttempts)
            assertFalse(oldTerminalAccepted.await(), "the cancelled attempt must fail its final publish fence")
            val finalPage = session.state.value.snapshot.activeChapter.pages.single()
            assertEquals(ReaderPageLoadState.Ready, finalPage.loadState)
            assertEquals(FRESH_REF, finalPage.encodedPageRef)
            assertEquals(2L, finalPage.attemptGeneration)
        } finally {
            allowOldTerminal.complete(Unit)
            session.close()
        }
    }

    private fun session(
        pages: List<ReaderPageDescriptor>,
        parentScope: CoroutineScope,
        pageFetchPortFactory: DesktopReaderPageFetchPortFactory,
        materializeExecutor: ReaderMaterializeExecutor = CanonicalReaderMaterializeExecutor,
    ) = DesktopReaderSession(
        initialContext = context(),
        core = ReaderSessionCore(
            initialChapterId = CHAPTER_ID,
            sessionId = "retry-attempt-linearization",
            requestScheduler = ReaderRequestScheduler(
                ReaderSchedulerPolicy(nearbyForward = 1, nearbyBackward = 0, maxConcurrentRequests = 1),
            ),
        ),
        encodedPageStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-${tempDir.list().orEmpty().size}")),
        chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
            ReaderChapterContentPort { pages }
        },
        pageFetchPortFactory = pageFetchPortFactory,
        progressPort = DesktopReaderProgressPort { _, _ -> },
        parentScope = parentScope,
        materializeExecutor = materializeExecutor,
    )

    private fun failedDescriptor(index: Int) = ReaderPageDescriptor(
        sourcePageIndex = index,
        url = "/page/$index",
        imageUrl = "https://example.test/failed/$index",
        encodedPageRef = FAILED_REF,
        initialLoadState = ReaderPageLoadState.Error(AppError.Network()),
    )

    private fun readyDescriptor(index: Int) = ReaderPageDescriptor(
        sourcePageIndex = index,
        url = "/page/$index",
        imageUrl = "https://example.test/ready/$index",
        encodedPageRef = UNTOUCHED_REF,
        initialLoadState = ReaderPageLoadState.Ready,
    )

    private fun inertPort(descriptor: ReaderPageDescriptor) = object : ReaderPageFetchPort {
        override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String =
            requireNotNull(descriptor.imageUrl)

        override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

        override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef = FRESH_REF
    }

    private fun context() = DesktopReaderChapterContext(
        chapterId = CHAPTER_ID.value,
        sourceId = 42L,
        chapterUrl = "/chapter/${CHAPTER_ID.value}",
        mangaTitle = "Manga",
        chapterTitle = "Chapter",
        chapterNumber = 1.0,
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
    )

    private companion object {
        val CHAPTER_ID = ReaderChapterId(41L)
        val FAILED_REF = EncodedPageRef("opaque://failed-page")
        val OLD_LATE_REF = EncodedPageRef("opaque://old-late-page")
        val FRESH_REF = EncodedPageRef("opaque://fresh-page")
        val UNTOUCHED_REF = EncodedPageRef("opaque://untouched-page")
    }
}
