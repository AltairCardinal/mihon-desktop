package mihon.desktop.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.reader.ZoomState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.IOException
import kotlin.math.roundToInt

internal val LocalReaderBlankPaperLogo = staticCompositionLocalOf<ImageBitmap?> { null }
internal val LocalReaderBlankPaperLogoLoader = staticCompositionLocalOf<suspend () -> ReaderBlankPaperLogo?> {
    { loadReaderBlankPaperLogo() }
}

internal class ReaderBlankPaperLogo(private val native: Bitmap) : AutoCloseable {
    val bitmap: ImageBitmap = native.asComposeImageBitmap()
    override fun close() = native.close()
}

internal fun loadReaderBlankPaperLogo(): ReaderBlankPaperLogo? {
    val bytes = ReaderBlankPaperResource::class.java.getResourceAsStream("/icons/reader-blank-logo.png")
        ?.use { it.readBytes() }
    return bytes?.let { encoded ->
        Image.makeFromEncoded(encoded).use { ReaderBlankPaperLogo(Bitmap.makeFromImage(it)) }
    }
}

/** Starts at reader entry; neither the paper nor the camera depends on this decorative result. */
@Composable
internal fun rememberReaderBlankPaperLogo(): ImageBitmap? {
    val loader = LocalReaderBlankPaperLogoLoader.current
    val logo by produceState<ImageBitmap?>(null, loader) {
        var loaded: ReaderBlankPaperLogo? = null
        try {
            withContext(Dispatchers.IO) {
                loaded = try {
                    loader()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            }
            value = loaded?.bitmap
            awaitDispose { }
        } finally {
            loaded?.close()
        }
    }
    return logo
}

private object ReaderBlankPaperResource

/** Mirrors the partner's actual fitted/cropped paper rectangle about the display-unit spine. */
internal fun readerBlankPaperBounds(viewport: IntSize, partner: ReaderRegionDrawGeometry?, left: Boolean): Rect {
    if (partner != null) {
        val bounds = partner.panBounds().content
        return Rect(viewport.width - bounds.right, bounds.top, viewport.width - bounds.left, bounds.bottom)
    }
    val width = viewport.width / 2f
    val height = minOf(viewport.height.toFloat(), width * 1.5f)
    val paperWidth = height / 1.5f
    val spine = viewport.width / 2f
    return Rect(
        if (left) spine - paperWidth else spine,
        (viewport.height - height) / 2f,
        if (left) spine else spine + paperWidth,
        (viewport.height + height) / 2f,
    )
}

@Composable
internal fun ReaderBlankPaper(
    modifier: Modifier,
    paperBounds: Rect? = null,
    viewport: ReaderImageViewport? = null,
    zoom: ZoomState = ZoomState(),
    left: Boolean = true,
) {
    val logo = LocalReaderBlankPaperLogo.current
    Canvas(modifier.readerImageTransform(zoom, viewport)) {
        val origin = viewport?.slotOrigin ?: IntOffset.Zero
        val paper = paperBounds?.translate(Offset(-origin.x.toFloat(), -origin.y.toFloat())) ?: run {
            val height = minOf(size.height, size.width * 1.5f)
            val width = height / 1.5f
            Rect(
                if (left) size.width - width else 0f,
                (size.height - height) / 2f,
                if (left) size.width else width,
                (size.height + height) / 2f,
            )
        }
        clipRect(paper.left, paper.top, paper.right, paper.bottom) {
            drawRect(Color.White, paper.topLeft, paper.size)
            if (logo != null) {
                val diameter = minOf(paper.width, paper.height * 0.62f) * (256f / 240f)
                val center = Offset(
                    paper.left + paper.width * if (left) 0.28f else 0.72f,
                    paper.top + paper.height * 0.84f,
                )
                rotate(if (left) 25f else -25f, center) {
                    drawImage(
                        logo,
                        dstOffset = IntOffset(
                            (center.x - diameter / 2f).roundToInt(),
                            (center.y - diameter / 2f).roundToInt(),
                        ),
                        dstSize = IntSize(diameter.roundToInt(), diameter.roundToInt()),
                    )
                }
            }
        }
    }
}
