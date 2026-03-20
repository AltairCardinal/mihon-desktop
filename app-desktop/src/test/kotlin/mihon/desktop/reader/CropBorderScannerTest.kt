package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * RED — CropBorderScanner does not exist yet.
 * Tests will fail until the production class is implemented.
 *
 * Verifies that CropBorderScanner correctly detects white borders on all four sides
 * of a BufferedImage and returns a CropRect with the trimmed content bounds.
 */
class CropBorderScannerTest {

    private val scanner = CropBorderScanner()

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Fills the entire image with white, then paints a colored rectangle. */
    private fun imageWithContent(
        width: Int,
        height: Int,
        contentLeft: Int,
        contentTop: Int,
        contentRight: Int,
        contentBottom: Int,
        contentColor: Color = Color.DARK_GRAY,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = contentColor
        g.fillRect(contentLeft, contentTop, contentRight - contentLeft, contentBottom - contentTop)
        g.dispose()
        return img
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `no border returns full image rect`() {
        // Image where every pixel is dark — no border to trim
        val img = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.DARK_GRAY
        g.fillRect(0, 0, 100, 80)
        g.dispose()

        val rect = scanner.detectCropRect(img)
        assertEquals(0, rect.top)
        assertEquals(0, rect.left)
        assertEquals(80, rect.bottom)
        assertEquals(100, rect.right)
    }

    @Test
    fun `detects top white border`() {
        // 10-pixel white border at the top
        val img = imageWithContent(100, 100, contentLeft = 0, contentTop = 10, contentRight = 100, contentBottom = 100)
        val rect = scanner.detectCropRect(img)
        assertEquals(10, rect.top)
        assertEquals(0, rect.left)
        assertEquals(100, rect.bottom)
        assertEquals(100, rect.right)
    }

    @Test
    fun `detects bottom white border`() {
        val img = imageWithContent(100, 100, contentLeft = 0, contentTop = 0, contentRight = 100, contentBottom = 90)
        val rect = scanner.detectCropRect(img)
        assertEquals(0, rect.top)
        assertEquals(90, rect.bottom)
    }

    @Test
    fun `detects left white border`() {
        val img = imageWithContent(100, 100, contentLeft = 8, contentTop = 0, contentRight = 100, contentBottom = 100)
        val rect = scanner.detectCropRect(img)
        assertEquals(8, rect.left)
    }

    @Test
    fun `detects right white border`() {
        val img = imageWithContent(100, 100, contentLeft = 0, contentTop = 0, contentRight = 92, contentBottom = 100)
        val rect = scanner.detectCropRect(img)
        assertEquals(92, rect.right)
    }

    @Test
    fun `detects all four borders simultaneously`() {
        // 5-pixel white border on all sides; content fills the inner 90x70 area
        val img = imageWithContent(
            width = 100,
            height = 80,
            contentLeft = 5,
            contentTop = 5,
            contentRight = 95,
            contentBottom = 75,
        )
        val rect = scanner.detectCropRect(img)
        assertEquals(5, rect.top)
        assertEquals(5, rect.left)
        assertEquals(75, rect.bottom)
        assertEquals(95, rect.right)
    }

    @Test
    fun `fully white image returns full rect (no crash)`() {
        val img = BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 50, 50)
        g.dispose()
        // Should not throw; returns the full image bounds
        val rect = scanner.detectCropRect(img)
        assertEquals(0, rect.top)
        assertEquals(0, rect.left)
        assertEquals(50, rect.bottom)
        assertEquals(50, rect.right)
    }

    @Test
    fun `custom threshold treats near-white pixels as border`() {
        // Pixel value 230 is below default 240 threshold but above 220
        val scanner220 = CropBorderScanner(whiteThreshold = 220)
        val img = BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        // Top 5 rows: color 230,230,230 (near-white, above 220 threshold → treated as border)
        g.color = Color(230, 230, 230)
        g.fillRect(0, 0, 50, 5)
        // Remaining rows: dark gray
        g.color = Color(50, 50, 50)
        g.fillRect(0, 5, 50, 45)
        g.dispose()

        val rect = scanner220.detectCropRect(img)
        assertEquals(5, rect.top)
    }
}
