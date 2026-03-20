package mihon.desktop.reader

import java.awt.image.BufferedImage

/**
 * Detects white (or near-white) borders around a manga page image and returns
 * the tightest bounding rectangle that contains non-border content.
 *
 * This is equivalent to Android's "Crop Borders" feature in SubsamplingScaleImageView.
 *
 * @param whiteThreshold  RGB channel value at or above which a pixel is considered "white border".
 *                        Default 240 matches Android's implementation.
 */
class CropBorderScanner(private val whiteThreshold: Int = 240) {

    /**
     * Analyses [image] and returns a [CropRect] describing the content region
     * after trimming white borders on all four sides.
     *
     * If the entire image is white (or below the minimum content area), returns
     * the full image bounds to avoid an empty rect.
     */
    fun detectCropRect(image: BufferedImage): CropRect {
        val w = image.width
        val h = image.height

        val top = findTopEdge(image, w, h)
        val bottom = findBottomEdge(image, w, h)
        val left = findLeftEdge(image, w, top, bottom)
        val right = findRightEdge(image, w, top, bottom)

        // Guard: if content area collapsed (fully white image), return full bounds
        if (top >= bottom || left >= right) return CropRect(0, 0, h, w)

        return CropRect(top = top, left = left, bottom = bottom, right = right)
    }

    // ── Edge finders ──────────────────────────────────────────────────────────

    private fun findTopEdge(image: BufferedImage, w: Int, h: Int): Int {
        for (y in 0 until h) {
            if (!isRowLight(image, y, w)) return y
        }
        return 0
    }

    private fun findBottomEdge(image: BufferedImage, w: Int, h: Int): Int {
        for (y in h - 1 downTo 0) {
            if (!isRowLight(image, y, w)) return y + 1
        }
        return h
    }

    private fun findLeftEdge(image: BufferedImage, w: Int, top: Int, bottom: Int): Int {
        for (x in 0 until w) {
            if (!isColumnLight(image, x, top, bottom)) return x
        }
        return 0
    }

    private fun findRightEdge(image: BufferedImage, w: Int, top: Int, bottom: Int): Int {
        for (x in w - 1 downTo 0) {
            if (!isColumnLight(image, x, top, bottom)) return x + 1
        }
        return w
    }

    // ── Pixel helpers ─────────────────────────────────────────────────────────

    /** Returns true if every pixel in row [y] has all channels >= [whiteThreshold]. */
    private fun isRowLight(image: BufferedImage, y: Int, w: Int): Boolean {
        for (x in 0 until w) {
            if (!isPixelLight(image.getRGB(x, y))) return false
        }
        return true
    }

    /** Returns true if every pixel in column [x] between [top] and [bottom] is light. */
    private fun isColumnLight(image: BufferedImage, x: Int, top: Int, bottom: Int): Boolean {
        for (y in top until bottom) {
            if (!isPixelLight(image.getRGB(x, y))) return false
        }
        return true
    }

    private fun isPixelLight(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r >= whiteThreshold && g >= whiteThreshold && b >= whiteThreshold
    }
}

/**
 * Content bounding rectangle returned by [CropBorderScanner].
 * All values are in pixels relative to the original image origin.
 *
 * @param top    First row that contains non-border content (inclusive).
 * @param left   First column that contains non-border content (inclusive).
 * @param bottom First row past the content area (exclusive — like Rect.bottom in Android).
 * @param right  First column past the content area (exclusive).
 */
data class CropRect(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
