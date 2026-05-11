package android.graphics

/**
 * Desktop stub for android.graphics.Color.
 */
object Color {
    const val BLACK = 0xFF000000.toInt()
    const val DKGRAY = 0xFF444444.toInt()
    const val GRAY = 0xFF888888.toInt()
    const val LTGRAY = 0xFFCCCCCC.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val CYAN = 0xFF00FFFF.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
    const val TRANSPARENT = 0x00000000

    private val namedColors = mapOf(
        "black" to BLACK, "darkgray" to DKGRAY, "gray" to GRAY,
        "lightgray" to LTGRAY, "white" to WHITE, "red" to RED,
        "green" to GREEN, "blue" to BLUE, "yellow" to YELLOW,
        "cyan" to CYAN, "magenta" to MAGENTA, "transparent" to TRANSPARENT,
    )

    @JvmStatic
    fun alpha(color: Int): Int = color.ushr(24) and 0xFF

    @JvmStatic
    fun red(color: Int): Int = color.ushr(16) and 0xFF

    @JvmStatic
    fun green(color: Int): Int = color.ushr(8) and 0xFF

    @JvmStatic
    fun blue(color: Int): Int = color and 0xFF

    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int = argb(255, red, green, blue)

    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha and 0xFF shl 24) or (red and 0xFF shl 16) or (green and 0xFF shl 8) or (blue and 0xFF)

    @JvmStatic
    fun parseColor(colorString: String): Int {
        val lower = colorString.lowercase().trim()
        namedColors[lower]?.let { return it }

        if (lower.startsWith("#")) {
            val hex = lower.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16)
                    val g = hex[1].digitToInt(16)
                    val b = hex[2].digitToInt(16)
                    argb(255, r * 17, g * 17, b * 17)
                }
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> throw IllegalArgumentException("Unknown color: $colorString")
            }
        }
        throw IllegalArgumentException("Unknown color: $colorString")
    }
}
