package mihon.desktop.reader

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects pairs of adjacent manga pages that are two halves of the same
 * physical double-page spread by comparing their edge pixel colours.
 *
 * When a manga spread is scanned as two separate portrait images, the inner
 * edges (near the binding spine) of those two images share continuous artwork.
 * This class detects such continuity by sampling a vertical column of pixels
 * from each page's inner edge and computing the average colour distance.
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * The core algorithm ([computeEdgeDistance], [sampleEdge]) operates on
 * [IntArray] (ARGB pixel values) and is pure Kotlin.  Only the image-loading
 * layer ([loadImage]) uses [java.awt.image.BufferedImage].  On Android,
 * replace [loadImage] with Coil/Glide bitmap loading and adapt [sampleEdge]
 * to use `android.graphics.Bitmap.getPixel()`.
 */
class EdgePixelMatcher(
    /** Number of evenly-spaced sample points along each edge. */
    private val samplePoints: Int = 50,
    /**
     * Maximum average colour distance (0–765) below which two edges are
     * considered a match.  Lower = stricter matching.
     *
     * Each sample computes `|rA−rB| + |gA−gB| + |bA−bB|` (range 0–765).
     * The threshold is applied to the mean of all samples.
     */
    private val threshold: Double = 30.0,
) {

    enum class Side { LEFT, RIGHT }

    /**
     * Scans all adjacent page pairs and returns the indices of pages that
     * should be displayed together as a dual-page spread.
     *
     * @param pageUrls Ordered list of page image URLs (local file:// or remote http(s)://).
     * @return Set of (smallerIndex, largerIndex) pairs that should be matched.
     */
    suspend fun findMatchedPairs(pageUrls: List<String>): Set<Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            val result = mutableSetOf<Pair<Int, Int>>()
            if (pageUrls.size < 3) return@withContext result // need at least cover + 2 pages

            // Skip page 0 (cover).  Scan pairs (1,2), (2,3), (3,4), …
            var i = 1
            while (i + 1 < pageUrls.size) {
                try {
                    val imgA = loadImage(pageUrls[i])
                    val imgB = loadImage(pageUrls[i + 1])
                    if (imgA != null && imgB != null) {
                        val score = bestEdgeScore(imgA, imgB)
                        if (score < threshold) {
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
     * Computes the best (lowest) edge distance between two images,
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
     * The sample column is offset 2px from the true edge to avoid JPEG
     * compression artefacts that often appear on the outermost pixel row.
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
     * pixel arrays.  Both arrays should have the same length; if they differ,
     * the shorter length is used.
     *
     * @return Average distance in range [0, 765] where 0 = identical.
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

    /**
     * Loads an image from a URL string.
     *
     * Supports both `file://` (local downloaded pages) and `http(s)://`
     * (remote source pages) via [ImageIO].
     */
    private fun loadImage(urlString: String): BufferedImage? {
        return try {
            val url = java.net.URI(urlString).toURL()
            ImageIO.read(url)
        } catch (_: Exception) {
            null
        }
    }
}
