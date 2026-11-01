package mihon.desktop.domain

import mihon.domain.task.NotificationEvent

class DesktopSystemNotifier(
    private val system: (DesktopNotification) -> Boolean,
    private val fallback: DesktopNotificationService,
) {
    fun notify(event: NotificationEvent) {
        // Library updates have one feedback owner: the session-scoped library banner.
        if (event.taskId == LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.id) return
        val notification = DesktopNotification(
            title = event.title,
            message = when (event) {
                is NotificationEvent.Progress -> event.progress?.let { "${(it * 100).toInt()}%" } ?: "In progress"
                is NotificationEvent.Success -> event.message
                is NotificationEvent.Failure -> event.message
                is NotificationEvent.Cancelled -> "Cancelled"
            },
        )
        post(notification)
    }

    fun post(notification: DesktopNotification): Boolean {
        if (runCatching { system(notification) }.getOrDefault(false).not()) fallback.post(notification)
        return true
    }
}
