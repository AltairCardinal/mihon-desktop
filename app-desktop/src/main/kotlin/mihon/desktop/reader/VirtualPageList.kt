package mihon.desktop.reader

import mihon.desktop.ui.reader.PageSplitHalf

/**
 * A virtual page entry that maps to either a full real page or one half of a wide page.
 *
 * @property realIndex Index into the original pageUrls list.
 * @property splitHalf Which half to display, or null for the full image.
 */
data class VirtualPage(
    val realIndex: Int,
    val splitHalf: PageSplitHalf? = null,
)

/**
 * Builds a virtual page list that expands wide (spread) pages into two sequential entries.
 *
 * Non-spread pages map 1:1. Each spread page becomes two entries:
 * - LTR: LEFT then RIGHT
 * - RTL: RIGHT then LEFT
 *
 * Spread indices that are >= [totalPages] are silently ignored.
 */
fun buildVirtualPageList(
    totalPages: Int,
    spreadPages: Set<Int>,
    isRtl: Boolean,
): List<VirtualPage> = buildList {
    for (i in 0 until totalPages) {
        if (i in spreadPages) {
            val first = if (isRtl) PageSplitHalf.RIGHT else PageSplitHalf.LEFT
            val second = if (isRtl) PageSplitHalf.LEFT else PageSplitHalf.RIGHT
            add(VirtualPage(i, first))
            add(VirtualPage(i, second))
        } else {
            add(VirtualPage(i))
        }
    }
}

/** Returns the real page index for the given virtual page index. */
fun List<VirtualPage>.realPageIndex(virtualIndex: Int): Int = this[virtualIndex].realIndex

/** Returns the first virtual page index that corresponds to the given real page index. */
fun List<VirtualPage>.firstVirtualIndex(realIndex: Int): Int =
    indexOfFirst { it.realIndex == realIndex }
