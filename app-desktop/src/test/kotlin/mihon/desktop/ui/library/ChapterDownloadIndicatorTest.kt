package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChapterDownloadIndicatorTest {

    @Test
    fun `returns null when totalPages is zero`() {
        assertNull(downloadProgressFraction(progress = 0, totalPages = 0))
    }

    @Test
    fun `returns null when progress is zero regardless of totalPages`() {
        assertNull(downloadProgressFraction(progress = 0, totalPages = 10))
    }

    @Test
    fun `returns correct fraction for partial progress`() {
        val result = downloadProgressFraction(progress = 5, totalPages = 10)
        assertEquals(0.5f, result)
    }

    @Test
    fun `returns 1f when all pages downloaded`() {
        val result = downloadProgressFraction(progress = 10, totalPages = 10)
        assertEquals(1.0f, result)
    }

    @Test
    fun `clamps to 1f if progress exceeds totalPages`() {
        val result = downloadProgressFraction(progress = 12, totalPages = 10)
        assertEquals(1.0f, result)
    }

    @Test
    fun `single page download at progress 1 returns 1f`() {
        val result = downloadProgressFraction(progress = 1, totalPages = 1)
        assertEquals(1.0f, result)
    }
}
