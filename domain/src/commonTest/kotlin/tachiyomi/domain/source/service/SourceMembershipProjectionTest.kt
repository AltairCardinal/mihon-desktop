package tachiyomi.domain.source.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source

class SourceMembershipProjectionTest {

    @Test
    fun `fixed-main membership keeps local filters disabled and projects stable pin and last-used copies`() {
        val lastUsed = source(1, "en", "alpha")
        val pinned = source(2, "en", "Bravo")
        val local = source(3, "fr", "Local")
        val disabled = source(4, "en", "Disabled")
        val disabledLocal = source(5, "ja", "Hidden local")

        val projected = FixedMainSourceMembershipProjection.project(
            candidates = listOf(
                SourceMembershipCandidate(pinned),
                SourceMembershipCandidate(disabled),
                SourceMembershipCandidate(local, isLocal = true),
                SourceMembershipCandidate(lastUsed),
                SourceMembershipCandidate(disabledLocal, isLocal = true),
            ),
            preferences = SourceMembershipPreferences(
                enabledLanguages = setOf("en"),
                disabledSourceIds = setOf(disabled.id.toString(), disabledLocal.id.toString()),
                pinnedSourceIds = setOf(pinned.id.toString(), lastUsed.id.toString()),
                lastUsedSourceId = lastUsed.id,
            ),
        )

        assertEquals(listOf(lastUsed.id, lastUsed.id, pinned.id, local.id), projected.map { it.id })
        assertFalse(projected[0].isUsedLast)
        assertTrue(Pin.Actual in projected[0].pin)
        assertTrue(projected[1].isUsedLast)
        assertFalse(Pin.Actual in projected[1].pin)
        assertTrue(Pin.Actual in projected[2].pin)
        assertEquals(listOf(lastUsed.id, pinned.id, local.id), projected.filterNot { it.isUsedLast }.map { it.id })
    }

    private fun source(id: Long, lang: String, name: String) =
        Source(id, lang, name, supportsLatest = false, isStub = false)
}
