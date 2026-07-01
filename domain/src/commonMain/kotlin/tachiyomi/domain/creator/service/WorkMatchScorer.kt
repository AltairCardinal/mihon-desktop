package tachiyomi.domain.creator.service

import kotlin.math.max

data class WorkMatchInput(
    val title: String,
    val creators: List<String>,
    val language: String?,
)

data class WorkMatchScore(
    val value: Double,
    val reason: String,
)

object WorkMatchScorer {

    fun score(current: WorkMatchInput, candidate: WorkMatchInput): WorkMatchScore {
        val titleScore = tokenSimilarity(
            CreatorNameNormalizer.normalize(current.title),
            CreatorNameNormalizer.normalize(candidate.title),
        )
        val creatorScore = creatorSimilarity(current.creators, candidate.creators)
        val languageScore = if (
            current.language != null &&
            candidate.language != null &&
            current.language == candidate.language
        ) {
            1.0
        } else {
            0.0
        }

        val score = titleScore * 0.55 + creatorScore * 0.35 + languageScore * 0.10
        return WorkMatchScore(
            value = score.coerceIn(0.0, 1.0),
            reason = "title=$titleScore creator=$creatorScore language=$languageScore",
        )
    }

    private fun creatorSimilarity(first: List<String>, second: List<String>): Double {
        val left = first.map(CreatorNameNormalizer::normalize).filter { it.isNotBlank() }
        val right = second.map(CreatorNameNormalizer::normalize).filter { it.isNotBlank() }
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.maxOf { a -> right.maxOf { b -> tokenSimilarity(a, b) } }
    }

    private fun tokenSimilarity(first: String, second: String): Double {
        val normalizedFirst = first.replace('-', ' ')
        val normalizedSecond = second.replace('-', ' ')
        if (normalizedFirst == normalizedSecond) return 1.0
        val left = normalizedFirst.split(" ").filter { it.isNotBlank() }.toSet()
        val right = normalizedSecond.split(" ").filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        val jaccard = intersection / union
        val containment = intersection / max(left.size, right.size).toDouble()
        return max(jaccard, containment)
    }
}
