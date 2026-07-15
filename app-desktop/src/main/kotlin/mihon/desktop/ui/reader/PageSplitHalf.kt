package mihon.desktop.ui.reader

import mihon.domain.reader.splitPageBounds

typealias PageSplitHalf = mihon.domain.reader.PageSplitHalf
typealias SplitBounds = mihon.domain.reader.PixelBounds

fun splitBounds(imageWidth: Int, imageHeight: Int, half: PageSplitHalf): SplitBounds =
    requireNotNull(splitPageBounds(imageWidth, imageHeight, half)) {
        "image dimensions must be positive"
    }
