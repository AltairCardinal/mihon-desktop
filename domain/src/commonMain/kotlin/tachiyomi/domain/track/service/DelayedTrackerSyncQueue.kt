package tachiyomi.domain.track.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.track.model.Track

data class DelayedTrackerSyncItem(
    val trackId: Long,
    val mangaId: Long,
    val trackerId: Long,
    val lastChapterRead: Double,
    val failureReason: String? = null,
    val eventId: String? = null,
    val attempt: Int = 0,
)

fun DelayedTrackerSyncItem.mergeHighest(candidate: DelayedTrackerSyncItem): DelayedTrackerSyncItem =
    if (candidate.lastChapterRead >= lastChapterRead) candidate else this

interface DelayedTrackerSyncPersistence {
    suspend fun getItems(): List<DelayedTrackerSyncItem>
    suspend fun upsertMax(item: DelayedTrackerSyncItem): DelayedTrackerSyncItem
    suspend fun removeUpTo(trackId: Long, lastChapterRead: Double): Boolean
}

data class DelayedTrackerSyncReport(
    val attempted: Int,
    val succeeded: Int,
    val queued: Int,
    val remaining: Int,
)

/**
 * Provider-neutral delayed tracking policy. Platform adapters supply durable storage,
 * current sessions and the provider workflow executor.
 */
class DelayedTrackerSyncQueue(
    private val persistence: DelayedTrackerSyncPersistence,
    private val session: (Long) -> TrackerProviderSession?,
    private val execute: suspend (TrackerProviderRequest.Edit) -> TrackerProviderResult,
) {
    suspend fun sync(tracks: List<Track>, chapterNumber: Double): DelayedTrackerSyncReport {
        val pending = persistence.getItems().associateBy { it.trackId }
        val eligible = tracks.map { track ->
            track to maxOf(chapterNumber, pending[track.id]?.lastChapterRead ?: chapterNumber)
        }.filter { (track, targetChapter) ->
            targetChapter > track.lastChapterRead && session(track.trackerId)?.loggedIn == true
        }
        val outcomes = coroutineScope {
            eligible.map { (track, targetChapter) ->
                async { syncOne(track, targetChapter) }
            }.awaitAll()
        }
        return report(eligible.size, outcomes)
    }

    suspend fun drain(loadTrack: suspend (Long) -> Track?): DelayedTrackerSyncReport {
        val items = persistence.getItems()
        val outcomes = coroutineScope {
            items.map { item ->
                async {
                    val track = loadTrack(item.trackId)
                    when {
                        track == null || item.lastChapterRead <= track.lastChapterRead -> {
                            persistence.removeUpTo(item.trackId, item.lastChapterRead)
                            true
                        }
                        session(track.trackerId)?.loggedIn != true -> null
                        else -> syncOne(track, item.lastChapterRead)
                    }
                }
            }.awaitAll()
        }
        return report(items.size, outcomes)
    }

    suspend fun markRetryExhausted(trackId: Long? = null) {
        persistence.getItems().filter { trackId == null || it.trackId == trackId }.forEach {
            if (it.failureReason == null) {
                persistence.upsertMax(it.copy(failureReason = RETRY_EXHAUSTED))
            }
        }
    }

    private suspend fun syncOne(track: Track, chapterNumber: Double): Boolean {
        val result = try {
            execute(
                TrackerProviderRequest.Edit(
                    track,
                    TrackEdit(lastChapterRead = chapterNumber, didReadChapter = true),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return enqueue(track, chapterNumber, TrackerProviderErrorKind.UNKNOWN.name)
        }
        return when (result) {
            is TrackerProviderResult.Success -> {
                persistence.removeUpTo(track.id, chapterNumber)
                true
            }
            is TrackerProviderResult.Failure -> enqueue(
                track,
                chapterNumber,
                buildString {
                    append(result.error.kind.name)
                    result.error.statusCode?.let { append(":").append(it) }
                },
            )
        }
    }

    private suspend fun enqueue(track: Track, chapterNumber: Double, reason: String): Boolean {
        persistence.upsertMax(
            DelayedTrackerSyncItem(
                track.id,
                track.mangaId,
                track.trackerId,
                chapterNumber,
                reason,
            ),
        )
        return false
    }

    private suspend fun report(attempted: Int, outcomes: List<Boolean?>) = DelayedTrackerSyncReport(
        attempted = attempted,
        succeeded = outcomes.count { it == true },
        queued = outcomes.count { it == false },
        remaining = persistence.getItems().size,
    )

    companion object {
        const val MAX_RUN_ATTEMPT_COUNT = 3
        const val RETRY_EXHAUSTED = "RETRY_EXHAUSTED"
    }
}
