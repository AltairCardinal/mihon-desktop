package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DualPagePairingTest {

    // region firstPage helpers

    @Test
    fun `buildPairings - all portrait pages are paired two by two`() {
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 4,
            isSpread = { false },
        )
        assertEquals(2, result.size)
        assertArrayEquals(intArrayOf(0, 1), result[0])
        assertArrayEquals(intArrayOf(2, 3), result[1])
    }

    @Test
    fun `buildPairings - odd count of portrait pages last page shown alone`() {
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 3,
            isSpread = { false },
        )
        assertEquals(2, result.size)
        assertArrayEquals(intArrayOf(0, 1), result[0])
        assertArrayEquals(intArrayOf(2), result[1])
    }

    @Test
    fun `buildPairings - spread page shown alone`() {
        // Pages: 0=portrait, 1=spread, 2=portrait, 3=portrait
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 4,
            isSpread = { index -> index == 1 },
        )
        assertEquals(3, result.size)
        assertArrayEquals(intArrayOf(0), result[0])
        assertArrayEquals(intArrayOf(1), result[1])
        assertArrayEquals(intArrayOf(2, 3), result[2])
    }

    @Test
    fun `buildPairings - portrait followed by spread both shown alone`() {
        // Pages: 0=portrait, 1=spread
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 2,
            isSpread = { index -> index == 1 },
        )
        assertEquals(2, result.size)
        assertArrayEquals(intArrayOf(0), result[0])
        assertArrayEquals(intArrayOf(1), result[1])
    }

    @Test
    fun `buildPairings - unknown dimensions treated as single page`() {
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 2,
            isSpread = { null },
        )
        assertEquals(2, result.size)
        assertArrayEquals(intArrayOf(0), result[0])
        assertArrayEquals(intArrayOf(1), result[1])
    }

    @Test
    fun `buildPairings - unknown first page prevents pairing with next portrait page`() {
        // 0=unknown, 1=portrait -> 0 alone, 1 alone (cannot pair unknown with known)
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 2,
            isSpread = { index -> if (index == 0) null else false },
        )
        assertEquals(2, result.size)
        assertArrayEquals(intArrayOf(0), result[0])
        assertArrayEquals(intArrayOf(1), result[1])
    }

    @Test
    fun `buildPairings - offset 1 makes first page single then pairs remainder`() {
        // Pages: 0=portrait, 1=portrait, 2=portrait, 3=portrait
        // offset=1 -> [0], [1,2], [3]
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 4,
            isSpread = { false },
            offset = 1,
        )
        assertEquals(3, result.size)
        assertArrayEquals(intArrayOf(0), result[0])
        assertArrayEquals(intArrayOf(1, 2), result[1])
        assertArrayEquals(intArrayOf(3), result[2])
    }

    @Test
    fun `buildPairings - offset wraps when exceeding adjusted range`() {
        // offset = pageCount means it resets to 0 effectively (modulo pageCount)
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 2,
            isSpread = { false },
            offset = 2,
        )
        // offset=2 for 2 pages → offset becomes 0 (mod 2) → normal pairing: [0,1]
        assertEquals(1, result.size)
        assertArrayEquals(intArrayOf(0, 1), result[0])
    }

    @Test
    fun `buildPairings - portrait followed by unknown does not form a pair`() {
        // 0=portrait, 1=unknown
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 2,
            isSpread = { index -> if (index == 1) null else false },
        )
        assertEquals(2, result.size)
        assertArrayEquals(intArrayOf(0), result[0])
        assertArrayEquals(intArrayOf(1), result[1])
    }

    @Test
    fun `buildPairings - first page in a pair is index 0 (right side in R2L)`() {
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 2,
            isSpread = { false },
        )
        val pair = result[0]
        assertEquals(2, pair.size)
        assertEquals(0, pair[0]) // right (first in reading order)
        assertEquals(1, pair[1]) // left (second in reading order)
    }

    @Test
    fun `buildPairings - single page count`() {
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 1,
            isSpread = { false },
        )
        assertEquals(1, result.size)
        assertArrayEquals(intArrayOf(0), result[0])
    }

    @Test
    fun `buildPairings - empty chapter returns empty list`() {
        val result = PagePairingAlgorithm.buildPairings(
            pageCount = 0,
            isSpread = { false },
        )
        assertEquals(0, result.size)
    }
}
