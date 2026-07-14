package mihon.desktop.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.DesktopTrackerSessionProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry

class DesktopTrackerSessionProviderTest {
    @Test
    fun `session provider exposes only logged in trackers and reacts to logout`() = runTest {
        val loggedIn = FakeService(1, true)
        val loggedOut = FakeService(2, false)
        val provider = DesktopTrackerSessionProvider(registry(loggedIn, loggedOut))

        assertEquals(setOf(1L), provider.loggedInTrackerIds().first())
        loggedIn.state.value = loggedIn.state.value.copy(loggedIn = false)

        assertEquals(emptySet<Long>(), provider.loggedInTrackerIds().first { it.isEmpty() })
    }

    private fun registry(vararg services: TrackerService) = object : TrackerServiceRegistry {
        override val services = services.toList()
    }

    private class FakeService(id: Long, loggedIn: Boolean) : TrackerService {
        val state = MutableStateFlow(TrackerProfile(id, "Service $id", TrackerAuthentication.OAUTH, loggedIn))
        override val profile = state
        override val statuses = emptyList<Pair<Long, String>>()
        override val scores = emptyList<Double>()
        override suspend fun search(query: String) = emptyList<TrackSearchResult>()
        override suspend fun bind(mangaId: Long, result: TrackSearchResult): Track = error("unused")
        override suspend fun update(track: Track, edit: TrackEdit): Track = error("unused")
        override suspend fun logout() { state.value = state.value.copy(loggedIn = false) }
    }
}
