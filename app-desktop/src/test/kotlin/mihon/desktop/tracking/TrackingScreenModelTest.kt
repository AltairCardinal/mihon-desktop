package mihon.desktop.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.desktop.ui.tracking.TrackingScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

class TrackingScreenModelTest {
    @Test
    fun `load merges persisted manga tracks with every registered service`() = runTest {
        val bound = track(trackerId = 1)
        val repository = FakeTrackRepository(mutableListOf(bound))
        val loggedIn = FakeTrackerService(1, loggedIn = true)
        val unavailable = FakeTrackerService(2, loggedIn = false, unavailableReason = "Source is not configured")
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(loggedIn, unavailable))

        model.load()

        assertFalse(model.state.value.loading)
        assertEquals(2, model.state.value.services.size)
        assertEquals(bound, model.state.value.services.single { it.profile.id == 1L }.track)
        assertEquals(null, model.state.value.services.single { it.profile.id == 2L }.track)
        assertEquals("Source is not configured", model.state.value.services.single { it.profile.id == 2L }.profile.unavailableReason)
    }

    @Test
    fun `bind uses real service result and persists returned track`() = runTest {
        val repository = FakeTrackRepository()
        val service = FakeTrackerService(1, loggedIn = true)
        val model = TrackingScreenModel(42, "Real title", 12, repository, registry(service))
        model.load()

        val results = model.search(1, "Real title")
        model.bind(1, results.single())

        assertEquals(listOf("Real title"), service.searches)
        assertNotNull(repository.rows.singleOrNull())
        assertEquals(repository.rows.single(), model.state.value.services.single().track)
    }

    @Test
    fun `update accepts only provider choices and chapter boundary then persists atomically`() = runTest {
        val original = track(trackerId = 1)
        val repository = FakeTrackRepository(mutableListOf(original))
        val service = FakeTrackerService(1, loggedIn = true)
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service))
        model.load()

        assertTrue(runCatching { model.update(1, TrackEdit(status = 99)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { model.update(1, TrackEdit(score = 7.5)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { model.update(1, TrackEdit(lastChapterRead = 13.0)) }.exceptionOrNull() is IllegalArgumentException)
        model.update(1, TrackEdit(status = 2, score = 10.0, lastChapterRead = 12.0))

        assertEquals(2, repository.rows.single().status)
        assertEquals(10.0, repository.rows.single().score)
        assertEquals(12.0, repository.rows.single().lastChapterRead)
    }

    @Test
    fun `remote update failure keeps persisted and visible local track`() = runTest {
        val original = track(trackerId = 1)
        val repository = FakeTrackRepository(mutableListOf(original))
        val service = FakeTrackerService(1, loggedIn = true).apply { updateFailure = IllegalStateException("remote failed") }
        val model = TrackingScreenModel(42, "Manga", null, repository, registry(service))
        model.load()

        val failure = runCatching { model.update(1, TrackEdit(lastChapterRead = 2.0)) }.exceptionOrNull()

        assertEquals("remote failed", failure?.message)
        assertEquals(original, repository.rows.single())
        assertEquals(original, model.state.value.services.single().track)
    }

    @Test
    fun `unbind deletes only selected service binding and logout clears service session after confirmation action`() = runTest {
        val repository = FakeTrackRepository(mutableListOf(track(1), track(2)))
        val service = FakeTrackerService(1, loggedIn = true)
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service, FakeTrackerService(2, true)))
        model.load()

        model.unbind(1)
        model.logout(1)

        assertEquals(listOf(2L), repository.rows.map { it.trackerId })
        assertTrue(service.loggedOut)
        assertFalse(service.profile.value.loggedIn)
    }

    private fun registry(vararg services: TrackerService) = object : TrackerServiceRegistry {
        override val services = services.toList()
    }

    private fun track(trackerId: Long) = Track(
        id = trackerId,
        mangaId = 42,
        trackerId = trackerId,
        remoteId = trackerId * 10,
        libraryId = null,
        title = "Manga",
        lastChapterRead = 1.0,
        totalChapters = 12,
        status = 1,
        score = 0.0,
        remoteUrl = "https://example/$trackerId",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    private class FakeTrackRepository(val rows: MutableList<Track> = mutableListOf()) : TrackRepository {
        override suspend fun getTrackById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = rows.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(rows)
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> = flowOf(rows.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) { rows.removeAll { it.mangaId == mangaId && it.trackerId == trackerId } }
        override suspend fun insert(track: Track) { rows.removeAll { it.mangaId == track.mangaId && it.trackerId == track.trackerId }; rows += track }
        override suspend fun insertAll(tracks: List<Track>) { tracks.forEach { insert(it) } }
    }

    private class FakeTrackerService(
        id: Long,
        loggedIn: Boolean,
        unavailableReason: String? = null,
    ) : TrackerService {
        override val profile = MutableStateFlow(TrackerProfile(id, "Service $id", TrackerAuthentication.OAUTH, loggedIn, unavailableReason = unavailableReason))
        override val statuses = listOf(1L to "Reading", 2L to "Completed")
        override val scores = listOf(0.0, 10.0)
        val searches = mutableListOf<String>()
        var updateFailure: Throwable? = null
        var loggedOut = false

        override suspend fun search(query: String): List<TrackSearchResult> {
            searches += query
            return listOf(TrackSearchResult(10, query, 12))
        }

        override suspend fun bind(mangaId: Long, result: TrackSearchResult) = Track(
            id = profile.value.id,
            mangaId = mangaId,
            trackerId = profile.value.id,
            remoteId = result.remoteId,
            libraryId = null,
            title = result.title,
            lastChapterRead = 0.0,
            totalChapters = result.totalChapters,
            status = 1,
            score = 0.0,
            remoteUrl = result.remoteUrl,
            startDate = 0,
            finishDate = 0,
            private = false,
        )
        override suspend fun update(track: Track, edit: TrackEdit): Track {
            updateFailure?.let { throw it }
            return track.copy(
                status = edit.status ?: track.status,
                score = edit.score ?: track.score,
                lastChapterRead = edit.lastChapterRead ?: track.lastChapterRead,
            )
        }
        override suspend fun logout() { loggedOut = true; profile.value = profile.value.copy(loggedIn = false) }
    }
}
