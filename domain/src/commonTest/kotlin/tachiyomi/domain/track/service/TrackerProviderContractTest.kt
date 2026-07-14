package tachiyomi.domain.track.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrackerProviderContractTest {
    @Test
    fun `shared provider contracts preserve Android ids and status wire values`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), TrackerProviderContracts.androidProviderIds)
        assertEquals("plan_to_read", TrackerProviderContracts.myAnimeList.statusToWire(6))
        assertEquals("CURRENT", TrackerProviderContracts.aniList.statusToWire(1))
        assertEquals("planned", TrackerProviderContracts.kitsu.statusToWire(5))
        assertEquals("rewatching", TrackerProviderContracts.shikimori.statusToWire(6))
        assertEquals("1", TrackerProviderContracts.bangumi.statusToWire(1))
    }
}
