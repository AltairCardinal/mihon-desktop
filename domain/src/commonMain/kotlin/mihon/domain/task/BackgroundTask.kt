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

@Serializable
data class TaskOccurrence(
    val task: BackgroundTask,
    val status: TaskStatus = TaskStatus.Pending,
)

sealed interface TaskLifecycleEvent {
    data class Register(val task: BackgroundTask) : TaskLifecycleEvent

    data object Start : TaskLifecycleEvent

    data class Checkpoint(val checkpoint: TaskCheckpoint) : TaskLifecycleEvent

    data object Complete : TaskLifecycleEvent

    data object Fail : TaskLifecycleEvent

    data object Cancel : TaskLifecycleEvent
}

enum class TaskLifecycleOutcome {
    Applied,
    AlreadyApplied,
    Rejected,
}

enum class TaskLifecycleRejection {
    MissingOccurrence,
    ActiveOccurrence,
    InvalidTransition,
}

data class TaskLifecycleDecision(
    val outcome: TaskLifecycleOutcome,
    val occurrence: TaskOccurrence?,
    val rejection: TaskLifecycleRejection? = null,
)

object BackgroundTaskLifecycle {
    fun reduce(
        current: TaskOccurrence?,
        event: TaskLifecycleEvent,
    ): TaskLifecycleDecision = when (event) {
        is TaskLifecycleEvent.Register -> register(current, event.task)
        else -> current?.let { transition(it, event) }
            ?: rejected(null, TaskLifecycleRejection.MissingOccurrence)
    }

    private fun register(
        current: TaskOccurrence?,
        task: BackgroundTask,
    ): TaskLifecycleDecision = when {
        current?.task?.idempotencyKey == task.idempotencyKey -> alreadyApplied(current)
        current == null || current.status in terminalStatuses -> applied(TaskOccurrence(task))
        else -> rejected(current, TaskLifecycleRejection.ActiveOccurrence)
    }

    private fun transition(
        current: TaskOccurrence,
        event: TaskLifecycleEvent,
    ): TaskLifecycleDecision = when (event) {
        is TaskLifecycleEvent.Register -> error("Register events are handled separately")
        TaskLifecycleEvent.Start -> when (current.status) {
            TaskStatus.Pending -> applied(current.copy(status = TaskStatus.Running))
            TaskStatus.Running -> alreadyApplied(current)
            else -> invalid(current)
        }
        is TaskLifecycleEvent.Checkpoint -> when {
            current.status != TaskStatus.Running -> invalid(current)
            current.task.checkpoint == event.checkpoint -> alreadyApplied(current)
            else -> applied(current.copy(task = current.task.copy(checkpoint = event.checkpoint)))
        }
        TaskLifecycleEvent.Complete -> finish(current, TaskStatus.Completed)
        TaskLifecycleEvent.Fail -> finish(current, TaskStatus.Failed)
        TaskLifecycleEvent.Cancel -> when (current.status) {
            TaskStatus.Running -> applied(current.copy(status = TaskStatus.Cancelled))
            else -> invalid(current)
        }
    }

    private fun finish(
        current: TaskOccurrence,
        status: TaskStatus,
    ): TaskLifecycleDecision = if (current.status == TaskStatus.Running) {
        applied(current.copy(status = status))
    } else {
        invalid(current)
    }

    private fun applied(occurrence: TaskOccurrence) = TaskLifecycleDecision(
        outcome = TaskLifecycleOutcome.Applied,
        occurrence = occurrence,
    )

    private fun alreadyApplied(occurrence: TaskOccurrence) = TaskLifecycleDecision(
        outcome = TaskLifecycleOutcome.AlreadyApplied,
        occurrence = occurrence,
    )

    private fun invalid(occurrence: TaskOccurrence) = rejected(
        occurrence = occurrence,
        rejection = TaskLifecycleRejection.InvalidTransition,
    )

    private fun rejected(
        occurrence: TaskOccurrence?,
        rejection: TaskLifecycleRejection,
    ) = TaskLifecycleDecision(
        outcome = TaskLifecycleOutcome.Rejected,
        occurrence = occurrence,
        rejection = rejection,
    )

    private val terminalStatuses = setOf(
        TaskStatus.Completed,
        TaskStatus.Failed,
        TaskStatus.Cancelled,
    )
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
