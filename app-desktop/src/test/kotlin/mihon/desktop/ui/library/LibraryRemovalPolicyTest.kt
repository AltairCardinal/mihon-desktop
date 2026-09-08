package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryRemovalPolicyTest {
    @Test
    fun `download option is unavailable when selection contains a local manga`() {
        val items = listOf(
            libraryManga(Manga.create().copy(id = 1L, source = 7L)),
            libraryManga(Manga.create().copy(id = 2L, source = 0L)),
        )

        assertFalse(libraryRemovalPolicy(items).canDeleteDownloads)
    }

    @Test
    fun `confirmation requires at least one selected action`() {
        val item = libraryManga(Manga.create().copy(id = 1L, source = 7L))
        val policy = libraryRemovalPolicy(listOf(item))

        assertFalse(policy.canConfirm(removeFromLibrary = false, deleteDownloads = false))
        assertTrue(policy.canConfirm(removeFromLibrary = true, deleteDownloads = false))
        assertTrue(policy.canConfirm(removeFromLibrary = false, deleteDownloads = true))
    }

    private fun libraryManga(manga: Manga) = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )
}
