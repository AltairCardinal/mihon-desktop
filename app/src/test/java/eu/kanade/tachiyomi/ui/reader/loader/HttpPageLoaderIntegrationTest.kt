package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.File

class HttpPageLoaderIntegrationTest {

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

        fixture.release(0)
        runCurrent()

        assertEquals(Page.State.Queue, fixture.pages[0].status)
        assertEquals(listOf(0, 2), fixture.started)
        assertFalse(fixture.pages[0].status is Page.State.Error)
        fixture.loader.recycle()
    }

    private class Fixture(
        scheduler: TestCoroutineScheduler,
        private val nonCancellablePages: Set<Int> = emptySet(),
    ) {
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        private val releases = List(6) { Channel<Unit>(Channel.UNLIMITED) }
        val pages: List<ReaderPage>
        val loader: HttpPageLoader

        init {
            val source = mockk<HttpSource>()
            val cache = mockk<ChapterCache>()
            val response = mockk<Response>()
            every { cache.isImageInCache(any()) } returns false
            every { cache.putImageToCache(any(), any()) } returns Unit
            every { cache.getImageFile(any()) } returns File("unused")
            coEvery { source.getImage(any()) } coAnswers {
                val page = firstArg<Page>()
                started += page.index
                try {
                    if (page.index in nonCancellablePages) {
                        withContext(NonCancellable) { releases[page.index].receive() }
                    } else {
                        releases[page.index].receive()
                    }
                    response
                } catch (e: CancellationException) {
                    cancelled += page.index
                    throw e
                }
            }
            val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 1))
            pages = List(6) { ReaderPage(it, imageUrl = "https://example/$it") }
                .onEach { it.chapter = chapter }
            chapter.state = ReaderChapter.State.Loaded(pages)
            loader = HttpPageLoader(
                chapter = chapter,
                source = source,
                chapterCache = cache,
                dispatcher = StandardTestDispatcher(scheduler),
            )
        }

        fun release(pageIndex: Int) {
            releases[pageIndex].trySend(Unit).getOrThrow()
        }
    }
}
