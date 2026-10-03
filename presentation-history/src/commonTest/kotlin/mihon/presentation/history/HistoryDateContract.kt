package mihon.presentation.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

abstract class HistoryDateContract {
    @Test
    fun `calendar ranges and disabled relative dates retain upstream preference behavior`() {
        val today = LocalDate.of(2026, 10, 4)
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        assertEquals(HistoryDateLabel.Today, historyDateLabel(today, true, formatter, today))
        assertEquals(HistoryDateLabel.DaysAgo(1), historyDateLabel(today.minusDays(1), true, formatter, today))
        assertEquals(HistoryDateLabel.DaysAgo(6), historyDateLabel(today.minusDays(6), true, formatter, today))
        assertEquals(
            HistoryDateLabel.Formatted("2026-09-27"),
            historyDateLabel(today.minusDays(7), true, formatter, today),
        )
        assertEquals(HistoryDateLabel.Upcoming(7), historyDateLabel(today.plusDays(7), true, formatter, today))
        assertEquals(
            HistoryDateLabel.Formatted("2026-10-12"),
            historyDateLabel(today.plusDays(8), true, formatter, today),
        )
        assertEquals(HistoryDateLabel.Formatted("2026-10-04"), historyDateLabel(today, false, formatter, today))
        assertEquals("04/10/2026", HistoryDatePreferences(false, "dd/MM/yyyy").formatter().format(today))
    }
}
