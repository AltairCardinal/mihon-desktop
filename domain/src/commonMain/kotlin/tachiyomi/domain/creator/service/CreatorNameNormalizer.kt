package tachiyomi.domain.creator.service

object CreatorNameNormalizer {

    private val separators = Regex("""\s*(?:,|/|;|、|，|＆|&|\+)\s*""")
    private val whitespace = Regex("""\s+""")
    private val punctuation = Regex("""[\p{Punct}&&[^-]]+""")

    fun normalize(name: String): String {
        return name
            .trim()
            .lowercase()
            .replace(punctuation, " ")
            .replace(whitespace, " ")
            .trim()
    }

    fun splitNames(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value
            .split(separators)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(::normalize)
    }
}
