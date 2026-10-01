package tachiyomi.domain.track.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.track.model.Track

interface TrackRepository {

    suspend fun getTrackById(id: Long): Track?

    suspend fun getTracksByMangaId(mangaId: Long): List<Track>

    fun getTracksAsFlow(): Flow<List<Track>>

    fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>>

    suspend fun delete(mangaId: Long, trackerId: Long)

    suspend fun insert(track: Track)

    /** Replace only the binding captured before a remote refresh, in one repository transaction. */
    suspend fun insertIfMatches(previous: Track, refreshed: Track): Boolean =
        throw UnsupportedOperationException("Conditional track refresh is unavailable")

    suspend fun insertAll(tracks: List<Track>)
}
