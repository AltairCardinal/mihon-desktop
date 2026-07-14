package tachiyomi.domain.track.interactor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry

class SyncReadingProgressWithTrackTest {
    @Test
    fun `sync updates logged in tracks behind reader progress`() = runTest {
        val repository = FakeRepository(mutableListOf(track(1, lastChapter = 2.0), track(2, lastChapter = 7.0)))
        val service = FakeService(9)
        val retries = mutableListOf<TrackerSyncRequest>()
        val sync = SyncReadingProgressWithTrack(repository, registry(service), TrackerSyncRetryScheduler(retries::add))

        sync.sync(TrackerSyncRequest("reader-1", mangaId = 3, chapterNumber = 5.0))

        assertEquals(listOf(5.0), service.updates.map { it.lastChapterRead })
        assertEquals(5.0, repository.tracks.first().lastChapterRead)
        assertEquals(emptyList<TrackerSyncRequest>(), retries)
    }

    @Test
    fun `sync schedules durable retry per failed tracker and continues others`() = runTest {
        val repository = FakeRepository(mutableListOf(track(1, trackerId = 8), track(2, trackerId = 9)))
        val failing = FakeService(8, fail = true)
        val success = FakeService(9)
        val retries = mutableListOf<TrackerSyncRequest>()
        val sync =
            SyncReadingProgressWithTrack(
                repository,
                registry(failing, success),
                TrackerSyncRetryScheduler(retries::add),
            )

        sync.sync(TrackerSyncRequest("reader-2", mangaId = 3, chapterNumber = 4.0))

        assertEquals(listOf(4.0), success.updates.map { it.lastChapterRead })
        assertEquals(listOf(8L), retries.map { it.trackerId })
        assertEquals("reader-2:8", retries.single().idempotencyKey)
    }

    private fun registry(vararg services: TrackerService) = object : TrackerServiceRegistry {
        override val services = services.toList()
    }

    private fun track(id: Long, trackerId: Long = 9, lastChapter: Double = 0.0) = Track(
        id, 3, trackerId, 10, null, "Manga", lastChapter, 10, 1, 0.0, "", 0, 0, false,
    )

    private class FakeService(id: Long, private val fail: Boolean = false) : TrackerService {
        override val profile = MutableStateFlow(TrackerProfile(id, "Tracker", TrackerAuthentication.OAUTH, true))
        override val statuses = emptyList<Pair<Long, String>>()
        override val scores = emptyList<Double>()
        val updates = mutableListOf<TrackEdit>()
        override suspend fun search(query: String) = emptyList<TrackSearchResult>()
        override suspend fun bind(mangaId: Long, result: TrackSearchResult): Track = error("unused")
        override suspend fun update(track: Track, edit: TrackEdit): Track {
            if (fail) error("offline")
            updates += edit
            return track.copy(lastChapterRead = requireNotNull(edit.lastChapterRead))
        }
        override suspend fun logout() = Unit
    }

    private class FakeRepository(val tracks: MutableList<Track>) : TrackRepository {
        override suspend fun getTrackById(id: Long) = tracks.find { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = tracks.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow() = flowOf(tracks)
        override fun getTracksByMangaIdAsFlow(mangaId: Long) = flowOf(tracks.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) {
            tracks.removeAll {
                it.mangaId == mangaId &&
                    it.trackerId == trackerId
            }
        }
        override suspend fun insert(track: Track) {
            tracks.replaceAll { if (it.id == track.id) track else it }
        }
        override suspend fun insertAll(tracks: List<Track>) {
            tracks.forEach { insert(it) }
        }
    }
}
