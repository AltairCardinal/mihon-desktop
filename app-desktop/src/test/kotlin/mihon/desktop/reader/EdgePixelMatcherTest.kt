package mihon.desktop.reader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Unit tests for [EdgePixelMatcher].
 *
 * Uses synthetic [BufferedImage] instances with known pixel colours
 * to validate the edge sampling and distance computation without any
 * network or file system access.
 */
class EdgePixelMatcherTest {

    private val matcher = EdgePixelMatcher(samplePoints = 10, threshold = 30.0)

    // ── Helper ──────────────────────────────────────────────────────────────

    /** Creates a solid-colour image. */
    private fun solidImage(width: Int, height: Int, color: Color): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }

    /**
     * Creates an image with a distinct right-edge colour.
     * The bulk of the image is [bodyColor], but the rightmost 5 columns
     * are [edgeColor].
     */
    private fun imageWithRightEdge(
        width: Int,
        height: Int,
        bodyColor: Color,
        edgeColor: Color,
    ): BufferedImage {
        val img = solidImage(width, height, bodyColor)
        val g = img.createGraphics()
        g.color = edgeColor
        g.fillRect(width - 5, 0, 5, height)
        g.dispose()
        return img
    }

    /**
     * Creates an image with a distinct left-edge colour.
     */
    private fun imageWithLeftEdge(
        width: Int,
        height: Int,
        bodyColor: Color,
        edgeColor: Color,
    ): BufferedImage {
        val img = solidImage(width, height, bodyColor)
        val g = img.createGraphics()
        g.color = edgeColor
        g.fillRect(0, 0, 5, height)
        g.dispose()
        return img
    }

    // ── computeEdgeDistance ──────────────────────────────────────────────────

    @Test
    fun `identical pixel arrays have zero distance`() {
        val pixels = IntArray(10) { Color.RED.rgb }
        assertEquals(0.0, matcher.computeEdgeDistance(pixels, pixels))
    }

    @Test
    fun `black vs white has maximum distance`() {
        val black = IntArray(10) { Color.BLACK.rgb }
        val white = IntArray(10) { Color.WHITE.rgb }
        // |255-0| + |255-0| + |255-0| = 765
        assertEquals(765.0, matcher.computeEdgeDistance(black, white))
    }

    @Test
    fun `slightly different colours have small distance`() {
        val a = IntArray(10) { Color(100, 100, 100).rgb }
        val b = IntArray(10) { Color(105, 100, 95).rgb }
        // |5| + |0| + |5| = 10 per sample
        assertEquals(10.0, matcher.computeEdgeDistance(a, b))
    }

    @Test
    fun `empty arrays return MAX_VALUE`() {
        assertEquals(Double.MAX_VALUE, matcher.computeEdgeDistance(IntArray(0), IntArray(0)))
    }

    // ── sampleEdge ──────────────────────────────────────────────────────────

    @Test
    fun `sampleEdge LEFT samples near left border`() {
        val img = imageWithLeftEdge(100, 100, Color.WHITE, Color.RED)
        val samples = matcher.sampleEdge(img, EdgePixelMatcher.Side.LEFT, 5)
        // x=2 should be within the 5px red edge
        for (pixel in samples) {
            assertEquals(Color.RED.rgb, pixel)
        }
    }

    @Test
    fun `sampleEdge RIGHT samples near right border`() {
        val img = imageWithRightEdge(100, 100, Color.WHITE, Color.BLUE)
        val samples = matcher.sampleEdge(img, EdgePixelMatcher.Side.RIGHT, 5)
        // x = 100-3 = 97, within the 5px blue edge (starts at 95)
        for (pixel in samples) {
            assertEquals(Color.BLUE.rgb, pixel)
        }
    }

    @Test
    fun `sampleEdge returns correct number of points`() {
        val img = solidImage(50, 200, Color.GREEN)
        assertEquals(20, matcher.sampleEdge(img, EdgePixelMatcher.Side.LEFT, 20).size)
        assertEquals(1, matcher.sampleEdge(img, EdgePixelMatcher.Side.RIGHT, 1).size)
    }

    // ── bestEdgeScore ───────────────────────────────────────────────────────

    @Test
    fun `matching right-left edges produce low score`() {
        // Page A has red right edge, page B has red left edge → match
        val pageA = imageWithRightEdge(100, 200, Color.WHITE, Color.RED)
        val pageB = imageWithLeftEdge(100, 200, Color.WHITE, Color.RED)
        val score = matcher.bestEdgeScore(pageA, pageB)
        assertTrue(score < 5.0, "Score should be near 0 for matching edges, was $score")
    }

    @Test
    fun `non-matching edges produce high score`() {
        // Page A: dark body + red right edge; page B: light body + blue left edge.
        // Neither orientation matches:
        //   A.right(red) vs B.left(blue) → mismatch
        //   A.left(dark) vs B.right(light) → mismatch
        val pageA = imageWithRightEdge(100, 200, Color(30, 30, 30), Color.RED)
        val pageB = imageWithLeftEdge(100, 200, Color(220, 220, 220), Color.BLUE)
        val score = matcher.bestEdgeScore(pageA, pageB)
        assertTrue(score > 100.0, "Score should be high for non-matching edges, was $score")
    }

    @Test
    fun `two identical solid images match`() {
        val imgA = solidImage(100, 200, Color(50, 100, 150))
        val imgB = solidImage(100, 200, Color(50, 100, 150))
        val score = matcher.bestEdgeScore(imgA, imgB)
        assertEquals(0.0, score)
    }

    @Test
    fun `different sized images still work`() {
        // Different heights — sampleEdge scales proportionally
        val imgA = solidImage(100, 200, Color.GRAY)
        val imgB = solidImage(80, 300, Color.GRAY)
        val score = matcher.bestEdgeScore(imgA, imgB)
        assertEquals(0.0, score)
    }
}
