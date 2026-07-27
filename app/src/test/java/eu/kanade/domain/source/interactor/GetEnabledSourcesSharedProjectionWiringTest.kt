package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceMembershipCandidate
import tachiyomi.domain.source.service.SourceMembershipPreferences
import tachiyomi.domain.source.service.SourceMembershipProjection

class GetEnabledSourcesSharedProjectionWiringTest {

    @Test
    fun `Android production interactor delegates repository extension membership to shared projection`() = runTest {
        val local = source(0, "fr", "Local")
        val installed = source(1, "en", "Installed")
        val repository = mockk<SourceRepository> {
            every { getSources() } returns flowOf(listOf(installed, local))
        }
        val preferences = mockk<SourcePreferences> {
            every { enabledLanguages() } returns preference(setOf("en"))
            every { disabledSources() } returns preference(setOf("9"))
            every { pinnedSources() } returns preference(setOf(installed.id.toString()))
            every { lastUsedSource() } returns preference(installed.id)
        }
        val candidates = slot<List<SourceMembershipCandidate>>()
        val membership = slot<SourceMembershipPreferences>()
        val projected = source(99, "en", "Shared projection result")
        val projection = mockk<SourceMembershipProjection> {
            every { project(capture(candidates), capture(membership)) } returns listOf(projected)
        }

        val result = GetEnabledSources(repository, preferences, projection).subscribe().first()

        assertEquals(listOf(projected), result)
        assertEquals(listOf(installed.id, local.id), candidates.captured.map { it.source.id })
        assertTrue(candidates.captured.single { it.source.id == local.id }.isLocal)
        assertEquals(setOf("en"), membership.captured.enabledLanguages)
        assertEquals(setOf("9"), membership.captured.disabledSourceIds)
        assertEquals(setOf(installed.id.toString()), membership.captured.pinnedSourceIds)
        assertEquals(installed.id, membership.captured.lastUsedSourceId)
        verify(exactly = 1) { projection.project(any(), any()) }
    }

    private fun <T> preference(value: T) = mockk<Preference<T>> {
        every { changes() } returns flowOf(value)
    }

    private fun source(id: Long, lang: String, name: String) =
        Source(id, lang, name, supportsLatest = false, isStub = false)
}
