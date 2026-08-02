package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapterWindowOwner
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadPurpose
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterWindowEffect
import mihon.domain.reader.session.ReaderChapterWindowIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger

class ChapterLoaderWindowEffectIntegrationTest {

    @Test
    fun `stale prefetch effect cannot restart a chapter after it leaves the retained window`() = runTest {
        val previous = readerChapter(1)
        val current = readerChapter(2)
        val target = readerChapter(3)
        val replacement = readerChapter(0)
        val owner = ReaderChapterWindowOwner()
        owner.replace(ViewerChapters(current, previous, target))
        val stalePrefetch = requireNotNull(
            owner.pageListEffect(target, ReaderChapterLoadPurpose.PREFETCH),
        )

        owner.dispatch(
            ReaderChapterWindowIntent.OpenAdjacent(
                direction = ReaderTransitionDirection.PREVIOUS,
                expectedCurrentChapterId = ReaderChapterId(2),
                expectedTargetChapterId = ReaderChapterId(1),
                replacementChapterId = ReaderChapterId(0),
            ),
            availableChapters = listOf(replacement, previous, current, target),
        )

        val factoryCalls = AtomicInteger()
        val leakedLoader = ImmediatePageLoader(target)
        val chapterLoader = chapterLoader {
            factoryCalls.incrementAndGet()
            leakedLoader
        }
        val failure = runCatching {
            chapterLoader.loadChapter(target, stalePrefetch)
        }.exceptionOrNull()
        owner.close()

        assertInstanceOf(CancellationException::class.java, failure)
        assertEquals(0, factoryCalls.get())
        assertSame(ReaderChapter.State.Wait, target.state)
        assertEquals(null, target.pageLoader)
        assertFalse(leakedLoader.isRecycled)
    }

    @Test
    fun `activation waits for the in-flight adjacent page list and reuses its retained session`() = runTest {
        val pageLoader = ControlledPageLoader()
        val factoryCalls = AtomicInteger()
        val chapterLoader = chapterLoader {
            factoryCalls.incrementAndGet()
            pageLoader
        }
        val chapter = readerChapter(3)
        chapter.ref()
        val prefetch = ReaderChapterWindowEffect.BeginPageListLoad(
            chapterId = ReaderChapterId(3),
            purpose = ReaderChapterLoadPurpose.PREFETCH,
        )
        val activation = prefetch.copy(purpose = ReaderChapterLoadPurpose.ACTIVATE)

        try {
            val prefetchLoad = async { chapterLoader.loadChapter(chapter, prefetch) }
            pageLoader.started.await()

            val loading = chapter.sharedSessionStateFlow.value.activeChapter
            assertInstanceOf(ReaderChapterLoadState.LoadingPageList::class.java, loading.loadState)
            assertEquals(emptyList<Any>(), loading.pages)

            val activationLoad = async { chapterLoader.loadChapter(chapter, activation) }
            runCurrent()

            assertFalse(activationLoad.isCompleted)
            assertEquals(1, factoryCalls.get())

            pageLoader.complete(listOf(ReaderPage(0, "/page/0")))
            prefetchLoad.await()
            activationLoad.await()

            assertSame(pageLoader, chapter.pageLoader)
            assertEquals("/page/0", chapter.pages?.single()?.url)
            assertEquals(1, factoryCalls.get())
        } finally {
            chapter.unref()
        }
    }

    private fun chapterLoader(factory: (ReaderChapter) -> PageLoader) = ChapterLoader(
        context = mockk<Context>(relaxed = true),
        downloadManager = mockk<DownloadManager>(relaxed = true),
        downloadProvider = mockk<DownloadProvider>(relaxed = true),
        manga = mockk<Manga>(relaxed = true),
        source = mockk<Source>(relaxed = true),
        pageLoaderFactory = factory,
    )

    private fun readerChapter(id: Long) = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1))

    private class ImmediatePageLoader(
        private val chapter: ReaderChapter,
    ) : PageLoader() {
        override var isLocal = false

        override suspend fun getPages() = listOf(
            ReaderPage(0, "/page/0").apply { this.chapter = this@ImmediatePageLoader.chapter },
        )
    }

    private class ControlledPageLoader : PageLoader() {
        override var isLocal = false
        val started = CompletableDeferred<Unit>()
        private val pages = CompletableDeferred<List<ReaderPage>>()

        override suspend fun getPages(): List<ReaderPage> {
            started.complete(Unit)
            return pages.await()
        }

        fun complete(value: List<ReaderPage>) {
            pages.complete(value)
        }
    }
}
