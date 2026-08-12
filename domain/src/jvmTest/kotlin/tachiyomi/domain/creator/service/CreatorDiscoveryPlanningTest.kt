package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.AuthorSearchCreatorMatch
import eu.kanade.tachiyomi.source.AuthorSearchCreatorRole
import eu.kanade.tachiyomi.source.AuthorSearchManga
import eu.kanade.tachiyomi.source.AuthorSearchPage
import eu.kanade.tachiyomi.source.AuthorSearchSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class CreatorDiscoveryPlanningTest {

    @Test
    fun `query plan uses enabled scoped searchable sources and only prefilters proven single language`() {
        val planner = CreatorDiscoveryQueryPlanner(
            CreatorDiscoveryBounds(
                maxAliases = 2,
                maxPagesPerAlias = 3,
                maxTotalPagesPerSource = 5,
                maxConcurrentSources = 2,
                sourceTimeoutMillis = 10_000,
            ),
        )
        val sources = listOf(
            source(1, CreatorSourceReadingLanguageProfile.Single("ja")),
            source(2, CreatorSourceReadingLanguageProfile.Multiple(setOf("ja", "en"))),
            source(3, CreatorSourceReadingLanguageProfile.Unknown),
            source(4, capabilities = setOf(CreatorSourceCapability.DETAILS)),
        )

        val plan = planner.plan(
            creatorId = 9,
            aliases = listOf(" ONE ", "one", "Tomohiro", "ignored"),
            enabledSources = sources,
            watchPolicy = policy(sourceIds = setOf(1, 2, 3, 4), languages = setOf("en")),
            startedAtMillis = 1_000,
        )

        assertEquals(listOf("ONE", "Tomohiro"), plan.aliases)
        assertEquals(listOf(2L, 3L), plan.sources.map { it.source.sourceId })
        assertTrue(plan.sources.all { it.maxPagesPerAlias == 3 && it.maxTotalPages == 5 })
        assertEquals(11_000, plan.deadlineAtMillis)
    }

    @Test
    fun `identity gate verifies exact fields or structured match and keeps search-only result possible`() {
        val aliases = listOf("ONE", "ワン")
        val exact = CreatorIdentityEvidenceEvaluator.evaluate(
            aliases,
            work(author = "Murata, ONE", artist = "ONE"),
        )
        val structured = CreatorIdentityEvidenceEvaluator.evaluate(
            aliases,
            work(
                author = "Someone else",
                structured = listOf(CreatorStructuredIdentityMatch("ONE", CreatorRole.AUTHOR, "author-id:one")),
            ),
        )
        val possible = CreatorIdentityEvidenceEvaluator.evaluate(aliases, work(author = null, artist = null))

        assertEquals(CreatorRelationVerification.VERIFIED, exact.verification)
        assertEquals(CreatorRole.BOTH, exact.role)
        assertEquals(CreatorRelationVerification.VERIFIED, structured.verification)
        assertEquals(CreatorRole.AUTHOR, structured.role)
        assertEquals("author-id:one", structured.evidence)
        assertEquals(CreatorRelationVerification.POSSIBLE, possible.verification)
        assertEquals(CreatorRole.UNKNOWN, possible.role)
    }

    @Test
    fun `result policy separates archive inclusion from notification eligibility`() {
        val policy = policy(
            languages = setOf("ja"),
            includeProbable = true,
            includeUnknown = false,
            notifyProbable = false,
            notifyUnknown = false,
        )

        val confirmed = CreatorDiscoveryResultPolicy.evaluate(language("ja", LanguageCertainty.CONFIRMED), policy)
        val probable = CreatorDiscoveryResultPolicy.evaluate(language("ja", LanguageCertainty.PROBABLE), policy)
        val unknown = CreatorDiscoveryResultPolicy.evaluate(language("und", LanguageCertainty.UNKNOWN), policy)
        val wrongLanguage = CreatorDiscoveryResultPolicy.evaluate(language("en", LanguageCertainty.CONFIRMED), policy)

        assertTrue(confirmed.includeInArchive && confirmed.notify)
        assertTrue(probable.includeInArchive)
        assertFalse(probable.notify)
        assertFalse(unknown.includeInArchive)
        assertFalse(wrongLanguage.includeInArchive)
    }

    @Test
    fun `catalogue adapter takes stable enabled snapshot and prefers structured author search`() = runBlocking {
        val enabled = StructuredSource(1)
        val disabled = FallbackSource(2)
        val adapter = CatalogueCreatorDiscoverySourceAdapter(
            enabledSourcesProvider = { listOf(enabled) },
            sourceResolver = { id -> listOf(enabled, disabled).firstOrNull { it.id == id } },
            clock = { 1_000 },
        )

        val snapshot = adapter.enabledSourcesSnapshot()
        val content = adapter.searchPage(
            BoundedAuthorSearchPageRequest(1, "ONE", page = 1, pageLimit = 2, deadlineAtMillis = 2_000),
        )
        val missing = adapter.searchPage(
            BoundedAuthorSearchPageRequest(2, "ONE", page = 1, pageLimit = 2, deadlineAtMillis = 2_000),
        )

        assertEquals(listOf(1L), snapshot.map { it.sourceId })
        assertTrue(CreatorSourceCapability.AUTHOR_SEARCH in snapshot.single().capabilities)
        val contentResult = assertInstanceOf(CreatorSourcePageResult.Content::class.java, content)
        assertEquals("structured:one", contentResult.works.single().structuredCreatorMatches.single().evidence)
        assertEquals(
            CreatorSourceFailure.MissingSource,
            assertInstanceOf(CreatorSourcePageResult.Failure::class.java, missing).error,
        )
        assertEquals(1, enabled.authorSearchCalls)
        assertEquals(0, enabled.fallbackSearchCalls)
    }

    @Test
    fun `catalogue adapter maps source failures and propagates caller cancellation`() {
        runBlocking {
            val failing = FallbackSource(1, searchFailure = HttpException(429))
            val cancelling = FallbackSource(2, searchFailure = CancellationException("stop"))
            val adapter = CatalogueCreatorDiscoverySourceAdapter(
                enabledSourcesProvider = { listOf(failing, cancelling) },
                sourceResolver = { id -> listOf(failing, cancelling).firstOrNull { it.id == id } },
                clock = { 1_000 },
            )
            adapter.enabledSourcesSnapshot()

            val failure = adapter.searchPage(
                BoundedAuthorSearchPageRequest(1, "ONE", page = 1, pageLimit = 1, deadlineAtMillis = 2_000),
            )
            assertInstanceOf(
                CreatorSourceFailure.RateLimited::class.java,
                assertInstanceOf(CreatorSourcePageResult.Failure::class.java, failure).error,
            )
            val cancellation = runCatching {
                adapter.searchPage(
                    BoundedAuthorSearchPageRequest(2, "ONE", page = 1, pageLimit = 1, deadlineAtMillis = 2_000),
                )
            }.exceptionOrNull()
            assertInstanceOf(CancellationException::class.java, cancellation)
        }
    }

    @Test
    fun `structured author search failure falls back without claiming verified identity`() {
        runBlocking {
            val source = FailingStructuredSource(1)
            val adapter = CatalogueCreatorDiscoverySourceAdapter(
                enabledSourcesProvider = { listOf(source) },
                sourceResolver = { source },
                clock = { 1_000 },
            )
            adapter.enabledSourcesSnapshot()

            val result = adapter.searchPage(
                BoundedAuthorSearchPageRequest(1, "ONE", page = 1, pageLimit = 1, deadlineAtMillis = 2_000),
            ) as CreatorSourcePageResult.Content

            assertEquals(1, source.authorSearchCalls)
            assertEquals(1, source.fallbackSearchCalls)
            assertTrue(result.works.single().structuredCreatorMatches.isEmpty())
        }
    }

    private fun source(
        id: Long,
        profile: CreatorSourceReadingLanguageProfile = CreatorSourceReadingLanguageProfile.Unknown,
        capabilities: Set<CreatorSourceCapability> = setOf(
            CreatorSourceCapability.CATALOGUE_SEARCH_FALLBACK,
            CreatorSourceCapability.DETAILS,
        ),
    ) = EnabledCreatorSource(id, "source-$id", capabilities, profile)

    private fun policy(
        sourceIds: Set<Long> = emptySet(),
        languages: Set<String> = emptySet(),
        includeProbable: Boolean = false,
        includeUnknown: Boolean = false,
        notifyProbable: Boolean = false,
        notifyUnknown: Boolean = false,
    ) = ArchiveWatchPolicy(
        creatorId = 9,
        enabled = true,
        periodMillis = 60_000,
        sourceIds = sourceIds,
        readingLanguageTags = languages,
        includeProbable = includeProbable,
        includeUnknown = includeUnknown,
        notifyProbable = notifyProbable,
        notifyUnknown = notifyUnknown,
    )

    private fun work(
        author: String? = null,
        artist: String? = null,
        structured: List<CreatorStructuredIdentityMatch> = emptyList(),
    ) = CreatorSourceWorkSnapshot(
        key = SourceWorkNaturalKey(1, "/work"),
        title = "Work",
        authorText = author,
        artistText = artist,
        thumbnailUrl = null,
        structuredCreatorMatches = structured,
    )

    private fun language(tag: String, certainty: LanguageCertainty) = LanguageProjectionContract(
        dimension = LanguageDimension.READING,
        tag = tag,
        certainty = certainty,
        evidenceKind = LanguageEvidenceKind.STRUCTURED_METADATA,
    )
}

