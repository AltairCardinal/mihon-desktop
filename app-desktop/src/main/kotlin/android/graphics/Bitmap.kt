package android.graphics

/**
 * Desktop stub for android.graphics.Bitmap.
 * Extensions that decode images via BitmapFactory or manipulate Bitmap
 * data can compile; actual pixel operations are no-ops on desktop.
 *
 * Note: width/height/config are Kotlin properties. Kotlin generates getWidth()/getHeight()/getConfig()
 * automatically, so we do not define them explicitly (would cause JVM signature clash).
 */
open class Bitmap private constructor(
    val width: Int,
    val height: Int,
    val config: Config,
) {
    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE }

    fun recycle() {}
    fun isRecycled(): Boolean = false
    fun hasAlpha(): Boolean = config == Config.ARGB_8888 || config == Config.RGBA_F16

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            Bitmap(width, height, config)

        @JvmStatic
        fun createBitmap(src: Bitmap): Bitmap = Bitmap(src.width, src.height, src.config)

        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap =
            Bitmap(dstWidth, dstHeight, src.config)
    }
}
