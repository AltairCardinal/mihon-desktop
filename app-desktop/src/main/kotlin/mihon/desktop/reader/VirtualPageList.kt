package mihon.desktop.reader

import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.buildVirtualReaderPages

typealias VirtualPage = mihon.domain.reader.VirtualReaderPage

fun buildVirtualPageList(
    totalPages: Int,
    spreadPages: Set<Int>,
    isRtl: Boolean,
): List<VirtualPage> = buildVirtualReaderPages(
    totalPages = totalPages,
    spreadPages = spreadPages,
    direction = if (isRtl) ReaderDirection.RTL else ReaderDirection.LTR,
)

fun List<VirtualPage>.realPageIndex(virtualIndex: Int): Int = this[virtualIndex].sourcePageIndex

fun List<VirtualPage>.firstVirtualIndex(realIndex: Int): Int = indexOfFirst { it.sourcePageIndex == realIndex }
