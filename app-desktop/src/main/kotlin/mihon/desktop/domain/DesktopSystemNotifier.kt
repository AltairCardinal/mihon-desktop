package mihon.desktop.domain

import mihon.domain.task.NotificationEvent

class DesktopSystemNotifier(
    private val system: (DesktopNotification) -> Boolean,
    private val fallback: DesktopNotificationService,
) {
    fun notify(event: NotificationEvent) {
        val notification = DesktopNotification(
            title = event.title,
            message = when (event) {
                is NotificationEvent.Progress -> event.progress?.let { "${(it * 100).toInt()}%" } ?: "In progress"
                is NotificationEvent.Success -> event.message
                is NotificationEvent.Failure -> event.message
                is NotificationEvent.Cancelled -> "Cancelled"
            },
        )
        if (!system(notification)) fallback.post(notification)
    }
}
