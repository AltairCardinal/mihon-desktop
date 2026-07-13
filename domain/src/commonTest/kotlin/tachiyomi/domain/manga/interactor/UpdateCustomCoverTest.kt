package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class UpdateCustomCoverTest {
    @Test
    fun `successful write invalidates cover cache timestamp`() = runTest {
        val store = RecordingCoverStore()
        val invalidated = mutableListOf<Long>()
        val useCase = UpdateCustomCover(store) { invalidated += it }

        val result = useCase(7, byteArrayOf(1, 2, 3))

        assertInstanceOf(TaskState.Success::class.java, result)
        assertEquals(7L, store.mangaId)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.bytes)
        assertEquals(listOf(7L), invalidated)
    }

    @Test
    fun `write failure is structured and does not invalidate cache`() = runTest {
        val invalidated = mutableListOf<Long>()
        val useCase = UpdateCustomCover(
            coverStore = object : CustomCoverStore {
                override suspend fun write(mangaId: Long, bytes: ByteArray) = error("disk full")
            },
            invalidateCover = { invalidated += it },
        )

        val result = useCase(7, byteArrayOf(1))

        val failure = assertInstanceOf(TaskState.Failure::class.java, result)
        assertInstanceOf(AppError.Storage::class.java, failure.error)
        assertEquals(emptyList<Long>(), invalidated)
    }

    private class RecordingCoverStore : CustomCoverStore {
        var mangaId: Long? = null
        var bytes: ByteArray? = null
        override suspend fun write(mangaId: Long, bytes: ByteArray) {
            this.mangaId = mangaId
            this.bytes = bytes
        }
    }
}
