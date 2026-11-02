package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.domain.reader.scheduler.ReaderRequestKind
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException

class HttpPageLoaderIntegrationTest {

    @Test
    fun `two held viewport operations retain the physical budget until an actual source exit`() = runTest {
        val fixture = Fixture(testScheduler, nonCancellablePages = setOf(0, 1))
        val loader = fixture.loader
        val lease = requireNotNull(loader.acquireNormalViewport())
        assertTrue(
            loader.updateNormalViewport(
                lease,
                mihon.domain.reader.ReaderDualPageFrameId(1, 0, 1),
                1,
                fixture.pages[0],
                fixture.pages.take(2),
            ),
        )
        try {
            loader.onPageSelected(fixture.pages[0])
            runCurrent()
            assertEquals(listOf(0, 1), fixture.started)
            loader.onPageSelected(fixture.pages[3])
            runCurrent()
            assertEquals(listOf(0, 1), fixture.started, "Logical cancellation cannot free either physical operation")
            assertEquals(2, fixture.peakInFlight)
            fixture.release(0)
            runCurrent()
            assertEquals(listOf(0, 1, 3), fixture.started, "The current source starts only after a real old exit")
            assertEquals(2, fixture.peakInFlight)
            assertFalse(fixture.pages[0].status == Page.State.Ready)
            fixture.release(3)
            fixture.release(1)
            runCurrent()
            assertEquals(Page.State.Ready, fixture.pages[3].status)
            assertFalse(fixture.pages[1].status == Page.State.Ready)
        } finally {
            fixture.release(0)
            fixture.release(1)
            loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `late ordinary holder cannot restore a previous viewport after the selected window changed`() = runTest {
        val fixture = Fixture(testScheduler)
        val loader = fixture.loader
        val lease = requireNotNull(loader.acquireNormalViewport())
        val frame = mihon.domain.reader.ReaderDualPageFrameId(1, 0, 1)
        assertTrue(loader.updateNormalViewport(lease, frame, 1, fixture.pages[0], fixture.pages.take(2)))
        loader.onPageSelected(fixture.pages[0])
        runCurrent()
        var oldHolder: kotlinx.coroutines.Job? = null
        try {
            loader.onPageSelected(fixture.pages[3])
            runCurrent()
            oldHolder = launch { loader.loadPage(fixture.pages[0]) }
            runCurrent()
            assertEquals(setOf(3), loader.pageRunnerSnapshot().activeRequestKeys.mapTo(mutableSetOf()) { it.pageIndex })
            assertFalse(loader.updateNormalViewport(lease, frame, 2, fixture.pages[0], fixture.pages.take(2)))
        } finally {
            oldHolder?.cancel()
            loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `rebound page objects cannot receive an old viewport grant with a new scheduler generation`() = runTest {
        val fixture = Fixture(testScheduler)
        val loader = fixture.loader
        val lease = requireNotNull(loader.acquireNormalViewport())
        assertTrue(
            loader.updateNormalViewport(
                lease,
                mihon.domain.reader.ReaderDualPageFrameId(1, 0, 1),
                1,
                fixture.pages[0],
                fixture.pages.take(2),
            ),
        )
        loader.onPageSelected(fixture.pages[0])
        runCurrent()
        val chapter = fixture.pages[0].chapter
        val replacement = ReaderPage(1, imageUrl = "https://example/rebound").apply { this.chapter = chapter }
        chapter.publishLoadedPageListForTest(fixture.pages.map { if (it.index == 1) replacement else it })
        var newHolder: kotlinx.coroutines.Job? = null
        try {
            loader.onPageSelected(fixture.pages[0])
            newHolder = launch { loader.loadPage(replacement) }
            runCurrent()
            assertEquals(setOf(0), loader.pageRunnerSnapshot().activeRequestKeys.mapTo(mutableSetOf()) { it.pageIndex })
        } finally {
            newHolder?.cancel()
            loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `current viewport restores older prepared frames while retired leases stay closed`() = runTest {
        val fixture = Fixture(testScheduler)
        val loader = fixture.loader
        val oldLease = requireNotNull(loader.acquireNormalViewport())
        val newerFrame = mihon.domain.reader.ReaderDualPageFrameId(2, 2, 20)
        val olderFrame = mihon.domain.reader.ReaderDualPageFrameId(1, 0, 2)
        try {
            assertTrue(loader.updateNormalViewport(oldLease, newerFrame, 1, fixture.pages[0], fixture.pages.take(2)))
            assertTrue(
                loader.updateNormalViewport(oldLease, olderFrame, 2, fixture.pages[0], fixture.pages.take(2)),
                "A frame identity is not a notification clock",
            )
            assertFalse(loader.updateNormalViewport(oldLease, newerFrame, 1, fixture.pages[0], fixture.pages.take(2)))
            val current = requireNotNull(loader.acquireNormalViewport())
            assertTrue(loader.updateNormalViewport(current, newerFrame, 1, fixture.pages[0], fixture.pages.take(2)))
            loader.releaseNormalViewport(oldLease)
            assertFalse(loader.updateNormalViewport(oldLease, newerFrame, 3, fixture.pages[0], fixture.pages.take(2)))
            loader.onPageSelected(fixture.pages[0])
            runCurrent()
            assertEquals(
                setOf(0, 1),
                loader.pageRunnerSnapshot().activeRequestKeys.mapTo(mutableSetOf()) {
                    it.pageIndex
                },
            )
            loader.releaseNormalViewport(current)
            assertFalse(loader.updateNormalViewport(current, newerFrame, 2, fixture.pages[0], fixture.pages.take(2)))
        } finally {
            loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `visible holder replaces an ordinary request when its URL content attempt or page object changed`() = runTest {
        for (change in listOf("url", "retry", "encoded", "page")) {
            val scheduler = ReaderRequestScheduler(ReaderSchedulerPolicy(nearbyForward = 1, maxConcurrentRequests = 1))
            val fixture = Fixture(testScheduler, requestScheduler = scheduler)
            var holder: kotlinx.coroutines.Job? = null
            try {
                fixture.loader.onPageSelected(fixture.pages[0])
                runCurrent()
                fixture.release(0)
                runCurrent()
                val original = scheduler.snapshot().activeRequests.single()
                var requested = fixture.pages[1]
                when (change) {
                    "url" -> requested.imageUrl = "https://example/replaced"
                    "retry" -> requested.retryHeaderFact()
                    "encoded" -> {
                        requested.encodedPageRef = mihon.domain.reader.session.EncodedPageRef("previous")
                        requested.encodedPageRef = mihon.domain.reader.session.EncodedPageRef("replacement")
                    }
                    "page" -> {
                        requested = ReaderPage(requested.index, requested.url, requested.imageUrl).apply {
                            chapter = fixture.pages[1].chapter
                        }
                        requested.chapter.publishLoadedPageListForTest(
                            fixture.pages.map { if (it.index == requested.index) requested else it },
                        )
                    }
                }
                holder = launch { fixture.loader.loadPage(requested) }
                runCurrent()
                assertEquals(listOf(1), fixture.cancelled)
                assertEquals(listOf(0, 1, 1), fixture.started)
                assertFalse(scheduler.accepts(original.jobKey))
                fixture.release(1)
                runCurrent()
                assertEquals(Page.State.Ready, requested.status, change)
            } finally {
                holder?.cancel()
                fixture.loader.recycle()
                runCurrent()
            }
        }
    }

    @Test
    fun `visible holder replaces a fact capability instead of inheriting its permit`() = runTest {
        val scheduler = ReaderRequestScheduler(ReaderSchedulerPolicy.originalMihon())
        val fixture = Fixture(testScheduler, requestScheduler = scheduler)
        val pageId = mihon.domain.reader.session.ReaderPageId(mihon.domain.reader.session.ReaderChapterId(1), 1)
        val budget = mihon.domain.reader.ReaderDualPageFactBudget()
        budget.reserve(listOf(pageId), 0, required = true)
        val permit = mihon.domain.reader.ReaderDualPageFactReadPermit(budget, pageId, { 0L })
        val factConsumer = async { fixture.loader.loadFactPage(fixture.pages[1], permit) }
        var holder: kotlinx.coroutines.Job? = null
        try {
            runCurrent()
            val original = scheduler.snapshot().activeRequests.single()
            assertSame(permit, original.factPermit)
            holder = launch { fixture.loader.loadPage(fixture.pages[1]) }
            runCurrent()
            assertEquals(listOf(1), fixture.cancelled)
            assertEquals(listOf(1, 1), fixture.started)
            assertFalse(scheduler.accepts(original.jobKey))
            assertEquals(null, scheduler.snapshot().activeRequests.single().factPermit)
            fixture.release(1)
            runCurrent()
            assertEquals(Page.State.Ready, fixture.pages[1].status)
        } finally {
            holder?.cancel()
            factConsumer.cancel()
            fixture.loader.recycle()
            budget.close()
            runCurrent()
        }
    }

    @Test
    fun `visible holder promotes the same bound ordinary source request without restarting it`() = runTest {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 1, maxConcurrentRequests = 1),
        )
        val fixture = Fixture(testScheduler, requestScheduler = scheduler)
        var holder: kotlinx.coroutines.Job? = null
        try {
            fixture.loader.onPageSelected(fixture.pages[0])
            runCurrent()
            fixture.release(0)
            runCurrent()
            assertEquals(listOf(0, 1), fixture.started)
            val original = scheduler.snapshot().activeRequests.single()
            assertEquals(ReaderRequestKind.NEARBY, original.kind)

            holder = launch { fixture.loader.loadPage(fixture.pages[1]) }
            runCurrent()

            assertEquals(emptyList<Int>(), fixture.cancelled, "A kind-only upgrade must retain the source request")
            assertEquals(listOf(0, 1), fixture.started, "The same source port must not be called a second time")
            val promoted = scheduler.snapshot().activeRequests.single()
            assertEquals(original.jobKey, promoted.jobKey)
            assertEquals(ReaderRequestKind.INTERACTIVE_VISIBLE, promoted.kind)
            assertTrue(scheduler.accepts(original.jobKey), "The original execution still owns its publication")
            assertTrue(fixture.loader.hasForegroundWork, "A promoted held source task remains busy")

            fixture.release(1)
            runCurrent()
            assertEquals(Page.State.Ready, fixture.pages[1].status)
            assertNotNull(fixture.pages[1].encodedPageRef)
            assertTrue(fixture.loader.pageRunnerSnapshot().activeRequestKeys.isEmpty())
            assertFalse(fixture.loader.hasForegroundWork)
            assertEquals(listOf(0, 1), fixture.started)
        } finally {
            holder?.cancel()
            fixture.loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `shared scheduler policy controls bounded Android production concurrency`() = runTest {
        val requestScheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 3, nearbyBackward = 0, maxConcurrentRequests = 2),
        )
        val fixture = Fixture(testScheduler, requestScheduler = requestScheduler)

        fixture.loader.onPageSelected(fixture.pages[0])
        runCurrent()

        assertEquals(listOf(0, 1), fixture.started)
        assertEquals(
            setOf(0, 1),
            fixture.loader.pageRunnerSnapshot().activeRequestKeys.mapTo(mutableSetOf()) { it.pageIndex },
        )
        fixture.release(0)
        runCurrent()
        assertEquals(listOf(0, 1, 2), fixture.started)
        fixture.loader.recycle()
    }

    @Test
    fun `holder binds do not cancel the selected page generation`() = runTest {
        val fixture = Fixture(testScheduler)

        fixture.loader.onPageSelected(fixture.pages[0])
        runCurrent()
        assertEquals(listOf(0), fixture.started)

        val holderOne = launch { fixture.loader.loadPage(fixture.pages[2]) }
        val holderTwo = launch { fixture.loader.loadPage(fixture.pages[3]) }
        runCurrent()

        assertEquals(listOf(0), fixture.started)
        assertFalse(fixture.pages[0].status == Page.State.Queue)

        holderOne.cancel()
        holderTwo.cancel()
        fixture.loader.recycle()
    }

    @Test
    fun `interactive preemption restores cancelled nearby page to queue and restarts it`() = runTest {
        val requestScheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 1, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        val fixture = Fixture(testScheduler, requestScheduler = requestScheduler)
        fixture.loader.onPageSelected(fixture.pages[0])
        runCurrent()
        fixture.release(0)
        runCurrent()
        assertEquals(listOf(0, 1), fixture.started)

        val visibleHolder = launch { fixture.loader.loadPage(fixture.pages[2]) }
        runCurrent()

        assertEquals(listOf(0, 1, 2), fixture.started)
        fixture.release(2)
        runCurrent()

        assertEquals(listOf(1), fixture.cancelled)
        assertEquals(listOf(0, 1, 2, 1), fixture.started)
        assertEquals(Page.State.DownloadImage, fixture.pages[1].status)
        fixture.release(1)
        runCurrent()
        assertEquals(Page.State.Ready, fixture.pages[1].status)
        visibleHolder.cancel()
        fixture.loader.recycle()
    }

    @Test
    fun `overlapping active page is restarted under the replacement generation`() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.loader.onPageSelected(fixture.pages[0])
        runCurrent()
        fixture.release(0)
        runCurrent()
        assertEquals(listOf(0, 1), fixture.started)

        fixture.loader.onPageSelected(fixture.pages[1])
        runCurrent()

        assertEquals(listOf(0, 1, 1), fixture.started)
        assertEquals(listOf(1), fixture.cancelled)
        assertEquals(Page.State.DownloadImage, fixture.pages[1].status)
        fixture.loader.recycle()
    }

    @Test
    fun `rapid zero one two selection cancels stale jobs without error and reorders current first`() = runTest {
        val fixture = Fixture(testScheduler)

        fixture.loader.onPageSelected(fixture.pages[0])
        runCurrent()
        fixture.loader.onPageSelected(fixture.pages[1])
        runCurrent()
        fixture.loader.onPageSelected(fixture.pages[2])
        runCurrent()

        assertEquals(listOf(0, 1, 2), fixture.started)
        assertEquals(listOf(0, 1), fixture.cancelled)
        assertEquals(Page.State.Queue, fixture.pages[0].status)
        assertEquals(Page.State.Queue, fixture.pages[1].status)
        assertInstanceOf(Page.State.DownloadImage::class.java, fixture.pages[2].status)
        fixture.loader.recycle()
    }

    @Test
    fun `non cooperative stale request cannot publish ready after a new generation`() = runTest {
        val fixture = Fixture(testScheduler, nonCancellablePages = setOf(0))
        fixture.loader.onPageSelected(fixture.pages[0])
        runCurrent()
        fixture.loader.onPageSelected(fixture.pages[2])
        runCurrent()

        assertEquals(listOf(0, 2), fixture.started)

        fixture.release(0)
        runCurrent()

        assertEquals(Page.State.Queue, fixture.pages[0].status)
        assertEquals(listOf(0, 2), fixture.started)
        assertFalse(fixture.pages[0].status is Page.State.Error)
        fixture.loader.recycle()
    }

    @Test
    fun `rapid non cooperative generations keep actual Android IO within the stale budget`() = runTest {
        val fixture = Fixture(
            scheduler = testScheduler,
            nonCancellablePages = (0..4).toSet(),
        )

        try {
            repeat(5) { pageIndex ->
                fixture.loader.onPageSelected(fixture.pages[pageIndex])
                runCurrent()
            }

            assertTrue(
                fixture.peakInFlight <= 2,
                "One current request plus at most one cancellation-ignoring stale request may perform physical IO",
            )
        } finally {
            repeat(5, fixture::release)
            runCurrent()
            fixture.loader.recycle()
        }
    }

    @Test
    fun `non cooperative stale request cannot publish ordinary failure after a new generation`() = runTest {
        val fixture = Fixture(
            scheduler = testScheduler,
            nonCancellablePages = setOf(0),
            failingPages = setOf(0),
        )
        fixture.loader.onPageSelected(fixture.pages[0])
        runCurrent()
        fixture.loader.onPageSelected(fixture.pages[2])
        runCurrent()

        fixture.release(0)
        runCurrent()

        assertEquals(Page.State.Queue, fixture.pages[0].status)
        assertFalse(fixture.pages[0].status is Page.State.Error)
        fixture.loader.recycle()
    }

    @Test
    fun `late failure from old generation cannot replace ready content of the same page`() = runTest {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        val response = mockk<Response>()
        every { response.code } returns 200
        every { response.close() } returns Unit
        val releaseFirst = Channel<Unit>(Channel.UNLIMITED)
        val cachedUrls = mutableSetOf<String>()
        var attempts = 0
        every { cache.isImageInCache(any()) } answers { firstArg<String>() in cachedUrls }
        every { cache.putImageToCache(any(), any()) } answers {
            cachedUrls += firstArg<String>()
            true
        }
        every { cache.removeImageFromCache(any()) } answers { cachedUrls.remove(firstArg<String>()) }
        every { cache.getImageFile(any()) } returns File("unused")
        coEvery { source.getImage(any()) } coAnswers {
            if (attempts++ == 0) {
                withContext(NonCancellable) {
                    releaseFirst.receive()
                    throw IOException("late stale failure")
                }
            }
            response
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 1))
        val page = ReaderPage(0, imageUrl = "https://example.test/image").apply { this.chapter = chapter }
        chapter.publishLoadedPageListForTest(listOf(page))
        val loader = HttpPageLoader(
            chapter = chapter,
            source = source,
            chapterCache = cache,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        loader.onPageSelected(page)
        runCurrent()
        loader.onPageSelected(page)
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(Page.State.Ready, page.status)
        val acceptedRef = page.encodedPageRef
        assertNotNull(acceptedRef)
        val acceptedSnapshot = chapter.sharedSessionStateFlow.value

        releaseFirst.trySend(Unit).getOrThrow()
        runCurrent()

        assertEquals(Page.State.Ready, page.status)
        assertEquals(acceptedRef, page.encodedPageRef)
        assertSame(acceptedSnapshot, chapter.sharedSessionStateFlow.value)
        loader.recycle()
    }

    private class Fixture(
        scheduler: TestCoroutineScheduler,
        private val nonCancellablePages: Set<Int> = emptySet(),
        private val failingPages: Set<Int> = emptySet(),
        private val requestScheduler: ReaderRequestScheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy.originalMihon(),
        ),
    ) {
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        var peakInFlight = 0
            private set
        private var inFlight = 0
        private val releases = List(6) { Channel<Unit>(Channel.UNLIMITED) }
        val pages: List<ReaderPage>
        val loader: HttpPageLoader

        init {
            val source = mockk<HttpSource>()
            val cache = mockk<ChapterCache>()
            val response = mockk<Response>()
            every { response.code } returns 200
            every { response.close() } returns Unit
            val cachedUrls = mutableSetOf<String>()
            every { cache.isImageInCache(any()) } answers { firstArg<String>() in cachedUrls }
            every { cache.putImageToCache(any(), any()) } answers {
                cachedUrls += firstArg<String>()
                true
            }
            every { cache.removeImageFromCache(any()) } answers { cachedUrls.remove(firstArg<String>()) }
            every { cache.getImageFile(any()) } returns File("unused")
            coEvery { source.getImage(any()) } coAnswers {
                val page = firstArg<Page>()
                started += page.index
                inFlight++
                peakInFlight = maxOf(peakInFlight, inFlight)
                try {
                    if (page.index in nonCancellablePages) {
                        withContext(NonCancellable) {
                            releases[page.index].receive()
                            if (page.index in failingPages) throw IOException("stale failure")
                            response
                        }
                    } else {
                        releases[page.index].receive()
                        if (page.index in failingPages) throw IOException("load failure")
                        response
                    }
                } catch (e: CancellationException) {
                    cancelled += page.index
                    throw e
                } finally {
                    inFlight--
                }
            }
            val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 1))
            pages = List(6) { ReaderPage(it, imageUrl = "https://example/$it") }
                .onEach { it.chapter = chapter }
            chapter.publishLoadedPageListForTest(pages)
            loader = HttpPageLoader(
                chapter = chapter,
                source = source,
                chapterCache = cache,
                dispatcher = StandardTestDispatcher(scheduler),
                requestScheduler = requestScheduler,
            )
        }

        fun release(pageIndex: Int) {
            releases[pageIndex].trySend(Unit).getOrThrow()
        }
    }
}
