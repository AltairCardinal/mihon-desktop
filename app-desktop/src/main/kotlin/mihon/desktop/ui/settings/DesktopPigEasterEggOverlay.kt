package mihon.desktop.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import mihon.desktop.easteregg.loadPig2EasterEggImage
import mihon.desktop.easteregg.loadPigEasterEggImage
import mihon.domain.easteregg.PigEasterEggController
import mihon.domain.easteregg.PigEasterEggDefaults
import mihon.domain.easteregg.PigEasterEggSession
import mihon.domain.easteregg.PigPlacement
import mihon.domain.easteregg.PigPlacementPlanner
import kotlin.math.roundToInt

internal const val DESKTOP_PIG_EASTER_EGG_TAG = "desktop_pig_easter_egg"
internal const val DESKTOP_PIG_EASTER_EGG_GIANT_TAG = "desktop_pig_easter_egg_giant"
internal const val DESKTOP_PIG_EASTER_EGG_SNOUT_TAG = "desktop_pig_easter_egg_snout"
internal const val DESKTOP_PIG_EASTER_EGG_STAR_TAG = "desktop_pig_easter_egg_star"

internal data class SnoutLingerState(
    val id: Long,
    val xPx: Float,
    val yPx: Float,
    val rotation: Float,
    val startMillis: Long,
)

internal data class StarParticleState(
    val id: Long,
    val angle: Double,
    val maxDist: Float,
    val rot: Float,
    val startMillis: Long,
)

internal object PigAppearanceSpec {
    const val ENTRANCE_MILLIS = 100
    const val EXIT_MILLIS = 160
    const val START_SCALE = 0.72f
    const val GIANT_POP_MILLIS = 200

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

    fun holdMillis(lifetimeMillis: Long): Long = (lifetimeMillis - EXIT_MILLIS).coerceAtLeast(0L)
}

