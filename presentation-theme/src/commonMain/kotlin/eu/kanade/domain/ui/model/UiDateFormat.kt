package eu.kanade.domain.ui.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Canonical appearance date choices shared by Android and Desktop. */
object UiDateFormat {
    val patterns = listOf("", "MM/dd/yy", "dd/MM/yy", "yyyy-MM-dd", "dd MMM yyyy", "MMM dd, yyyy")

    /** Relative labels follow local calendar days, including seven upcoming days. */
    fun relativeDays(date: LocalDate, today: LocalDate, enabled: Boolean): Int? =
        if (enabled) ChronoUnit.DAYS.between(date, today).takeIf { it in -7L..6L }?.toInt() else null

    fun formatter(pattern: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        if (pattern.isEmpty()) {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        } else {
            DateTimeFormatter.ofPattern(pattern, locale)
        }
}
