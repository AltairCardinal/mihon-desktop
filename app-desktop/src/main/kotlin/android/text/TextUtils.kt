package android.text

/**
 * Desktop stub for android.text.TextUtils.
 */
object TextUtils {
    @JvmStatic
    fun isEmpty(str: CharSequence?): Boolean = str.isNullOrEmpty()

    @JvmStatic
    fun join(delimiter: CharSequence, tokens: Iterable<*>): String =
        tokens.joinToString(delimiter)

    @JvmStatic
    fun isDigitsOnly(str: CharSequence): Boolean =
        str.isNotEmpty() && str.all { it.isDigit() }

    @JvmStatic
    fun equals(a: CharSequence?, b: CharSequence?): Boolean = a?.toString() == b?.toString()

    @JvmStatic
    fun htmlEncode(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    @JvmStatic
    fun getTrimmedLength(s: CharSequence): Int = s.toString().trim().length

    @JvmStatic
    fun substring(source: CharSequence, start: Int, end: Int): String =
        source.subSequence(start, end).toString()
}
