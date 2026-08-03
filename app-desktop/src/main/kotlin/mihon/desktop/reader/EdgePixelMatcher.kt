package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.image.BufferedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects pairs of adjacent manga pages that are two halves of the same
 * physical double-page spread.
 *
 * Production matching uses colour continuity with meaningful edge detail.
 * White, dark and low-variance edges are rejected because equal page margins
 * are not evidence that two independent pages form one spread.
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * [findMatchedPairs] accepts only caller-owned, already decoded [ImageBitmap]
 * values from a bounded cache and performs no URL resolution or network I/O.
 * The [BufferedImage] overloads remain test helpers for the pure sampling and
 * false-positive guards.
 */
class EdgePixelMatcher(
    /** Number of evenly-spaced sample points along each edge column. */
    private val samplePoints: Int = 50,
    /**
     * Maximum average colour distance (0–765) below which two edge columns
     * are considered matching.
     *
     * Each sample contributes `|rA−rB| + |gA−gB| + |bA−bB|` (0–765).
     */
    private val threshold: Double = 30.0,
    /**
     * Band width (columns) checked by the white-gutter detector.
     * A larger band makes detection more robust against JPEG noise but
     * slightly more expensive.
     */
    private val gutterBandWidth: Int = 20,
    /**
     * Minimum per-channel brightness (0–255) for a pixel to be counted as
     * "white" in the gutter detector.  Slightly below 255 to tolerate
     * scan noise and JPEG compression.
     */
    private val gutterBrightnessThreshold: Int = 220,
    /**
     * Fraction of sampled pixels that must exceed [gutterBrightnessThreshold]
     * for an edge to be classified as a white gutter.
     */
    private val gutterCoverageThreshold: Double = 0.85,
) {

    companion object {
        /** Maximum per-channel value for a pixel to be counted as "dark". */
        private const val DARK_THRESHOLD = 50

        /**
         * Minimum luminance variance for an edge to contain meaningful detail.
         * Below this, the edge is considered a flat/uniform colour strip.
         */
        private const val MIN_EDGE_VARIANCE = 100.0
    }

    enum class Side { LEFT, RIGHT }

    /**
     * Scans all adjacent page pairs and returns the indices of pages that
     * should be displayed together as a dual-page spread.
     *
     * [pageAt] must return an already bounded decoded image owned by the caller's cache.
     * The matcher never resolves URLs or performs network I/O.
     *
     * @param pageCount Number of logical source pages.
     * @return Set of (smallerIndex, largerIndex) pairs that should be matched.
     */
    suspend fun findMatchedPairs(
        pageCount: Int,
        pageAt: (Int) -> ImageBitmap?,
    ): Set<Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            val result = mutableSetOf<Pair<Int, Int>>()
            if (pageCount < 3) return@withContext result // need cover + ≥2 pages

            // Skip page 0 (cover).  Scan pairs (1,2), (2,3), (3,4), …
            var i = 1
            while (i + 1 < pageCount) {
                try {
                    val imgA = pageAt(i)
                    val imgB = pageAt(i + 1)
                    if (imgA != null && imgB != null) {
                        // Landscape images are full-spread pages already handled by spreadPages;
                        // they cannot be halves of a scan spread — skip to avoid false positives.
                        if (imgA.width > imgA.height || imgB.width > imgB.height) {
                            i++
                            continue
                        }
                        if (isSpreadPair(imgA, imgB)) {
                            result.add(i to i + 1)
                            i += 2 // skip past the matched pair
                            continue
                        }
                    }
                } catch (_: Exception) {
                    // Cached image sampling failure — skip this pair.
                }
                i++
            }
            result
        }

    /**
     * Returns true if [imgA] and [imgB] appear to be the two halves of a
     * single double-page spread.
     *
     * Only **colour continuity with actual content** is used ([hasContentEdgeMatch]).
     *
     * White-gutter detection ([isWhiteGutterPair]) was removed because it cannot
     * distinguish between "one spread image split into two halves" and "two
     * independent pages that both have binding-side whitespace" — the latter is
     * the normal case for virtually every adjacent pair in scanned manga, causing
     * pervasive false positives.  Verified with Chainsaw Man Vol.1 Ch.1 pages
     * 4–5 (0-indexed) where both pages have asymmetric white binding edges yet
     * are clearly two separate story pages.
     */
    internal fun isSpreadPair(imgA: BufferedImage, imgB: BufferedImage): Boolean =
        hasContentEdgeMatch(imgA, imgB)

    private fun isSpreadPair(imgA: ImageBitmap, imgB: ImageBitmap): Boolean =
        hasContentContinuity(
            sampleEdge(imgA, Side.RIGHT, samplePoints),
            sampleEdge(imgB, Side.LEFT, samplePoints),
        ) || hasContentContinuity(
            sampleEdge(imgA, Side.LEFT, samplePoints),
            sampleEdge(imgB, Side.RIGHT, samplePoints),
        )

    /**
     * Returns true if the inner edges of two pages share continuous artwork
     * whose colour distance is below [threshold].
     *
     * **White-margin guard**: if both matched edge columns are predominantly
     * bright/white, the low distance is meaningless (it's just margin ≈ margin)
     * and the match is rejected.  Without this guard, every pair of pages with
     * standard white margins would false-positive because white ≈ white → 0.
     */
    private fun hasContentEdgeMatch(imgA: BufferedImage, imgB: BufferedImage): Boolean {
        // Orientation 1: A is left page → check A.right vs B.left
        val aRight = sampleEdge(imgA, Side.RIGHT, samplePoints)
        val bLeft  = sampleEdge(imgB, Side.LEFT,  samplePoints)
        if (hasContentContinuity(aRight, bLeft)) return true

        // Orientation 2: A is right page → check A.left vs B.right
        val aLeft  = sampleEdge(imgA, Side.LEFT,  samplePoints)
        val bRight = sampleEdge(imgB, Side.RIGHT, samplePoints)
        if (hasContentContinuity(aLeft, bRight)) return true

        return false
    }

    /**
     * Returns true if two edge columns show meaningful content continuity.
     *
     * Three guards prevent false positives from uniform edge colours:
     * 1. **Bright guard** — both edges are predominantly white (page margins)
     * 2. **Dark guard** — both edges are predominantly dark (manga ink)
     * 3. **Variance guard** — both edges are flat/uniform (no actual detail)
     *
     * Only when all guards pass AND the colour distance is below [threshold]
     * do we consider the edges to have genuine artwork continuity.
     */
    private fun hasContentContinuity(edgeA: IntArray, edgeB: IntArray): Boolean {
        if (computeEdgeDistance(edgeA, edgeB) >= threshold) return false
        // Guard 1: white ≈ white is meaningless
        if (isEdgeBright(edgeA) && isEdgeBright(edgeB)) return false
        // Guard 2: dark ≈ dark is meaningless
        if (isEdgeDark(edgeA) && isEdgeDark(edgeB)) return false
        // Guard 3: uniform ≈ uniform is meaningless (no content detail)
        if (pixelVariance(edgeA) < MIN_EDGE_VARIANCE &&
            pixelVariance(edgeB) < MIN_EDGE_VARIANCE
        ) return false
        return true
    }

    /**
     * Returns true if the majority (≥ 80%) of pixels in [pixels] are bright/white.
     */
    private fun isEdgeBright(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var bright = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (minOf(r, g, b) > gutterBrightnessThreshold) bright++
        }
        return bright.toDouble() / pixels.size >= 0.80
    }

    /**
     * Returns true if the majority (≥ 80%) of pixels in [pixels] are dark.
     * Mirrors [isEdgeBright] for the opposite end of the spectrum.
     */
    private fun isEdgeDark(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var dark = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (maxOf(r, g, b) < DARK_THRESHOLD) dark++
        }
        return dark.toDouble() / pixels.size >= 0.80
    }

    /**
     * Computes the luminance variance of a pixel array.
     * High variance indicates detailed content; low variance indicates
     * a uniform colour strip (white margin, dark ink, solid background).
     */
    private fun pixelVariance(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            sum += lum
            sumSq += lum * lum
        }
        val mean = sum / pixels.size
        return sumSq / pixels.size - mean * mean
    }

    /**
     * Returns true when both pages have a predominantly-white strip on their
     * facing inner edges only, indicating a cleaned-up scan binding gutter.
     *
     * **Asymmetry requirement**: the inner edge must be white AND the outer edge
     * must NOT be white.  This distinguishes a genuine binding gutter (one-sided)
     * from a normal page with symmetric margins (both sides white), which would
     * otherwise produce false positives.
     *
     * Orientation 1 — LTR scan spread:
     *   A = left physical page:  RIGHT edge is binding gutter (white), LEFT edge has content
     *   B = right physical page: LEFT edge is binding gutter (white), RIGHT edge has content
     *
     * Orientation 2 — RTL scan spread (typical Japanese manga):
     *   A = right physical page: LEFT edge is binding gutter (white), RIGHT edge has content
     *   B = left physical page:  RIGHT edge is binding gutter (white), LEFT edge has content
     */
    internal fun isWhiteGutterPair(imgA: BufferedImage, imgB: BufferedImage): Boolean {
        val aLeft  = hasWhiteGutter(imgA, Side.LEFT)
        val aRight = hasWhiteGutter(imgA, Side.RIGHT)
        val bLeft  = hasWhiteGutter(imgB, Side.LEFT)
        val bRight = hasWhiteGutter(imgB, Side.RIGHT)

        // Orientation 1 (LTR): A.right gutter (white), A.left NOT white; B.left gutter (white), B.right NOT white
        if (aRight && !aLeft && bLeft && !bRight) return true

        // Orientation 2 (RTL): A.left gutter (white), A.right NOT white; B.right gutter (white), B.left NOT white
        if (aLeft && !aRight && bRight && !bLeft) return true

        return false
    }

    /**
     * Returns true if a band of [gutterBandWidth] columns along [side] of
     * [img] is predominantly white/light.
     *
     * Samples [samplePoints] rows for every column in the band and counts
     * pixels whose average channel value exceeds [gutterBrightnessThreshold].
     * Triggers when the bright fraction ≥ [gutterCoverageThreshold].
     */
    internal fun hasWhiteGutter(
        img: BufferedImage,
        side: Side,
        bandWidth: Int = gutterBandWidth,
    ): Boolean {
        val effectiveBand = bandWidth.coerceAtMost(img.width)
        val xRange = when (side) {
            Side.LEFT -> 0 until effectiveBand
            Side.RIGHT -> (img.width - effectiveBand) until img.width
        }
        val step = img.height.toDouble() / samplePoints.coerceAtLeast(1)
        var brightCount = 0
        var totalCount = 0
        for (x in xRange) {
            for (i in 0 until samplePoints) {
                val y = (i * step).toInt().coerceIn(0, img.height - 1)
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                // Use minimum channel to avoid counting desaturated-but-dark pixels as white
                val minChannel = minOf(r, g, b)
                if (minChannel > gutterBrightnessThreshold) brightCount++
                totalCount++
            }
        }
        return totalCount > 0 && (brightCount.toDouble() / totalCount) >= gutterCoverageThreshold
    }

    /**
     * Computes the best (lowest) colour-distance between two images by
     * testing both orientations:
     *   - A.right vs B.left  (A is left page, B is right page)
     *   - A.left  vs B.right (B is left page, A is right page)
     *
     * Returns the lower of the two scores.
     */
    internal fun bestEdgeScore(imgA: BufferedImage, imgB: BufferedImage): Double {
        val rl = computeEdgeDistance(
            sampleEdge(imgA, Side.RIGHT, samplePoints),
            sampleEdge(imgB, Side.LEFT, samplePoints),
        )
        val lr = computeEdgeDistance(
            sampleEdge(imgA, Side.LEFT, samplePoints),
            sampleEdge(imgB, Side.RIGHT, samplePoints),
        )
        return minOf(rl, lr)
    }

    /**
     * Samples a vertical column of pixels near the specified edge.
     *
     * Offset 2 px from the true edge to avoid JPEG compression artefacts
     * on the outermost pixel row.
     *
     * @return Array of ARGB [Int] values, one per sample point.
     */
    internal fun sampleEdge(
        img: BufferedImage,
        side: Side,
        points: Int,
    ): IntArray {
        val x = when (side) {
            Side.LEFT -> 2.coerceAtMost(img.width - 1)
            Side.RIGHT -> (img.width - 3).coerceAtLeast(0)
        }
        val step = img.height.toDouble() / points.coerceAtLeast(1)
        return IntArray(points) { i ->
            val y = (i * step).toInt().coerceIn(0, img.height - 1)
            img.getRGB(x, y)
        }
    }

    private fun sampleEdge(
        image: ImageBitmap,
        side: Side,
        points: Int,
    ): IntArray {
        val bitmap = image.asSkiaBitmap()
        val x = when (side) {
            Side.LEFT -> 2.coerceAtMost(bitmap.width - 1)
            Side.RIGHT -> (bitmap.width - 3).coerceAtLeast(0)
        }
        val step = bitmap.height.toDouble() / points.coerceAtLeast(1)
        return IntArray(points) { index ->
            val y = (index * step).toInt().coerceIn(0, bitmap.height - 1)
            bitmap.getColor(x, y)
        }
    }

    /**
     * Computes the mean per-channel absolute colour distance between two
     * pixel arrays.
     *
     * @return Average distance in range [0, 765] where 0 = identical,
     *         or [Double.MAX_VALUE] for empty arrays.
     */
    internal fun computeEdgeDistance(pixelsA: IntArray, pixelsB: IntArray): Double {
        val len = minOf(pixelsA.size, pixelsB.size)
        if (len == 0) return Double.MAX_VALUE
        var totalDist = 0L
        for (i in 0 until len) {
            val a = pixelsA[i]
            val b = pixelsB[i]
            val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
            totalDist += dr + dg + db
        }
        return totalDist.toDouble() / len
    }
}
