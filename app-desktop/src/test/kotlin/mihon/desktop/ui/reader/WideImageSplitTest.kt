package mihon.desktop.ui.reader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class WideImageSplitTest {

    @Test
    fun `LEFT half of a 200x100 image covers pixels 0 to 99`() {
        val bounds = splitBounds(imageWidth = 200, imageHeight = 100, half = PageSplitHalf.LEFT)
        assertEquals(0, bounds.x)
        assertEquals(0, bounds.y)
        assertEquals(100, bounds.width)
        assertEquals(100, bounds.height)
    }

    @Test
    fun `RIGHT half of a 200x100 image covers pixels 100 to 199`() {
        val bounds = splitBounds(imageWidth = 200, imageHeight = 100, half = PageSplitHalf.RIGHT)
        assertEquals(100, bounds.x)
        assertEquals(0, bounds.y)
        assertEquals(100, bounds.width)
        assertEquals(100, bounds.height)
    }

    @Test
    fun `LEFT half of an odd-width image handles integer division`() {
        val bounds = splitBounds(imageWidth = 201, imageHeight = 100, half = PageSplitHalf.LEFT)
        assertEquals(0, bounds.x)
        assertEquals(100, bounds.width)   // floor(201/2) = 100
    }

    @Test
    fun `RIGHT half of an odd-width image starts at mid-point`() {
        val bounds = splitBounds(imageWidth = 201, imageHeight = 100, half = PageSplitHalf.RIGHT)
        assertEquals(100, bounds.x)       // starts right after LEFT portion
        assertEquals(101, bounds.width)   // remaining pixels
    }
}
