package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChapterLoaderSessionGenerationIntegrationTest {

    @Test
    fun `late page-list success cannot replace the newer generation`() = runTest {
        assertLateFirstResultCannotReplaceNewerSession(
            firstResult = Result.success(listOf(ReaderPage(0, "/old"))),
        )
    }

    @Test
    fun `late page-list failure cannot replace the newer generation`() = runTest {
        assertLateFirstResultCannotReplaceNewerSession(
            firstResult = Result.failure(IllegalStateException("late old failure")),
        )
    }

    @Test
    fun `late loader construction cannot replace the newer generation loader`() = runTest {
        val firstLoader = ControlledPageLoader()
        val replacementLoader = ControlledPageLoader()
        val firstFactoryEntered = CompletableDeferred<Unit>()
        val releaseFirstFactory = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val chapterLoader = chapterLoader(pageLoaderFactory = { _ ->
            if (factoryCalls.getAndIncrement() == 0) {
                firstFactoryEntered.complete(Unit)
                check(releaseFirstFactory.await(30, TimeUnit.SECONDS))
                firstLoader
            } else {
                replacementLoader
            }
        })
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))

        val firstLoad = async { runCatching { chapterLoader.loadChapter(chapter) } }
        firstFactoryEntered.await()
        val replacementLoad = async { runCatching { chapterLoader.loadChapter(chapter) } }
        replacementLoader.started.await()
        replacementLoader.complete(Result.success(listOf(ReaderPage(0, "/replacement"))))
        replacementLoad.await().getOrThrow()
        val replacementSnapshot = chapter.sharedSessionStateFlow.value
        val replacementState = chapter.state

        firstLoader.complete(Result.success(listOf(ReaderPage(0, "/old"))))
        releaseFirstFactory.countDown()
        firstLoad.await().getOrThrow()

        assertSame(replacementSnapshot, chapter.sharedSessionStateFlow.value)
        assertSame(replacementState, chapter.state)
        assertSame(replacementLoader, chapter.pageLoader)
        assertTrue(firstLoader.isRecycled)
    }

    @Test
    fun `replacement generation retires its installed predecessor before resolving a loader`() = runTest {
        val firstLoader = ControlledPageLoader()
        val secondFactoryEntered = CompletableDeferred<Unit>()
        val releaseSecondFactory = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val chapterLoader = chapterLoader(pageLoaderFactory = { _ ->
            if (factoryCalls.getAndIncrement() == 0) {
                firstLoader
            } else {
                secondFactoryEntered.complete(Unit)
                check(releaseSecondFactory.await(30, TimeUnit.SECONDS))
                throw IllegalStateException("replacement factory failed")
            }
        })
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))

        val firstLoad = async { runCatching { chapterLoader.loadChapter(chapter) } }
        firstLoader.started.await()
        val replacementLoad = async { runCatching { chapterLoader.loadChapter(chapter) } }
        secondFactoryEntered.await()
        val loaderWhileReplacementFactoryIsBlocked = chapter.pageLoader
        val recycleCallsBeforeReplacementFailure = firstLoader.recycleCalls.get()

        releaseSecondFactory.countDown()
        assertTrue(replacementLoad.await().isFailure)
        firstLoader.complete(Result.success(listOf(ReaderPage(0, "/stale"))))
        firstLoad.await().getOrThrow()

        assertNull(loaderWhileReplacementFactoryIsBlocked)
        assertEquals(1, recycleCallsBeforeReplacementFailure)
        assertEquals(1, firstLoader.recycleCalls.get())
        assertNull(chapter.pageLoader)
    }

    @Test
    fun `load accepted while another caller waits to begin remains the owner`() = runTest {
        val firstLoader = ControlledPageLoader()
        val replacementLoader = ControlledPageLoader().apply {
            complete(Result.success(listOf(ReaderPage(0, "/replacement"))))
        }
        val secondBeforeBegin = CompletableDeferred<Unit>()
        val releaseSecondBegin = CountDownLatch(1)
        val beginCalls = AtomicInteger()
        val factoryCalls = AtomicInteger()
        val chapterLoader = chapterLoader(
            pageLoaderFactory = {
                if (factoryCalls.getAndIncrement() == 0) firstLoader else replacementLoader
            },
            beforeBeginPageListLoad = {
                if (beginCalls.getAndIncrement() == 1) {
                    secondBeforeBegin.complete(Unit)
                    check(releaseSecondBegin.await(30, TimeUnit.SECONDS))
                }
            },
        )
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))

        val firstLoad = async { runCatching { chapterLoader.loadChapter(chapter) } }
        firstLoader.started.await()
        val waitingLoad = async(Dispatchers.Default) { runCatching { chapterLoader.loadChapter(chapter) } }
        secondBeforeBegin.await()

        firstLoader.complete(Result.success(listOf(ReaderPage(0, "/accepted"))))
        firstLoad.await().getOrThrow()
        val acceptedSnapshot = chapter.sharedSessionStateFlow.value
        val acceptedState = chapter.state
        releaseSecondBegin.countDown()
        waitingLoad.await().getOrThrow()

        assertSame(acceptedSnapshot, chapter.sharedSessionStateFlow.value)
        assertSame(acceptedState, chapter.state)
        assertSame(firstLoader, chapter.pageLoader)
        assertEquals(1, factoryCalls.get())
        assertEquals(0, firstLoader.recycleCalls.get())
    }

    private suspend fun TestScope.assertLateFirstResultCannotReplaceNewerSession(
        firstResult: Result<List<ReaderPage>>,
    ) {
        val firstLoader = ControlledPageLoader()
        val replacementLoader = ControlledPageLoader()
        val loaders = ArrayDeque(listOf(firstLoader, replacementLoader))
        val chapterLoader = chapterLoader(pageLoaderFactory = { loaders.removeFirst() })
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))

        val firstLoad = async { runCatching { chapterLoader.loadChapter(chapter) } }
        firstLoader.started.await()
        val replacementLoad = async { runCatching { chapterLoader.loadChapter(chapter) } }
        replacementLoader.started.await()

        replacementLoader.complete(Result.success(listOf(ReaderPage(0, "/replacement"))))
        replacementLoad.await().getOrThrow()
        val replacementSnapshot = chapter.sharedSessionStateFlow.value
        val replacementState = chapter.state

        firstLoader.complete(firstResult)
        val firstOutcome = firstLoad.await()
        if (firstResult.isFailure) {
            assertTrue(firstOutcome.isFailure)
        } else {
            firstOutcome.getOrThrow()
        }

        assertSame(replacementSnapshot, chapter.sharedSessionStateFlow.value)
        assertSame(replacementState, chapter.state)
        assertSame(replacementLoader, chapter.pageLoader)
        assertEquals("/replacement", chapter.pages?.single()?.url)
        assertEquals("/replacement", chapter.sharedSessionStateFlow.value.activeChapter.pages.single().url)
        assertEquals(1, firstLoader.recycleCalls.get())
    }

    private fun chapterLoader(
        pageLoaderFactory: (ReaderChapter) -> PageLoader,
        beforeBeginPageListLoad: (() -> Unit)? = null,
    ) = ChapterLoader(
        context = mockk<Context>(relaxed = true),
        downloadManager = mockk<DownloadManager>(relaxed = true),
        downloadProvider = mockk<DownloadProvider>(relaxed = true),
        manga = mockk<Manga>(relaxed = true),
        source = mockk<Source>(relaxed = true),
        pageLoaderFactory = pageLoaderFactory,
        beforeBeginPageListLoad = beforeBeginPageListLoad,
    )

    private class ControlledPageLoader : PageLoader() {
        override var isLocal: Boolean = false
        val started = CompletableDeferred<Unit>()
        private val result = CompletableDeferred<Result<List<ReaderPage>>>()
        val recycleCalls = AtomicInteger()

        override suspend fun getPages(): List<ReaderPage> {
            started.complete(Unit)
            return result.await().getOrThrow()
        }

        fun complete(value: Result<List<ReaderPage>>) {
            result.complete(value)
        }

        override fun recycle() {
            recycleCalls.incrementAndGet()
            super.recycle()
        }
    }
}
