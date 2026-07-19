package android.text

/**
 * Desktop compatibility adapter for the fixed HTML used by source extensions.
 * Produces plain text while preserving Android's legacy paragraph separators.
 */
object Html {
    const val FROM_HTML_MODE_LEGACY = 0
    const val FROM_HTML_MODE_COMPACT = 63

    @JvmStatic
    fun fromHtml(source: String): Spanned = fromHtml(source, FROM_HTML_MODE_LEGACY)

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun fromHtml(source: String, flags: Int): Spanned = PlainSpanned(parse(source))

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun fromHtml(source: String, flags: Int, imageGetter: Any?, tagHandler: Any?): CharSequence =
        fromHtml(source, flags)

    @JvmStatic
    fun toHtml(text: CharSequence): String = text.toString()

    private fun parse(source: String): String {
        val output = StringBuilder()
        var textStart = 0
        TAG.findAll(source).forEach { match ->
            output.append(decodeEntities(source.substring(textStart, match.range.first)))
            when (match.value.lowercase().replace(" ", "")) {
                "<br>", "<br/>", "</br>" -> output.append('\n')
                "</p>", "</div>" -> output.appendNewlines(2)
                "<p>", "<div>" -> if (output.isNotEmpty()) output.appendNewlines(2)
            }
            textStart = match.range.last + 1
        }
        output.append(decodeEntities(source.substring(textStart)))
        return output.toString()
    }

    private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val entity = match.groupValues[1]
        when (entity.lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            "copy" -> "©"
            else -> decodeNumericEntity(entity) ?: match.value
        }
    }

    private fun decodeNumericEntity(entity: String): String? {
        if (!entity.startsWith('#')) return null
        val hexadecimal = entity.startsWith("#x", ignoreCase = true)
        val digits = entity.substring(if (hexadecimal) 2 else 1)
        val codePoint = digits.toIntOrNull(if (hexadecimal) 16 else 10) ?: return null
        if (!Character.isValidCodePoint(codePoint)) return null
        return String(Character.toChars(codePoint))
    }

    private fun StringBuilder.appendNewlines(minimum: Int) {
        val existing = takeLastWhile { it == '\n' }.length
        repeat((minimum - existing).coerceAtLeast(0)) { append('\n') }
    }

    private class PlainSpanned(private val value: String) : Spanned {
        override val length: Int get() = value.length
        override fun get(index: Int): Char = value[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.subSequence(startIndex, endIndex)
        override fun toString(): String = value
    }

    private val TAG = Regex("<[^>]+>", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#(?:x[0-9a-f]+|[0-9]+)|[a-z]+);", RegexOption.IGNORE_CASE)
}
