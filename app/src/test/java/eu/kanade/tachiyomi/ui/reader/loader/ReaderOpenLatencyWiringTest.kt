package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.readerTestPng
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterLoadState
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderOpenLatencyWiringTest {
    @Test
    fun `new page list is published without opening chapter image headers`() = runTest {
        var reads = 0
        val png = readerTestPng(32, 48)
        val pages = readyPages {
            reads++
            ByteArrayInputStream(png)
        }
        val pageLoader = localLoader(pages)
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1, lastPageRead = 125))

        chapterLoader(pageLoader).loadChapter(chapter)

        assertEquals(0, reads, "Publishing the page list must not open the chapter's image streams")
        assertSame(pageLoader, chapter.pageLoader)
        assertSame(pages, chapter.pages)
        val snapshot = chapter.sharedSessionStateFlow.value
        assertEquals(ReaderChapterLoadState.Loaded, snapshot.activeChapter.loadState)
        assertTrue(snapshot.generation > 0)
        assertEquals(125, chapter.requestedPage)
        assertEquals((0 until 187).toList(), snapshot.activeChapter.pages.map { it.id.sourcePageIndex })
        pages.forEach { page ->
            assertSame(chapter, page.chapter)
            assertEquals(EncodedPageRef("android-reader:7:${snapshot.generation}:${page.index}"), page.encodedPageRef)
            assertEquals(page.encodedPageRef, snapshot.activeChapter.pages[page.index].encodedPageRef)
            assertEquals(ReaderPageFactState.UNKNOWN, page.pageFact.value.state)
        }
    }

    @Test
    fun `loaded chapter skips header scan while target still publishes its own fact`() = runTest {
        var reads = 0
        val png = readerTestPng(32, 48)
        val pages = readyPages {
            reads++
            ByteArrayInputStream(png)
        }
        val pageLoader = localLoader(pages)
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val generation = checkNotNull(chapter.beginPageListLoadIfNeeded())
        assertTrue(chapter.installPageLoader(generation, pageLoader))
        pages.forEach { page ->
            page.chapter = chapter
            page.encodedPageRef = EncodedPageRef("loaded:${page.index}")
        }
        assertTrue(chapter.completePageListLoad(generation, pages))
        chapter.requestedPage = 125
        val target = pages[125]
        val identity = target.encodedContentIdentity()
        val reference = target.encodedPageRef

        chapterLoader(pageLoader).loadChapter(chapter)

        assertEquals(0, reads, "Reusing Loaded pages must not scan image headers")
        assertSame(pages, chapter.pages)
        assertSame(pageLoader, chapter.pageLoader)
        assertEquals(generation, chapter.sharedSessionStateFlow.value.generation)
        assertEquals(125, chapter.requestedPage)
        assertTrue(pages.all { it.pageFact.value.state == ReaderPageFactState.UNKNOWN })
        target.publishHeaderFact()
        assertEquals(1, reads)
        assertEquals(ReaderPageFactState.PORTRAIT, target.pageFact.value.state)
        assertEquals(32, target.pageFact.value.width)
        assertEquals(48, target.pageFact.value.height)
        assertEquals(ReaderImageOrientation.NORMAL, target.pageFact.value.sourceOrientation)
        assertEquals(identity, target.encodedContentIdentity())
        assertEquals(reference, target.encodedPageRef)
        assertTrue(pages.filter { it !== target }.all { it.pageFact.value.state == ReaderPageFactState.UNKNOWN })
    }

    private fun readyPages(openStream: () -> ByteArrayInputStream) = List(187) { index ->
        ReaderPage(index, url = "page:$index", stream = openStream).apply { status = Page.State.Ready }
    }

    private fun localLoader(pages: List<ReaderPage>) = object : PageLoader() {
        override var isLocal = true
        override suspend fun getPages() = pages
    }

    private fun chapterLoader(pageLoader: PageLoader) = ChapterLoader(
        context = mockk<Context>(relaxed = true),
        downloadManager = mockk<DownloadManager>(relaxed = true),
        downloadProvider = mockk<DownloadProvider>(relaxed = true),
        manga = mockk<Manga>(relaxed = true),
        source = mockk<Source>(relaxed = true),
        pageLoaderFactory = { pageLoader },
    )
}
