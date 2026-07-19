package android.text

import android.graphics.Canvas
import android.graphics.Typeface
import org.jetbrains.skia.Font
import kotlin.math.ceil

abstract class Layout {
    enum class Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }

    abstract val height: Int
    abstract fun draw(canvas: Canvas)
}

class StaticLayout(
    source: CharSequence,
    private val paint: TextPaint,
    private val width: Int,
    private val alignment: Layout.Alignment,
    spacingMultiplier: Float,
    spacingAddition: Float,
    @Suppress("UNUSED_PARAMETER") includePadding: Boolean,
) : Layout() {
    private val lines: List<String>
    private val lineHeight: Float

    init {
        Font(paint.getTypeface()?.native ?: Typeface.DEFAULT.native, paint.textSize).use { font ->
            lines = source.toString().split('\n').flatMap { wrap(it, font) }
            lineHeight = (font.spacing * spacingMultiplier + spacingAddition).coerceAtLeast(1f)
        }
    }

    override val height: Int = ceil(lines.size * lineHeight).toInt()

    override fun draw(canvas: Canvas) {
        Font(paint.getTypeface()?.native ?: Typeface.DEFAULT.native, paint.textSize).use { font ->
            val baselineOffset = -font.metrics.ascent
            lines.forEachIndexed { index, line ->
                val lineWidth = font.measureTextWidth(line)
                val x = when (alignment) {
                    Layout.Alignment.ALIGN_NORMAL -> 0f
                    Layout.Alignment.ALIGN_OPPOSITE -> width - lineWidth
                    Layout.Alignment.ALIGN_CENTER -> (width - lineWidth) / 2f
                }
                canvas.drawText(line, x, baselineOffset + index * lineHeight, paint)
            }
        }
    }

    private fun wrap(paragraph: String, font: Font): List<String> {
        if (paragraph.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        paragraph.split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && font.measureTextWidth(candidate) > width) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        lines += current
        return lines
    }
}
