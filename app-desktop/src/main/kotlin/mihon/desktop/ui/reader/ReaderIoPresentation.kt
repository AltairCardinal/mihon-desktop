package mihon.desktop.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import mihon.desktop.test.state.ReaderDrawPoint
import java.awt.geom.AffineTransform

internal data class ReaderBaseImageDraw(
    val capture: Long,
    val mountId: Long,
    val bitmapIdentity: Int,
    val sourceRect: List<Float>,
    val destinationRect: List<Float>,
    val rootQuad: List<ReaderDrawPoint>,
    val layoutQuad: List<ReaderDrawPoint>,
    val relativeTransform: List<Double>,
    val monotonicNanos: Long,
    val localQuad: List<ReaderDrawPoint>,
    val layoutWidth: Int,
    val layoutHeight: Int,
    val level: String = "base-image-draw-command",
    val coordinatePhase: String = "actual-painter-command",
    val composeFrameNanos: Long? = null,
    val lastActualCommand: Long? = null,
    val lastLayoutCallbackMonotonicNanos: Long? = null,
)

/** The original BitmapPainter remains the only producer of the observed bitmap command. */
internal class ReaderObservedBitmapPainter(
    private val image: ImageBitmap,
) : Painter() {
    private val originalPainter = BitmapPainter(image)
    override val intrinsicSize get() = originalPainter.intrinsicSize
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null
    var capture = 0L
    var mountId = 0L
    var coordinates: (() -> LayoutCoordinates?)? = null
    var onDraw: ((ReaderBaseImageDraw) -> Unit)? = null
    var lastLayoutCallbackMonotonicNanos = 0L
    private var lastActualCommand: ReaderBaseImageDraw? = null

    /** Reads current applied coordinates at a Compose clock callback, not that tick's future layout. */
    fun sampleAppliedGeometry(frameNanos: Long): ReaderBaseImageDraw? {
        val command = lastActualCommand ?: return null
        val layout = coordinates?.invoke()?.takeIf { it.isAttached } ?: return null
        if (capture == 0L || command.capture != capture || layout.size.width != command.layoutWidth ||
            layout.size.height != command.layoutHeight
        ) {
            return null
        }
        fun root(point: Offset) = layout.localToRoot(point).let { ReaderDrawPoint(it.x, it.y) }
        return command.copy(
            rootQuad = command.localQuad.map { root(Offset(it.x, it.y)) },
            layoutQuad = listOf(
                Offset.Zero,
                Offset(layout.size.width.toFloat(), 0f),
                Offset(layout.size.width.toFloat(), layout.size.height.toFloat()),
                Offset(0f, layout.size.height.toFloat()),
            ).map(::root),
            monotonicNanos = System.nanoTime(),
            level = "compose-frame-geometry",
            coordinatePhase = "last-applied-layout-at-compose-clock-callback",
            composeFrameNanos = frameNanos,
            lastActualCommand = command.monotonicNanos,
            lastLayoutCallbackMonotonicNanos = lastLayoutCallbackMonotonicNanos,
        )
    }

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }
    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val original = drawContext.canvas
        val native = original.nativeCanvas
        val layout = coordinates?.invoke()?.takeIf { it.isAttached }
        val layer = drawContext.graphicsLayer
        if (capture == 0L || onDraw == null || layout == null || layer == null ||
            layer.size != layout.size || layer.topLeft != IntOffset.Zero
        ) {
            with(originalPainter) { draw(size, alpha, colorFilter) }
            return
        }
        fun affine(values: FloatArray) = AffineTransform(
            values[0].toDouble(),
            values[3].toDouble(),
            values[1].toDouble(),
            values[4].toDouble(),
            values[2].toDouble(),
            values[5].toDouble(),
        )
        val observing = object : Canvas by original {
            override fun drawImageRect(
                image: ImageBitmap,
                srcOffset: IntOffset,
                srcSize: IntSize,
                dstOffset: IntOffset,
                dstSize: IntSize,
                paint: Paint,
            ) {
                original.drawImageRect(image, srcOffset, srcSize, dstOffset, dstSize, paint)
                val drawn = native.localToDeviceAsMatrix33.mat
                if (drawn[6] != 0f || drawn[7] != 0f || drawn[8] != 1f) return
                // Image appends a local clip layer. Its recording canvas and attached Image
                // coordinates share the same zero-origin viewport; no outer canvas is mixed in.
                val relative = affine(drawn)
                val rect = listOf(
                    dstOffset.x.toFloat(),
                    dstOffset.y.toFloat(),
                    (dstOffset.x + dstSize.width).toFloat(),
                    (dstOffset.y + dstSize.height).toFloat(),
                )
                val local = doubleArrayOf(
                    rect[0].toDouble(),
                    rect[1].toDouble(),
                    rect[2].toDouble(),
                    rect[1].toDouble(),
                    rect[2].toDouble(),
                    rect[3].toDouble(),
                    rect[0].toDouble(),
                    rect[3].toDouble(),
                )
                relative.transform(local, 0, local, 0, 4)
                fun root(point: Offset) = layout.localToRoot(point).let { ReaderDrawPoint(it.x, it.y) }
                val quad = (0..3).map { root(Offset(local[it * 2].toFloat(), local[it * 2 + 1].toFloat())) }
                val layoutQuad = listOf(
                    Offset.Zero,
                    Offset(layout.size.width.toFloat(), 0f),
                    Offset(layout.size.width.toFloat(), layout.size.height.toFloat()),
                    Offset(0f, layout.size.height.toFloat()),
                ).map(::root)
                val matrix = DoubleArray(6).also(relative::getMatrix).toList()
                val command = ReaderBaseImageDraw(
                    capture, mountId, System.identityHashCode(image),
                    listOf(
                        srcOffset.x.toFloat(),
                        srcOffset.y.toFloat(),
                        (srcOffset.x + srcSize.width).toFloat(),
                        (srcOffset.y + srcSize.height).toFloat(),
                    ),
                    rect, quad, layoutQuad, matrix, System.nanoTime(),
                    (0..3).map { ReaderDrawPoint(local[it * 2].toFloat(), local[it * 2 + 1].toFloat()) },
                    layout.size.width, layout.size.height,
                )
                lastActualCommand = command
                onDraw?.invoke(command)
            }
        }
        drawContext.canvas = observing
        try {
            with(originalPainter) { draw(size, alpha, colorFilter) }
        } finally {
            drawContext.canvas = original
        }
    }
}

internal fun Modifier.observeReaderPageDraw(
    acknowledgeDraw: (() -> Boolean)?,
    prepareDraw: (() -> Boolean)? = null,
): Modifier {
    return if (acknowledgeDraw != null) {
        drawWithContent {
            if (prepareDraw?.invoke() == false) return@drawWithContent
            drawContent()
            acknowledgeDraw()
        }
    } else {
        this
    }
}
