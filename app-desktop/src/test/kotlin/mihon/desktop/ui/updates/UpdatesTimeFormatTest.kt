package mihon.desktop.ui.updates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class UpdatesTimeFormatTest {

    private val zone = ZoneId.systemDefault()

    private fun epochOf(date: LocalDate, hour: Int = 10, minute: Int = 30): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `today returns HH-mm time string`() {
        val today = LocalDate.now()
        val result = formatUpdateTime(epochOf(today, 10, 5), zone)
        assertEquals("10:05", result)
    }

    @Test
    fun `yesterday returns Yesterday`() {
        val yesterday = LocalDate.now().minusDays(1)
        val result = formatUpdateTime(epochOf(yesterday), zone)
        assertEquals("Yesterday", result)
    }

    @Test
    fun `within 7 days returns non-time non-yesterday string`() {
        val twoDaysAgo = LocalDate.now().minusDays(2)
        val result = formatUpdateTime(epochOf(twoDaysAgo), zone)
        // Not a time (no colon), not "Yesterday", not empty
        assert(!result.contains(":")) { "Should not be a time string, got '$result'" }
        assert(result != "Yesterday") { "Should not be 'Yesterday', got '$result'" }
        assert(result.isNotBlank()) { "Should not be blank" }
    }

    @Test
    fun `older than 7 days returns date string distinct from day abbreviation`() {
        val today = LocalDate.now()
        val oldDate = today.minusDays(10)
        val recentDate = today.minusDays(3)
        val oldResult = formatUpdateTime(epochOf(oldDate), zone)
        val recentResult = formatUpdateTime(epochOf(recentDate), zone)
        // Old date format must differ from recent day-name format
        assert(oldResult != recentResult) { "Old ('$oldResult') should differ from recent ('$recentResult')" }
        assert(!oldResult.contains(":")) { "Old date should not be a time, got '$oldResult'" }
        assert(oldResult != "Yesterday") { "Old date should not be 'Yesterday'" }
    }
}
