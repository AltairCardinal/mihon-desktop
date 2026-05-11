package mihon.desktop.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A lightweight application-level notification bus.
 *
 * Any component can [post] a [DesktopNotification] and any Composable can
 * collect [notifications] to show a snackbar.
 */
class DesktopNotificationService {

    private val _notifications = MutableSharedFlow<DesktopNotification>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Observe notifications; collect in a Composable to show snackbars. */
    val notifications = _notifications.asSharedFlow()

    /** Broadcasts [notification] to all active collectors. */
    fun post(notification: DesktopNotification) {
        _notifications.tryEmit(notification)
    }
}

data class DesktopNotification(
    val title: String,
    val message: String,
)
