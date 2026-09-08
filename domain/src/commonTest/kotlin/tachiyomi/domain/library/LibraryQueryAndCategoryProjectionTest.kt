package tachiyomi.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryQueryAndCategoryProjectionTest {
    @Test
    fun `library query keeps upstream prefix and negatable term semantics`() {
        val item = item(
            id = 42L,
            source = 7L,
            title = "A title",
            author = "An author",
            artist = "An artist",
            description = "A description",
            genre = listOf("Action", "Drama"),
        )

        assertTrue(matchesLibraryQuery(item, "id:42", "Remote source"))
        assertFalse(matchesLibraryQuery(item, "id:41", "Remote source"))
        assertTrue(matchesLibraryQuery(item, "src:7", "Remote source"))
        assertTrue(matchesLibraryQuery(item, "src:local", "Local", localSourceId = 7L))
        assertTrue(matchesLibraryQuery(item, "author", "Remote source"))
        assertTrue(matchesLibraryQuery(item, "Remote, -Romance", "Remote source"))
        assertFalse(matchesLibraryQuery(item, "Remote, -Action", "Remote source"))
    }

    @Test
    fun `library category projection hides unused system category but keeps empty custom categories`() {
        val categories = listOf(
            Category(Category.UNCATEGORIZED_ID, "Uncategorized", 0L, 0L),
            Category(1L, "Action", 1L, 0L),
            Category(2L, "Empty", 2L, 0L),
        )

        assertEquals(
            listOf(1L, 2L),
            projectLibraryCategories(categories, listOf(item(10L, categories = listOf(1L)))).map { it.id },
        )
        assertEquals(
            listOf(0L, 1L, 2L),
            projectLibraryCategories(categories, listOf(item(10L, categories = listOf(0L)))).map { it.id },
        )
    }

    @Test
    fun `library category delta preserves each manga and excludes system category`() {
        assertEquals(
            listOf(0L, 1L, 3L),
            applyLibraryCategoryDelta(
                currentCategoryIds = listOf(0L, 1L, 2L),
                addCategoryIds = setOf(3L, 0L),
                removeCategoryIds = setOf(2L, 0L),
            ),
        )
    }

    @Test
    fun `library download selection filters queue and disk before applying limit`() {
        val chapters = (1L..4L).map { id -> Chapter.create().copy(id = id) }

        assertEquals(
            listOf(4L),
            selectLibraryDownloadChapters(
                candidates = chapters,
                limit = 1,
                isQueued = { it.id == 1L },
                isDownloaded = { it.id == 2L || it.id == 3L },
            ).map { it.id },
        )
    }

    private fun item(
        id: Long,
        source: Long = 1L,
        title: String = "Title",
        author: String? = null,
        artist: String? = null,
        description: String? = null,
        genre: List<String>? = null,
        categories: List<Long> = emptyList(),
    ) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            source = source,
            title = title,
            author = author,
            artist = artist,
            description = description,
            genre = genre,
        ),
        categories = categories,
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )
}
