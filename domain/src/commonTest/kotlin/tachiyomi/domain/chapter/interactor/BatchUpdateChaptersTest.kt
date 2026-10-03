package tachiyomi.domain.chapter.interactor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class BatchUpdateChaptersTest {
    @Test
    fun `batch cancellation propagates and never processes the next chapter`() = runTest {
        val visited = mutableListOf<Long>()
        var canceled = false
        try {
            BatchUpdateChapters().await(listOf(1L, 2L).map { Chapter.create().copy(id = it) }) {
                visited += it.id
                throw CancellationException("operation canceled")
            }
        } catch (expected: CancellationException) {
            canceled = true
        }
        assertEquals(true, canceled)
        assertEquals(listOf(1L), visited)
    }

    @Test
    fun `empty selection returns empty result`() = runTest {
        assertEquals(BatchChapterResult.Empty, BatchUpdateChapters().await(emptyList()) {})
    }

    @Test
    fun `continues after failure and reports each item`() = runTest {
        val chapters = listOf(Chapter.create().copy(id = 1L), Chapter.create().copy(id = 2L))
        val result = BatchUpdateChapters().await(chapters) { if (it.id == 2L) error("disk full") }

        assertEquals(listOf(1L), result.succeededIds)
        assertEquals(2L, result.failures.single().id)
        assertEquals("disk full", result.failures.single().message)
    }
}