private open class FallbackSource(
    override val id: Long,
    private val searchFailure: Throwable? = null,
) : CatalogueSource {
    override val name = "source-$id"
    override val lang = "en"
    override val supportsLatest = false
    var fallbackSearchCalls = 0

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        fallbackSearchCalls += 1
        searchFailure?.let { throw it }
        return MangasPage(listOf(manga("/fallback", "Fallback", "ONE")), false)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<eu.kanade.tachiyomi.source.model.SChapter>()
    override suspend fun getPageList(
        chapter: eu.kanade.tachiyomi.source.model.SChapter,
    ) = emptyList<eu.kanade.tachiyomi.source.model.Page>()
    override fun getFilterList() = FilterList()
}

private class StructuredSource(id: Long) : FallbackSource(id), AuthorSearchSource {
    var authorSearchCalls = 0

    override suspend fun getAuthorSearchManga(page: Int, authorQuery: String): AuthorSearchPage {
        authorSearchCalls += 1
        return AuthorSearchPage(
            mangas = listOf(
                AuthorSearchManga(
                    manga = manga("/structured", "Structured", "ONE"),
                    matchedCreators = listOf(
                        AuthorSearchCreatorMatch(
                            displayName = "ONE",
                            role = AuthorSearchCreatorRole.AUTHOR,
                            evidence = "structured:one",
                        ),
                    ),
                ),
            ),
            hasNextPage = false,
        )
    }
}

private class FailingStructuredSource(id: Long) : FallbackSource(id), AuthorSearchSource {
    var authorSearchCalls = 0

    override suspend fun getAuthorSearchManga(page: Int, authorQuery: String): AuthorSearchPage {
        authorSearchCalls += 1
        error("optional author endpoint unavailable")
    }
}

private fun manga(url: String, title: String, author: String?): SManga = SManga.create().also {
    it.url = url
    it.title = title
    it.author = author
}
