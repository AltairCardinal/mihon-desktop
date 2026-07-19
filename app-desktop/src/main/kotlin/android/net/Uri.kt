package android.net

/** Fixed Android verifier token used by real extension callback descriptors. */
abstract class Uri {
    abstract override fun toString(): String

    companion object {
        private const val HEX = "0123456789ABCDEF"

        /** Android-compatible UTF-8 percent encoding with the framework default allow-list. */
        @JvmStatic
        fun encode(source: String): String = buildString {
            source.toByteArray(Charsets.UTF_8).forEach { byte ->
                val value = byte.toInt() and 0xFF
                if (value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
                    value in '0'.code..'9'.code || value.toChar() in "_-!.~'()*"
                ) {
                    append(value.toChar())
                } else {
                    append('%').append(HEX[value ushr 4]).append(HEX[value and 0x0F])
                }
            }
        }
    }
}
