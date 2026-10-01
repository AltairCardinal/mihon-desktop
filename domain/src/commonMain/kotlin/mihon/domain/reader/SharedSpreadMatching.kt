package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId
import kotlin.math.abs

/** Original encoded coordinates for the normalized, theme-independent left and right edges. */
data class ReaderSpreadSamplingPlan(
    val left: List<Pair<Int, Int>>,
    val right: List<Pair<Int, Int>>,
)

/** Samples are straight-alpha sRGB ARGB values before fixed white-background composition. */
data class ReaderSpreadEdgeSamples(val left: List<Int>, val right: List<Int>) {
    init {
        require(left.size == SharedSpreadMatching.SAMPLE_POINTS && right.size == SharedSpreadMatching.SAMPLE_POINTS)
    }
}

data class ReaderSpreadCandidate(val first: ReaderPageId, val second: ReaderPageId, val distance: Double)

/** Shared sampling, continuity scoring and complete-candidate selection policy. */
object SharedSpreadMatching {
    const val SAMPLE_POINTS = 50

    fun plan(
        rawWidth: Int,
        rawHeight: Int,
        orientation: ReaderImageOrientation = ReaderImageOrientation.NORMAL,
    ): ReaderSpreadSamplingPlan? {
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val size = orientation.orientedSize(rawWidth, rawHeight)
        if (size.width > size.height) return null
        fun edge(x: Int) = List(SAMPLE_POINTS) { index ->
            val y = (index.toLong() * size.height / SAMPLE_POINTS).toInt()
            orientation.sourcePixel(x, y, rawWidth, rawHeight)
        }
        return ReaderSpreadSamplingPlan(
            edge(minOf(2, size.width - 1)),
            edge(maxOf(0, size.width - 3)),
        )
    }

    fun normalizeSample(argb: Int): Int {
        val alpha = argb ushr 24
        fun channel(shift: Int) = (((argb ushr shift) and 255) * alpha + 255 * (255 - alpha) + 127) / 255
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun score(earlier: ReaderSpreadEdgeSamples, later: ReaderSpreadEdgeSamples, direction: ReaderDirection): Double? {
        require(direction != ReaderDirection.VERTICAL)
        val a = (if (direction == ReaderDirection.RTL) earlier.left else earlier.right).map(::normalizeSample)
        val b = (if (direction == ReaderDirection.RTL) later.right else later.left).map(::normalizeSample)
        val distance = a.zip(b).sumOf { (first, second) ->
            abs(red(first) - red(second)) + abs(green(first) - green(second)) + abs(blue(first) - blue(second))
        }.toDouble() / SAMPLE_POINTS
        if (distance >= COLOR_THRESHOLD) return null
        if (bright(a) && bright(b)) return null
        if (dark(a) && dark(b)) return null
        if (variance(a) < MIN_EDGE_VARIANCE && variance(b) < MIN_EDGE_VARIANCE) return null
        return distance
    }

    fun select(
        orderedPages: List<ReaderPageId>,
        candidates: Collection<ReaderSpreadCandidate>,
        forcedSinglePages: Set<ReaderPageId> = emptySet(),
        enabled: Boolean = true,
    ): Set<Pair<ReaderPageId, ReaderPageId>> {
        if (!enabled) return emptySet()
        require(orderedPages.distinct().size == orderedPages.size)
        require(orderedPages.map { it.chapterId }.distinct().size <= 1)
        val indices = orderedPages.withIndex().associate { it.value to it.index }
        val valid = candidates.filter { candidate ->
            val first = indices[candidate.first]
            val second = indices[candidate.second]
            first != null && second != null && first > 0 && second == first + 1 &&
                candidate.first !in forcedSinglePages && candidate.second !in forcedSinglePages &&
                candidate.distance.isFinite() && candidate.distance >= 0 && candidate.distance < COLOR_THRESHOLD
        }.sortedWith(compareBy<ReaderSpreadCandidate> { it.distance }.thenBy { indices.getValue(it.first) })
        val occupied = mutableSetOf<ReaderPageId>()
        return buildSet {
            valid.forEach { candidate ->
                if (candidate.first !in occupied && candidate.second !in occupied) {
                    add(candidate.first to candidate.second)
                    occupied += candidate.first
                    occupied += candidate.second
                }
            }
        }
    }

    private const val COLOR_THRESHOLD = 30.0
    private const val MIN_EDGE_VARIANCE = 100.0
    private fun red(color: Int) = (color ushr 16) and 255
    private fun green(color: Int) = (color ushr 8) and 255
    private fun blue(color: Int) = color and 255
    private fun bright(edge: List<Int>) =
        edge.count { minOf(red(it), green(it), blue(it)) > 220 } >= SAMPLE_POINTS * 0.8
    private fun dark(edge: List<Int>) = edge.count { maxOf(red(it), green(it), blue(it)) < 50 } >= SAMPLE_POINTS * 0.8
    private fun variance(edge: List<Int>): Double {
        val luminances = edge.map { 0.299 * red(it) + 0.587 * green(it) + 0.114 * blue(it) }
        val mean = luminances.average()
        return luminances.sumOf { it * it } / SAMPLE_POINTS - mean * mean
    }
}
