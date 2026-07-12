package mihon.domain.task

import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TaskStateTest {
    @Test
    fun `任务状态表达进度结果失败与取消`() {
        val running: TaskState<String> = TaskState.Running(progress = 0.5f)
        val success: TaskState<String> = TaskState.Success("done")
        val failure: TaskState<String> = TaskState.Failure(AppError.Cancelled)

        assertEquals(0.5f, (running as TaskState.Running).progress)
        assertEquals("done", (success as TaskState.Success).value)
        assertInstanceOf(AppError.Cancelled::class.java, (failure as TaskState.Failure).error)
        assertInstanceOf(TaskState.Cancelled::class.java, TaskState.Cancelled)
    }
}
