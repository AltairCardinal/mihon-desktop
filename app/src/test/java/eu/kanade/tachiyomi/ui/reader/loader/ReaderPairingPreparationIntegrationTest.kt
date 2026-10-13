package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadPurpose
import mihon.domain.reader.session.ReaderChapterWindowEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** These enter ChapterLoader rather than calling the header port with different flags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderPairingPreparationIntegrationTest {
    @Test
    fun truncatedHeaderCannotConfirmNormalWhenExifFollowsTheByteLimit() {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        val sos = (2 until jpeg.size - 1).first {
            jpeg[it].toInt() and 255 == 255 &&
                jpeg[it + 1].toInt() and 255 == 0xda
        }
        val late = ByteArrayOutputStream().also { out ->
            out.write(jpeg, 0, sos)
            repeat(9) {
                out.write(byteArrayOf(-1, -30, -1, -2))
                out.write(ByteArray(65532))
            }
            val exif = "45786966000049492a0008000000010012010300010000000600000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            out.write(byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()))
            out.write(exif)
            out.write(jpeg, sos, jpeg.size - sos)
        }.toByteArray()
        assertEquals(
            mihon.domain.reader.ReaderImageOrientation.CLOCKWISE,
            mihon.domain.reader.readImageOrientation(late),
        )
        val page = eu.kanade.tachiyomi.ui.reader.model.ReaderPage(0, stream = { late.inputStream() })
        page.publishHeaderFact()
        assertEquals(
            "Truncated metadata cannot certify a wide page in the wrong orientation",
            ReaderPageFactState.FAILED,
            page.pageFact.value.state,
        )
        page.publishCompleteHeaderFact()
        assertEquals(
            "Required original preparation recovers valid late metadata",
            ReaderPageFactState.PORTRAIT,
            page.pageFact.value.state,
        )
        assertEquals(80, page.pageFact.value.width)
        assertEquals(120, page.pageFact.value.height)
    }

    @Test
    fun directDownloadPublishesOriginalHeader() = fixture { loader, chapter ->
        loader.loadChapter(chapter)
        assertTrue(chapter.pageLoader is DownloadPageLoader)
        assertEquals(ReaderPageFactState.PORTRAIT, chapter.pages!!.single().pageFact.value.state)
    }

    @Test
    fun activatingPrefetchedDownloadPublishesMissingOriginalHeader() = fixture { loader, chapter ->
        val prefetch = ReaderChapterWindowEffect.BeginPageListLoad(
            ReaderChapterId(chapter.chapter.id!!),
            ReaderChapterLoadPurpose.PREFETCH,
        )
        loader.loadChapter(chapter, prefetch)
        val retained = chapter.pages!!.single()
        assertEquals(ReaderPageFactState.UNKNOWN, retained.pageFact.value.state)
        loader.loadChapter(chapter, prefetch.copy(purpose = ReaderChapterLoadPurpose.ACTIVATE))
        assertSame(retained, chapter.pages!!.single())
        assertTrue(chapter.pageLoader is DownloadPageLoader)
        assertEquals(ReaderPageFactState.PORTRAIT, retained.pageFact.value.state)
    }

    @Test
    fun directReusePreparesMissingHeaderAndDoesNotReadConfirmedHeaderAgain() = fixture { loader, chapter ->
        loader.loadChapter(
            chapter,
            ReaderChapterWindowEffect.BeginPageListLoad(
                ReaderChapterId(chapter.chapter.id!!),
                ReaderChapterLoadPurpose.PREFETCH,
            ),
        )
        val retained = chapter.pages!!.single()
        var opens = 0
        val original = retained.stream!!
        retained.stream = {
            opens++
            original()
        }
        loader.loadChapter(chapter)
        assertSame(retained, chapter.pages!!.single())
        assertEquals(ReaderPageFactState.PORTRAIT, retained.pageFact.value.state)
        assertEquals(1, opens)
        loader.loadChapter(chapter)
        retained.publishHeaderFact()
        assertEquals("Confirmed content metadata is reused by loader and holder", 1, opens)
    }

    private fun fixture(test: suspend (ChapterLoader, ReaderChapter) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("reader-pairing-download").toFile()
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val context = RuntimeEnvironment.getApplication()
        Injekt.addSingleton<Application>(context)
        val chapter = ReaderChapter(Chapter.create().copy(id = 73001, mangaId = 1))
        chapter.ref()
        try {
            val image = directory.resolve("01.png")
            val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
            image.writeBytes(
                ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray(),
            )
            bitmap.recycle()
            val source = mockk<Source>()
            val manga = mockk<Manga>()
            every { manga.title } returns "Preparation fixture"
            every { manga.source } returns 1
            val provider = mockk<DownloadProvider>()
            every { provider.findChapterDir(any(), any(), any(), any(), any()) } returns UniFile.fromFile(directory)
            val manager = mockk<DownloadManager>()
            every { manager.isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns true
            every { manager.buildPageList(any(), any(), any()) } returns
                listOf(Page(0).apply { uri = Uri.fromFile(image) })
            test(ChapterLoader(context, manager, provider, manga, source), chapter)
        } finally {
            chapter.unref()
            Injekt = previous
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
