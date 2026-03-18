package tachiyomi.core.common.util.system

import logcat.LogPriority

inline fun Any.logcat(
    priority: LogPriority = LogPriority.DEBUG,
    throwable: Throwable? = null,
    message: () -> String = { "" },
) {
    val tag = this::class.simpleName ?: "Mihon"
    var msg = message()
    if (throwable != null) {
        if (msg.isNotBlank()) msg += "\n"
        msg += throwable.stackTraceToString()
    }
    val level = when (priority) {
        LogPriority.ERROR, LogPriority.ASSERT -> System.err
        else -> System.out
    }
    level.println("[$tag] $msg")
}
