package mihon.desktop.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry

class TrackingTestModeControllerTest {
    @Test
    fun `test actions drive login search bind update and logout state`() = runTest {
        val repository = MemoryRepository()
        val service = TestService()
        val controller = TrackingTestModeController(
            repository = repository,
            chapterRepository = FakeChapterRepository(),
            registry = registry(service),
        )

        controller.execute("tracking_login", mapOf("trackerId" to "1", "username" to "user", "password" to "pass"))
        controller.execute("tracking_search", mapOf("trackerId" to "1", "mangaId" to "42", "title" to "Manga", "totalChapters" to "12"))
        controller.execute("tracking_bind", mapOf("trackerId" to "1", "mangaId" to "42", "resultIndex" to "0"))
        controller.execute("tracking_update", mapOf("trackerId" to "1", "mangaId" to "42", "status" to "2", "score" to "10", "chapter" to "12"))

        assertTrue(service.profile.value.loggedIn)
        assertEquals(2, repository.rows.single().status)
        assertEquals(12.0, repository.rows.single().lastChapterRead)

        controller.execute("tracking_logout", mapOf("trackerId" to "1"))
        assertFalse(service.profile.value.loggedIn)
    }

    private fun registry(service: TrackerService) = object : TrackerServiceRegistry { override val services = listOf(service) }

    private class MemoryRepository : TrackRepository {
        val rows = mutableListOf<Track>()
        override suspend fun getTrackById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = rows.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(rows)
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> = flowOf(rows.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) { rows.removeAll { it.mangaId == mangaId && it.trackerId == trackerId } }
        override suspend fun insert(track: Track) { rows.removeAll { it.mangaId == track.mangaId && it.trackerId == track.trackerId }; rows += track }
        override suspend fun insertAll(tracks: List<Track>) { tracks.forEach { insert(it) } }
    }

    private class TestService : DesktopAuthenticatingTrackerService {
        override val profile = MutableStateFlow(TrackerProfile(1, "Test", TrackerAuthentication.USERNAME_PASSWORD, false))
        override val statuses = listOf(1L to "Reading", 2L to "Completed")
        override val scores = listOf(0.0, 10.0)
        override fun authorizationUrl(redirectUri: String, state: String) = ""
        override suspend fun finishOAuth(code: String, redirectUri: String) = Unit
        override suspend fun login(username: String, password: String) { profile.value = profile.value.copy(loggedIn = true, username = username) }
        override suspend fun loginWithApiKey(apiKey: String) { profile.value = profile.value.copy(loggedIn = true) }
        override suspend fun search(query: String) = listOf(TrackSearchResult(10, query, 12))
        override suspend fun bind(mangaId: Long, result: TrackSearchResult) = Track(0, mangaId, 1, result.remoteId, null, result.title, 0.0, 12, 1, 0.0, "", 0, 0, false)
        override suspend fun update(track: Track, edit: TrackEdit) = track.copy(status = edit.status ?: track.status, score = edit.score ?: track.score, lastChapterRead = edit.lastChapterRead ?: track.lastChapterRead)
        override suspend fun logout() { profile.value = profile.value.copy(loggedIn = false, username = null) }
    }
}
