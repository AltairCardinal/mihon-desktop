package mihon.desktop.ui.authors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.MangaCreator

class AuthorDetailBehaviorTest {

    @Test
    fun `collect on open skips discovery when candidates are already cached`() {
        assertFalse(
            shouldCollectAuthorOnOpen(
                collectOnOpen = true,
                candidates = listOf(candidate()),
                mangaLinks = emptyList(),
            ),
        )
    }

    @Test
    fun `collect on open discovers when cache is empty`() {
        assertTrue(
            shouldCollectAuthorOnOpen(
                collectOnOpen = true,
                candidates = emptyList(),
                mangaLinks = emptyList(),
            ),
        )
    }

    @Test
    fun `discovered candidate converts to source manga for unified detail`() {
        val manga = authorCandidateSourceManga(candidate())

        assertEquals("/comic/18147/", manga.url)
        assertEquals("炎炎消防队", manga.title)
    }

    @Test
    fun `discovered candidate does not pass cached thumbnail into unified detail`() {
        val manga = authorCandidateSourceManga(candidate())

        assertNull(manga.thumbnail_url)
    }

    private fun candidate() = DiscoveryCandidate(
        id = 1L,
        source = 10L,
        url = "/comic/18147/",
        title = "炎炎消防队",
        normalizedTitle = "炎炎消防队",
        authorText = "大久保笃",
        artistText = null,
        languageTag = "zh",
        languageConfidence = 1.0,
        languageEvidence = "SOURCE_LANGUAGE",
        thumbnailUrl = "https://example.invalid/cover.jpg",
        firstSeenAt = 1L,
        lastSeenAt = 1L,
        detailsFetchedAt = 1L,
        state = DiscoveryCandidateState.NEW,
    )

    @Suppress("unused")
    private fun mangaLink() = MangaCreator(
        mangaId = 1L,
        creatorId = 1L,
        role = CreatorRole.AUTHOR,
        sourceText = "大久保笃",
        confidence = 1.0,
        evidence = "test",
    )
}
