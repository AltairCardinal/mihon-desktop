package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Forwards the draw operations used by SSIV 66e0db195d to its original canvas.
 * The bitmap gate runs before the real image command; no image is copied or rendered twice.
 */
internal class ReaderDecodedPageCanvas(
    target: Canvas,
    private val beforeBitmap: (Bitmap, Matrix) -> Boolean,
) : Canvas() {
    private val targetAfterConstruction: Canvas? = target
    private val target: Canvas get() = checkNotNull(targetAfterConstruction)

    var drewBitmap = false
        private set

    override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        if (!beforeBitmap(bitmap, matrix)) return
        target.drawBitmap(bitmap, matrix, paint)
        drewBitmap = true
    }

    override fun getWidth(): Int = target.width

    override fun getHeight(): Int = target.height

    override fun getMaximumBitmapWidth(): Int = target.maximumBitmapWidth

    override fun getMaximumBitmapHeight(): Int = target.maximumBitmapHeight

    // Canvas calls this virtual getter from its constructor, before Kotlin fields are assigned.
    override fun isHardwareAccelerated(): Boolean =
        targetAfterConstruction?.isHardwareAccelerated ?: super.isHardwareAccelerated()

    override fun drawRect(rect: Rect, paint: Paint) = target.drawRect(rect, paint)

    override fun drawRect(rect: RectF, paint: Paint) = target.drawRect(rect, paint)

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        target.drawRect(left, top, right, bottom, paint)

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) =
        target.drawLine(startX, startY, stopX, stopY, paint)

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) =
        target.drawCircle(cx, cy, radius, paint)

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) = target.drawText(text, x, y, paint)
}
