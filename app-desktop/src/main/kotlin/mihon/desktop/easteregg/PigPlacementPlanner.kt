package mihon.desktop.easteregg

import kotlin.math.hypot
import kotlin.random.Random

/** Placement relative to the window, so one plan works at any window size. */
internal data class PigPlacement(
    val centerXFraction: Float,
    val centerYFraction: Float,
    val rotationDegrees: Float,
)

internal object PigEasterEggDefaults {
    const val PIG_COUNT = 7
    const val LIFETIME_MILLIS = 2_000L
    const val MAX_ROTATION_DEGREES = 45f
    const val WINDOW_FRACTION = 0.18f
    const val MIN_PIG_SIZE_PX = 64f
    const val MAX_PIG_SIZE_PX = 260f
}

/**
 * Spreads the pigs with best candidate sampling: every pig takes the sampled position that is
 * furthest away from the pigs already placed. Plain uniform sampling regularly stacks two pigs
 * in the same corner, which is exactly what "as even as possible" has to avoid.
 */
internal class PigPlacementPlanner(
    private val random: Random = Random.Default,
    private val candidatesPerPig: Int = DEFAULT_CANDIDATES,
) {
    fun plan(count: Int = PigEasterEggDefaults.PIG_COUNT): List<PigPlacement> {
        require(count > 0) { "A pig parade needs at least one pig" }
        require(candidatesPerPig > 0) { "A pig needs at least one candidate position" }
        val points = ArrayList<Pair<Float, Float>>(count)
        repeat(count) { index ->
            points += if (index == 0) candidate() else bestCandidate(points)
        }
        return points.map { (x, y) -> PigPlacement(x, y, rotation()) }
    }

    private fun bestCandidate(placed: List<Pair<Float, Float>>): Pair<Float, Float> {
        var best = candidate()
        var bestDistance = closest(best, placed)
        repeat(candidatesPerPig - 1) {
            val candidate = candidate()
            val distance = closest(candidate, placed)
            if (distance > bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private fun closest(point: Pair<Float, Float>, placed: List<Pair<Float, Float>>): Float =
        placed.minOf { hypot(point.first - it.first, point.second - it.second) }

    private fun candidate(): Pair<Float, Float> = random.nextFloat() to random.nextFloat()

    private fun rotation(): Float = random.nextFloat() * 2 * PigEasterEggDefaults.MAX_ROTATION_DEGREES -
        PigEasterEggDefaults.MAX_ROTATION_DEGREES

    private companion object {
        const val DEFAULT_CANDIDATES = 24
    }
}
