package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageProjectionContract

data class CreatorDiscoveryBounds(
    val maxAliases: Int = 4,
    val maxPagesPerAlias: Int = 3,
    val maxTotalPagesPerSource: Int = 8,
    val maxConcurrentSources: Int = 3,
    val sourceTimeoutMillis: Long = 20_000,
    val maxSourcesPerWatch: Int = 8,
) {
    init {
        require(maxAliases > 0)
        require(maxPagesPerAlias > 0)
        require(maxTotalPagesPerSource > 0)
        require(maxConcurrentSources > 0)
        require(sourceTimeoutMillis > 0)
        require(maxSourcesPerWatch > 0)
    }
}

/**
 * Frozen per-source retry schedule used by the discovery executor.
 *
 * The first failure waits 30 minutes, then 2 h, 8 h, capped at 24 h. A jitter value (default 0) is
 * added to spread retries across watches; negative jitter is clamped to zero so a retry can never
 * become due earlier than the schedule guarantees.
 */
object CreatorDiscoveryBackoff {
    val DELAY_MILLIS: List<Long> = listOf(
        30 * 60 * 1_000L,
        2 * 60 * 60 * 1_000L,
        8 * 60 * 60 * 1_000L,
        24 * 60 * 60 * 1_000L,
    )

    fun delayMillis(consecutiveFailures: Long): Long {
        val index = (consecutiveFailures - 1).coerceAtLeast(0).toInt()
        return DELAY_MILLIS[index.coerceAtMost(DELAY_MILLIS.lastIndex)]
    }

    fun backoffUntilMillis(now: Long, consecutiveFailures: Long, jitterMillis: Long = 0L): Long {
        require(now >= 0)
        return now + delayMillis(consecutiveFailures) + jitterMillis.coerceAtLeast(0)
    }
}

data class CreatorDiscoveryQueryPlan(
    val creatorId: Long,
    val aliases: List<String>,
    val sources: List<CreatorSourceQueryPlan>,
    val maxConcurrentSources: Int,
    val deadlineAtMillis: Long,
)

data class CreatorSourceQueryPlan(
    val source: EnabledCreatorSource,
    val maxPagesPerAlias: Int,
    val maxTotalPages: Int,
)

class CreatorDiscoveryQueryPlanner(
    private val bounds: CreatorDiscoveryBounds = CreatorDiscoveryBounds(),
) {
    fun plan(
        creatorId: Long,
        aliases: List<String>,
        enabledSources: List<EnabledCreatorSource>,
        watchPolicy: ArchiveWatchPolicy,
        startedAtMillis: Long,
    ): CreatorDiscoveryQueryPlan {
        val reducedAliases = aliases
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(CreatorNameNormalizer::normalize)
            .take(bounds.maxAliases)
        require(reducedAliases.isNotEmpty())

        val selectedSources = enabledSources
            .asSequence()
            .filter { watchPolicy.sourceIds.isEmpty() || it.sourceId in watchPolicy.sourceIds }
            .filter { it.isSearchable() }
            .filter { source -> source.isCompatibleBeforeRequest(watchPolicy.readingLanguageTags) }
            .distinctBy(EnabledCreatorSource::sourceId)
            .sortedBy(EnabledCreatorSource::sourceId)
            .map {
                CreatorSourceQueryPlan(
                    source = it,
                    maxPagesPerAlias = bounds.maxPagesPerAlias,
                    maxTotalPages = bounds.maxTotalPagesPerSource,
                )
            }
            .toList()

        return CreatorDiscoveryQueryPlan(
            creatorId = creatorId,
            aliases = reducedAliases,
            sources = selectedSources,
            maxConcurrentSources = bounds.maxConcurrentSources,
            deadlineAtMillis = startedAtMillis + bounds.sourceTimeoutMillis,
        )
    }

    private fun EnabledCreatorSource.isSearchable(): Boolean {
        return CreatorSourceCapability.AUTHOR_SEARCH in capabilities ||
            CreatorSourceCapability.CATALOGUE_SEARCH_FALLBACK in capabilities
    }

    private fun EnabledCreatorSource.isCompatibleBeforeRequest(languageTags: Set<String>): Boolean {
        if (languageTags.isEmpty()) return true
        return when (val profile = readingLanguageProfile) {
            CreatorSourceReadingLanguageProfile.Unknown -> true
            is CreatorSourceReadingLanguageProfile.Multiple -> true
            is CreatorSourceReadingLanguageProfile.Single -> profile.languageTag in languageTags
        }
    }
}

