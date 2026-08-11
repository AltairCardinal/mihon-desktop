package tachiyomi.domain.creator.service

import com.aallam.similarity.NormalizedLevenshtein
import kotlin.math.max

/** Shared title matching semantics used by migration search and author-archive matching. */
object SmartSearchSimilarity {
    private val normalizedLevenshtein = NormalizedLevenshtein()

    fun similarity(first: String, second: String): Double {
        val left = cleanTitle(first)
        val right = cleanTitle(second)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        return max(normalizedLevenshtein.similarity(left, right), tokenContainment(left, right))
    }

    fun cleanTitle(title: String): String {
        val lowered = title.lowercase()
        var cleaned = removeTextInBrackets(lowered, true)
        if (cleaned.length <= 5) cleaned = removeTextInBrackets(lowered, false)
        return cleaned
            .replace(CHAPTER_REF_CYRILLIC, " ")
            .replace(NON_TITLE_CHARACTERS, " ")
            .replace(" - ", " ")
            .replace(CONSECUTIVE_SPACES, " ")
            .trim()
    }

    private fun tokenContainment(first: String, second: String): Double {
        val left = first.replace('-', ' ').split(' ').filter(String::isNotBlank).toSet()
        val right = second.replace('-', ' ').split(' ').filter(String::isNotBlank).toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / minOf(left.size, right.size)
    }

    private fun removeTextInBrackets(text: String, readForward: Boolean): String {
        val openingChars = if (readForward) "([<{" else ")]}>"
        val closingChars = if (readForward) ")]}>" else "([<{"
        var depth = 0
        return buildString {
            for (char in if (readForward) text else text.reversed()) {
                when (char) {
                    in openingChars -> depth++
                    in closingChars -> if (depth > 0) depth--
                    else -> if (depth == 0) {
                        if (readForward) append(char) else insert(0, char)
                    }
                }
            }
        }
    }

    private val NON_TITLE_CHARACTERS = Regex("[^\\p{L}0-9- ]")
    private val CONSECUTIVE_SPACES = Regex(" +")
    private val CHAPTER_REF_CYRILLIC = Regex("""((- том|- глава) \d*)""")
}
