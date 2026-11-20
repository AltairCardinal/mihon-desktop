package eu.kanade.tachiyomi.data.download

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.feedback.withDiagnosticRecording
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DownloadManagerPageListWiringTest {

    @Test
    fun `download manager uses upstream probe policy and case sensitive lexical order`() = runBlocking {
        val diagnosticLog = withDiagnosticRecording(DiagnosticCategory.DOWNLOAD_LOCAL) {
            withIsolatedDownloadManager { manager, provider ->
                withSuccessfulImageProbe {
                    val file10 = uniFile("10.jpg", marker = 10)
                    val file2 = uniFile("2.jpg", marker = 2)
                    val unknown3 = uniFile("3.bin", marker = 3)
                    val empty4 = uniFile("4.jpg", marker = null)
                    val jp2 = uniFile("5.jp2", marker = 5)
                    val jpx = uniFile("6.jpx", marker = 6)
                    val fileA = uniFile("A.jpg", marker = 65)
                    val fileB = uniFile("b.jpg", marker = 98)
                    val nullName = uniFile(null, marker = 99)
                    val directory = mockk<UniFile>()
                    every { directory.listFiles() } returns
                        arrayOf(fileB, jpx, file2, nullName, empty4, unknown3, fileA, jp2, file10)
                    every { provider.findChapterDir(any(), any(), any(), any(), any()) } returns directory
                    val source = mockk<Source>()
                    val manga = Manga.create().copy(id = 1, source = 7, title = "Manga")
                    val chapter = Chapter.create().copy(
                        id = 2,
                        mangaId = manga.id,
                        name = "Chapter 1",
                        url = "/chapter/1",
                    )

                    manager.downloadChapters(manga, emptyList(), autoStart = false)
                    val pages = manager.buildPageList(source, manga, chapter)

                    assertEquals(
                        listOf(file10.uri, file2.uri, unknown3.uri, empty4.uri, jp2.uri, jpx.uri, fileA.uri, fileB.uri),
                        pages.map { it.uri },
                    )
                    assertTrue(pages.all { it.status == Page.State.Ready })
                    verify(exactly = 0) { file10.openInputStream() }
                    verify(exactly = 0) { file2.openInputStream() }
                    verify(exactly = 0) { empty4.openInputStream() }
                    verify(exactly = 0) { jp2.openInputStream() }
                    verify(exactly = 0) { jpx.openInputStream() }
                    verify(exactly = 0) { fileA.openInputStream() }
                    verify(exactly = 0) { fileB.openInputStream() }
                    verify(exactly = 1) { unknown3.openInputStream() }
                    verify(exactly = 0) { nullName.openInputStream() }
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertAll(
            { org.junit.Assert.assertTrue(diagnosticLog.contains("LOCAL_FILE")) },
            { org.junit.Assert.assertTrue(diagnosticLog.contains("DOWNLOAD")) },
        )
        Unit
    }

    @Test
    fun `reader deletion queue retains bookmark protection and explicit bookmark permission`() = runBlocking {
        withIsolatedDownloadManager { manager, _ ->
            val context = RuntimeEnvironment.getApplication()
            context.getSharedPreferences("chapters_to_delete", 0).edit().clear().commit()
            try {
                val preferences = Injekt.get<DownloadPreferences>()
                coEvery { Injekt.get<GetCategories>().await(1) } returns emptyList()
                every { preferences.removeExcludeCategories().get() } returns emptySet()
                every { preferences.removeBookmarkedChapters().get() } returns false
                val manga = Manga.create().copy(id = 1, source = 7, title = "Work")
                val ordinary = Chapter.create().copy(id = 2, mangaId = 1, read = true)
                val bookmarked = ordinary.copy(id = 3, bookmark = true)
                manager.enqueueChaptersToDelete(listOf(ordinary, bookmarked), manga)
                val pending = org.robolectric.util.ReflectionHelpers.getField<DownloadPendingDeleter>(
                    manager,
                    "pendingDeleter",
                )
                assertEquals(listOf(2L), pending.getPendingChapters().values.flatten().map { it.id })
                every { preferences.removeBookmarkedChapters().get() } returns true
                manager.enqueueChaptersToDelete(listOf(bookmarked), manga)
                assertEquals(listOf(3L), pending.getPendingChapters().values.flatten().map { it.id })
                every { preferences.mangaDeletion(1).get() } returns
                    tachiyomi.domain.download.service.MangaDownloadDeletion.ENABLED
                every { preferences.mangaDeletionSettings(1).get() } returns
                    tachiyomi.domain.download.service.MangaDownloadDeletionSettings(
                        tachiyomi.domain.download.service.MangaDownloadDeletion.ENABLED,
                        2,
                        false,
                    )
                manager.enqueueChaptersToDelete(listOf(bookmarked), manga)
                assertTrue(pending.getPendingChapters().values.flatten().isEmpty())
                every { preferences.removeBookmarkedChapters().get() } returns false
                every { preferences.mangaDeletionSettings(1).get() } returns
                    tachiyomi.domain.download.service.MangaDownloadDeletionSettings(
                        tachiyomi.domain.download.service.MangaDownloadDeletion.ENABLED,
                        2,
                        true,
                    )
                manager.enqueueChaptersToDelete(listOf(bookmarked), manga)
                assertEquals(listOf(3L), pending.getPendingChapters().values.flatten().map { it.id })
            } finally {
                context.getSharedPreferences("chapters_to_delete", 0).edit().clear().commit()
            }
        }
    }

    @Test
    fun `reader deletion queue protects an excluded default or explicit category`() = runBlocking {
        withIsolatedDownloadManager { manager, _ ->
            val context = RuntimeEnvironment.getApplication()
            context.getSharedPreferences("chapters_to_delete", 0).edit().clear().commit()
            try {
                val preferences = Injekt.get<DownloadPreferences>()
                val categories = Injekt.get<GetCategories>()
                every { preferences.removeBookmarkedChapters().get() } returns false
                val manga = Manga.create().copy(id = 1, source = 7, title = "Work")
                val completed = Chapter.create().copy(id = 2, mangaId = 1, read = true)
                for (categoryId in listOf(0L, 4L)) {
                    coEvery { categories.await(1) } returns if (categoryId == 0L) {
                        emptyList()
                    } else {
                        listOf(
                            tachiyomi.domain.category.model.Category(categoryId, "Protected", 0, 0),
                        )
                    }
                    every { preferences.removeExcludeCategories().get() } returns setOf(categoryId.toString())
                    manager.enqueueChaptersToDelete(listOf(completed), manga)
                    assertTrue(DownloadPendingDeleter(context).getPendingChapters().values.flatten().isEmpty())
                }
            } finally {
                context.getSharedPreferences("chapters_to_delete", 0).edit().clear().commit()
            }
        }
    }

    private fun uniFile(name: String?, marker: Int?): UniFile = mockk<UniFile>().also { file ->
        every { file.name } returns name
        every { file.isFile } returns true
        every { file.isDirectory } returns false
        every { file.uri } returns mockk<Uri>()
        every { file.openInputStream() } answers { markerStream(marker) }
    }

    private fun markerStream(marker: Int?): InputStream = ByteArrayInputStream(
        marker?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf(),
    )

    private inline fun <T> withSuccessfulImageProbe(block: () -> T): T {
        mockkObject(ImageUtil)
        every { ImageUtil.findImageType(any<() -> InputStream>()) } answers {
            firstArg<() -> InputStream>()().use { }
            ImageUtil.ImageType.JPEG
        }
        return try {
            block()
        } finally {
            unmockkObject(ImageUtil)
        }
    }

    private inline fun <T> withIsolatedDownloadManager(
        block: (DownloadManager, DownloadProvider) -> T,
    ): T {
        val previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        return try {
            val sourceManager = mockk<SourceManager>(relaxed = true)
            val chapterCache = mockk<ChapterCache>(relaxed = true)
            val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
            every { downloadPreferences.mangaDeletionSettings(any()).get() } returns
                tachiyomi.domain.download.service.MangaDownloadDeletionSettings()
            val getCategories = mockk<GetCategories>(relaxed = true)
            Injekt.addSingleton<SourceManager>(sourceManager)
            Injekt.addSingleton<ChapterCache>(chapterCache)
            Injekt.addSingleton<DownloadPreferences>(downloadPreferences)
            Injekt.addSingleton<XML>(XML {})
            Injekt.addSingleton<Json>(Json)
            Injekt.addSingleton<GetCategories>(getCategories)
            Injekt.addSingleton<GetTracks>(mockk(relaxed = true))
            Injekt.addSingleton<GetManga>(mockk(relaxed = true))
            Injekt.addSingleton<GetChapter>(mockk(relaxed = true))
            val context = RuntimeEnvironment.getApplication()
            val provider = mockk<DownloadProvider>()
            val manager = DownloadManager(
                context = context,
                provider = provider,
                cache = mockk(relaxed = true),
                getCategories = getCategories,
                sourceManager = sourceManager,
                downloadPreferences = downloadPreferences,
            )
            block(manager, provider)
        } finally {
            Injekt = previousInjekt
        }
    }
}
