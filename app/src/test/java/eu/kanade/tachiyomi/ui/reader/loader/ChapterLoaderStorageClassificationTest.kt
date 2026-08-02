package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import java.io.File
import java.io.IOException

class ChapterLoaderStorageClassificationTest {

    @Test
    fun `local route construction failure is published as storage error`() = runTest {
        val source = mockk<LocalSource>()
        every { source.getFormat(any()) } throws IOException("local chapter unavailable")
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val loader = ChapterLoader(
            context = mockk<Context>(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            manga = mockk<Manga>(relaxed = true),
            source = source,
        )

        val result = runCatching { loader.loadChapter(chapter) }

        assertTrue(result.isFailure)
        val state = assertInstanceOf(
            ReaderChapterLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.activeChapter.loadState,
        )
        assertInstanceOf(AppError.Storage::class.java, state.error)
    }

    @Test
    fun `local archive loader construction failure is published as storage error`() = runTest {
        val source = mockk<LocalSource>()
        every { source.getFormat(any()) } returns Format.Archive(mockk<UniFile>())
        val factories = ReaderPageLoaderFactories(
            download = { error("unexpected download") },
            localDirectory = { error("unexpected directory") },
            localArchive = { throw IOException("archive cannot be opened") },
            localEpub = { error("unexpected epub") },
            online = { _, _ -> error("unexpected online") },
            missingSource = { error("unexpected missing source") },
            unsupported = { error("unexpected unsupported source") },
        )
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val loader = ChapterLoader(
            context = mockk<Context>(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            manga = mockk<Manga>(relaxed = true),
            source = source,
            routedPageLoaderFactories = factories,
        )

        val result = runCatching { loader.loadChapter(chapter) }

        assertTrue(result.isFailure)
        val state = assertInstanceOf(
            ReaderChapterLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.activeChapter.loadState,
        )
        assertInstanceOf(AppError.Storage::class.java, state.error)
    }

    @Test
    fun `encoded cache write failure is published as page storage error`() = runTest {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        every { cache.isImageInCache(any()) } returns false
        every { cache.putImageToCache(any(), any()) } throws IOException("disk full")
        every { cache.getImageFile(any()) } returns File("unused")
        coEvery { source.getImage(any()) } returns mockk<Response>()
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val page = ReaderPage(0, imageUrl = "https://example.test/image").apply { this.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val loader = HttpPageLoader(
            chapter = chapter,
            source = source,
            chapterCache = cache,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        loader.onPageSelected(page)
        runCurrent()

        assertInstanceOf(Page.State.Error::class.java, page.status)
        val pageState = assertInstanceOf(
            ReaderPageLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
        assertInstanceOf(AppError.Storage::class.java, pageState.error)
        loader.recycle()
    }

    @Test
    fun `encoded cache write preserves an existing classified error`() = runTest {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        every { cache.isImageInCache(any()) } returns false
        every { cache.putImageToCache(any(), any()) } throws AppErrorException(AppError.Permission())
        coEvery { source.getImage(any()) } returns mockk<Response>()
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val page = ReaderPage(0, imageUrl = "https://example.test/image").apply { this.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val loader = HttpPageLoader(
            chapter = chapter,
            source = source,
            chapterCache = cache,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        loader.onPageSelected(page)
        runCurrent()

        val pageState = assertInstanceOf(
            ReaderPageLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
        assertInstanceOf(AppError.Permission::class.java, pageState.error)
        loader.recycle()
    }

    @Test
    fun `encoded cache write cancellation remains cancellation rather than an error`() = runTest {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        every { cache.isImageInCache(any()) } returns false
        every { cache.putImageToCache(any(), any()) } throws CancellationException("cancelled")
        coEvery { source.getImage(any()) } returns mockk<Response>()
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val page = ReaderPage(0, imageUrl = "https://example.test/image").apply { this.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val loader = HttpPageLoader(
            chapter = chapter,
            source = source,
            chapterCache = cache,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        loader.onPageSelected(page)
        runCurrent()

        assertEquals(Page.State.Queue, page.status)
        assertEquals(
            ReaderPageLoadState.Queued,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
        loader.recycle()
    }
}
