package mihon.desktop.ui.updates

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
/**
 * Formats a chapter fetch timestamp for display in the Updates tab:
 * - Today → "HH:mm" (e.g. "10:05")
 * - Yesterday → "Yesterday"
 * - Within the past 7 days → 3-letter day abbreviation (e.g. "Mon")
 * - Older → "MMM dd" (e.g. "Mar 16")
 */
fun formatUpdateTime(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val instant = Instant.ofEpochMilli(epochMillis)
    val itemDate = instant.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)

    return when {
        itemDate == today -> instant.atZone(zone).format(timeFormatter)
        itemDate == today.minusDays(1) -> tachiyomi.i18n.MR.strings.desktop_ui_yesterday.localized(locale)
        itemDate.isAfter(today.minusDays(7)) ->
            itemDate.atStartOfDay(zone).format(DateTimeFormatter.ofPattern("EEE", locale))
        else ->
            itemDate.atStartOfDay(zone).format(DateTimeFormatter.ofPattern("MMM dd", locale))
    }
}
