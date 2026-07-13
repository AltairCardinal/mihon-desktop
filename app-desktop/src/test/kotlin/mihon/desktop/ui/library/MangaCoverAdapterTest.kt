package mihon.desktop.ui.library

import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MangaCoverAdapterTest {
    @Test
    fun `cancelled picker has no business side effects`() = runTest {
        var updates = 0
        val adapter = MangaCoverAdapter(CoverFilePicker { null }) { _, _ -> updates++; TaskState.Success(Unit) }

        val result = adapter.chooseAndUpdate(1)

        assertNull(result)
        assertEquals(0, updates)
    }

    @Test
    fun `selected bytes use shared workflow and preserve structured failure`() = runTest {
        val adapter = MangaCoverAdapter(CoverFilePicker { byteArrayOf(4, 2) }) { _, bytes ->
            assertEquals(listOf<Byte>(4, 2), bytes.toList())
            TaskState.Failure(AppError.Storage())
        }

        val result = adapter.chooseAndUpdate(1)

        val failure = assertInstanceOf(TaskState.Failure::class.java, result)
        assertInstanceOf(AppError.Storage::class.java, failure.error)
    }
}
