package mihon.desktop.ui.reader

/** Which half of a wide (landscape) manga page to display. */
enum class PageSplitHalf { LEFT, RIGHT }

/** Pixel-coordinate bounds for a [half]-side crop of an image. */
data class SplitBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Computes the crop bounds for [half] of a wide image.
 *
 * The image is split at `imageWidth / 2` (integer division).
 * - LEFT  →  x=0, width=imageWidth/2
 * - RIGHT →  x=imageWidth/2, width=imageWidth-imageWidth/2
 */
fun splitBounds(imageWidth: Int, imageHeight: Int, half: PageSplitHalf): SplitBounds {
    val mid = imageWidth / 2
    return when (half) {
        PageSplitHalf.LEFT -> SplitBounds(x = 0, y = 0, width = mid, height = imageHeight)
        PageSplitHalf.RIGHT -> SplitBounds(x = mid, y = 0, width = imageWidth - mid, height = imageHeight)
    }
}
