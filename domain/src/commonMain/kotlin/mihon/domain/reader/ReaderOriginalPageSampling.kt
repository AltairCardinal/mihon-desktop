package mihon.domain.reader

/** Two bounded original-source regions; the first points on each side belong to page-side evidence. */
class ReaderOriginalPageSamplingPlan internal constructor(
    val rawWidth: Int,
    val rawHeight: Int,
    val sourceOrientation: ReaderImageOrientation,
    val rotation: PageRotation,
    val left: List<Pair<Int, Int>>,
    val right: List<Pair<Int, Int>>,
    private val sidePoints: Int,
    private val hasEdges: Boolean,
) {
    val algorithmVersion: Int get() = ReaderOriginalPageSampling.ALGORITHM_VERSION

    /** Consumes straight-alpha sRGB, uncropped pixels; no display sampling or filters are admitted. */
    fun samples(leftArgb: List<Int>, rightArgb: List<Int>): ReaderOriginalPageSamples {
        require(leftArgb.size == left.size && rightArgb.size == right.size)
        val side = if (sidePoints == 0) {
            ReaderPageSideHint.UNKNOWN
        } else {
            ReaderPageSideHints.classify(leftArgb.take(sidePoints), rightArgb.take(sidePoints))
        }
        val edges = if (hasEdges) {
            ReaderSpreadEdgeSamples(leftArgb.drop(sidePoints), rightArgb.drop(sidePoints))
        } else {
            null
        }
        return ReaderOriginalPageSamples(side, edges)
    }
}

/** Only scalar page-side evidence and bounded edge samples survive region release. */
data class ReaderOriginalPageSamples(val sideHint: ReaderPageSideHint, val edges: ReaderSpreadEdgeSamples?)

object ReaderOriginalPageSampling {
    const val ALGORITHM_VERSION = 1

    fun plan(
        rawWidth: Int,
        rawHeight: Int,
        sourceOrientation: ReaderImageOrientation = ReaderImageOrientation.NORMAL,
        rotation: PageRotation = PageRotation.NONE,
    ): ReaderOriginalPageSamplingPlan? {
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val orientation = sourceOrientation.rotatedAfter(rotation)
        val size = orientation.orientedSize(rawWidth, rawHeight)
        val side = ReaderPageSideHints.plan(size.width, size.height)
        fun original(points: List<Pair<Int, Int>>) = points.map { (x, y) ->
            orientation.sourcePixel(x, y, rawWidth, rawHeight)
        }
        val edges = SharedSpreadMatching.plan(rawWidth, rawHeight, orientation)
        return ReaderOriginalPageSamplingPlan(
            rawWidth,
            rawHeight,
            sourceOrientation,
            rotation,
            original(side?.left.orEmpty()) + edges?.left.orEmpty(),
            original(side?.right.orEmpty()) + edges?.right.orEmpty(),
            side?.left?.size ?: 0,
            edges != null,
        )
    }
}
