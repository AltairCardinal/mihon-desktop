package mihon.desktop.reader

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects pairs of adjacent manga pages that are two halves of the same
 * physical double-page spread.
 *
 * Two complementary signals are used (either is sufficient for a match):
 *
 * 1. **Colour continuity** — the inner edges share continuous artwork.
 *    Sampled via [sampleEdge] + [computeEdgeDistance].
 *
 * 2. **White gutter** — the inner edges of both pages are predominantly
 *    white/light.  Scanned spreads that have been digitally cleaned often
 *    have the binding area replaced with a plain white margin rather than
 *    continuous artwork, so colour continuity alone misses them.
 *    Detected via [hasWhiteGutter] + [isWhiteGutterPair].
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * [computeEdgeDistance] and [sampleEdge] operate on [IntArray] (ARGB) and
 * are pure Kotlin.  [hasWhiteGutter] uses [BufferedImage.getRGB].
 * On Android, replace [loadImage] with Coil/Glide bitmap loading and
 * adapt the pixel-access calls to `android.graphics.Bitmap.getPixel()`.
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

    enum class Side { LEFT, RIGHT }

    /**
     * Scans all adjacent page pairs and returns the indices of pages that
     * should be displayed together as a dual-page spread.
     *
     * @param pageUrls Ordered list of page image URLs.
     * @return Set of (smallerIndex, largerIndex) pairs that should be matched.
     */
    suspend fun findMatchedPairs(pageUrls: List<String>): Set<Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            val result = mutableSetOf<Pair<Int, Int>>()
            if (pageUrls.size < 3) return@withContext result // need cover + ≥2 pages

            // Skip page 0 (cover).  Scan pairs (1,2), (2,3), (3,4), …
            var i = 1
            while (i + 1 < pageUrls.size) {
                try {
                    val imgA = loadImage(pageUrls[i])
                    val imgB = loadImage(pageUrls[i + 1])
                    if (imgA != null && imgB != null) {
                        // Landscape images are full-spread pages already handled by spreadPages;
                        // they cannot be halves of a scan spread — skip to avoid false positives.
                        if (imgA.width >= imgA.height || imgB.width >= imgB.height) {
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
                    // Image load failure — skip this pair
                }
                i++
            }
            result
        }

    /**
     * Returns true if [imgA] and [imgB] appear to be the two halves of a
     * single double-page spread.
     *
     * Either signal is sufficient:
     * - Colour continuity across the inner edges ([bestEdgeScore] < [threshold])
     * - Both inner edges are a white gutter ([isWhiteGutterPair])
     */
    internal fun isSpreadPair(imgA: BufferedImage, imgB: BufferedImage): Boolean =
        bestEdgeScore(imgA, imgB) < threshold || isWhiteGutterPair(imgA, imgB)

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

    private fun loadImage(urlString: String): BufferedImage? = try {
        val url = java.net.URI(urlString).toURL()
        ImageIO.read(url)
    } catch (_: Exception) {
        null
    }
}
