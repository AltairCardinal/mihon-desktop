package mihon.desktop.ui.authors

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.repository.CreatorRepository

class AuthorDetailBehaviorTest {
    @Test
    fun `author production interactors preserve list details candidate and follow behavior`() = runTest {
        val repository = mockk<CreatorRepository>()
        val creator = Creator(
            id = 7L,
            displayName = "Jane",
            normalizedName = "jane",
            sortName = null,
            aliases = emptyList(),
            createdAt = 1L,
            lastModifiedAt = 1L,
        )
        val candidate = candidate()
        every { repository.getCreatorsAsFlow() } returns flowOf(listOf(creator))
        every { repository.getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
        coEvery { repository.getCreator(7L) } returns creator
        coEvery { repository.getDiscoveryCandidatesForCreator(7L) } returns listOf(candidate)
        coEvery { repository.getMangaCreatorsForCreator(7L) } returns emptyList()
        coEvery { repository.getDiscoveryCandidate(candidate.id) } returns candidate
        coEvery { repository.followCreator(7L, emptyList(), emptyList()) } returns mockk()
        coEvery { repository.unfollowCreator(7L) } returns Unit

        val creators = GetCreators(repository)
        val details = GetCreatorDetails(repository)
        val follow = SetCreatorFollow(repository)

        assertEquals(listOf(creator), creators.subscribe().first())
        assertTrue(creators.subscribeFollowed().first().isEmpty())
        assertEquals(creator, details.await(7L).creator)
        assertEquals(candidate, details.awaitCandidate(candidate.id))
        follow.await(7L, followed = true)
        follow.await(7L, followed = false)

        coVerify(exactly = 1) { repository.followCreator(7L, emptyList(), emptyList()) }
        coVerify(exactly = 1) { repository.unfollowCreator(7L) }
    }

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
