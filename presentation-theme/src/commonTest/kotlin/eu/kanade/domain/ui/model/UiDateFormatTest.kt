package eu.kanade.domain.ui.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiDateFormatTest {
    @Test
    fun `calendar window is today past one through six and future one through seven`() {
        val today = LocalDate.of(2024, 3, 10)
        for (offset in -7L..6L) {
            assertEquals(
                offset.toInt(),
                UiDateFormat.relativeDays(today.minusDays(offset), today, true),
                "offset $offset",
            )
        }
        assertNull(UiDateFormat.relativeDays(today.minusDays(7), today, true))
        assertNull(UiDateFormat.relativeDays(today.plusDays(8), today, true))
    }

    @Test
    fun `disabled relative display and a leap day use the same calendar authority`() {
        val today = LocalDate.of(2024, 3, 1)
        assertNull(UiDateFormat.relativeDays(today, today, false))
        assertEquals(1, UiDateFormat.relativeDays(LocalDate.of(2024, 2, 29), today, true))
    }

    @Test
    fun `DST and zone crossings count local days instead of elapsed twenty four hours`() {
        val zone = ZoneId.of("America/New_York")
        val previous = Instant.parse("2024-03-10T04:30:00Z").atZone(zone).toLocalDate()
        val now = Instant.parse("2024-03-11T03:30:00Z").atZone(zone).toLocalDate()
        assertEquals(
            23,
            ChronoUnit.HOURS.between(Instant.parse("2024-03-10T04:30:00Z"), Instant.parse("2024-03-11T03:30:00Z")),
        )
        assertEquals(1, UiDateFormat.relativeDays(previous, now, true))
        val utcPrevious = Instant.parse("2024-03-10T04:30:00Z").atZone(ZoneId.of("UTC")).toLocalDate()
        val utcNow = Instant.parse("2024-03-11T03:30:00Z").atZone(ZoneId.of("UTC")).toLocalDate()
        assertEquals(1, UiDateFormat.relativeDays(utcPrevious, utcNow, true))
    }
}
