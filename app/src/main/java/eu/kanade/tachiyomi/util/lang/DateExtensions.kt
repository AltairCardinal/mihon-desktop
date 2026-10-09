package eu.kanade.tachiyomi.util.lang

import android.content.Context
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

fun LocalDateTime.toDateTimestampString(dateTimeFormatter: DateTimeFormatter): String {
    val date = dateTimeFormatter.format(this)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(this)
    return "$date $time"
}

fun Date.toTimestampString(): String {
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(this)
}

fun Long.convertEpochMillisZone(
    from: ZoneId,
    to: ZoneId,
): Long {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(this), from)
        .atZone(to)
        .toInstant()
        .toEpochMilli()
}

fun Long.toLocalDate(): LocalDate {
    return LocalDate.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
}

fun Instant.toLocalDate(zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
    return LocalDate.ofInstant(this, zoneId)
}

fun LocalDate.toRelativeString(
    context: Context,
    relative: Boolean = true,
    dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT),
    today: LocalDate = LocalDate.now(),
): String {
    return when (val label = mihon.presentation.history.historyDateLabel(this, relative, dateFormat, today)) {
        mihon.presentation.history.HistoryDateLabel.Today -> context.stringResource(MR.strings.relative_time_today)
        is mihon.presentation.history.HistoryDateLabel.DaysAgo -> context.pluralStringResource(
            MR.plurals.relative_time,
            label.days,
            label.days,
        )
        is mihon.presentation.history.HistoryDateLabel.Upcoming -> context.pluralStringResource(
            MR.plurals.upcoming_relative_time,
            label.days,
            label.days,
        )
        is mihon.presentation.history.HistoryDateLabel.Formatted -> label.text
    }
}
