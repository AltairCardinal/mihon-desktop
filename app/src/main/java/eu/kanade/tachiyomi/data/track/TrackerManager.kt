package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.myanimelist.MALTitleNotApproved
import eu.kanade.tachiyomi.data.track.myanimelist.MALTokenExpired
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackerChapterReadPolicy
import tachiyomi.domain.track.service.TrackerProviderConfiguration
import tachiyomi.domain.track.service.TrackerProviderContracts
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderException
import tachiyomi.domain.track.service.TrackerProviderPort
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderSession
import tachiyomi.domain.track.service.TrackerProviderWorkflow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class TrackerManager internal constructor(
    private val trackerOverride: List<Tracker>? = null,
    private val persist: suspend (Track) -> Unit = { Injekt.get<InsertTrack>().await(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val ANILIST = 2L
        const val KITSU = 3L
        const val KAVITA = 8L
    }

    val myAnimeList by lazy { MyAnimeList(1L) }
    val aniList by lazy { Anilist(ANILIST) }
    val kitsu by lazy { Kitsu(KITSU) }
    val shikimori by lazy { Shikimori(4L) }
    val bangumi by lazy { Bangumi(5L) }
    val komga by lazy { Komga(6L) }
    val mangaUpdates by lazy { MangaUpdates(7L) }
    val kavita by lazy { Kavita(KAVITA) }
    val suwayomi by lazy { Suwayomi(9L) }

    val trackers by lazy {
        trackerOverride
            ?: listOf(myAnimeList, aniList, kitsu, shikimori, bangumi, komga, mangaUpdates, kavita, suwayomi)
    }

    fun loggedInTrackers() = trackers.filter { it.isLoggedIn }

    fun loggedInTrackersFlow() = combine(trackers.map { it.isLoggedInFlow }) {
        it.mapIndexedNotNull { index, isLoggedIn ->
            if (isLoggedIn) trackers[index] else null
        }
    }

    fun get(id: Long) = trackers.find { it.id == id }

    fun getAll(ids: Set<Long>) = trackers.filter { it.id in ids }

    fun configuration(id: Long): TrackerProviderConfiguration? = get(id)?.configuration()

    fun session(id: Long): TrackerProviderSession? = get(id)?.let {
        TrackerProviderSession(it.id, it.isLoggedIn, it.getUsername().takeIf(String::isNotBlank))
    }

    suspend fun execute(request: TrackerProviderRequest): TrackerProviderResult {
        val tracker = get(request.track.trackerId)
            ?: return TrackerProviderResult.Failure(
                tachiyomi.domain.track.service.TrackerProviderError(
                    when (request) {
                        is TrackerProviderRequest.Edit -> tachiyomi.domain.track.service.TrackerProviderOperation.EDIT
                        is TrackerProviderRequest.Delete ->
                            tachiyomi.domain.track.service.TrackerProviderOperation.DELETE
                    },
                    TrackerProviderErrorKind.NOT_CONFIGURED,
                ),
            )
        val port = object : TrackerProviderPort {
            override val configuration = tracker.configuration()
            override val session = requireNotNull(session(tracker.id))
            override suspend fun refresh(track: Track): Track =
                requireNotNull(tracker.refresh(track.toDbTrack()).toDomainTrack(idRequired = false))
            override suspend fun update(track: Track): Track {
                val updated = requireNotNull(tracker.update(track.toDbTrack(), false).toDomainTrack(idRequired = false))
                persist(updated)
                return updated
            }
            override suspend fun delete(track: Track) {
                (tracker as? DeletableTracker)?.delete(track)
                    ?: throw UnsupportedOperationException("Tracker does not support remote deletion")
            }
        }
        return TrackerProviderWorkflow(clock, ::classify).execute(port, request)
    }

    private fun Tracker.configuration() = TrackerProviderConfiguration(
        id = id,
        authentication = TrackerProviderContracts.authentication(id),
        readingStatus = getReadingStatus(),
        completionStatus = getCompletionStatus(),
        rereadingStatus = getRereadingStatus().takeIf { it >= 0 },
        supportsReadingDates = supportsReadingDates,
        supportsPrivateTracking = supportsPrivateTracking,
        supportsDelete = this is DeletableTracker,
        chapterReadPolicy = if (id ==
            7L
        ) {
            TrackerChapterReadPolicy.ALWAYS_READING
        } else {
            TrackerChapterReadPolicy.AUTO_COMPLETE
        },
    )

    private fun classify(error: Throwable): Pair<TrackerProviderErrorKind, Int?> = when {
        error is HttpException && error.code in setOf(401, 403) -> TrackerProviderErrorKind.AUTHENTICATION to error.code
        error is HttpException && error.code == 404 -> TrackerProviderErrorKind.NOT_FOUND to error.code
        error is HttpException && error.code == 429 -> TrackerProviderErrorKind.RATE_LIMITED to error.code
        error is HttpException && error.code >= 500 -> TrackerProviderErrorKind.SERVER to error.code
        error is MALTitleNotApproved -> TrackerProviderErrorKind.TITLE_NOT_APPROVED to null
        error.hasMalTokenExpiredCause() -> TrackerProviderErrorKind.AUTHENTICATION to null
        error is IOException -> TrackerProviderErrorKind.NETWORK to null
        error is TrackerProviderException -> error.kind to error.statusCode
        error is IllegalArgumentException -> TrackerProviderErrorKind.INVALID_REQUEST to null
        error is UnsupportedOperationException -> TrackerProviderErrorKind.UNSUPPORTED to null
        else -> TrackerProviderErrorKind.UNKNOWN to null
    }

    private fun Throwable.hasMalTokenExpiredCause(): Boolean {
        var current: Throwable? = this
        repeat(8) {
            if (current is MALTokenExpired) return true
            val next = current?.cause ?: return false
            if (next === current) return false
            current = next
        }
        return false
    }
}
