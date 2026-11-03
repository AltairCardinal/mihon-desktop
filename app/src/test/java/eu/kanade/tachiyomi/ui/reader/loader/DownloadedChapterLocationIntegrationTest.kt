package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.reader.session.ReaderChapterLoadState
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DownloadedChapterLocationIntegrationTest {
    @Test
    fun directoryOpenResolvesLocationOnceAndPreservesOrderAndBytes() = fixture { manager, provider, source, manga ->
        val directory = Files.createTempDirectory("reader-location").toFile()
        try {
            val second = png(2)
            val first = png(10)
            directory.resolve("2.png").writeBytes(second)
            directory.resolve("10.png").writeBytes(first)
            val location = requireNotNull(UniFile.fromFile(directory))
            every { provider.findChapterDir(any(), any(), any(), any(), source) } returns location
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = manga.id, name = "Chapter"))
            val loader = ChapterLoader(RuntimeEnvironment.getApplication(), manager, provider, manga, source)

            loader.loadChapter(chapter)

            assertTrue(chapter.pageLoader is DownloadPageLoader)
            assertEquals(listOf(0, 1), chapter.pages!!.map { it.index })
            assertArrayEquals(first, chapter.pages!![0].stream!!().use { it.readBytes() })
            assertArrayEquals(second, chapter.pages!![1].stream!!().use { it.readBytes() })
            verify(exactly = 1) { provider.findChapterDir(any(), any(), any(), manga.title, source) }
            chapter.pageLoader!!.recycle()
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun newOpenResolvesAgainInsteadOfReusingThePreviousChapterLocation() = fixture { manager, provider, source, manga ->
        val directory = Files.createTempDirectory("reader-fresh-location").toFile()
        try {
            val first = directory.resolve("first").apply { mkdir() }
            val second = directory.resolve("second").apply { mkdir() }
            val firstBytes = png(3)
            val secondBytes = png(7)
            first.resolve("1.png").writeBytes(firstBytes)
            second.resolve("1.png").writeBytes(secondBytes)
            var location = requireNotNull(UniFile.fromFile(first))
            every { provider.findChapterDir(any(), any(), any(), any(), source) } answers { location }
            val loader = ChapterLoader(RuntimeEnvironment.getApplication(), manager, provider, manga, source)
            val oldChapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = manga.id))
            loader.loadChapter(oldChapter)
            oldChapter.pageLoader!!.recycle()
            location = requireNotNull(UniFile.fromFile(second))
            val newChapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = manga.id))
            loader.loadChapter(newChapter)
            assertArrayEquals(firstBytes, oldChapter.pages!!.single().stream!!().use { it.readBytes() })
            assertArrayEquals(secondBytes, newChapter.pages!!.single().stream!!().use { it.readBytes() })
            verify(exactly = 2) { provider.findChapterDir(any(), any(), any(), manga.title, source) }
            newChapter.pageLoader!!.recycle()
        } finally {
            directory.listFiles()?.forEach { child ->
                child.listFiles()?.forEach { it.delete() }
                child.delete()
            }
            directory.delete()
        }
    }

    @Test
    fun selectedLocationFailurePublishesStorageErrorWithoutResolvingOrFallingBack() = fixture {
            manager,
            provider,
            source,
            manga,
        ->
        val location = mockk<UniFile> {
            every { isFile } returns false
            every { listFiles() } throws IOException("Selected download was removed")
        }
        every { provider.findChapterDir(any(), any(), any(), any(), source) } returns location
        val online = mockk<HttpSource>()
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = manga.id))
        val result = runCatching {
            ChapterLoader(RuntimeEnvironment.getApplication(), manager, provider, manga, online).loadChapter(chapter)
        }
        assertTrue(result.isFailure)
        val state = chapter.sharedSessionStateFlow.value.activeChapter.loadState
        assertTrue(state is ReaderChapterLoadState.Error && state.error is AppError.Storage)
        assertTrue(chapter.pageLoader is DownloadPageLoader)
        verify(exactly = 1) { provider.findChapterDir(any(), any(), any(), manga.title, source) }
        chapter.pageLoader!!.recycle()
    }

    @Test
    fun missingDownloadKeepsTheDefaultOnlineAndMissingSourceRoutes() = fixture { manager, provider, source, manga ->
        every { provider.findChapterDir(any(), any(), any(), any(), source) } returns null
        val cache = Injekt.get<ChapterCache>()
        every { cache.getPageListFromCache(any()) } returns listOf(Page(55, "cached-page"))
        val onlineChapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = manga.id))
        ChapterLoader(RuntimeEnvironment.getApplication(), manager, provider, manga, mockk<HttpSource>())
            .loadChapter(onlineChapter)
        assertTrue(onlineChapter.pageLoader is HttpPageLoader)
        assertEquals("cached-page", onlineChapter.pages!!.single().url)
        assertEquals(0, onlineChapter.pages!!.single().index)
        onlineChapter.pageLoader!!.recycle()
        val missingChapter = ReaderChapter(Chapter.create().copy(id = 8, mangaId = manga.id))
        val result = runCatching {
            ChapterLoader(
                RuntimeEnvironment.getApplication(),
                manager,
                provider,
                manga,
                StubSource(42, "en", "Missing"),
            ).loadChapter(missingChapter)
        }
        assertTrue(result.isFailure)
        assertTrue(missingChapter.sharedSessionStateFlow.value.activeChapter.loadState is ReaderChapterLoadState.Error)
        verify(exactly = 2) { provider.findChapterDir(any(), any(), any(), manga.title, source) }
    }

    private fun fixture(test: suspend (DownloadManager, DownloadProvider, Source, Manga) -> Unit) = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val context = RuntimeEnvironment.getApplication()
        val provider = mockk<DownloadProvider>()
        val source = mockk<Source>()
        val sourceManager = mockk<SourceManager> {
            every { getOrStub(42) } returns source
            every { catalogueSources } returns emptyFlow()
        }
        val storage = mockk<StorageManager> {
            every { getDownloadsDirectory() } returns null
            every { changes } returns MutableSharedFlow<Unit>()
        }
        val cache = DownloadCache(context, provider, sourceManager, mockk(), storage)
        try {
            val preferences = mockk<DownloadPreferences>(relaxed = true)
            val categories = mockk<GetCategories>(relaxed = true)
            Injekt.addSingleton<Application>(context)
            Injekt.addSingleton<SourceManager>(sourceManager)
            Injekt.addSingleton<ChapterCache>(mockk(relaxed = true))
            Injekt.addSingleton<DownloadPreferences>(preferences)
            Injekt.addSingleton<GetCategories>(categories)
            Injekt.addSingleton<GetTracks>(mockk(relaxed = true))
            Injekt.addSingleton<GetManga>(mockk(relaxed = true))
            Injekt.addSingleton<GetChapter>(mockk(relaxed = true))
            Injekt.addSingleton<Json>(Json)
            Injekt.addSingleton<XML>(XML {})
            val manager = DownloadManager(context, provider, cache, categories, sourceManager, preferences)
            test(manager, provider, source, Manga.create().copy(id = 1, source = 42, title = "Location fixture"))
            verify(atLeast = 1) { sourceManager.getOrStub(42) }
        } finally {
            (
                DownloadCache::class.java.getDeclaredField("scope").apply { isAccessible = true }
                    .get(cache) as CoroutineScope
                ).cancel()
            Injekt = previous
        }
    }

    private fun png(width: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, 20, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
