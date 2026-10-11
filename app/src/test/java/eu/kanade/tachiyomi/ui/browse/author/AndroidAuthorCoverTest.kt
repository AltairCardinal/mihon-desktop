package eu.kanade.tachiyomi.ui.browse.author

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class AndroidAuthorCoverTest {
    @Test
    fun `discovered work loads its thumbnail with the source identity before opening details`() {
        val cover = version().toMangaCover()

        assertEquals("https://images.example/cover.jpg", cover.url)
        assertEquals(42L, cover.sourceId)
        assertEquals(-7L, cover.mangaId)
        assertEquals(false, cover.isMangaFavorite)
        assertEquals(100L, cover.lastModified)
    }

    @Test
    fun `library work preserves custom cover identity and uses refreshed thumbnail on return`() {
        val updated = version().copy(
            mangaId = 321L,
            inLibrary = true,
            thumbnailUrl = "https://images.example/refreshed.jpg",
            detailsFetchedAt = 200L,
        )
        val cover = updated.toMangaCover()

        assertEquals(updated.thumbnailUrl, cover.url)
        assertEquals(321L, cover.mangaId)
        assertEquals(true, cover.isMangaFavorite)
        assertEquals(200L, cover.lastModified)
    }

    @Test
    fun `missing thumbnail never falls back to downloading the work detail page as an image`() {
        val cover = version().copy(thumbnailUrl = null, detailsFetchedAt = null).toMangaCover()

        assertNull(cover.url)
        assertEquals(0L, cover.lastModified)
    }

    private fun version() = SourceWorkArchiveVersion(
        sourceWorkId = 7L,
        naturalKey = SourceWorkNaturalKey(42L, "https://source.example/manga/7"),
        mangaId = null,
        title = "Discovered work",
        readingLanguage = LanguageProjectionContract(
            LanguageDimension.READING,
            "und",
            LanguageCertainty.UNKNOWN,
            LanguageEvidenceKind.UNKNOWN,
        ),
        chapterCount = 0L,
        inLibrary = false,
        detailsFetchedAt = 100L,
        lastSeenAt = 100L,
        decision = null,
        thumbnailUrl = "https://images.example/cover.jpg",
    )
}
