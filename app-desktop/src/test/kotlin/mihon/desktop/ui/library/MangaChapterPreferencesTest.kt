package mihon.desktop.ui.library

import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.di.isolatedDesktopPreferenceStore
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale

@Isolated
class MangaChapterPreferencesTest {
    @Test
    fun `chapter defaults report rejected repository writes and preserve exception boundary`() = runBlocking {
        val manga = Manga.create().copy(id = 7L, favorite = true)
        val backing = FakeMangaRepository().apply { seed(manga) }
        var reject = true
        val repository = object : MangaRepository by backing {
            override suspend fun update(update: MangaUpdate): Boolean {
                if (reject) return false
                throw IllegalStateException("real repository rejection")
            }
        }
        val defaults = SetMangaDefaultChapterFlags(
            LibraryPreferences(InMemoryPreferenceStore()),
            SetMangaChapterFlags(repository),
            GetFavorites(repository),
        )

        assertEquals(false, defaults.await(manga))
        assertEquals(manga, backing.get(manga.id))
        reject = false
        assertThrows(IllegalStateException::class.java) { runBlocking { defaults.awaitAll() } }
        Unit
    }

    @Test
    fun `batch chapter defaults reject false without silently reporting completion`() = runBlocking {
        val manga = Manga.create().copy(id = 8L, favorite = true)
        val backing = FakeMangaRepository().apply { seed(manga) }
        val repository = object : MangaRepository by backing {
            override suspend fun update(update: MangaUpdate): Boolean = false
        }
        val defaults = SetMangaDefaultChapterFlags(
            LibraryPreferences(InMemoryPreferenceStore()),
            SetMangaChapterFlags(repository),
            GetFavorites(repository),
        )
        assertThrows(IllegalStateException::class.java) { runBlocking { defaults.awaitAll() } }
        assertEquals(manga, backing.get(manga.id))
    }

    @Test
    fun `desktop alphabet order consumes shared locale collator`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            val chapters = listOf(
                Chapter.create().copy(id = 1L, name = "z"),
                Chapter.create().copy(id = 2L, name = "ä"),
            )
            assertEquals(
                listOf(2L, 1L),
                sortMangaDetailChapters(chapters, ChapterSortMode.BY_ALPHABET, true).map { it.id },
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `production DI resolves shared chapter defaults`(@TempDir directory: File) = runBlocking {
        val context = initDesktopDIForTest(
            appDir = directory,
            preferenceStore = isolatedDesktopPreferenceStore(),
            startDownloadWorker = false,
        )
        try {
            assertNotNull(Injekt.get<SetMangaDefaultChapterFlags>())
        } finally {
            context.close()
        }
    }

    @Test
    fun `chapter preference compensation propagates cancellation instead of reporting ordinary failure`() {
        val manga = Manga.create().copy(id = 23L)
        for (canceledRead in listOf(2, 3)) {
            val actual = FakeMangaRepository().apply { seed(manga) }
            var reads = 0
            val repository = object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean = false
                override suspend fun getMangaById(id: Long): Manga {
                    if (++reads == canceledRead) {
                        throw kotlinx.coroutines.CancellationException("authority read canceled")
                    }
                    return actual.getMangaById(id)
                }
            }
            val model = MangaDetailScreenModel(
                mangaId = manga.id,
                getMangaWithChapters = GetMangaWithChapters(repository, FakeChapterRepository()),
                setMangaChapterFlags = SetMangaChapterFlags(repository),
            )
            model.setManga(manga)
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { model.setChapterBookmarkFilter(TriState.ENABLED_IS) }
            }
        }
    }
}
