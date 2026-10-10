package mihon.presentation.sync

import java.text.DateFormat
import java.util.Date

internal actual fun syncScheduleDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
