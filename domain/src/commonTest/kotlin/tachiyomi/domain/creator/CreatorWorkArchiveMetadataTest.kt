package tachiyomi.domain.creator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorWorkDatePolicy

class CreatorWorkArchiveMetadataTest {
    @Test
    fun `first seen timestamp is write once`() {
        assertEquals(1_735_689_600_000L, CreatorWorkDatePolicy.firstSeenAt(null, 1_735_689_600_000L))
        assertEquals(1_735_689_600_000L, CreatorWorkDatePolicy.firstSeenAt(1_735_689_600_000L, 1_800_000_000_000L))
    }

    @Test
    fun `earliest valid observation is selected across source versions`() {
        assertEquals(
            1_700_000_000_000L,
            CreatorWorkDatePolicy.earliestFirstSeenAt(
                listOf(0L, 1_800_000_000_000L, 1_700_000_000_000L, -1L),
            ),
        )
        assertEquals(null, CreatorWorkDatePolicy.earliestFirstSeenAt(listOf(0L, -1L)))
    }
}
