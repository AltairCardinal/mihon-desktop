package mihon.desktop.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import mihon.desktop.easteregg.DesktopPigEasterEggController
import mihon.desktop.easteregg.PigEasterEggDefaults
import mihon.desktop.easteregg.PigPlacement
import mihon.desktop.easteregg.PigPlacementPlanner
import mihon.desktop.easteregg.loadPigEasterEggImage
import kotlin.math.roundToInt

internal const val DESKTOP_PIG_EASTER_EGG_TAG = "desktop_pig_easter_egg"

/**
 * The feel of the parade: the pigs pop into place with an accelerating zoom, then linger and fade
 * out gently. It lives apart from the composable so the timing and easing stay pinned by tests.
 */
internal object PigAppearanceSpec {
    private const val ENTRANCE_MILLIS = 100
    private const val EXIT_MILLIS = 160
    private const val START_SCALE = 0.72f

    /** Accelerating: the zoom creeps at first and snaps to full size just under a tenth of a second. */
    private val Accelerating = object : Easing {
        override fun transform(fraction: Float): Float = fraction * fraction
    }

    private val Settling = object : Easing {
        override fun transform(fraction: Float): Float = 1f - (1f - fraction) * (1f - fraction)
    }

    val entrance: TweenSpec<Float> = tween(durationMillis = ENTRANCE_MILLIS, easing = Accelerating)

    val exit: TweenSpec<Float> = tween(durationMillis = EXIT_MILLIS, easing = Settling)

    fun scaleFor(progress: Float): Float = START_SCALE + (1f - START_SCALE) * progress

    fun alphaFor(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Keeps the entrance, the parade and the fade inside the lifetime the caller asked for. */
    fun holdMillis(lifetimeMillis: Long): Long = (lifetimeMillis - EXIT_MILLIS).coerceAtLeast(0L)
}

/**
 * Draws the pig parade over the whole window for a fixed lifetime. It never takes pointer or
 * focus input, so the page underneath keeps working while the pigs are up.
 */
@Composable
internal fun DesktopPigEasterEggOverlay(
    controller: DesktopPigEasterEggController,
    modifier: Modifier = Modifier,
    lifetimeMillis: Long = PigEasterEggDefaults.LIFETIME_MILLIS,
) {
    val trigger by controller.triggerCount.collectAsState()
    var visible by remember { mutableStateOf(false) }
    val appearance = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        visible = true
        delay(PigAppearanceSpec.holdMillis(lifetimeMillis))
        visible = false
    }
    LaunchedEffect(visible) {
        appearance.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = if (visible) PigAppearanceSpec.entrance else PigAppearanceSpec.exit,
        )
    }
    // The pigs stay mounted while they fade out, so the lifetime is one uninterrupted parade.
    if (!visible && appearance.value <= 0f) return

    val painter = remember { BitmapPainter(loadPigEasterEggImage()) }
    val planner = remember { PigPlacementPlanner() }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val pigPx = remember(widthPx, heightPx) { pigSizePx(widthPx, heightPx) }
        val pigSize = with(density) { pigPx.toDp() }
        val placements = remember(trigger, pigPx) { planner.plan() }
        placements.forEach { placement ->
            val position = remember(placement, widthPx, heightPx, pigPx) {
                IntOffset(placement.offsetX(widthPx, pigPx), placement.offsetY(heightPx, pigPx))
            }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(pigSize)
                    .offset { position }
                    .graphicsLayer {
                        val progress = appearance.value
                        val scale = PigAppearanceSpec.scaleFor(progress)
                        alpha = PigAppearanceSpec.alphaFor(progress)
                        scaleX = scale
                        scaleY = scale
                        rotationZ = placement.rotationDegrees
                    }
                    .testTag(DESKTOP_PIG_EASTER_EGG_TAG),
            )
        }
    }
}

private fun pigSizePx(widthPx: Float, heightPx: Float): Float {
    val edge = minOf(widthPx, heightPx)
    return (edge * PigEasterEggDefaults.WINDOW_FRACTION)
        .coerceIn(PigEasterEggDefaults.MIN_PIG_SIZE_PX, PigEasterEggDefaults.MAX_PIG_SIZE_PX)
        .coerceAtMost(edge)
}

private fun PigPlacement.offsetX(widthPx: Float, pigSize: Float): Int =
    ((centerXFraction * widthPx) - pigSize / 2f)
        .coerceIn(0f, (widthPx - pigSize).coerceAtLeast(0f))
        .roundToInt()

private fun PigPlacement.offsetY(heightPx: Float, pigSize: Float): Int =
    ((centerYFraction * heightPx) - pigSize / 2f)
        .coerceIn(0f, (heightPx - pigSize).coerceAtLeast(0f))
        .roundToInt()

