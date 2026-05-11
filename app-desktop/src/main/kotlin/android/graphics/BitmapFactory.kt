package android.graphics

import java.io.InputStream

/**
 * Desktop stub for android.graphics.BitmapFactory.
 * Returns a minimal placeholder Bitmap; actual decoding does not happen.
 */
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
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? {
        opts?.outWidth = 1
        opts?.outHeight = 1
        return if (opts?.inJustDecodeBounds == true) null
        else Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    @JvmStatic
    fun decodeStream(stream: InputStream): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @JvmStatic
    fun decodeStream(stream: InputStream, pad: Any?, opts: Options?): Bitmap? {
        opts?.outWidth = 1
        opts?.outHeight = 1
        return if (opts?.inJustDecodeBounds == true) null
        else Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    @JvmStatic
    fun decodeFile(pathName: String): Bitmap? =
        if (java.io.File(pathName).exists()) Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) else null

    @JvmStatic
    fun decodeFile(pathName: String, opts: Options?): Bitmap? = decodeFile(pathName)
}
