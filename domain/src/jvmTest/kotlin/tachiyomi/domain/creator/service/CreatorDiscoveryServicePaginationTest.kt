package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateCreator
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.MangaWorkMatch
import tachiyomi.domain.creator.model.WorkMatchState
import tachiyomi.domain.creator.repository.CreatorRepository

class CreatorDiscoveryServicePaginationTest {

    @Test
    fun `specific creator discovery reuses paginated source search results`() = runBlocking {
        val repository = FakeCreatorRepository(creator = creator(1L, "大久保笃"))
        val source = StubSource(
            pages = mapOf(
                1 to MangasPage(listOf(smanga("/fire-force", "炎炎消防队", "大久保笃")), true),
                2 to MangasPage(
                    listOf(
                        smanga("/soul-eater", "噬魂师", "大久保笃"),
                        smanga("/soul-eater-not", "噬魂师NOT", "大久保笃"),
                    ),
                    false,
                ),
            ),
        )

        val result = CreatorDiscoveryService(repository).discoverCreator(1L, listOf(source))

        assertEquals(listOf(1, 2), source.requestedPages)
        assertEquals(3, result.newCandidateCount)
        assertEquals(
            listOf("/fire-force", "/soul-eater", "/soul-eater-not"),
            repository.candidates.map { it.url },
        )
    }

    @Test
    fun `creator discovery keeps search result url when source details omit url`() = runBlocking {
        val repository = FakeCreatorRepository(creator = creator(1L, "大久保笃"))
        val source = StubSource(
            pages = mapOf(
                1 to MangasPage(listOf(smanga("/comic/18147/", "炎炎消防队", "大久保笃")), false),
            ),
            detailsWithoutUrl = true,
        )

        val result = CreatorDiscoveryService(repository).discoverCreator(1L, listOf(source))

        assertEquals(1, result.newCandidateCount)
        assertEquals(0, result.errorCount)
        assertEquals("/comic/18147/", repository.candidates.single().url)
    }

    private class StubSource(
        private val pages: Map<Int, MangasPage>,
        private val detailsWithoutUrl: Boolean = false,
    ) : CatalogueSource {
        val requestedPages = mutableListOf<Int>()

        override val id: Long = 10L
        override val name: String = "漫画柜"
        override val lang: String = "zh"
        override val supportsLatest: Boolean = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            requestedPages += page
            return pages[page] ?: MangasPage(emptyList(), false)
        }
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga): SManga {
            if (!detailsWithoutUrl) return manga
            return SManga.create().apply {
                title = manga.title
                author = manga.author
                initialized = true
            }
        }
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class FakeCreatorRepository(
        private val creator: Creator,
    ) : CreatorRepository {
        val candidates = mutableListOf<DiscoveryCandidate>()
        private var nextCandidateId = 1L

        override suspend fun upsertCreator(displayName: String, aliases: List<String>) = creator
        override suspend fun getCreator(id: Long) = creator.takeIf { it.id == id }
        override fun getCreatorsAsFlow(): Flow<List<Creator>> = flowOf(listOf(creator))
        override suspend fun linkMangaCreator(
            mangaId: Long,
            creatorId: Long,
            role: CreatorRole,
            sourceText: String?,
            confidence: Double,
            evidence: String,
        ) = Unit
        override suspend fun linkDiscoveryCandidateCreator(
            candidateId: Long,
            creatorId: Long,
            role: CreatorRole,
            sourceText: String?,
            confidence: Double,
            evidence: String,
        ) = Unit
        override suspend fun followCreator(
            creatorId: Long,
            sourceIds: List<Long>,
            languageTags: List<String>,
        ): CreatorWatch = error("unused")
        override suspend fun unfollowCreator(creatorId: Long) = Unit
        override suspend fun getFollowedCreators(): List<CreatorWatch> = emptyList()
        override fun getFollowedCreatorsAsFlow(): Flow<List<CreatorWatch>> = flowOf(emptyList())
        override suspend fun updateWatchCheckResult(
            creatorId: Long,
            checkedAt: Long,
            success: Boolean,
            error: String?,
        ) = Unit
        override suspend fun upsertDiscoveryCandidate(
            source: Long,
            url: String,
            title: String,
            authorText: String?,
            artistText: String?,
            languageTag: String,
            languageConfidence: Double,
            languageEvidence: String,
            thumbnailUrl: String?,
            detailsFetchedAt: Long?,
            state: DiscoveryCandidateState,
        ): DiscoveryCandidate {
            return DiscoveryCandidate(
                id = nextCandidateId++,
                source = source,
                url = url,
                title = title,
                normalizedTitle = CreatorNameNormalizer.normalize(title),
                authorText = authorText,
                artistText = artistText,
                languageTag = languageTag,
                languageConfidence = languageConfidence,
                languageEvidence = languageEvidence,
                thumbnailUrl = thumbnailUrl,
                firstSeenAt = 1L,
                lastSeenAt = 1L,
                detailsFetchedAt = detailsFetchedAt,
                state = state,
            ).also { candidates += it }
        }
        override suspend fun getDiscoveryCandidatesForCreator(creatorId: Long): List<DiscoveryCandidate> = candidates
        override suspend fun getDiscoveryCandidate(
            id: Long,
        ): DiscoveryCandidate? = candidates.firstOrNull { it.id == id }
        override suspend fun getMangaCreatorsForCreator(creatorId: Long): List<MangaCreator> = emptyList()
        override suspend fun getDiscoveryCandidateCreatorsForCreator(
            creatorId: Long,
        ): List<DiscoveryCandidateCreator> = emptyList()
        override suspend fun createCanonicalWork(
            primaryTitle: String,
            primaryCreatorId: Long?,
            originalLanguage: String?,
        ): CanonicalWork = error("unused")
        override suspend fun upsertMangaWorkMatch(
            mangaId: Long,
            workId: Long,
            confidence: Double,
            matchReason: String,
            state: WorkMatchState,
            manuallyConfirmed: Boolean,
        ): MangaWorkMatch = error("unused")
    }

    private fun creator(id: Long, name: String) = Creator(
        id = id,
        displayName = name,
        normalizedName = CreatorNameNormalizer.normalize(name),
        sortName = name,
        aliases = emptyList(),
        createdAt = 1L,
        lastModifiedAt = 1L,
    )

    private fun smanga(url: String, title: String, author: String): SManga {
        return SManga.create().apply {
            this.url = url
            this.title = title
            this.author = author
            initialized = true
        }
    }
}
