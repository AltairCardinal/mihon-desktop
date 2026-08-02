package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.storage.EncodedPageEvictionResult
import mihon.domain.reader.storage.EncodedPageStoreDiagnostics
import mihon.domain.reader.storage.EncodedPageStoreEntry
import mihon.domain.reader.storage.EncodedPageStoreLifecycleResult
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import mihon.domain.reader.storage.ReaderEncodedPageStore
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class ReaderMaterializeProductionWiringTest {

    @Test
    fun `chapter loader publishes the canonical executor descriptors`() = runTest {
        val executor = RecordingExecutor()
        val platformPage = ReaderPage(0, url = "").apply {
            stream = { ByteArrayInputStream(byteArrayOf(1)) }
            status = Page.State.Ready
        }
        val pageLoader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = listOf(platformPage)
        }
        val loader = ChapterLoader(
            context = mockk<Context>(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            manga = mockk<Manga>(relaxed = true),
            source = mockk<Source>(relaxed = true),
            pageLoaderFactory = { pageLoader },
            materializeExecutor = executor,
        )
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))

        loader.loadChapter(chapter)

        assertEquals(1, executor.chapterCalls)
        assertNotNull(executor.chapterDescriptors.single().encodedPageRef)
        assertEquals(
            executor.chapterDescriptors.single().encodedPageRef,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().encodedPageRef,
        )
        assertEquals(platformPage, chapter.pages?.single())
    }

    @Test
    fun `HTTP page request runs through canonical executor and retry forces redownload`() = runTest {
        val executor = RecordingExecutor()
        val encodedStore = RecordingEncodedPageStore()
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        val response = mockk<Response>()
        every { cache.isImageInCache(any()) } returns true
        every { cache.putImageToCache(any(), any()) } returns true
        every { cache.getImageFile(any()) } returns File("unused")
        coEvery { source.getImage(any()) } returns response
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val page = ReaderPage(0, imageUrl = "https://example.test/image").apply {
            this.chapter = chapter
            status = Page.State.Error(IOException("first attempt"))
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        val loader = HttpPageLoader(
            chapter = chapter,
            source = source,
            chapterCache = cache,
            dispatcher = StandardTestDispatcher(testScheduler),
            materializeExecutor = executor,
            encodedPageStore = encodedStore,
        )

        loader.retryPage(page)
        runCurrent()

        assertEquals(1, executor.pageCalls)
        assertTrue(executor.forceRefreshes.single())
        assertEquals(1, encodedStore.writeCalls)
        verify(exactly = 1) { cache.putImageToCache("https://example.test/image", response) }
        assertEquals(Page.State.Ready, page.status)
        loader.recycle()
    }

    @Test
    fun `current HTTP page failure is classified and retry recovers in place`() = runTest {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        val response = mockk<Response>()
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
            if (attempts++ == 0) throw IOException("offline")
            response
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
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

        assertInstanceOf(Page.State.Error::class.java, page.status)
        val sharedFailure = assertInstanceOf(
            ReaderPageLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
        assertInstanceOf(AppError.Network::class.java, sharedFailure.error)

        loader.retryPage(page)
        runCurrent()

        assertEquals(Page.State.Ready, page.status)
        assertEquals(
            ReaderPageLoadState.Ready,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
        assertEquals(2, attempts)
        loader.recycle()
    }

    @Test
    fun `cache editor rejection is published as storage failure instead of ready`() = runTest {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        val response = mockk<Response>()
        every { cache.isImageInCache(any()) } returns false
        every { cache.putImageToCache(any(), any()) } returns false
        every { cache.getImageFile(any()) } returns File("missing")
        coEvery { source.getImage(any()) } returns response
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val page = ReaderPage(0, imageUrl = "https://example.test/contended").apply { this.chapter = chapter }
        chapter.publishLoadedPageListForTest(listOf(page))
        val loader = HttpPageLoader(
            chapter = chapter,
            source = source,
            chapterCache = cache,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        loader.onPageSelected(page)
        runCurrent()

        assertInstanceOf(Page.State.Error::class.java, page.status)
        val sharedFailure = assertInstanceOf(
            ReaderPageLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
        assertInstanceOf(AppError.Storage::class.java, sharedFailure.error)
        loader.recycle()
    }

    private class RecordingExecutor : ReaderMaterializeExecutor {
        var chapterCalls = 0
        var pageCalls = 0
        var chapterDescriptors = emptyList<mihon.domain.reader.session.ReaderPageDescriptor>()
        val forceRefreshes = mutableListOf<Boolean>()

        override suspend fun materializeChapter(
            request: ReaderChapterContentRequest,
            port: ReaderChapterContentPort,
        ): ReaderChapterMaterializeResult {
            chapterCalls++
            return CanonicalReaderMaterializeExecutor.materializeChapter(
                request,
                ReaderChapterContentPort {
                    port.loadChapterContent(it).also { descriptors -> chapterDescriptors = descriptors }
                },
            )
        }

        override suspend fun materializePage(
            request: ReaderPageFetchRequest,
            port: ReaderPageFetchPort,
            forceRefresh: Boolean,
            publish: (ReaderPageMaterializeEvent) -> Boolean,
        ): ReaderPageMaterializeResult {
            pageCalls++
            forceRefreshes += forceRefresh
            return CanonicalReaderMaterializeExecutor.materializePage(request, port, forceRefresh, publish)
        }
    }

    private class RecordingEncodedPageStore : ReaderEncodedPageStore {
        var writeCalls = 0

        override suspend fun beginSession(retainedRefs: Set<EncodedPageRef>) = EncodedPageStoreLifecycleResult(
            availableRefs = emptySet(),
            missingRefs = retainedRefs,
            evictedRefs = emptySet(),
        )

        override suspend fun contains(ref: EncodedPageRef): Boolean = false

        override suspend fun store(
            ref: EncodedPageRef,
            writer: suspend () -> Long,
        ): EncodedPageStoreWriteResult {
            writeCalls++
            return EncodedPageStoreWriteResult.Stored(
                entry = EncodedPageStoreEntry(ref, writer()),
                evictedRefs = emptySet(),
            )
        }

        override suspend fun evict(ref: EncodedPageRef): EncodedPageEvictionResult =
            EncodedPageEvictionResult.Missing

        override fun diagnostics() = EncodedPageStoreDiagnostics(
            refs = emptySet(),
            usedBytes = 0,
            maxBytes = 1,
            hitCount = 0,
            missCount = 0,
            writeCount = writeCalls.toLong(),
            evictionCount = 0,
            isSessionOpen = true,
        )

        override fun endSession(): EncodedPageStoreDiagnostics = diagnostics().copy(isSessionOpen = false)
    }
}
