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

class Canvas(private val target: Bitmap) {

    @Suppress("UNUSED_PARAMETER")
    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        val targetNative = target.native()
        val image = SkiaImage.makeFromBitmap(bitmap.native())
        image.use {
            SkiaCanvas(targetNative).use { canvas -> canvas.drawImage(it, left, top) }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun drawBitmap(bitmap: Bitmap, src: Rect, dst: Rect, paint: Paint?) {
        val targetNative = target.native()
        val image = SkiaImage.makeFromBitmap(bitmap.native())
        image.use {
            SkiaCanvas(targetNative).use { canvas ->
                canvas.drawImageRect(
                    it,
                    SkiaRect.makeLTRB(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat()),
                    SkiaRect.makeLTRB(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat()),
                )
            }
        }
    }
}
