package mihon.desktop.reader

import mihon.desktop.ui.reader.PageSplitHalf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VirtualPageListTest {

    // --- buildVirtualPageList ---

    @Test
    fun `no spreads returns identity mapping`() {
        val result = buildVirtualPageList(totalPages = 5, spreadPages = emptySet(), isRtl = false)
        assertEquals(5, result.size)
        result.forEachIndexed { i, vp ->
            assertEquals(i, vp.realIndex)
            assertNull(vp.splitHalf)
        }
    }

    @Test
    fun `LTR single spread produces LEFT then RIGHT`() {
        val result = buildVirtualPageList(totalPages = 5, spreadPages = setOf(2), isRtl = false)
        assertEquals(6, result.size)
        // pages 0, 1 unchanged
        assertEquals(VirtualPage(0, null), result[0])
        assertEquals(VirtualPage(1, null), result[1])
        // page 2 splits into LEFT, RIGHT
        assertEquals(VirtualPage(2, PageSplitHalf.LEFT), result[2])
        assertEquals(VirtualPage(2, PageSplitHalf.RIGHT), result[3])
        // pages 3, 4 shifted
        assertEquals(VirtualPage(3, null), result[4])
        assertEquals(VirtualPage(4, null), result[5])
    }

    @Test
    fun `RTL single spread produces RIGHT then LEFT`() {
        val result = buildVirtualPageList(totalPages = 5, spreadPages = setOf(2), isRtl = true)
        assertEquals(6, result.size)
        assertEquals(VirtualPage(2, PageSplitHalf.RIGHT), result[2])
        assertEquals(VirtualPage(2, PageSplitHalf.LEFT), result[3])
    }

    @Test
    fun `multiple spreads expand correctly`() {
        val result = buildVirtualPageList(totalPages = 5, spreadPages = setOf(1, 3), isRtl = false)
        // 5 pages + 2 extra from spreads = 7
        assertEquals(7, result.size)
        assertEquals(VirtualPage(0, null), result[0])
        assertEquals(VirtualPage(1, PageSplitHalf.LEFT), result[1])
        assertEquals(VirtualPage(1, PageSplitHalf.RIGHT), result[2])
        assertEquals(VirtualPage(2, null), result[3])
        assertEquals(VirtualPage(3, PageSplitHalf.LEFT), result[4])
        assertEquals(VirtualPage(3, PageSplitHalf.RIGHT), result[5])
        assertEquals(VirtualPage(4, null), result[6])
    }

    @Test
    fun `all pages are spreads doubles virtual count`() {
        val result = buildVirtualPageList(totalPages = 3, spreadPages = setOf(0, 1, 2), isRtl = false)
        assertEquals(6, result.size)
        for (i in 0 until 3) {
            val base = i * 2
            assertEquals(VirtualPage(i, PageSplitHalf.LEFT), result[base])
            assertEquals(VirtualPage(i, PageSplitHalf.RIGHT), result[base + 1])
        }
    }

    @Test
    fun `empty page list returns empty`() {
        val result = buildVirtualPageList(totalPages = 0, spreadPages = emptySet(), isRtl = false)
        assertEquals(0, result.size)
    }

    @Test
    fun `out-of-range spread index is ignored`() {
        val result = buildVirtualPageList(totalPages = 3, spreadPages = setOf(10), isRtl = false)
        assertEquals(3, result.size)
        result.forEachIndexed { i, vp ->
            assertEquals(i, vp.realIndex)
            assertNull(vp.splitHalf)
        }
    }

    // --- realPageIndex ---

    @Test
    fun `realPageIndex maps both halves to same real index`() {
        val vps = buildVirtualPageList(totalPages = 5, spreadPages = setOf(2), isRtl = false)
        // virtual indices 2 and 3 both map to real page 2
        assertEquals(2, vps.realPageIndex(2))
        assertEquals(2, vps.realPageIndex(3))
        // normal pages map directly
        assertEquals(0, vps.realPageIndex(0))
        assertEquals(1, vps.realPageIndex(1))
        assertEquals(3, vps.realPageIndex(4))
        assertEquals(4, vps.realPageIndex(5))
    }

    // --- firstVirtualIndex ---

    @Test
    fun `firstVirtualIndex maps real to first virtual occurrence`() {
        val vps = buildVirtualPageList(totalPages = 5, spreadPages = setOf(2), isRtl = false)
        assertEquals(0, vps.firstVirtualIndex(0))
        assertEquals(1, vps.firstVirtualIndex(1))
        assertEquals(2, vps.firstVirtualIndex(2)) // first half of spread
        assertEquals(4, vps.firstVirtualIndex(3))
        assertEquals(5, vps.firstVirtualIndex(4))
    }

    // --- boundary cases ---

    @Test
    fun `first page as spread works correctly`() {
        val vps = buildVirtualPageList(totalPages = 3, spreadPages = setOf(0), isRtl = false)
        assertEquals(4, vps.size)
        assertEquals(VirtualPage(0, PageSplitHalf.LEFT), vps[0])
        assertEquals(VirtualPage(0, PageSplitHalf.RIGHT), vps[1])
        assertEquals(0, vps.realPageIndex(0))
        assertEquals(0, vps.realPageIndex(1))
        assertEquals(0, vps.firstVirtualIndex(0))
    }

    @Test
    fun `last page as spread works correctly`() {
        val vps = buildVirtualPageList(totalPages = 3, spreadPages = setOf(2), isRtl = false)
        assertEquals(4, vps.size)
        assertEquals(VirtualPage(2, PageSplitHalf.LEFT), vps[2])
        assertEquals(VirtualPage(2, PageSplitHalf.RIGHT), vps[3])
        assertEquals(2, vps.realPageIndex(2))
        assertEquals(2, vps.realPageIndex(3))
        assertEquals(2, vps.firstVirtualIndex(2))
    }
}
