package mihon.domain.reader

enum class PageRotation { NONE, CLOCKWISE_90, HALF_TURN, COUNTER_CLOCKWISE_90 }

enum class PageLayout { PORTRAIT, SPREAD, UNKNOWN }

enum class PageSplitHalf { LEFT, RIGHT }

data class PixelBounds(val x: Int, val y: Int, val width: Int, val height: Int)

data class VirtualReaderPage(
    val sourcePageIndex: Int,
    val splitHalf: PageSplitHalf? = null,
    val sourceBounds: PixelBounds? = null,
) {
    val realIndex: Int get() = sourcePageIndex
}

fun classifyPage(width: Int, height: Int, rotation: PageRotation): PageLayout {
    if (width <= 0 || height <= 0) return PageLayout.UNKNOWN
    val rotated = rotation == PageRotation.CLOCKWISE_90 || rotation == PageRotation.COUNTER_CLOCKWISE_90
    val displayWidth = if (rotated) height else width
    val displayHeight = if (rotated) width else height
    return if (displayWidth >= displayHeight) PageLayout.SPREAD else PageLayout.PORTRAIT
}

/**
 * Maps a half of the rotated display page back to source-image coordinates.
 *
 * Unlike Android's historical `splitInHalf`, odd dimensions intentionally keep the center pixel:
 * the two returned source regions cover the image completely without overlap.
 */
fun splitPageBounds(
    imageWidth: Int,
    imageHeight: Int,
    half: PageSplitHalf,
    rotation: PageRotation = PageRotation.NONE,
): PixelBounds? {
    if (imageWidth <= 0 || imageHeight <= 0) return null
    return when (rotation) {
        PageRotation.NONE -> {
            val midpoint = imageWidth / 2
            when (half) {
                PageSplitHalf.LEFT -> PixelBounds(0, 0, midpoint, imageHeight)
                PageSplitHalf.RIGHT -> PixelBounds(midpoint, 0, imageWidth - midpoint, imageHeight)
            }
        }
        PageRotation.HALF_TURN -> {
            val midpoint = imageWidth / 2
            when (half) {
                PageSplitHalf.LEFT -> PixelBounds(imageWidth - midpoint, 0, midpoint, imageHeight)
                PageSplitHalf.RIGHT -> PixelBounds(0, 0, imageWidth - midpoint, imageHeight)
            }
        }
        PageRotation.CLOCKWISE_90 -> {
            val midpoint = imageHeight / 2
            when (half) {
                PageSplitHalf.LEFT -> PixelBounds(0, imageHeight - midpoint, imageWidth, midpoint)
                PageSplitHalf.RIGHT -> PixelBounds(0, 0, imageWidth, imageHeight - midpoint)
            }
        }
        PageRotation.COUNTER_CLOCKWISE_90 -> {
            val midpoint = imageHeight / 2
            when (half) {
                PageSplitHalf.LEFT -> PixelBounds(0, 0, imageWidth, midpoint)
                PageSplitHalf.RIGHT -> PixelBounds(0, midpoint, imageWidth, imageHeight - midpoint)
            }
        }
    }
}

fun buildVirtualReaderPages(
    totalPages: Int,
    spreadPages: Set<Int>,
    direction: ReaderDirection,
    pageSizes: Map<Int, ReaderPageSize> = emptyMap(),
    rotations: Map<Int, PageRotation> = emptyMap(),
): List<VirtualReaderPage> = buildList {
    repeat(totalPages.coerceAtLeast(0)) { index ->
        if (index in spreadPages) {
            val first = if (direction == ReaderDirection.LTR) PageSplitHalf.LEFT else PageSplitHalf.RIGHT
            val second = if (direction == ReaderDirection.LTR) PageSplitHalf.RIGHT else PageSplitHalf.LEFT
            val size = pageSizes[index]
            val rotation = rotations[index] ?: PageRotation.NONE
            add(
                VirtualReaderPage(
                    sourcePageIndex = index,
                    splitHalf = first,
                    sourceBounds = size?.let { splitPageBounds(it.width, it.height, first, rotation) },
                ),
            )
            add(
                VirtualReaderPage(
                    sourcePageIndex = index,
                    splitHalf = second,
                    sourceBounds = size?.let { splitPageBounds(it.width, it.height, second, rotation) },
                ),
            )
        } else {
            add(VirtualReaderPage(index))
        }
    }
}

data class PagePair(val first: Int, val second: Int) {
    fun normalized(): PagePair = if (first <= second) this else PagePair(second, first)
}

enum class ReaderColorFilterEffect { TINT, BRIGHTNESS, GRAYSCALE, INVERT }

data class ReaderColorFilterParams(
    val tintEnabled: Boolean = false,
    val brightnessEnabled: Boolean = false,
    val brightness: Float = 0f,
    val r: Int = 0,
    val g: Int = 0,
    val b: Int = 0,
    val alpha: Int = 128,
    val grayscaleEnabled: Boolean = false,
    val invertEnabled: Boolean = false,
) {
    /** Canonical application order shared by both platform renderers. */
    val activeEffects: List<ReaderColorFilterEffect>
        get() = buildList {
            if (tintEnabled && alpha > 0) add(ReaderColorFilterEffect.TINT)
            if (brightnessEnabled && brightness != 0f) add(ReaderColorFilterEffect.BRIGHTNESS)
            if (grayscaleEnabled) add(ReaderColorFilterEffect.GRAYSCALE)
            if (invertEnabled) add(ReaderColorFilterEffect.INVERT)
        }

    val isEffective: Boolean
        get() = activeEffects.isNotEmpty()

    fun normalized(): ReaderColorFilterParams = copy(
        brightness = brightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX),
        r = r.coerceIn(0, 255),
        g = g.coerceIn(0, 255),
        b = b.coerceIn(0, 255),
        alpha = alpha.coerceIn(0, 255),
    )

    companion object {
        const val BRIGHTNESS_MIN = -0.75f
        const val BRIGHTNESS_MAX = 1.0f
    }
}
