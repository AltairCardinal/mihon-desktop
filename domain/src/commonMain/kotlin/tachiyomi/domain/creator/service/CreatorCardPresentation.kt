package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.CreatorCardWorkCandidate
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionState

/** View-only author-index projection shared with the author detail grouping rules. */
object CreatorCardPresentation {
    fun project(
        candidates: List<CreatorCardWorkCandidate>,
        excludedNaturalKeys: Set<SourceWorkNaturalKey> = emptySet(),
    ): List<CreatorCardWorkCandidate> {
        val canonical = candidates.filter { it.workKey.startsWith("canonical:") }.groupBy { it.workKey }
        val canonicalByTitle = canonical.entries
            .flatMap { (key, values) ->
                values.filterNot { it.naturalKey in excludedNaturalKeys }.flatMap { candidate ->
                    listOfNotNull(candidate.canonicalTitle, candidate.title)
                        .mapNotNull { title ->
                            WorkTitleNormalizer.normalizeForPresentationGroup(title)
                                .takeIf(String::isNotBlank)?.let { normalized -> normalized to key }
                        }
                }
            }
            .groupBy({ it.first }, { it.second })
        val projected = candidates.map { candidate ->
            if (candidate.naturalKey in excludedNaturalKeys) {
                candidate.copy(workKey = sourceWorkKey(candidate.naturalKey))
            } else if (candidate.workKey.startsWith("canonical:")) {
                candidate
            } else {
                val normalized = WorkTitleNormalizer.normalizeForPresentationGroup(candidate.title)
                val canonicalKeys = canonicalByTitle[normalized].orEmpty().distinct()
                when {
                    canonicalKeys.size == 1 -> candidate.copy(workKey = canonicalKeys.single())
                    normalized.isNotBlank() -> candidate.copy(workKey = "presentation:$normalized")
                    else -> candidate.copy(
                        workKey = "source:${candidate.naturalKey.sourceId}:${candidate.naturalKey.stableSourceUrl}",
                    )
                }
            }
        }.filter { it.decisionState != WorkDecisionState.REJECTED }
        return projected
            .groupBy { it.workKey }
            .flatMap { (workKey, values) ->
                if (!workKey.startsWith("presentation:") || values.map { it.title }.distinct().size >= 2) {
                    values
                } else {
                    values.map {
                        it.copy(workKey = "source:${it.naturalKey.sourceId}:${it.naturalKey.stableSourceUrl}")
                    }
                }
            }
    }

    fun uniqueWorkCount(
        candidates: List<CreatorCardWorkCandidate>,
        excludedNaturalKeys: Set<SourceWorkNaturalKey> = emptySet(),
    ): Int = uniqueProjectedWorkCount(project(candidates, excludedNaturalKeys))

    fun uniqueProjectedWorkCount(candidates: List<CreatorCardWorkCandidate>): Int = candidates
        .asSequence()
        .filter { it.relationVerification == CreatorRelationVerification.VERIFIED }
        .map(CreatorCardWorkCandidate::workKey)
        .distinct()
        .count()

    fun unreadWorkCount(
        candidates: List<CreatorCardWorkCandidate>,
        excludedNaturalKeys: Set<SourceWorkNaturalKey> = emptySet(),
    ): Int = unreadProjectedWorkCount(project(candidates, excludedNaturalKeys))

    fun unreadProjectedWorkCount(candidates: List<CreatorCardWorkCandidate>): Int = candidates
        .asSequence()
        .filter(CreatorCardWorkCandidate::unread)
        .map(CreatorCardWorkCandidate::workKey)
        .distinct()
        .count()

    private fun sourceWorkKey(key: SourceWorkNaturalKey): String =
        "source:${key.sourceId}:${key.stableSourceUrl}"
}
