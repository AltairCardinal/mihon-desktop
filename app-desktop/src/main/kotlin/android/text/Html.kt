package android.text

/**
 * Desktop stub for android.text.Html.
 * Strips HTML tags to produce plain text. Not a full HTML parser.
 */
object Html {
    const val FROM_HTML_MODE_LEGACY = 0
    const val FROM_HTML_MODE_COMPACT = 63

    @JvmStatic
    fun fromHtml(source: String, flags: Int): CharSequence =
        source.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<p[^>]*>"), "\n")
            .replace("</p>", "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun fromHtml(source: String, flags: Int, imageGetter: Any?, tagHandler: Any?): CharSequence =
        fromHtml(source, flags)

    @JvmStatic
    fun toHtml(text: CharSequence): String = text.toString()
}
