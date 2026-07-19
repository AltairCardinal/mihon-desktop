package android.graphics

import android.text.TextPaint
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect

open class Paint(flags: Int = 0) {
    var color: Int = 0xFF000000.toInt()
    var textSize: Float = 16f
    var isAntiAlias: Boolean = flags and ANTI_ALIAS_FLAG != 0
    private var currentTypeface: Typeface? = null

    fun getTypeface(): Typeface? = currentTypeface
    fun setTypeface(typeface: Typeface?): Typeface? {
        val previous = currentTypeface
        currentTypeface = typeface
        return previous
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
}

class Rect(
    @JvmField var left: Int,
    @JvmField var top: Int,
    @JvmField var right: Int,
    @JvmField var bottom: Int,
)

class Canvas(private val target: Bitmap) {
    private val savedTranslations = ArrayDeque<Pair<Float, Float>>()
    private var translationX = 0f
    private var translationY = 0f

    init {
        target.native()
    }

    fun drawColor(color: Int) = withCanvas { it.clear(color) }

    fun save(): Int {
        target.native()
        savedTranslations.addLast(translationX to translationY)
        return savedTranslations.size
    }

    fun translate(dx: Float, dy: Float) {
        target.native()
        translationX += dx
        translationY += dy
    }

    fun restore() {
        target.native()
        val restored = checkNotNull(savedTranslations.removeLastOrNull()) { "Underflow in restore" }
        translationX = restored.first
        translationY = restored.second
    }

    internal fun drawText(text: String, x: Float, baseline: Float, paint: TextPaint) = withCanvas { canvas ->
        SkiaFont(paint.getTypeface()?.native ?: Typeface.DEFAULT.native, paint.textSize).use { font ->
            SkiaPaint().use { nativePaint ->
                nativePaint.color = paint.color
                nativePaint.isAntiAlias = paint.isAntiAlias
                canvas.drawString(text, x + translationX, baseline + translationY, font, nativePaint)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        val targetNative = target.native()
        val image = SkiaImage.makeFromBitmap(bitmap.native())
        image.use {
            SkiaCanvas(targetNative).use { canvas -> canvas.drawImage(it, left + translationX, top + translationY) }
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
                    SkiaRect.makeLTRB(
                        dst.left + translationX,
                        dst.top + translationY,
                        dst.right + translationX,
                        dst.bottom + translationY,
                    ),
                )
            }
        }
    }

    private inline fun withCanvas(block: (SkiaCanvas) -> Unit) {
        SkiaCanvas(target.native()).use(block)
    }
}
