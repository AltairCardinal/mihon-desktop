package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.WorkDecisionState

data class WorkMatchInput(
    val title: String,
    val titleAliases: List<String> = emptyList(),
    val creators: List<String>,
    val language: String?,
    val externalIds: Map<String, String> = emptyMap(),
    val chapterCount: Int? = null,
)

enum class WorkMatchEvidenceKind {
    STABLE_EXTERNAL_ID,
    TITLE,
    TITLE_SCRIPT_VARIANT,
    TITLE_ALIAS,
    CREATOR,
    CREATOR_CONFLICT,
    LANGUAGE,
    LANGUAGE_CONFLICT,
    CHAPTER_COVERAGE,
}

data class WorkMatchEvidence(val kind: WorkMatchEvidenceKind, val score: Double, val summary: String)

enum class WorkMatchTier { WEAK, POSSIBLE, STRONG, STABLE_ID }

data class WorkMatchScore(
    val value: Double,
    val evidence: List<WorkMatchEvidence>,
    val algorithmVersion: String,
    val tier: WorkMatchTier,
    val recommendedState: WorkDecisionState,
    val eligibleForAutomaticConfirmation: Boolean,
) {
    val reason: String = evidence.joinToString("; ") { "${it.kind.name.lowercase()}=${it.score}" }
}

object WorkMatchScorer {
    const val ALGORITHM_VERSION = "author-work-match-v3"

    fun score(current: WorkMatchInput, candidate: WorkMatchInput): WorkMatchScore {
        val stableIdMatch = current.externalIds.entries.any { (provider, id) ->
            id.isNotBlank() && candidate.externalIds[provider]?.equals(id, ignoreCase = true) == true
        }
        val directTitleScore = SmartSearchSimilarity.similarity(current.title, candidate.title)
        val aliasTitleScore = (current.titleAliases + current.title).maxOf { alias ->
            (candidate.titleAliases + candidate.title).maxOf { other -> SmartSearchSimilarity.similarity(alias, other) }
        }
        val scriptVariant = WorkTitleNormalizer.isSimplifiedTraditionalVariant(current.title, candidate.title)
        val titleScore = maxOf(directTitleScore, aliasTitleScore, if (scriptVariant) 1.0 else 0.0)
        val creatorScore = creatorSimilarity(current.creators, candidate.creators)
        val languagesKnown = current.language != null && candidate.language != null
        val languageScore = if (languagesKnown && current.language == candidate.language) 1.0 else 0.0
        val chapterScore = chapterCoverage(current.chapterCount, candidate.chapterCount)
        val evidence = buildList {
            if (stableIdMatch) {
                add(
                    WorkMatchEvidence(WorkMatchEvidenceKind.STABLE_EXTERNAL_ID, 1.0, "same structured provider id"),
                )
            }
            add(
                WorkMatchEvidence(
                    when {
                        scriptVariant -> WorkMatchEvidenceKind.TITLE_SCRIPT_VARIANT
                        aliasTitleScore > directTitleScore -> WorkMatchEvidenceKind.TITLE_ALIAS
                        else -> WorkMatchEvidenceKind.TITLE
                    },
                    titleScore,
                    if (scriptVariant) "simplified/traditional title equivalence" else "normalized title similarity",
                ),
            )
            add(
                WorkMatchEvidence(
                    if (creatorScore < CREATOR_MATCH_THRESHOLD && current.creators.isNotEmpty() &&
                        candidate.creators.isNotEmpty()
                    ) {
                        WorkMatchEvidenceKind.CREATOR_CONFLICT
                    } else {
                        WorkMatchEvidenceKind.CREATOR
                    },
                    creatorScore,
                    "creator identity similarity",
                ),
            )
            if (languagesKnown) {
                add(
                    WorkMatchEvidence(
                        if (languageScore ==
                            1.0
                        ) {
                            WorkMatchEvidenceKind.LANGUAGE
                        } else {
                            WorkMatchEvidenceKind.LANGUAGE_CONFLICT
                        },
                        languageScore,
                        "reading language evidence",
                    ),
                )
            }
            if (chapterScore != null) {
                add(
                    WorkMatchEvidence(WorkMatchEvidenceKind.CHAPTER_COVERAGE, chapterScore, "chapter-count coverage"),
                )
            }
        }
        val value = if (stableIdMatch) {
            1.0
        } else {
            titleScore * 0.55 + creatorScore * 0.30 + languageScore * 0.10 + (chapterScore ?: 0.0) * 0.05
        }
        val tier = when {
            stableIdMatch -> WorkMatchTier.STABLE_ID
            value >= 0.80 -> WorkMatchTier.STRONG
            value >= 0.50 -> WorkMatchTier.POSSIBLE
            else -> WorkMatchTier.WEAK
        }
        return WorkMatchScore(
            value = value.coerceIn(0.0, 1.0),
            evidence = evidence,
            algorithmVersion = ALGORITHM_VERSION,
            tier = tier,
            recommendedState = if (stableIdMatch) WorkDecisionState.CONFIRMED else WorkDecisionState.SUGGESTED,
            eligibleForAutomaticConfirmation = stableIdMatch,
        )
    }

    private fun creatorSimilarity(first: List<String>, second: List<String>): Double {
        val left = first.map(CreatorNameNormalizer::normalize).filter(String::isNotBlank)
        val right = second.map(CreatorNameNormalizer::normalize).filter(String::isNotBlank)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.maxOf { a -> right.maxOf { b -> SmartSearchSimilarity.similarity(a, b) } }
    }

    private fun chapterCoverage(first: Int?, second: Int?): Double? {
        if (first == null || second == null || first < 0 || second < 0) return null
        if (first == 0 && second == 0) return 1.0
        val maximum = maxOf(first, second)
        return if (maximum == 0) 0.0 else minOf(first, second).toDouble() / maximum
    }

    private const val CREATOR_MATCH_THRESHOLD = 0.5
}
