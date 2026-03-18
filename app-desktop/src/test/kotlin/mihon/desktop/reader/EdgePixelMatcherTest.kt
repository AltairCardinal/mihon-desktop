package mihon.desktop.reader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Unit tests for [EdgePixelMatcher].
 *
 * Uses synthetic [BufferedImage] instances with known pixel colours
 * to validate the edge sampling, distance computation, and white-gutter
 * detection without any network or file system access.
 */
class EdgePixelMatcherTest {

    private val matcher = EdgePixelMatcher(samplePoints = 10, threshold = 30.0)

    // ── Helpers ─────────────────────────────────────────────────────────────

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
     * Creates an image with a distinct right-edge colour band.
     * The bulk is [bodyColor]; the rightmost [edgeWidth] columns are [edgeColor].
     */
    private fun imageWithRightEdge(
        width: Int,
        height: Int,
        bodyColor: Color,
        edgeColor: Color,
        edgeWidth: Int = 5,
    ): BufferedImage {
        val img = solidImage(width, height, bodyColor)
        val g = img.createGraphics()
        g.color = edgeColor
        g.fillRect(width - edgeWidth, 0, edgeWidth, height)
        g.dispose()
        return img
    }

    /**
     * Creates an image with a distinct left-edge colour band.
     */
    private fun imageWithLeftEdge(
        width: Int,
        height: Int,
        bodyColor: Color,
        edgeColor: Color,
        edgeWidth: Int = 5,
    ): BufferedImage {
        val img = solidImage(width, height, bodyColor)
        val g = img.createGraphics()
        g.color = edgeColor
        g.fillRect(0, 0, edgeWidth, height)
        g.dispose()
        return img
    }

