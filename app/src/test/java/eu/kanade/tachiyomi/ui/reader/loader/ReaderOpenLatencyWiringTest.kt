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
import eu.kanade.tachiyomi.ui.reader.viewer.pager.readerTestPng
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
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderOpenLatencyWiringTest {
    @Test
    fun `probe brackets all production header reads before pages ready`() = runTest {
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.configure(true, 1, 7, "wiring")
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.startChapter(1, 7)
        var reads = 0
        val png = readerTestPng(32, 48)
        val pages = List(187) { index ->
            ReaderPage(index, url = "").apply {
                stream = {
                    val events = eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.capturedEvents().map { it.event }
                    assertTrue("HEADER_FACTS_BEGIN" in events)
                    assertTrue("HEADER_FACTS_END" !in events)
                    reads++
                    ByteArrayInputStream(png)
                }
                status = Page.State.Ready
            }
        }
        val pageLoader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = pages
        }
        val loader = ChapterLoader(
            context = mockk<Context>(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            manga = mockk<Manga>(relaxed = true),
            source = mockk<Source>(relaxed = true),
            pageLoaderFactory = { pageLoader },
        )
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        loader.loadChapter(chapter)
        assertEquals(187, reads)
        val names = eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.capturedEvents().map { it.event }
        assertTrue(names.indexOf("GET_PAGES_END") < names.indexOf("HEADER_FACTS_BEGIN"))
        assertTrue(names.indexOf("HEADER_FACTS_END") < names.indexOf("PAGE_LIST_READY"))
        assertEquals(187, chapter.pages?.size)
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.configure(false, 1, 7, "off")
    }
}
