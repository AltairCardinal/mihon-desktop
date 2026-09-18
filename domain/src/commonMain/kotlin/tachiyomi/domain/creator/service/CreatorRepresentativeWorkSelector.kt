package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.CreatorCardWorkCandidate
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRepresentativeWorkCache
import tachiyomi.domain.creator.model.CreatorRepresentativeWorkSelection
import tachiyomi.domain.creator.model.CreatorSelectedWorkKey
import tachiyomi.domain.creator.model.WorkDecisionState

object CreatorRepresentativeWorkSelector {
    const val STRATEGY_VERSION = 1
    const val MAX_WORKS = 3

    fun select(
        candidates: List<CreatorCardWorkCandidate>,
        previous: CreatorRepresentativeWorkCache? = null,
        preferredLanguages: Set<String> = emptySet(),
    ): CreatorRepresentativeWorkSelection {
        val priorSelection = previous
            ?.takeIf { it.strategyVersion == STRATEGY_VERSION }
            ?.selected
            .orEmpty()
            .distinctBy { it.workKey }
        val priorVersionByWork = priorSelection.associate { it.workKey to it.naturalKey }
        val preferredLanguageKeys = preferredLanguages.mapTo(mutableSetOf()) { it.lowercase() }

        val works = candidates
            .asSequence()
            .filter { candidate ->
                candidate.workKey.isNotBlank() &&
                    candidate.title.isNotBlank() &&
                    candidate.relationVerification == CreatorRelationVerification.VERIFIED &&
                    candidate.decisionState != WorkDecisionState.REJECTED
            }
            .groupBy { it.workKey }
            .mapNotNull { (workKey, versions) ->
                val previousVersion = priorVersionByWork[workKey]
                val version = versions
                    .distinctBy { it.naturalKey }
                    .minWithOrNull(versionComparator(previousVersion, preferredLanguageKeys))
                    ?: return@mapNotNull null
                EligibleWork(
                    workKey = workKey,
                    candidate = version,
                    tier = workTier(versions),
                    hasCoverReference = versions.any(::hasCoverReference),
                    mostRecentReadAt = versions.mapNotNull { it.lastReadAt }.maxOrNull(),
                )
            }
        val worksByKey = works.associateBy { it.workKey }

        val selected = priorSelection
            .mapNotNull { worksByKey[it.workKey] }
            .distinctBy { it.workKey }
            .take(MAX_WORKS)
            .toMutableList()
        val newlyRanked = works
            .filterNot { it.workKey in selected.mapTo(mutableSetOf()) { selectedWork -> selectedWork.workKey } }
            .sortedWith(workPriorityComparator())

        for (work in newlyRanked) {
            if (selected.size < MAX_WORKS) {
                selected += work
                continue
            }

            val lowestTier = selected.minOf { it.tier }
            if (work.tier > lowestTier) {
                val replaceIndex = selected.indexOfLast { it.tier == lowestTier }
                selected[replaceIndex] = work
            }
        }

        selected.sortWith(compareByDescending { it.tier })
        val representatives = selected.map { it.candidate }
        return CreatorRepresentativeWorkSelection(
            representatives = representatives,
            cache = CreatorRepresentativeWorkCache(
                strategyVersion = STRATEGY_VERSION,
                selected = selected.map { CreatorSelectedWorkKey(it.workKey, it.candidate.naturalKey) },
            ),
        )
    }

    private fun workTier(versions: List<CreatorCardWorkCandidate>): Int {
        val favorite = versions.any { it.inLibrary }
        val read = versions.any { it.lastReadAt != null }
        return when {
            favorite && read -> 3
            favorite -> 2
            read -> 1
            else -> 0
        }
    }

    private fun versionComparator(
        previous: tachiyomi.domain.creator.model.SourceWorkNaturalKey?,
        preferredLanguages: Set<String>,
    ) = compareByDescending<CreatorCardWorkCandidate> { if (it.naturalKey == previous) 1 else 0 }
        .thenByDescending { it.hasCustomCover }
        .thenByDescending { it.inLibrary }
        .thenByDescending { if (it.sourceLanguage?.lowercase() in preferredLanguages) 1 else 0 }
        .thenByDescending(::hasCoverReference)
        .thenByDescending { it.lastReadAt ?: Long.MIN_VALUE }
        .thenBy { it.naturalKey.sourceId }
        .thenBy { it.naturalKey.stableSourceUrl }

    private fun workPriorityComparator() =
        compareByDescending<EligibleWork> { it.tier }
            .thenByDescending { it.hasCoverReference }
            .thenByDescending { it.mostRecentReadAt ?: Long.MIN_VALUE }
            .thenBy { it.workKey }

    private fun hasCoverReference(candidate: CreatorCardWorkCandidate): Boolean =
        !candidate.coverRequest.url.isNullOrBlank()

    private data class EligibleWork(
        val workKey: String,
        val candidate: CreatorCardWorkCandidate,
        val tier: Int,
        val hasCoverReference: Boolean,
        val mostRecentReadAt: Long?,
    )
}
