package eu.kanade.domain.chapter.model

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ChapterPreferencesContractTest {
    private lateinit var previousInjekt: InjektScope
    private lateinit var basePreferences: BasePreferences
    private lateinit var preferences: LibraryPreferences
    private lateinit var store: android.content.SharedPreferences

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val context = RuntimeEnvironment.getApplication()
        store = context.getSharedPreferences("chapter-contract-${System.nanoTime()}", Context.MODE_PRIVATE)
        val preferenceStore = AndroidPreferenceStore(context, store)
        basePreferences = BasePreferences(context, preferenceStore)
        preferences = LibraryPreferences(preferenceStore)
        Injekt.addSingleton(basePreferences)
    }

    @After
    fun tearDown() {
        Injekt = previousInjekt
        store.edit().clear().commit()
    }

    @Test
    fun `both Android consumers use identical read bookmark download and source ordering contracts`() {
        val chapters = listOf(
            Chapter.create().copy(id = 1, name = "First", sourceOrder = 2),
            Chapter.create().copy(id = 2, name = "Second", sourceOrder = 1, read = true, bookmark = true),
            Chapter.create().copy(id = 3, name = "Third", sourceOrder = 0, bookmark = true),
        )
        val manager = mockk<DownloadManager> {
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers { firstArg<String>() == "Second" }
        }
        val items = chapters.map {
            ChapterList.Item(it, if (it.id == 2L) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED, 0)
        }
        val cases = listOf(
            0L to listOf(3L, 2L, 1L),
            Manga.CHAPTER_SHOW_READ to listOf(2L),
            Manga.CHAPTER_SHOW_UNREAD to listOf(3L, 1L),
            Manga.CHAPTER_SHOW_BOOKMARKED to listOf(3L, 2L),
            Manga.CHAPTER_SHOW_NOT_BOOKMARKED to listOf(1L),
            Manga.CHAPTER_SHOW_DOWNLOADED to listOf(2L),
            Manga.CHAPTER_SHOW_NOT_DOWNLOADED to listOf(3L, 1L),
        )
        for ((flags, expected) in cases) {
            val manga = Manga.create().copy(source = 42, chapterFlags = flags)
            assertEquals(flags != 0L, manga.chaptersFiltered())
            assertEquals(expected, chapters.applyFilters(manga, manager).map { it.id })
            assertEquals(expected, items.applyFilters(manga).map { it.id }.toList())
        }
        val local = Manga.create().copy(source = 0, chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED)
        assertEquals(listOf(3L, 2L, 1L), chapters.applyFilters(local, manager).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), items.applyFilters(local).map { it.id }.toList())
        val remote = Manga.create().copy(source = 42, chapterFlags = Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
        basePreferences.downloadedOnly().set(true)
        assertEquals(true, remote.chaptersFiltered())
        assertEquals(listOf(2L), chapters.applyFilters(remote, manager).map { it.id })
        assertEquals(listOf(2L), items.applyFilters(remote).map { it.id }.toList())
        assertEquals(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, remote.downloadedFilterRaw)
        basePreferences.downloadedOnly().set(false)
        assertEquals(listOf(3L, 1L), items.applyFilters(remote).map { it.id }.toList())
    }

    @Test
    fun `Android shared defaults reject false and retain exception cancellation and stop first boundaries`() {
        val manga = Manga.create().copy(id = 1, favorite = true)
        val repository = mockk<MangaRepository> {
            coEvery { getFavorites() } returns listOf(manga, manga.copy(id = 2))
            coEvery { update(any()) } returns false
        }
        val useCase =
            SetMangaDefaultChapterFlags(preferences, SetMangaChapterFlags(repository), GetFavorites(repository))
        assertFalse(runBlocking { useCase.await(manga) })
        assertThrows(IllegalStateException::class.java) { runBlocking { useCase.awaitAll() } }
        coEvery { repository.update(any()) } throws IllegalArgumentException("storage failure")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { useCase.awaitAll() } }
        coEvery { repository.update(any()) } throws CancellationException("canceled")
        assertThrows(CancellationException::class.java) { runBlocking { useCase.awaitAll() } }
        coVerify(exactly = 0) { repository.update(match { it.id == 2L }) }
    }
}