    /**
     * Simulates a scanned spread half: dark manga content with a wide white
     * gutter on the inner side.
     *
     * @param whiteOnRight  true → white gutter on right (left page of spread)
     *                      false → white gutter on left (right page of spread)
     */
    private fun spreadHalfImage(
        width: Int = 200,
        height: Int = 300,
        whiteOnRight: Boolean,
        gutterWidth: Int = 25,
    ): BufferedImage {
        // Dark grey body (typical manga ink-on-paper tone)
        val img = solidImage(width, height, Color(60, 60, 60))
        val g = img.createGraphics()
        g.color = Color.WHITE
        if (whiteOnRight) {
            g.fillRect(width - gutterWidth, 0, gutterWidth, height)
        } else {
            g.fillRect(0, 0, gutterWidth, height)
        }
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

    // ── hasWhiteGutter ───────────────────────────────────────────────────────

    @Test
    fun `white right edge detected as white gutter`() {
        val img = spreadHalfImage(whiteOnRight = true, gutterWidth = 25)
        assertTrue(
            matcher.hasWhiteGutter(img, EdgePixelMatcher.Side.RIGHT),
            "Right gutter should be white",
        )
    }

    @Test
    fun `white left edge detected as white gutter`() {
        val img = spreadHalfImage(whiteOnRight = false, gutterWidth = 25)
        assertTrue(
            matcher.hasWhiteGutter(img, EdgePixelMatcher.Side.LEFT),
            "Left gutter should be white",
        )
    }

    @Test
    fun `dark edge not classified as white gutter`() {
        val img = solidImage(200, 300, Color(60, 60, 60))
        assertFalse(
            matcher.hasWhiteGutter(img, EdgePixelMatcher.Side.RIGHT),
            "Dark right edge should not be a white gutter",
        )
        assertFalse(
            matcher.hasWhiteGutter(img, EdgePixelMatcher.Side.LEFT),
            "Dark left edge should not be a white gutter",
        )
    }

    @Test
    fun `spread-half page has white gutter only on inner side`() {
        // Left half of spread: white on right, content on left
        val leftHalf = spreadHalfImage(whiteOnRight = true, gutterWidth = 25)
        assertTrue(matcher.hasWhiteGutter(leftHalf, EdgePixelMatcher.Side.RIGHT))
        assertFalse(matcher.hasWhiteGutter(leftHalf, EdgePixelMatcher.Side.LEFT))
    }

    // ── isWhiteGutterPair ────────────────────────────────────────────────────

    @Test
    fun `scanned spread pair detected via white gutter`() {
        val leftHalf = spreadHalfImage(whiteOnRight = true, gutterWidth = 25)
        val rightHalf = spreadHalfImage(whiteOnRight = false, gutterWidth = 25)
        assertTrue(
            matcher.isWhiteGutterPair(leftHalf, rightHalf),
            "Left+right spread halves should be a white gutter pair",
        )
    }

    @Test
    fun `reversed order also detected via white gutter`() {
        // isWhiteGutterPair tests both orientations
        val leftHalf = spreadHalfImage(whiteOnRight = true, gutterWidth = 25)
        val rightHalf = spreadHalfImage(whiteOnRight = false, gutterWidth = 25)
        assertTrue(matcher.isWhiteGutterPair(rightHalf, leftHalf))
    }

    @Test
    fun `two dark pages not detected as white gutter pair`() {
        val pageA = solidImage(200, 300, Color(60, 60, 60))
        val pageB = solidImage(200, 300, Color(40, 40, 40))
        assertFalse(matcher.isWhiteGutterPair(pageA, pageB))
    }

    // ── isSpreadPair ─────────────────────────────────────────────────────────

    @Test
    fun `isSpreadPair true when edge colours match`() {
        val pageA = imageWithRightEdge(100, 200, Color.WHITE, Color.RED)
        val pageB = imageWithLeftEdge(100, 200, Color.WHITE, Color.RED)
        assertTrue(matcher.isSpreadPair(pageA, pageB))
    }

    @Test
    fun `isSpreadPair true when white gutter detected even if colours differ`() {
        // Pages have different content colours but matching white gutters — typical
        // for cleaned-up scans where the gutter is pure white regardless of content.
        val leftHalf = spreadHalfImage(width = 200, height = 300, whiteOnRight = true)
        val rightHalf = spreadHalfImage(width = 200, height = 300, whiteOnRight = false)
        assertTrue(matcher.isSpreadPair(leftHalf, rightHalf))
    }

    @Test
    fun `isSpreadPair false when neither signal fires`() {
        // Dark pages with different content — no colour match, no white gutters
        val pageA = imageWithRightEdge(100, 200, Color(30, 30, 30), Color.RED)
        val pageB = imageWithLeftEdge(100, 200, Color(220, 220, 220), Color.BLUE)
        assertFalse(matcher.isSpreadPair(pageA, pageB))
    }

    // ── isWhiteGutterPair — asymmetry fix (regression guard) ─────────────────

    /**
     * Creates a page with wide white margins on BOTH left and right sides,
     * simulating a standalone manga page with symmetric page margins.
     * Such a page is NOT a scan-spread half and must not trigger the gutter detector.
     */
    private fun pageWithSymmetricMargins(
        width: Int = 200,
        height: Int = 300,
        marginWidth: Int = 25,
    ): BufferedImage {
        val img = solidImage(width, height, Color(60, 60, 60))
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, marginWidth, height)            // left margin
        g.fillRect(width - marginWidth, 0, marginWidth, height) // right margin
        g.dispose()
        return img
    }

    @Test
    fun `symmetric white margins on both sides do NOT trigger isWhiteGutterPair`() {
        // Regression: before the asymmetry fix, two pages with white on both left
        // and right edges would produce a false positive because Orientation 2
        // (A.left white AND B.right white) was satisfied even though both sides are white.
        val pageA = pageWithSymmetricMargins()
        val pageB = pageWithSymmetricMargins()
        // Verify the setup: both edges of each page ARE white (pre-condition for the regression)
        assertTrue(matcher.hasWhiteGutter(pageA, EdgePixelMatcher.Side.LEFT), "setup: pageA left should be white")
        assertTrue(matcher.hasWhiteGutter(pageA, EdgePixelMatcher.Side.RIGHT), "setup: pageA right should be white")
        // After the asymmetry fix, symmetric margins must NOT be a gutter pair
        assertFalse(
            matcher.isWhiteGutterPair(pageA, pageB),
            "Pages with symmetric white margins on both sides must not be detected as a gutter pair",
        )
    }

    @Test
    fun `asymmetric gutter (one side white, other dark) still detected`() {
        // Confirm the fix doesn't break legitimate detection:
        // spreadHalfImage has white on ONE side only → asymmetric → should still match
        val leftPage  = spreadHalfImage(whiteOnRight = true,  gutterWidth = 25)  // white RIGHT only
        val rightPage = spreadHalfImage(whiteOnRight = false, gutterWidth = 25)  // white LEFT only
        assertTrue(
            matcher.isWhiteGutterPair(leftPage, rightPage),
            "Genuine scan-spread pair with asymmetric gutters must be detected",
        )
    }
}
