package mihon.desktop.ui.authors

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.domain.sync.SyncMutationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.CreatorLibraryIndexState

class AuthorDetailBehaviorTest {
    @Test
    fun `author root presentation distinguishes indexing empty metadata failure and content`() {
        assertEquals(
            AuthorIndexPresentation.Indexing(25, 100),
            authorIndexPresentation(CreatorLibraryIndexState.Indexing(25, 100), creatorCount = 3),
        )
        assertEquals(
            AuthorIndexPresentation.EmptyLibrary,
            authorIndexPresentation(CreatorLibraryIndexState.Empty, creatorCount = 0),
        )
        assertEquals(
            AuthorIndexPresentation.NoAuthorMetadata,
            authorIndexPresentation(CreatorLibraryIndexState.Ready(4), creatorCount = 0),
        )
        assertEquals(
            AuthorIndexPresentation.Failed("disk full"),
            authorIndexPresentation(CreatorLibraryIndexState.Failed(2, 4, "disk full"), creatorCount = 1),
        )
        assertEquals(
            AuthorIndexPresentation.Content,
            authorIndexPresentation(CreatorLibraryIndexState.Ready(4), creatorCount = 2),
        )
    }

    @Test
    fun `author identity actions call typed repository commands`() = runTest {
        val repository = mockk<CreatorArchiveRepository>()
        coEvery { repository.addManualCreatorAlias(1L, "One-sensei") } returns Unit
        coEvery { repository.getManualCreatorAliases(1L) } returns listOf("One-sensei")
        coEvery { repository.removeManualCreatorAlias(1L, "One-sensei") } returns Unit
        coEvery { repository.mergeCreatorIdentities(1L, 2L) } returns Unit
        coEvery { repository.splitCreatorIdentity(1L, setOf(11L, 12L), "ONE") } returns 3L
        val actions = AuthorIdentityActions(ManageCreatorIdentity(repository))

        actions.addAlias(1L, "One-sensei")
        assertEquals(listOf("One-sensei"), actions.getManualAliases(1L))
        actions.removeAlias(1L, "One-sensei")
        actions.merge(sourceCreatorId = 1L, targetCreatorId = 2L)
        assertEquals(3L, actions.split(1L, setOf(11L, 12L), "ONE"))

        coVerify(exactly = 1) { repository.addManualCreatorAlias(1L, "One-sensei") }
        coVerify(exactly = 1) { repository.getManualCreatorAliases(1L) }
        coVerify(exactly = 1) { repository.removeManualCreatorAlias(1L, "One-sensei") }
        coVerify(exactly = 1) { repository.mergeCreatorIdentities(1L, 2L) }
        coVerify(exactly = 1) { repository.splitCreatorIdentity(1L, setOf(11L, 12L), "ONE") }
    }

    @Test
    fun `manual discovery interactor executes production service before reloading details`() {
        runTest {
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
            coEvery { repository.getCreator(7L) } returns creator
            coEvery { repository.getDiscoveryCandidatesForCreator(7L) } returns emptyList()
            coEvery { repository.getMangaCreatorsForCreator(7L) } returns emptyList()
            coEvery { repository.getMangaTitlesForCreator(7L) } returns emptyMap()

            val interactor = DiscoverCreatorWorks(
                discoveryService = CreatorDiscoveryService(repository),
                getCreatorDetails = GetCreatorDetails(repository),
            )

            val details = interactor.await(7L, emptyList())

            assertEquals(creator, details.creator)
            coVerify(exactly = 3) { repository.getCreator(7L) }
            coVerify(exactly = 2) { repository.getDiscoveryCandidatesForCreator(7L) }
            coVerify(exactly = 1) { repository.getMangaCreatorsForCreator(7L) }
            coVerify(exactly = 1) { repository.getMangaTitlesForCreator(7L) }
        }
    }

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
        coEvery { repository.followCreator(7L, syncContext = SyncMutationContext.User) } returns mockk()
        coEvery { repository.unfollowCreator(7L, syncContext = SyncMutationContext.User) } returns Unit

        val creators = GetCreators(repository)
        val details = GetCreatorDetails(repository)
        val follow = SetCreatorFollow(repository)

        assertEquals(listOf(creator), creators.subscribe().first())
        assertTrue(creators.subscribeFollowed().first().isEmpty())
        assertEquals(creator, details.await(7L).creator)
        assertEquals(candidate, details.awaitCandidate(candidate.id))
        follow.await(7L, followed = true)
        follow.await(7L, followed = false)

        coVerify(exactly = 1) { repository.followCreator(7L, syncContext = SyncMutationContext.User) }
        coVerify(exactly = 1) { repository.unfollowCreator(7L, syncContext = SyncMutationContext.User) }
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