data class CreatorIdentityEvidence(
    val verification: CreatorRelationVerification,
    val role: CreatorRole,
    val matchedAlias: String?,
    val confidence: Double,
    val evidence: String,
)

object CreatorIdentityEvidenceEvaluator {
    fun evaluate(aliases: List<String>, work: CreatorSourceWorkSnapshot): CreatorIdentityEvidence {
        val aliasesByNormalized = aliases
            .map(String::trim)
            .filter(String::isNotBlank)
            .associateBy(CreatorNameNormalizer::normalize)

        work.structuredCreatorMatches.firstOrNull { match ->
            CreatorNameNormalizer.normalize(match.displayName) in aliasesByNormalized
        }?.let { match ->
            return CreatorIdentityEvidence(
                verification = CreatorRelationVerification.VERIFIED,
                role = match.role,
                matchedAlias = aliasesByNormalized[CreatorNameNormalizer.normalize(match.displayName)],
                confidence = 1.0,
                evidence = match.evidence,
            )
        }

        val authorAlias = matchingAlias(work.authorText, aliasesByNormalized)
        val artistAlias = matchingAlias(work.artistText, aliasesByNormalized)
        if (authorAlias != null || artistAlias != null) {
            return CreatorIdentityEvidence(
                verification = CreatorRelationVerification.VERIFIED,
                role = when {
                    authorAlias != null && artistAlias != null -> CreatorRole.BOTH
                    authorAlias != null -> CreatorRole.AUTHOR
                    else -> CreatorRole.ARTIST
                },
                matchedAlias = authorAlias ?: artistAlias,
                confidence = 0.95,
                evidence = "exact creator metadata field",
            )
        }

        return CreatorIdentityEvidence(
            verification = CreatorRelationVerification.POSSIBLE,
            role = CreatorRole.UNKNOWN,
            matchedAlias = null,
            confidence = 0.2,
            evidence = "catalogue search result without matching creator metadata",
        )
    }

    private fun matchingAlias(
        sourceText: String?,
        aliasesByNormalized: Map<String, String>,
    ): String? = CreatorNameNormalizer.splitNames(sourceText).firstNotNullOfOrNull { name ->
        aliasesByNormalized[CreatorNameNormalizer.normalize(name)]
    }
}

data class CreatorDiscoveryPolicyDecision(
    val includeInArchive: Boolean,
    val notify: Boolean,
)

object CreatorDiscoveryResultPolicy {
    fun evaluate(
        language: LanguageProjectionContract,
        policy: ArchiveWatchPolicy,
    ): CreatorDiscoveryPolicyDecision {
        val languageMatches = policy.readingLanguageTags.isEmpty() ||
            language.tag in policy.readingLanguageTags
        return when (language.certainty) {
            LanguageCertainty.CONFIRMED -> CreatorDiscoveryPolicyDecision(
                includeInArchive = languageMatches,
                notify = languageMatches,
            )
            LanguageCertainty.PROBABLE -> CreatorDiscoveryPolicyDecision(
                includeInArchive = languageMatches && policy.includeProbable,
                notify = languageMatches && policy.includeProbable && policy.notifyProbable,
            )
            LanguageCertainty.UNKNOWN, LanguageCertainty.CONFLICT -> CreatorDiscoveryPolicyDecision(
                includeInArchive = policy.includeUnknown,
                notify = policy.includeUnknown && policy.notifyUnknown,
            )
        }
    }
}