@Composable
internal fun DesktopPigEasterEggOverlay(
    controller: PigEasterEggController,
    modifier: Modifier = Modifier,
    initialLifetimeMillis: Long = PigEasterEggDefaults.INITIAL_LIFETIME_MILLIS,
    extensionPerClickMillis: Long = PigEasterEggDefaults.EXTENSION_PER_CLICK_MILLIS,
    giantLifetimeMillis: Long = PigEasterEggDefaults.GIANT_PIG_LIFETIME_MILLIS,
    warningBeforeEndMillis: Long = PigEasterEggDefaults.WARNING_BEFORE_END_MILLIS,
) {
    val trigger by controller.triggerCount.collectAsState()
    var session by remember { mutableStateOf<PigEasterEggSession?>(null) }
    val clickedPigs = remember { mutableStateListOf<Int>() }
    val activeSnouts = remember { mutableStateListOf<SnoutLingerState>() }
    val activeStars = remember { mutableStateListOf<StarParticleState>() }
    var visible by remember { mutableStateOf(false) }
    var isEndingSoon by remember { mutableStateOf(false) }
    var isGiantPigPhase by remember { mutableStateOf(false) }

    val smallAppearance = remember { Animatable(0f) }
    val giantAppearance = remember { Animatable(0f) }

    val infiniteTransition = rememberInfiniteTransition()
    val loopScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (PigEasterEggDefaults.LOOP_CYCLE_MILLIS / 2).toInt(),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    var particleTick by remember { mutableStateOf(0L) }
    LaunchedEffect(activeSnouts.isNotEmpty(), activeStars.isNotEmpty()) {
        if (activeSnouts.isNotEmpty() || activeStars.isNotEmpty()) {
            while (activeSnouts.isNotEmpty() || activeStars.isNotEmpty()) {
                delay(16L)
                particleTick = System.currentTimeMillis()
            }
        }
    }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        val current = PigEasterEggSession(
            startTime = System.currentTimeMillis(),
            initialLifetimeMillis = initialLifetimeMillis,
            extensionPerClickMillis = extensionPerClickMillis,
            warningBeforeEndMillis = warningBeforeEndMillis,
            giantPigLifetimeMillis = giantLifetimeMillis,
        )
        session = current
        clickedPigs.clear()
        activeSnouts.clear()
        activeStars.clear()
        isEndingSoon = false
        isGiantPigPhase = false
        visible = true

        while (true) {
            val now = System.currentTimeMillis()
            if (current.isAllPigsClicked) {
                visible = false
                isEndingSoon = false
                isGiantPigPhase = true
                break
            }
            val remaining = current.remainingMillis(now)
            if (remaining <= 0L) {
                visible = false
                isEndingSoon = false
                break
            }
            isEndingSoon = current.isEndingSoon(now)
            delay(minOf(remaining, 40L).coerceAtLeast(10L))
        }

        if (isGiantPigPhase) {
            giantAppearance.snapTo(0.01f)
            giantAppearance.animateTo(1f, tween(PigAppearanceSpec.GIANT_POP_MILLIS, easing = FastOutSlowInEasing))
            delay(giantLifetimeMillis)
            giantAppearance.animateTo(0f, tween(150))
            isGiantPigPhase = false
            session = null
            activeStars.clear()
        }
    }

    LaunchedEffect(isGiantPigPhase) {
        if (!isGiantPigPhase) return@LaunchedEffect
        delay(40L)
        val stars = (0 until 18).map { i ->
            val angle = (i.toDouble() / 18.0) * 2.0 * Math.PI + (kotlin.random.Random.nextDouble() - 0.5) * 0.15
            val contourFactor = 0.98f + kotlin.random.Random.nextFloat() * 0.08f
            val rot = (kotlin.random.Random.nextFloat() - 0.5f) * 360f
            StarParticleState(
                id = System.nanoTime() + i,
                angle = angle,
                maxDist = contourFactor,
                rot = rot,
                startMillis = System.currentTimeMillis(),
            )
        }
        activeStars.addAll(stars)
    }

    LaunchedEffect(visible) {
        smallAppearance.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = if (visible) PigAppearanceSpec.entrance else PigAppearanceSpec.exit,
        )
    }

    if (!visible && smallAppearance.value <= 0f && !isGiantPigPhase && giantAppearance.value <= 0f && activeSnouts.isEmpty() && activeStars.isEmpty()) return

    val pig2Painter = remember { BitmapPainter(loadPig2EasterEggImage()) }
    val giantPigPainter = remember { BitmapPainter(loadPigEasterEggImage()) }
    val snoutPainter = remember { BitmapPainter(mihon.desktop.easteregg.loadSnoutParticleImage()) }
    val starPainter = remember { BitmapPainter(mihon.desktop.easteregg.loadGoldStarImage()) }
    val planner = remember { PigPlacementPlanner() }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val now = System.currentTimeMillis().coerceAtLeast(particleTick)

        // 渲染小猪原地留鼻变大淡出动效
        activeSnouts.removeAll { now - it.startMillis >= 380L }
        val pigPx = remember(widthPx, heightPx) { pigSizePx(widthPx, heightPx) }
        val snoutBaseSizeDp = with(density) { (pigPx * 0.35f).toDp() }
        val snoutBaseSizePx = pigPx * 0.35f
        activeSnouts.forEach { snout ->
            val elapsed = now - snout.startMillis
            val progress = (elapsed / 380f).coerceIn(0f, 1f)
            val scale = 1.0f + 1.0f * progress
            val alpha = (1.0f - progress).coerceIn(0f, 1f)
            val offsetX = (snout.xPx - snoutBaseSizePx / 2f).roundToInt()
            val offsetY = (snout.yPx - snoutBaseSizePx / 2f).roundToInt()

            Image(
                painter = snoutPainter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(snoutBaseSizeDp)
                    .offset { IntOffset(offsetX, offsetY) }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        rotationZ = snout.rotation
                    }
                    .testTag(DESKTOP_PIG_EASTER_EGG_SNOUT_TAG),
            )
        }

        if (isGiantPigPhase || giantAppearance.value > 0f) {
            val giantScale = giantAppearance.value
            val giantEdge = minOf(widthPx, heightPx) * PigEasterEggDefaults.GIANT_PIG_SCREEN_FRACTION
            val giantSizeDp = with(density) { giantEdge.toDp() }
            val finalScale = giantScale
            val finalRotation = 0f

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = giantPigPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(giantSizeDp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin.Center
                            scaleX = finalScale
                            scaleY = finalScale
                            alpha = giantScale.coerceIn(0f, 1f)
                            rotationZ = finalRotation
                        }
                        .testTag(DESKTOP_PIG_EASTER_EGG_GIANT_TAG),
                )
            }

            // 渲染终极大猪爆散五角星光环（紧随大猪轮廓边缘与实时放大）
            val cx = widthPx / 2f
            val cy = heightPx / 2f
            val contourRx = giantEdge * 0.52f
            val contourRy = giantEdge * 0.46f
            activeStars.removeAll { now - it.startMillis >= 650L }
            val starBaseSizeDp = with(density) { (giantEdge * 0.08f).coerceIn(24f, 48f).toDp() }
            val starBaseSizePx = with(density) { starBaseSizeDp.toPx() }
            activeStars.forEach { star ->
                val elapsed = now - star.startMillis
                val progress = (elapsed / 650f).coerceIn(0f, 1f)
                val spread = 1.0f + 0.14f * progress
                val currentScale = (giantScale.coerceAtLeast(0.1f)) * star.maxDist * spread
                val dx = kotlin.math.cos(star.angle).toFloat() * contourRx * currentScale
                val dy = kotlin.math.sin(star.angle).toFloat() * contourRy * currentScale
                val starScale = if (progress < 0.18f) {
                    0.3f + (1.2f - 0.3f) * (progress / 0.18f)
                } else {
                    1.2f - (1.2f - 0.5f) * ((progress - 0.18f) / 0.82f)
                }
                val starAlpha = when {
                    progress < 0.15f -> progress / 0.15f
                    progress < 0.65f -> 1f
                    else -> 1f - (progress - 0.65f) / 0.35f
                }.coerceIn(0f, 1f)
                val offsetX = (cx + dx - starBaseSizePx / 2f).roundToInt()
                val offsetY = (cy + dy - starBaseSizePx / 2f).roundToInt()

                Image(
                    painter = starPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(starBaseSizeDp)
                        .offset { IntOffset(offsetX, offsetY) }
                        .graphicsLayer {
                            transformOrigin = TransformOrigin.Center
                            scaleX = starScale
                            scaleY = starScale
                            this.alpha = starAlpha
                            rotationZ = star.rot * progress
                        }
                        .testTag(DESKTOP_PIG_EASTER_EGG_STAR_TAG),
                )
            }
        } else if (visible || smallAppearance.value > 0f) {
            val pigSize = with(density) { pigPx.toDp() }
            val placements = remember(trigger, pigPx) { planner.plan(PigEasterEggDefaults.PIG_COUNT) }

            placements.forEachIndexed { index, placement ->
                if (index !in clickedPigs) {
                    val position = remember(placement, widthPx, heightPx, pigPx) {
                        IntOffset(placement.offsetX(widthPx, pigPx), placement.offsetY(heightPx, pigPx))
                    }
                    Image(
                        painter = pig2Painter,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(pigSize)
                            .offset { position }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                session?.let { s ->
                                    val nowClick = System.currentTimeMillis()
                                    if (s.clickPig(index, nowClick)) {
                                        clickedPigs.add(index)
                                        // 记录原地猪鼻子粒子
                                        val centerX = placement.centerXFraction * widthPx
                                        val centerY = placement.centerYFraction * heightPx
                                        activeSnouts.add(
                                            SnoutLingerState(
                                                id = System.nanoTime(),
                                                xPx = centerX,
                                                yPx = centerY,
                                                rotation = placement.rotationDegrees,
                                                startMillis = nowClick,
                                            ),
                                        )
                                    }
                                }
                            }
                            .graphicsLayer {
                                val progress = smallAppearance.value
                                val loop = if (isEndingSoon) loopScale else 1f
                                val scale = (PigAppearanceSpec.scaleFor(progress) * progress) * loop
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
