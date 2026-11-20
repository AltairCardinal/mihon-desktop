package tachiyomi.presentation.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

enum class DetailActionPlatform { Mobile, Desktop }

/** The rail uses physical pixel distances and shares its bounds between paging and drag sorting. */
class DetailActionMotionState {
    private data class Metrics(val slot: Float = 1f, val width: Float = 1f, val count: Int = 1)

    private var metrics by mutableStateOf(Metrics())
    var offset by mutableFloatStateOf(0f)
        private set
    var boundaryPullProgress by mutableFloatStateOf(0f)
        private set

    private val maximum get() = (metrics.count * metrics.slot - metrics.width).coerceAtLeast(0f)
    val canPageBack get() = offset > EDGE_TOLERANCE
    val canPageForward get() = offset < maximum - EDGE_TOLERANCE
    val pageCount get() = 1 + ceil((maximum - EDGE_TOLERANCE).coerceAtLeast(0f) / metrics.width).toInt()
    val currentPage get() = if (canPageForward) (offset / metrics.width).roundToInt() else pageCount - 1
    val visibleFraction get() = (metrics.width / (metrics.count * metrics.slot)).coerceAtMost(1f)
    val scrollFraction get() = if (maximum == 0f) 0f else offset / maximum

    private var lastWheel: Long? = null
    private var boundaryDistance = 0f

    fun configure(slotWidth: Float, viewportWidth: Float, actionCount: Int) {
        val position = offset / metrics.slot
        metrics = Metrics(slotWidth.coerceAtLeast(1f), viewportWidth.coerceAtLeast(1f), actionCount)
        offset = (position * metrics.slot).coerceIn(0f, maximum)
    }

    fun scrollForReorder(delta: Float) {
        moveTo(offset + delta)
    }

    internal fun moveTo(position: Float) {
        offset = position.coerceIn(0f, maximum)
    }

    internal fun mobileSettleTarget(direction: Float, travel: Float): Float {
        if (direction == 0f) return offset
        val projected = (offset + travel).coerceIn(0f, maximum)
        // Round in the release direction so the newly revealed edge button is fully in view.
        val snapped = if (direction > 0f) {
            ceil((projected - EDGE_TOLERANCE) / metrics.slot)
        } else {
            floor((projected + EDGE_TOLERANCE) / metrics.slot)
        }
        return (snapped * metrics.slot).coerceIn(0f, maximum)
    }

    fun drag(fingerDelta: Float) {
        val delta = -fingerDelta
        if (delta == 0f) return
        val target = offset + delta
        scrollForReorder(delta)
        val excess = target - offset
        if (excess == 0f || excess.sign != boundaryDistance.sign) boundaryDistance = 0f
        boundaryDistance += excess
        boundaryPullProgress = (boundaryDistance / metrics.slot).coerceIn(-1f, 1f)
    }

    fun endDrag() {
        boundaryDistance = 0f
        boundaryPullProgress = 0f
    }

    fun turnPage(direction: Int) {
        if (direction == 0 || (direction < 0 && !canPageBack) || (direction > 0 && !canPageForward)) return
        val page = if (direction > 0) {
            floor((offset + EDGE_TOLERANCE) / metrics.width) + 1
        } else {
            ceil((offset - EDGE_TOLERANCE) / metrics.width) - 1
        }
        offset = (page * metrics.width).coerceIn(0f, maximum)
    }

    fun wheel(delta: Float, now: Long, modified: Boolean = false) {
        if (modified || delta == 0f || !delta.isFinite()) return
        val newBurst = lastWheel?.let { now - it >= COOLDOWN } ?: true
        lastWheel = now
        if (newBurst) turnPage(delta.sign.toInt())
    }

    companion object {
        const val COOLDOWN = 300L
        private const val EDGE_TOLERANCE = .5f
    }
}
