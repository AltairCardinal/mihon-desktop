package tachiyomi.domain.chapter.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that domain logic compiles and runs correctly on JVM target.
 * This mirrors the existing Android unit tests to confirm KMP source set setup.
 */
class MissingChaptersJvmTest {

    @Test
    fun `missingChaptersCount returns 0 for empty list`() {
        assertEquals(0, emptyList<Double>().missingChaptersCount())
    }

    @Test
    fun `missingChaptersCount detects missing chapters`() {
        // Chapters 1, 2, 5 → missing 3, 4
        assertEquals(2, listOf(1.0, 2.0, 5.0).missingChaptersCount())
    }

    @Test
    fun `missingChaptersCount ignores unknown chapter numbers`() {
        // -1 is unknown, should be ignored
        assertEquals(0, listOf(-1.0, -1.0).missingChaptersCount())
    }

    @Test
    fun `calculateChapterGap returns gap between chapters`() {
        // 5.0 to 2.0 → gap of 2 (chapters 3, 4)
        assertEquals(2, calculateChapterGap(5.0, 2.0))
    }

    @Test
    fun `calculateChapterGap returns 0 for negative numbers`() {
        assertEquals(0, calculateChapterGap(-1.0, 2.0))
        assertEquals(0, calculateChapterGap(5.0, -1.0))
    }

    @Test
    fun `calculateChapterGap returns 0 for consecutive chapters`() {
        assertEquals(0, calculateChapterGap(3.0, 2.0))
    }
}
