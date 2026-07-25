package tachiyomi.domain.track.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `MAL preserves fixed-main fallback semantics for unknown statuses`() {
        assertEquals("reading", TrackerProviderContracts.myAnimeList.statusToWire(7))
        assertEquals("reading", TrackerProviderContracts.myAnimeList.statusToWire(Long.MAX_VALUE))
        assertEquals(1L, TrackerProviderContracts.myAnimeList.wireToStatus("reading"))
        assertEquals(1L, TrackerProviderContracts.myAnimeList.wireToStatus("unknown"))
    }

    @Test
    fun `non-MAL providers reject unknown outbound statuses`() {
        listOf(
            TrackerProviderContracts.aniList,
            TrackerProviderContracts.kitsu,
            TrackerProviderContracts.shikimori,
            TrackerProviderContracts.bangumi,
        ).forEach { contract ->
            assertThrows(IllegalArgumentException::class.java) {
                contract.statusToWire(Long.MAX_VALUE)
            }
        }
    }

    @Test
    fun `Shikimori rejects unknown inbound statuses`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerProviderContracts.shikimori.wireToStatus("unknown")
        }
    }

    @Test
    fun `Android provider configuration preserves fixed main authentication`() {
        assertEquals(
            listOf(
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.USERNAME_PASSWORD,
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.API_KEY,
                TrackerAuthentication.USERNAME_PASSWORD,
                TrackerAuthentication.API_KEY,
                TrackerAuthentication.API_KEY,
            ),
            TrackerProviderContracts.androidProviderIds.map(TrackerProviderContracts::authentication),
        )
    }
}
