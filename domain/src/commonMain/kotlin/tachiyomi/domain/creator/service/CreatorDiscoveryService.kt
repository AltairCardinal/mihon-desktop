package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorArchiveV2Policy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.source.service.SourceMangaSearchService

class CreatorDiscoveryService(
    private val creatorRepository: CreatorRepository,
    private val sourceMangaSearchService: SourceMangaSearchService = SourceMangaSearchService(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var sourcePort: CreatorDiscoverySourcePort? = null
    private var archiveRepository: CreatorArchiveRepository? = creatorRepository as? CreatorArchiveRepository
    private var bounds: CreatorDiscoveryBounds = CreatorDiscoveryBounds()

    constructor(
        creatorRepository: CreatorRepository,
        archiveRepository: CreatorArchiveRepository,
        sourcePort: CreatorDiscoverySourcePort,
        bounds: CreatorDiscoveryBounds = CreatorDiscoveryBounds(),
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(creatorRepository, SourceMangaSearchService(), clock) {
        this.archiveRepository = archiveRepository
        this.sourcePort = sourcePort
        this.bounds = bounds
    }

    suspend fun discoverDueWatches(): CreatorDiscoveryResult {
        return discoverDueWatches(requireNotNull(sourcePort) { "Creator discovery source port is not configured" })
    }

    suspend fun discoverCreator(
        creatorId: Long,
        sourceIds: Set<Long> = emptySet(),
        languageTags: Set<String> = emptySet(),
    ): CreatorDiscoveryResult {
        return discoverCreator(
            creatorId = creatorId,
            sourceIds = sourceIds,
            languageTags = languageTags,
            port = requireNotNull(sourcePort) { "Creator discovery source port is not configured" },
        )
    }

    /** Temporary Android compatibility entry; Desktop production uses the enabled-source port. */
    suspend fun discoverDueWatches(sources: List<CatalogueSource>): CreatorDiscoveryResult {
        return discoverDueWatches(legacyPort(sources))
    }

    /** Temporary Android/test compatibility entry; it is still bounded and identity-gated. */
    suspend fun discoverCreator(
        creatorId: Long,
        sources: List<CatalogueSource>,
        sourceIds: List<Long> = emptyList(),
        languageTags: List<String> = emptyList(),
    ): CreatorDiscoveryResult = discoverCreator(
        creatorId = creatorId,
        sourceIds = sourceIds.toSet(),
        languageTags = languageTags.toSet(),
        port = legacyPort(sources),
        permissiveLegacyResultPolicy = true,
    )

    private suspend fun discoverDueWatches(port: CreatorDiscoverySourcePort): CreatorDiscoveryResult {
        val enabledSources = port.enabledSourcesSnapshot()
        val results = creatorRepository.getFollowedCreators()
            .filter { it.enabled }
            .mapNotNull { watch ->
                val creator = creatorRepository.getCreator(watch.creatorId) ?: return@mapNotNull null
                val policy = archiveRepository?.getWatchPolicy(creator.id) ?: ArchiveWatchPolicy(
                    creatorId = creator.id,
                    enabled = true,
                    periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
                    sourceIds = watch.sourceIds.toSet(),
                    readingLanguageTags = watch.languageTags.toSet(),
                    includeProbable = archiveRepository == null,
                    includeUnknown = archiveRepository == null,
                )
                val result = discoverForCreator(creator, policy, enabledSources, port)
                creatorRepository.updateWatchCheckResult(
                    creatorId = creator.id,
                    checkedAt = clock(),
                    success = result.errorCount == 0,
                    error = result.sourceResults.firstNotNullOfOrNull { it.failure?.safeCode() },
                )
                result
            }
        return results.merge()
    }

    private suspend fun discoverCreator(
        creatorId: Long,
        sourceIds: Set<Long>,
        languageTags: Set<String>,
        port: CreatorDiscoverySourcePort,
        permissiveLegacyResultPolicy: Boolean = false,
    ): CreatorDiscoveryResult {
        val creator = creatorRepository.getCreator(creatorId) ?: return CreatorDiscoveryResult.Empty
        val storedPolicy = archiveRepository?.getWatchPolicy(creatorId)
        val policy = (
            storedPolicy ?: ArchiveWatchPolicy(
                creatorId = creatorId,
                enabled = true,
                periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
                sourceIds = emptySet(),
                readingLanguageTags = emptySet(),
                includeProbable = permissiveLegacyResultPolicy,
                includeUnknown = permissiveLegacyResultPolicy,
            )
            ).copy(
            enabled = true,
            sourceIds = sourceIds.takeIf(Set<Long>::isNotEmpty) ?: storedPolicy?.sourceIds.orEmpty(),
            readingLanguageTags = languageTags.takeIf(Set<String>::isNotEmpty)
                ?: storedPolicy?.readingLanguageTags.orEmpty(),
        )
        return discoverForCreator(creator, policy, port.enabledSourcesSnapshot(), port)
    }

    private suspend fun discoverForCreator(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        enabledSources: List<EnabledCreatorSource>,
        port: CreatorDiscoverySourcePort,
    ): CreatorDiscoveryResult {
        val startedAt = clock()
        val plan = CreatorDiscoveryQueryPlanner(bounds).plan(
            creatorId = creator.id,
            aliases = listOf(creator.displayName) + creator.aliases,
            enabledSources = enabledSources,
            watchPolicy = policy,
            startedAtMillis = startedAt,
        )
        val semaphore = Semaphore(plan.maxConcurrentSources)
        val sourceResults = coroutineScope {
            plan.sources.map { sourcePlan ->
                async {
                    semaphore.withPermit {
                        discoverSource(creator, policy, plan, sourcePlan, port)
                    }
                }
            }.awaitAll()
        }
        return CreatorDiscoveryResult(
            newCandidateCount = sourceResults.sumOf(CreatorDiscoverySourceResult::insertedVerifiedCount),
            errorCount = sourceResults.count { it.failure != null },
            candidates = creatorRepository.getDiscoveryCandidatesForCreator(creator.id),
            sourceResults = sourceResults,
        )
    }

    private suspend fun discoverSource(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        plan: CreatorDiscoveryQueryPlan,
        sourcePlan: CreatorSourceQueryPlan,
        port: CreatorDiscoverySourcePort,
    ): CreatorDiscoverySourceResult {
        val works = linkedMapOf<SourceWorkNaturalKey, CreatorSourceWorkSnapshot>()
        var pageCount = 0
        var truncated = false
        for (alias in plan.aliases) {
            var aliasPage = 1
            while (aliasPage <= sourcePlan.maxPagesPerAlias && pageCount < sourcePlan.maxTotalPages) {
                val result = port.searchPage(
                    BoundedAuthorSearchPageRequest(
                        sourceId = sourcePlan.source.sourceId,
                        alias = alias,
                        page = aliasPage,
                        pageLimit = sourcePlan.maxPagesPerAlias,
                        deadlineAtMillis = plan.deadlineAtMillis,
                    ),
                )
                pageCount += 1
                when (result) {
                    is CreatorSourcePageResult.Content -> {
                        result.works.forEach { works.putIfAbsent(it.key, it) }
                        if (!result.hasNextPage) break
                        if (aliasPage == sourcePlan.maxPagesPerAlias || pageCount == sourcePlan.maxTotalPages) {
                            truncated = true
                            break
                        }
                    }
                    CreatorSourcePageResult.Empty -> break
                    is CreatorSourcePageResult.Failure -> return CreatorDiscoverySourceResult(
                        sourceId = sourcePlan.source.sourceId,
                        pageCount = pageCount,
                        matchedCount = 0,
                        possibleCount = 0,
                        insertedVerifiedCount = 0,
                        notificationEligibleCount = 0,
                        truncated = truncated,
                        failure = result.error,
                    )
                }
                aliasPage += 1
            }
            if (pageCount == sourcePlan.maxTotalPages) {
                truncated = true
                break
            }
        }

        var matched = 0
        var possible = 0
        var insertedVerified = 0
        var notificationEligible = 0
        for (listedWork in works.values) {
            val remainingMillis = plan.deadlineAtMillis - clock()
            if (remainingMillis <= 0) {
                truncated = true
                return CreatorDiscoverySourceResult(
                    sourcePlan.source.sourceId,
                    pageCount,
                    matched,
                    possible,
                    insertedVerified,
                    notificationEligible,
                    truncated,
                    CreatorSourceFailure.Timeout,
                )
            }
            val detailsResult = try {
                withTimeout(remainingMillis) { port.loadDetails(listedWork.key) }
            } catch (_: TimeoutCancellationException) {
                CreatorSourceDetailsResult.Failure(CreatorSourceFailure.Timeout)
            } catch (error: CancellationException) {
                throw error
            }
            val details = when (detailsResult) {
                is CreatorSourceDetailsResult.Content -> detailsResult.details
                is CreatorSourceDetailsResult.Failure -> return CreatorDiscoverySourceResult(
                    sourcePlan.source.sourceId,
                    pageCount,
                    matched,
                    possible,
                    insertedVerified,
                    notificationEligible,
                    truncated,
                    detailsResult.error,
                )
            }
            val identity = CreatorIdentityEvidenceEvaluator.evaluate(plan.aliases, details.work)
            if (identity.verification == CreatorRelationVerification.POSSIBLE) possible += 1 else matched += 1

            val languageAssertion = details.toReadingLanguageAssertion(sourcePlan.source)
            val languageProjection = CreatorArchiveV2Policy.projectLanguage(
                LanguageDimension.READING,
                listOf(languageAssertion),
            )
            val policyDecision = CreatorDiscoveryResultPolicy.evaluate(languageProjection, policy)
            if (!policyDecision.includeInArchive) continue
            if (policyDecision.notify && identity.verification == CreatorRelationVerification.VERIFIED) {
                notificationEligible += 1
            }

            val archive = archiveRepository
            if (archive == null) {
                persistLegacyCandidate(creator, details, identity, languageAssertion)
                if (identity.verification == CreatorRelationVerification.VERIFIED) insertedVerified += 1
                continue
            }
            archive.upsertSourceWork(
                sourceId = details.work.key.sourceId,
                stableSourceUrl = details.work.key.stableSourceUrl,
                mangaId = null,
                title = details.work.title,
                authorText = details.work.authorText,
                artistText = details.work.artistText,
                thumbnailUrl = details.work.thumbnailUrl,
                detailsFetchedAt = clock(),
            )
            archive.appendLanguageAssertion(
                subject = ArchiveLanguageSubject.SourceWork(details.work.key),
                assertion = languageAssertion,
                actor = DecisionActor.ALGORITHM,
                evidencePayload = "creator discovery metadata",
                algorithmVersion = DISCOVERY_ALGORITHM_VERSION,
                assertedAt = clock(),
                idempotencyKey = languageAssertion.idempotencyKey(details.work.key),
            )
            val relation = archive.upsertSourceWorkCreator(
                sourceWork = details.work.key,
                creatorId = creator.id,
                role = identity.role,
                order = 0,
                origin = CreatorRelationOrigin.AUTOMATIC,
                verification = identity.verification,
                sourceText = details.work.authorText ?: details.work.artistText,
                confidence = identity.confidence,
                evidence = identity.evidence,
            )
            if (identity.verification == CreatorRelationVerification.VERIFIED &&
                relation is ArchiveUpsertOutcome.Inserted
            ) {
                insertedVerified += 1
            }
        }
        return CreatorDiscoverySourceResult(
            sourceId = sourcePlan.source.sourceId,
            pageCount = pageCount,
            matchedCount = matched,
            possibleCount = possible,
            insertedVerifiedCount = insertedVerified,
            notificationEligibleCount = notificationEligible,
            truncated = truncated,
            failure = null,
        )
    }

    private suspend fun persistLegacyCandidate(
        creator: Creator,
        details: CreatorSourceDetails,
        identity: CreatorIdentityEvidence,
        language: LanguageAssertionContract,
    ) {
        val candidate = creatorRepository.upsertDiscoveryCandidate(
            source = details.work.key.sourceId,
            url = details.work.key.stableSourceUrl,
            title = details.work.title,
            authorText = details.work.authorText,
            artistText = details.work.artistText,
            languageTag = language.tag,
            languageConfidence = language.confidence,
            languageEvidence = language.evidenceKind.name,
            thumbnailUrl = details.work.thumbnailUrl,
            detailsFetchedAt = clock(),
            state = DiscoveryCandidateState.NEW,
        )
        creatorRepository.linkDiscoveryCandidateCreator(
            candidateId = candidate.id,
            creatorId = creator.id,
            role = identity.role,
            sourceText = details.work.authorText ?: details.work.artistText,
            confidence = identity.confidence,
            evidence = identity.evidence,
        )
    }

    private fun CreatorSourceDetails.toReadingLanguageAssertion(
        source: EnabledCreatorSource,
    ): LanguageAssertionContract {
        val singleSourceLanguage = (source.readingLanguageProfile as? CreatorSourceReadingLanguageProfile.Single)
            ?.languageTag
        val genres = metadata["genres"]?.split('\u001f').orEmpty()
        val detection = MangaLanguageDetector.detect(
            sourceLang = singleSourceLanguage,
            explicitLanguage = readingLanguageTag,
            title = work.title,
            description = metadata["description"],
            genres = genres,
        )
        return LanguageAssertionContract(
            dimension = LanguageDimension.READING,
            tag = detection.tag.takeUnless { it == "unknown" } ?: "und",
            confidence = detection.confidence,
            evidenceKind = when (detection.evidence) {
                LanguageEvidence.EXPLICIT_METADATA -> LanguageEvidenceKind.STRUCTURED_METADATA
                LanguageEvidence.GENRE_TAG -> LanguageEvidenceKind.SOURCE_FILTER_OR_TAG
                LanguageEvidence.SOURCE_LANGUAGE -> LanguageEvidenceKind.SINGLE_LANGUAGE_SOURCE
                LanguageEvidence.TEXT_DETECTED -> LanguageEvidenceKind.TEXT_DETECTION
                LanguageEvidence.UNKNOWN -> LanguageEvidenceKind.UNKNOWN
            },
        )
    }

    private fun LanguageAssertionContract.idempotencyKey(key: SourceWorkNaturalKey): String {
        return listOf(
            "creator-discovery-language-v1",
            key.sourceId,
            key.stableSourceUrl,
            dimension,
            tag,
            confidence,
            evidenceKind,
        ).joinToString(":")
    }

    private fun legacyPort(sources: List<CatalogueSource>): CreatorDiscoverySourcePort {
        val sourcesById = sources.distinctBy(CatalogueSource::id).associateBy(CatalogueSource::id)
        return CatalogueCreatorDiscoverySourceAdapter(
            enabledSourcesProvider = { sourcesById.values.toList() },
            sourceResolver = sourcesById::get,
            sourceMangaSearchService = sourceMangaSearchService,
            languageProfileProvider = { source ->
                source.lang.takeIf(String::isNotBlank)
                    ?.let(CreatorSourceReadingLanguageProfile::Single)
                    ?: CreatorSourceReadingLanguageProfile.Unknown
            },
            clock = clock,
        )
    }

    private fun List<CreatorDiscoveryResult>.merge(): CreatorDiscoveryResult = CreatorDiscoveryResult(
        newCandidateCount = sumOf(CreatorDiscoveryResult::newCandidateCount),
        errorCount = sumOf(CreatorDiscoveryResult::errorCount),
        candidates = flatMap(CreatorDiscoveryResult::candidates),
        sourceResults = flatMap(CreatorDiscoveryResult::sourceResults),
    )

    private fun CreatorSourceFailure.safeCode(): String = when (this) {
        is CreatorSourceFailure.AuthenticationRequired -> "AUTHENTICATION_REQUIRED"
        is CreatorSourceFailure.RateLimited -> "RATE_LIMITED"
        is CreatorSourceFailure.Http -> "HTTP_$statusCode"
        CreatorSourceFailure.Timeout -> "TIMEOUT"
        is CreatorSourceFailure.Network -> "NETWORK"
        is CreatorSourceFailure.MalformedResponse -> "MALFORMED_RESPONSE"
        CreatorSourceFailure.MissingSource -> "MISSING_SOURCE"
        CreatorSourceFailure.UnsupportedCapability -> "UNSUPPORTED_CAPABILITY"
    }

    private companion object {
        const val DEFAULT_WATCH_PERIOD_MILLIS = 24 * 60 * 60 * 1_000L
        const val DISCOVERY_ALGORITHM_VERSION = "creator-discovery-v1"
    }
}

data class CreatorDiscoverySourceResult(
    val sourceId: Long,
    val pageCount: Int,
    val matchedCount: Int,
    val possibleCount: Int,
    val insertedVerifiedCount: Int,
    val notificationEligibleCount: Int,
    val truncated: Boolean,
    val failure: CreatorSourceFailure?,
)

data class CreatorDiscoveryResult(
    val newCandidateCount: Int,
    val errorCount: Int,
    val candidates: List<DiscoveryCandidate>,
    val sourceResults: List<CreatorDiscoverySourceResult> = emptyList(),
) {
    companion object {
        val Empty = CreatorDiscoveryResult(0, 0, emptyList())
    }
}
