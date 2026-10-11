package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionEvidence
import tachiyomi.domain.creator.model.CreatorMetadataField
import tachiyomi.domain.creator.model.CreatorRole

/** Bounded preview grammar: optional (C123), then [circle (one creator)], then a real title. */
object TitleCreditParser {
    private const val MAX_TITLE_LENGTH = 4096
    private const val MAX_PREFIX_LENGTH = 512
    private val activity = Regex("[（(]C[0-9]{1,4}[）)]")
    private val ambiguousNameCharacters = setOf(',', '，', '、', '/', '／', ';', '；', '&', '+', '|')
    private val brackets = setOf('(', ')', '（', '）', '[', ']', '［', '］')
    private val nonCreatorLabels = listOf("汉化", "漢化", "翻译", "翻譯", "掃圖", "扫图", "scanlation", "translation")

    fun parse(title: String): List<CreatorMention> {
        if (title.length > MAX_TITLE_LENGTH) return emptyList()
        var start = 0
        while (start < title.length && title[start].isWhitespace()) start++
        if (start == title.length) return emptyList()
        if (title[start] == '(' || title[start] == '（') {
            val marker = activity.find(title, start)?.takeIf { it.range.first == start } ?: return emptyList()
            if (!paired(title[marker.range.first], title[marker.range.last])) return emptyList()
            start = marker.range.last + 1
            while (start < title.length && title[start].isWhitespace()) start++
        }
        if (start >= title.length || title[start] !in setOf('[', '［')) return emptyList()
        val end = title.indexOfFirstFrom(start + 1) { it == ']' || it == '］' }
        if (end < 0 || end - start > MAX_PREFIX_LENGTH || !paired(title[start], title[end])) return emptyList()
        val rawCredits = title.substring(start + 1, end).trim()
        val personalStart = rawCredits.indexOfFirst { it == '(' || it == '（' }
        if (personalStart <= 0 || rawCredits.isEmpty() || !paired(rawCredits[personalStart], rawCredits.last())) {
            return emptyList()
        }
        val circle = rawCredits.substring(0, personalStart).trim()
        val creator = rawCredits.substring(personalStart + 1, rawCredits.lastIndex).trim()
        if (listOf(circle, creator).any { name ->
                name.isBlank() || name.any { it in brackets } ||
                    nonCreatorLabels.any { name.contains(it, ignoreCase = true) }
            } || creator.any { it in ambiguousNameCharacters }
        ) {
            return emptyList()
        }
        val actualTitle = title.substring(end + 1).trim()
        if (actualTitle.isBlank() || actualTitle.first() in brackets) return emptyList()

        return listOf(
            creator to CreatorMetadataField.TITLE_CREATOR,
            circle to CreatorMetadataField.TITLE_CIRCLE,
        ).groupBy({ it.first }, { it.second }).entries.mapIndexed { index, (name, fields) ->
            CreatorMention(
                displayName = name,
                normalizedName = CreatorNameNormalizer.normalize(name),
                role = CreatorRole.UNKNOWN,
                order = index.toLong(),
                evidence = fields.map { CreatorMentionEvidence(it, title, name, index) },
            )
        }
    }

    private fun paired(open: Char, close: Char): Boolean = when (open) {
        '(' -> close == ')'
        '（' -> close == '）'
        '[' -> close == ']'
        '［' -> close == '］'
        else -> false
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length.coerceAtMost(start + MAX_PREFIX_LENGTH + 1)) {
            if (predicate(this[index])) return index
        }
        return -1
    }
}
