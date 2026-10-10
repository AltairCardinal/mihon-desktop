package mihon.presentation.history

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

data class HistoryDatePreferences(val relativeTime: Boolean = true, val dateFormat: String = "") {
    fun formatter(): DateTimeFormatter = if (dateFormat.isEmpty()) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofPattern(dateFormat, Locale.getDefault())
    }
}

sealed interface HistoryDateLabel {
    data object Today : HistoryDateLabel
    data class DaysAgo(val days: Int) : HistoryDateLabel
    data class Upcoming(val days: Int) : HistoryDateLabel
    data class Formatted(val text: String) : HistoryDateLabel
}

/** Pinned upstream calendar-day ranges; preferences and resource translation are adapters. */
fun historyDateLabel(
    date: LocalDate,
    relative: Boolean,
    formatter: DateTimeFormatter,
    today: LocalDate = LocalDate.now(),
): HistoryDateLabel {
    if (!relative) return HistoryDateLabel.Formatted(formatter.format(date))
    val difference = ChronoUnit.DAYS.between(date, today)
    return when {
        difference < -7 -> HistoryDateLabel.Formatted(formatter.format(date))
        difference < 0 -> HistoryDateLabel.Upcoming((-difference).toInt())
        difference < 1 -> HistoryDateLabel.Today
        difference < 7 -> HistoryDateLabel.DaysAgo(difference.toInt())
        else -> HistoryDateLabel.Formatted(formatter.format(date))
    }
}
