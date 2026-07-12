package mihon.domain.task

import kotlinx.serialization.Serializable

@Serializable
data class BackgroundTask(
    val id: String,
    val idempotencyKey: String,
    val constraints: Set<TaskConstraint> = emptySet(),
    val checkpoint: TaskCheckpoint? = null,
)

@Serializable
enum class TaskConstraint {
    NetworkConnected,
    UnmeteredNetwork,
    Charging,
}

@Serializable
data class TaskCheckpoint(
    val cursor: String? = null,
    val completedUnits: Int = 0,
    val progress: Float? = null,
) {
    init {
        require(completedUnits >= 0)
        require(progress == null || progress in 0f..1f)
    }
}

@Serializable
enum class TaskStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
}

sealed interface NotificationEvent {
    val taskId: String
    val title: String

    data class Progress(
        override val taskId: String,
        override val title: String,
        val progress: Float?,
    ) : NotificationEvent

    data class Success(
        override val taskId: String,
        override val title: String,
        val message: String,
    ) : NotificationEvent

    data class Failure(
        override val taskId: String,
        override val title: String,
        val message: String,
    ) : NotificationEvent

    data class Cancelled(
        override val taskId: String,
        override val title: String,
    ) : NotificationEvent
}
