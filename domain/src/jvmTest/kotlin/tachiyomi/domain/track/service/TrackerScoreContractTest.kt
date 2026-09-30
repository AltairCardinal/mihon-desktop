package tachiyomi.domain.track.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrackerScoreContractTest {
    @Test
    fun `storage scale is explicit and never guessed from score magnitude`() {
        for (score in listOf(0.0, 3.0, 20.0, 85.0, 100.0)) {
            assertEquals(score / 10.0, TrackerProviderContracts.tenPointScore(2, score))
        }
        for (id in listOf(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)) {
            for (score in listOf(0.0, 3.0, 8.5, 10.0)) {
                assertEquals(score, TrackerProviderContracts.tenPointScore(id, score))
            }
        }
        assertEquals(
            4.0,
            listOf(80.0 to 2L, 0.0 to 3L).map {
                TrackerProviderContracts.tenPointScore(it.second, it.first)
            }.average(),
        )
    }
}
