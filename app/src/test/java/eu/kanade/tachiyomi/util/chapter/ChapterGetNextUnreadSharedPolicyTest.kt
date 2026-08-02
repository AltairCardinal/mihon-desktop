package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.mockk.every
import io.mockk.mockk
import mihon.domain.reader.progress.ReaderChapterDisplayOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class ChapterGetNextUnreadSharedPolicyTest {

    @Test
    fun `Android entry adapter chooses the same story chapter from ascending or descending input`() {
        val ascending = listOf(
            chapter(1, read = true),
            chapter(2, read = false),
            chapter(3, read = false),
        )

        assertEquals(
            2L,
            resolveAndroidReaderEntry(ascending, ReaderChapterDisplayOrder.STORY_ASCENDING)?.id,
        )
        assertEquals(
            2L,
            resolveAndroidReaderEntry(ascending.reversed(), ReaderChapterDisplayOrder.STORY_DESCENDING)?.id,
        )
    }

    @Test
    fun `Android manga reader entry preserves fixed original sorting semantics in both directions`() {
        withIsolatedInjekt {
            val basePreferences = mockk<BasePreferences>(relaxed = true)
            every { basePreferences.downloadedOnly().get() } returns false
            Injekt.addSingleton(basePreferences)
            val chapters = listOf(
                chapter(3, read = false),
                chapter(1, read = true),
                chapter(2, read = false),
            )
            val downloads = mockk<DownloadManager>(relaxed = true)
            val ascending = manga(Manga.CHAPTER_SORT_ASC)
            val descending = manga(Manga.CHAPTER_SORT_DESC)

            assertEquals(2L, chapters.getNextUnread(ascending, downloads)?.id)
            assertEquals(2L, chapters.getNextUnread(descending, downloads)?.id)
        }
    }

    private inline fun <T> withIsolatedInjekt(block: () -> T): T {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        return try {
            block()
        } finally {
            Injekt = previous
        }
    }

    private fun chapter(id: Long, read: Boolean) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        chapterNumber = id.toDouble(),
        read = read,
    )

    private fun manga(order: Long) = Manga.create().copy(
        id = 1,
        source = 1,
        chapterFlags = Manga.CHAPTER_SORTING_NUMBER or order,
    )
}
