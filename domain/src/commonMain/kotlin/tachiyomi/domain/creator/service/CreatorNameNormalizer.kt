package tachiyomi.domain.creator.service

object CreatorNameNormalizer {

    private val separators = Regex("""\s*(?:,|/|;|、|，|＆|&|\+)\s*""")
    private val whitespace = Regex("""\s+""")
    private val punctuation = Regex("""[\p{Punct}&&[^-]]+""")

    fun normalize(name: String): String {
        return foldWidth(name)
            .trim()
            .lowercase()
            .replace(punctuation, " ")
            .replace(whitespace, " ")
            .trim()
    }

    fun splitNames(value: String?): List<String> {
        return tokenizeNames(value)
            .map(NameToken::rawToken)
            .distinctBy(::normalize)
    }

    fun tokenizeNames(value: String?): List<NameToken> {
        if (value.isNullOrBlank()) return emptyList()
        return value
            .split(separators)
            .mapIndexedNotNull { index, token ->
                token.trim().takeIf(String::isNotBlank)?.let { NameToken(it, index) }
            }
    }

    private fun foldWidth(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '\u3000' -> ' '
                    in '\uFF01'..'\uFF5E' -> (character.code - FULL_WIDTH_ASCII_OFFSET).toChar()
                    else -> character
                },
            )
        }
    }

    data class NameToken(
        val rawToken: String,
        val tokenIndex: Int,
    )

    private const val FULL_WIDTH_ASCII_OFFSET = 0xFEE0
}
