package android.graphics

import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import java.io.OutputStream

open class Bitmap internal constructor(
    internal val native: SkiaBitmap,
    val config: Config,
) {
    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE }
    enum class CompressFormat { JPEG, PNG, WEBP }

    val width: Int get() = native().width
    val height: Int get() = native().height

    private var recycled = false

    fun recycle() {
        if (!recycled) {
            recycled = true
            native.close()
        }
    }

    fun isRecycled(): Boolean = recycled
    fun hasAlpha(): Boolean = config != Config.RGB_565

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        val encodedFormat = when (format) {
            CompressFormat.JPEG -> EncodedImageFormat.JPEG
            CompressFormat.PNG -> EncodedImageFormat.PNG
            CompressFormat.WEBP -> EncodedImageFormat.WEBP
        }
        val image = SkiaImage.makeFromBitmap(native())
        return try {
            val data = image.encodeToData(encodedFormat, quality.coerceIn(0, 100)) ?: return false
            try {
                stream.write(data.bytes)
                true
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
    }

    internal fun native(): SkiaBitmap {
        check(!recycled) { "Cannot use a recycled bitmap" }
        return native
    }

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            require(width > 0 && height > 0) { "Bitmap dimensions must be positive" }
            val native = SkiaBitmap()
            check(native.allocN32Pixels(width, height)) { "Unable to allocate $width x $height bitmap" }
            native.erase(0x00000000)
            return Bitmap(native, config)
        }

        @JvmStatic
        fun createBitmap(src: Bitmap): Bitmap = Bitmap(src.native().makeClone(), src.config)

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            val output = createBitmap(dstWidth, dstHeight, src.config)
            val image = SkiaImage.makeFromBitmap(src.native())
            try {
                SkiaCanvas(output.native()).drawImageRect(
                    image,
                    SkiaRect.makeWH(src.width.toFloat(), src.height.toFloat()),
                    SkiaRect.makeWH(dstWidth.toFloat(), dstHeight.toFloat()),
                )
            } finally {
                image.close()
            }
            return output
        }
    }
}
