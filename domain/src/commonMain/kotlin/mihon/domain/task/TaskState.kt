package mihon.domain.task

import mihon.domain.error.AppError

sealed interface TaskState<out T> {
    data object Idle : TaskState<Nothing>
    data class Running(val progress: Float? = null) : TaskState<Nothing>
    data class Success<out T>(val value: T) : TaskState<T>
    data class Failure(val error: AppError) : TaskState<Nothing>
    data object Cancelled : TaskState<Nothing>
}
