package tachiyomi.domain.creator.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.prefs.Preferences

class CreatorDiscoveryScheduleTest {
    @Test
    fun `calendar frequency clamps month end and handles DST gap and overlap`() {
        fun check(frequency: CreatorCheckFrequency, zone: String, start: String, expected: String) {
            val schedule = CreatorDiscoverySchedule({ frequency }, zone = { ZoneId.of(zone) })
            assertEquals(Instant.parse(expected).toEpochMilli(), schedule.nextDue(Instant.parse(start).toEpochMilli()))
        }
        check(CreatorCheckFrequency.MONTHLY, "UTC", "2024-01-31T12:00:00Z", "2024-02-29T12:00:00Z")
        check(CreatorCheckFrequency.MONTHLY, "UTC", "2025-01-31T12:00:00Z", "2025-02-28T12:00:00Z")
        check(CreatorCheckFrequency.DAILY, "America/New_York", "2025-03-08T07:30:00Z", "2025-03-09T07:30:00Z")
        check(CreatorCheckFrequency.WEEKLY, "America/New_York", "2025-03-02T17:00:00Z", "2025-03-09T16:00:00Z")
        check(CreatorCheckFrequency.DAILY, "America/New_York", "2025-11-01T05:30:00Z", "2025-11-02T05:30:00Z")
    }

    @Test
    fun `zone and frequency changes recalculate from the same successful instant`() {
        var zone = ZoneId.of("UTC")
        var frequency = CreatorCheckFrequency.DAILY
        val schedule = CreatorDiscoverySchedule({ frequency }, Clock.fixed(Instant.EPOCH, zone), { zone })
        val success = Instant.parse("2025-03-08T17:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2025-03-09T17:00:00Z").toEpochMilli(), schedule.nextDue(success))
        zone = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2025-03-09T16:00:00Z").toEpochMilli(), schedule.nextDue(success))
        frequency = CreatorCheckFrequency.WEEKLY
        assertEquals(Instant.parse("2025-03-15T16:00:00Z").toEpochMilli(), schedule.nextDue(success))
    }

    @Test
    fun `migration persists daily once and preserves existing global choices`() {
        val node = Preferences.userRoot().node("/mihon-tests/ga03-${UUID.randomUUID()}")
        try {
            val store = DesktopPreferenceStore(node)
            val preferences = CreatorDiscoveryPreferences(store)
            assertEquals(CreatorCheckFrequency.DAILY, preferences.current())
            assertEquals("daily", store.getAll()[CreatorDiscoveryPreferences.FREQUENCY_KEY])
            preferences.frequency().set("monthly")
            assertEquals(CreatorCheckFrequency.MONTHLY, CreatorDiscoveryPreferences(store).current())
            val prior = DesktopPreferenceStore(node.node("prior"))
            prior.getString(CreatorDiscoveryPreferences.FREQUENCY_KEY).set("weekly")
            assertEquals(CreatorCheckFrequency.WEEKLY, CreatorDiscoveryPreferences(prior).current())
        } finally {
            node.removeNode()
        }
    }
}
