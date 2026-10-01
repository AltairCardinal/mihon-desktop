package tachiyomi.data.track

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

class TrackRepositoryImpl(
    private val handler: DatabaseHandler,
) : TrackRepository {

    override suspend fun getTrackById(id: Long): Track? {
        return handler.awaitOneOrNull { manga_syncQueries.getTrackById(id, TrackMapper::mapTrack) }
    }

    override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
        return handler.awaitList {
            manga_syncQueries.getTracksByMangaId(mangaId, TrackMapper::mapTrack)
        }
    }

    override fun getTracksAsFlow(): Flow<List<Track>> {
        return handler.subscribeToList {
            manga_syncQueries.getTracks(TrackMapper::mapTrack)
        }
    }

    override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> {
        return handler.subscribeToList {
            manga_syncQueries.getTracksByMangaId(mangaId, TrackMapper::mapTrack)
        }
    }

    override suspend fun delete(mangaId: Long, trackerId: Long) {
        handler.await {
            manga_syncQueries.delete(
                mangaId = mangaId,
                syncId = trackerId,
            )
        }
    }

    override suspend fun insert(track: Track) {
        insertValues(track)
    }

    override suspend fun insertIfMatches(previous: Track, refreshed: Track): Boolean =
        handler.await(inTransaction = true) {
            val current = manga_syncQueries.getTrackById(previous.id, TrackMapper::mapTrack).executeAsOneOrNull()
            if (current == null || current.mangaId != previous.mangaId || current.trackerId != previous.trackerId ||
                current.remoteId != previous.remoteId || current.libraryId != previous.libraryId
            ) {
                return@await false
            }
            require(refreshed.mangaId == previous.mangaId && refreshed.trackerId == previous.trackerId)
            insertTrackValues(refreshed)
            true
        }

    override suspend fun insertAll(tracks: List<Track>) {
        insertValues(*tracks.toTypedArray())
    }

    private suspend fun insertValues(vararg tracks: Track) {
        handler.await(inTransaction = true) {
            tracks.forEach { mangaTrack ->
                insertTrackValues(mangaTrack)
            }
        }
    }

    private fun Database.insertTrackValues(track: Track) {
        manga_syncQueries.insert(
            mangaId = track.mangaId,
            syncId = track.trackerId,
            remoteId = track.remoteId,
            libraryId = track.libraryId,
            title = track.title,
            lastChapterRead = track.lastChapterRead,
            totalChapters = track.totalChapters,
            status = track.status,
            score = track.score,
            remoteUrl = track.remoteUrl,
            startDate = track.startDate,
            finishDate = track.finishDate,
            private = track.private,
        )
    }
}
