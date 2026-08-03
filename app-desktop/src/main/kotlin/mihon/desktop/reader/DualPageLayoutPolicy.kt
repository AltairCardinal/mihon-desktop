package mihon.desktop.reader

enum class SinglePageSide { LEADING, TRAILING, CENTER }

internal fun singlePageBoxOnRight(side: SinglePageSide, isRtl: Boolean): Boolean = when (side) {
    SinglePageSide.TRAILING -> !isRtl
    SinglePageSide.LEADING -> isRtl
    SinglePageSide.CENTER -> true
}
