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

class CreatorDiscoveryServiceTest {

    @Test
    fun `discovers candidates for followed creator`() = runBlocking {
        val repository = FakeCreatorRepository(
            creators = listOf(creator(1L, "ONE")),
            watches = listOf(watch(1L)),
        )
        val source = StubSource(
            id = 10L,
            lang = "en",
            results = listOf(smanga("/opm", "One-Punch Man", author = "ONE")),
        )

        val result = CreatorDiscoveryService(repository).discoverDueWatches(listOf(source))

        assertEquals(1, result.newCandidateCount)
        assertEquals("/opm", repository.candidates.single().url)
        assertEquals(1L, repository.candidateLinks.single().creatorId)
        assertEquals(0, repository.mangaLinks.size)
    }

    @Test
    fun `respects watch language filter`() = runBlocking {
        val repository = FakeCreatorRepository(
            creators = listOf(creator(1L, "ONE")),
            watches = listOf(watch(1L, languageTags = listOf("ja"))),
        )
        val source = StubSource(
            id = 10L,
            lang = "en",
            results = listOf(smanga("/opm", "One-Punch Man", author = "ONE")),
        )

        val result = CreatorDiscoveryService(repository).discoverDueWatches(listOf(source))

        assertEquals(0, result.newCandidateCount)
        assertEquals(0, repository.candidates.size)
    }

    @Test
    fun `continues when one source fails`() = runBlocking {
        val repository = FakeCreatorRepository(
            creators = listOf(creator(1L, "ONE")),
            watches = listOf(watch(1L)),
        )
        val failing = StubSource(id = 10L, lang = "en", failSearch = true)
        val working = StubSource(
            id = 11L,
            lang = "en",
            results = listOf(smanga("/mob", "Mob Psycho 100", author = "ONE")),
        )

        val result = CreatorDiscoveryService(repository).discoverDueWatches(listOf(failing, working))

        assertEquals(1, result.newCandidateCount)
        assertEquals(1, result.errorCount)
        assertEquals("/mob", repository.candidates.single().url)
    }

    @Test
    fun `discovers candidates for a specific creator without requiring a watch`() = runBlocking {
        val repository = FakeCreatorRepository(
            creators = listOf(creator(1L, "吾峠呼世晴")),
            watches = emptyList(),
        )
        val source = StubSource(
            id = 10L,
            lang = "ja",
            results = listOf(smanga("/kny", "鬼灭之刃", author = "吾峠呼世晴")),
        )

        val result = CreatorDiscoveryService(repository).discoverCreator(1L, listOf(source))

        assertEquals(1, result.newCandidateCount)
        assertEquals("/kny", repository.candidates.single().url)
        assertEquals(1L, repository.candidateLinks.single().creatorId)
    }

    @Test
    fun `specific creator discovery respects language filter`() = runBlocking {
        val repository = FakeCreatorRepository(
            creators = listOf(creator(1L, "吾峠呼世晴")),
            watches = emptyList(),
        )
        val source = StubSource(
            id = 10L,
            lang = "ja",
            results = listOf(smanga("/kny", "鬼灭之刃", author = "吾峠呼世晴")),
        )

        val result = CreatorDiscoveryService(repository).discoverCreator(
            creatorId = 1L,
            sources = listOf(source),
            languageTags = listOf("en"),
        )

        assertEquals(0, result.newCandidateCount)
        assertEquals(0, repository.candidates.size)
    }

    @Test
    fun `specific creator discovery follows paginated source search`() = runBlocking {
        val repository = FakeCreatorRepository(
            creators = listOf(creator(1L, "大久保笃")),
            watches = emptyList(),
        )
        val source = StubSource(
            id = 10L,
            lang = "zh",
            pages = mapOf(
                1 to MangasPage(
                    listOf(smanga("/fire-force", "炎炎消防队", author = "大久保笃")),
                    true,
                ),
                2 to MangasPage(
                    listOf(
                        smanga("/soul-eater", "噬魂师", author = "大久保笃"),
                        smanga("/soul-eater-not", "噬魂师NOT", author = "大久保笃"),
                    ),
                    false,
                ),
            ),
        )

        val result = CreatorDiscoveryService(repository).discoverCreator(1L, listOf(source))

        assertEquals(3, result.newCandidateCount)
        assertEquals(
            listOf("/fire-force", "/soul-eater", "/soul-eater-not"),
            repository.candidates.map { it.url },
        )
    }

    private class StubSource(
        override val id: Long,
        override val lang: String,
        private val results: List<SManga> = emptyList(),
        private val pages: Map<Int, MangasPage>? = null,
        private val failSearch: Boolean = false,
    ) : CatalogueSource {
        override val name: String = "Stub $id"
        override val supportsLatest: Boolean = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            if (failSearch) error("failed")
            pages?.let { return it[page] ?: MangasPage(emptyList(), false) }
            return MangasPage(results, false)
        }
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class FakeCreatorRepository(
        private val creators: List<Creator>,
        private val watches: List<CreatorWatch>,
    ) : CreatorRepository {
        val candidates = mutableListOf<DiscoveryCandidate>()
        val candidateLinks = mutableListOf<DiscoveryCandidateCreator>()
        val mangaLinks = mutableListOf<Pair<Long, Long>>()
        private var nextCandidateId = 1L

        override suspend fun upsertCreator(displayName: String, aliases: List<String>) = error("unused")
        override suspend fun getCreator(id: Long) = creators.firstOrNull { it.id == id }
        override fun getCreatorsAsFlow(): Flow<List<Creator>> = flowOf(creators)
        override suspend fun linkMangaCreator(
            mangaId: Long,
            creatorId: Long,
            role: CreatorRole,
            sourceText: String?,
            confidence: Double,
            evidence: String,
        ) {
            mangaLinks += mangaId to creatorId
        }
        override suspend fun linkDiscoveryCandidateCreator(
            candidateId: Long,
            creatorId: Long,
            role: CreatorRole,
            sourceText: String?,
            confidence: Double,
            evidence: String,
        ) {
            candidateLinks += DiscoveryCandidateCreator(
                candidateId = candidateId,
                creatorId = creatorId,
                role = role,
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
            )
        }
        override suspend fun followCreator(
            creatorId: Long,
            sourceIds: List<Long>,
            languageTags: List<String>,
        ) = error("unused")
        override suspend fun unfollowCreator(creatorId: Long) = error("unused")
        override suspend fun getFollowedCreators(): List<CreatorWatch> = watches
        override fun getFollowedCreatorsAsFlow(): Flow<List<CreatorWatch>> = flowOf(watches)
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
            val existing = candidates.firstOrNull { it.source == source && it.url == url }
            if (existing != null) return existing
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
        ): List<DiscoveryCandidateCreator> = candidateLinks.filter { it.creatorId == creatorId }
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

    private fun watch(creatorId: Long, languageTags: List<String> = emptyList()) = CreatorWatch(
        creatorId = creatorId,
        enabled = true,
        sourceIds = emptyList(),
        languageTags = languageTags,
        lastCheckedAt = null,
        lastSuccessAt = null,
        lastError = null,
        createdAt = 1L,
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
