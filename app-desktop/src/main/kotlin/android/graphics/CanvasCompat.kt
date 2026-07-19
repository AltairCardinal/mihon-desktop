package android.graphics

import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect

class Paint

class Rect(
    @JvmField var left: Int,
    @JvmField var top: Int,
    @JvmField var right: Int,
    @JvmField var bottom: Int,
)

class Canvas(bitmap: Bitmap) {
    private val native = SkiaCanvas(bitmap.native())

    @Suppress("UNUSED_PARAMETER")
    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        val image = SkiaImage.makeFromBitmap(bitmap.native())
        try {
            native.drawImage(image, left, top)
        } finally {
            image.close()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun drawBitmap(bitmap: Bitmap, src: Rect, dst: Rect, paint: Paint?) {
        val image = SkiaImage.makeFromBitmap(bitmap.native())
        try {
            native.drawImageRect(
                image,
                SkiaRect.makeLTRB(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat()),
                SkiaRect.makeLTRB(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat()),
            )
        } finally {
            image.close()
        }
    }
}
