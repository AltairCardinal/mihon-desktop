package android.graphics

import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Image as SkiaImage
import java.io.InputStream

object BitmapFactory {

    class Options {
        var inJustDecodeBounds: Boolean = false
        var inSampleSize: Int = 1
        var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
        var outWidth: Int = 0
        var outHeight: Int = 0
        var outMimeType: String? = null
    }

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? =
        decode(data.copyOfRange(offset, offset + length), null)

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? {
        return decode(data.copyOfRange(offset, offset + length), opts)
    }

    @JvmStatic
    fun decodeStream(stream: InputStream): Bitmap? = decode(stream.readBytes(), null)

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun decodeStream(stream: InputStream, pad: Any?, opts: Options?): Bitmap? = decode(stream.readBytes(), opts)

    @JvmStatic
    fun decodeFile(pathName: String): Bitmap? = java.io.File(pathName).takeIf { it.isFile }?.readBytes()?.let {
        decode(it, null)
    }

    @JvmStatic
    fun decodeFile(pathName: String, opts: Options?): Bitmap? = java.io.File(pathName).takeIf { it.isFile }?.readBytes()
        ?.let { decode(it, opts) }

    private fun decode(bytes: ByteArray, opts: Options?): Bitmap? = try {
        val image = SkiaImage.makeFromEncoded(bytes)
        try {
            opts?.outWidth = image.width
            opts?.outHeight = image.height
            if (opts?.inJustDecodeBounds == true) return null
            val native = SkiaBitmap()
            if (!native.allocN32Pixels(image.width, image.height)) {
                native.close()
                return null
            }
            SkiaCanvas(native).drawImage(image, 0f, 0f)
            Bitmap(native, opts?.inPreferredConfig ?: Bitmap.Config.ARGB_8888)
        } finally {
            image.close()
        }
    } catch (_: Exception) {
        opts?.outWidth = -1
        opts?.outHeight = -1
        null
    }
}
