package tachiyomi.domain.creator.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CreatorDiscoveryBackoffTest {

    @Test
    fun `backoff delays follow the frozen schedule`() {
        assertEquals(30 * 60 * 1_000L, CreatorDiscoveryBackoff.delayMillis(1))
        assertEquals(2 * 60 * 60 * 1_000L, CreatorDiscoveryBackoff.delayMillis(2))
        assertEquals(8 * 60 * 60 * 1_000L, CreatorDiscoveryBackoff.delayMillis(3))
        assertEquals(24 * 60 * 60 * 1_000L, CreatorDiscoveryBackoff.delayMillis(4))
    }

    @Test
    fun `backoff caps at the maximum delay and never falls below the first step`() {
        assertEquals(CreatorDiscoveryBackoff.DELAY_MILLIS.last(), CreatorDiscoveryBackoff.delayMillis(10))
        assertEquals(CreatorDiscoveryBackoff.DELAY_MILLIS.first(), CreatorDiscoveryBackoff.delayMillis(0))
        assertEquals(CreatorDiscoveryBackoff.DELAY_MILLIS.first(), CreatorDiscoveryBackoff.delayMillis(-3))
    }

    @Test
    fun `jitter is clamped so a retry is never earlier than the schedule`() {
        val base = CreatorDiscoveryBackoff.backoffUntilMillis(1_000L, 1, jitterMillis = 0L)
        assertEquals(base, CreatorDiscoveryBackoff.backoffUntilMillis(1_000L, 1, jitterMillis = -5_000L))
        assertEquals(base + 1_000L, CreatorDiscoveryBackoff.backoffUntilMillis(1_000L, 1, jitterMillis = 1_000L))
    }
}
