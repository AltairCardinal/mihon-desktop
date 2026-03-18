package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [PairingState] — the pure pairing-state manager extracted from DualPageViewerAdapter.
 *
 * This validates:
 * - Initial pairings with unknown dimensions (all singles)
 * - Pairings rebuild when dimensions arrive
 * - adjustPairing() increments offset and rebuilds
 * - R2L reversal produces reversed display order
 */
class DualPageViewerAdapterTest {

    @Test
    fun `initial state with no dimensions treats all pages as singles`() {
        val state = PairingState(pageCount = 4, isR2L = false)
        val units = state.pairings
        assertEquals(4, units.size)
        units.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun `after dimensions arrive portrait pages pair together`() {
        val state = PairingState(pageCount = 4, isR2L = false)
        // Mark all pages as portrait
        for (i in 0 until 4) state.updateDimensions(i, width = 100, height = 200)
        val units = state.pairings
        assertEquals(2, units.size)
        assertArrayEquals(intArrayOf(0, 1), units[0])
        assertArrayEquals(intArrayOf(2, 3), units[1])
    }

    @Test
    fun `adjustPairing increments offset and rebuilds`() {
        val state = PairingState(pageCount = 4, isR2L = false)
        for (i in 0 until 4) state.updateDimensions(i, width = 100, height = 200)

        state.adjustPairing()
        val units = state.pairings
        // offset=1 → [0], [1,2], [3]
        assertEquals(3, units.size)
        assertArrayEquals(intArrayOf(0), units[0])
        assertArrayEquals(intArrayOf(1, 2), units[1])
        assertArrayEquals(intArrayOf(3), units[2])
    }

    @Test
    fun `adjustPairing wraps offset back to 0 after full cycle`() {
        val state = PairingState(pageCount = 4, isR2L = false)
        for (i in 0 until 4) state.updateDimensions(i, width = 100, height = 200)
        // Offset cycles: 0→1→2→3→0
        repeat(4) { state.adjustPairing() }
        val units = state.pairings
        // back to default: [0,1], [2,3]
        assertEquals(2, units.size)
        assertArrayEquals(intArrayOf(0, 1), units[0])
        assertArrayEquals(intArrayOf(2, 3), units[1])
    }

    @Test
    fun `R2L mode reverses the pairing list`() {
        val state = PairingState(pageCount = 4, isR2L = true)
        for (i in 0 until 4) state.updateDimensions(i, width = 100, height = 200)
        val units = state.pairings
        // Reversed: [2,3], [0,1]
        assertEquals(2, units.size)
        assertArrayEquals(intArrayOf(2, 3), units[0])
        assertArrayEquals(intArrayOf(0, 1), units[1])
    }

    @Test
    fun `spread page keeps neighboring pages from pairing`() {
        val state = PairingState(pageCount = 4, isR2L = false)
        state.updateDimensions(0, width = 100, height = 200) // portrait
        state.updateDimensions(1, width = 200, height = 100) // spread
        state.updateDimensions(2, width = 100, height = 200) // portrait
        state.updateDimensions(3, width = 100, height = 200) // portrait
        val units = state.pairings
        // [0], [1], [2,3]
        assertEquals(3, units.size)
        assertArrayEquals(intArrayOf(0), units[0])
        assertArrayEquals(intArrayOf(1), units[1])
        assertArrayEquals(intArrayOf(2, 3), units[2])
    }

    @Test
    fun `findDisplayUnitIndexForPage returns correct position`() {
        val state = PairingState(pageCount = 4, isR2L = false)
        for (i in 0 until 4) state.updateDimensions(i, width = 100, height = 200)
        // pairings: [0,1]=index0, [2,3]=index1
        assertEquals(0, state.findDisplayUnitIndexForPage(0))
        assertEquals(0, state.findDisplayUnitIndexForPage(1))
        assertEquals(1, state.findDisplayUnitIndexForPage(2))
        assertEquals(1, state.findDisplayUnitIndexForPage(3))
    }

    @Test
    fun `findDisplayUnitIndexForPage with R2L gives reversed positions`() {
        val state = PairingState(pageCount = 4, isR2L = true)
        for (i in 0 until 4) state.updateDimensions(i, width = 100, height = 200)
        // reversed pairings: [2,3]=index0, [0,1]=index1
        assertEquals(1, state.findDisplayUnitIndexForPage(0))
        assertEquals(1, state.findDisplayUnitIndexForPage(1))
        assertEquals(0, state.findDisplayUnitIndexForPage(2))
        assertEquals(0, state.findDisplayUnitIndexForPage(3))
    }
}
