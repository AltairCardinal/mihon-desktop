package mihon.desktop.ui.tracking

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.EnhancedTrackerManga
import tachiyomi.domain.track.service.EnhancedTrackerService
import tachiyomi.domain.track.service.EnhancedTrackerWorkflow
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerProviderCatalog
import tachiyomi.domain.track.service.TrackerProviderConfiguration
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderException
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.track.service.trackOrThrow

data class TrackingServiceState(
    val profile: TrackerProfile,
    val statuses: List<Pair<Long, String>>,
    val scores: List<Double>,
    val track: Track?,
    val providerConfiguration: TrackerProviderConfiguration?,
)

sealed interface TrackingMessage {
    data object LoadFailed : TrackingMessage
    data object Bound : TrackingMessage
    data object Updated : TrackingMessage
    data object Removed : TrackingMessage
    data object LoggedOut : TrackingMessage
    data object SearchTitleEmpty : TrackingMessage
    data object MangaRequired : TrackingMessage
    data object NotBound : TrackingMessage
    data class UnsupportedStatus(val service: String) : TrackingMessage
    data class UnsupportedScore(val service: String) : TrackingMessage
    data object NegativeChapter : TrackingMessage
    data class ChapterOutOfRange(val maximum: Long) : TrackingMessage
    data object UnknownService : TrackingMessage
    data object ServiceUnavailable : TrackingMessage
    data object LoginRequired : TrackingMessage
    data object LoginCancelled : TrackingMessage
    data object LoginFailed : TrackingMessage
    data object LogoutFailed : TrackingMessage
    data object UnbindFailed : TrackingMessage
    data object EnhancedNoMatch : TrackingMessage
    data class External(val text: String) : TrackingMessage
}

internal interface TrackingMessageException {
    val trackingMessage: TrackingMessage
}

private class TrackingArgumentException(
    override val trackingMessage: TrackingMessage,
) : IllegalArgumentException(), TrackingMessageException

private class TrackingStateException(
    override val trackingMessage: TrackingMessage,
) : IllegalStateException(), TrackingMessageException

data class TrackingState(
    val loading: Boolean = true,
    val services: List<TrackingServiceState> = emptyList(),
    val error: TrackingMessage? = null,
    val feedback: TrackingMessage? = null,
)

class TrackingScreenModel(
    val mangaId: Long?,
    val mangaTitle: String?,
    val totalChapters: Long?,
    private val repository: TrackRepository,
    private val chapterRepository: ChapterRepository,
    private val registry: TrackerServiceRegistry,
    private val mangaRepository: MangaRepository? = null,
) : ScreenModel {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    suspend fun load() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        val enhancedManga: EnhancedTrackerManga?
        val tracks: Map<Long, Track>
        val services: List<TrackerService>
        try {
            registry.refresh()
            tracks = mangaId?.let { repository.getTracksByMangaId(it) }.orEmpty().associateBy(Track::trackerId)
            enhancedManga = mangaId
                ?.takeIf { mangaRepository != null }
                ?.let { mangaRepository!!.getMangaById(it) }
                ?.let { manga -> EnhancedTrackerManga(manga.id, manga.source, manga.url, manga.title) }
            services = registry.services.filter { service ->
                service !is EnhancedTrackerService ||
                    enhancedManga == null ||
                    service.accept(enhancedManga)
            }
            mutableState.value = mutableState.value.copy(
                loading = false,
                services = services.map { service -> service.toState(tracks[service.profile.value.id]) },
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(loading = false, error = error.toTrackingMessage(TrackingMessage.LoadFailed))
            return
        }
        if (enhancedManga == null) return
        services.filterIsInstance<EnhancedTrackerService>().forEach { service ->
            val trackerId = service.profile.value.id
            if (tracks[trackerId] != null) return@forEach
            try {
                val matched = EnhancedTrackerWorkflow().bindIfMatched(service, enhancedManga)
                if (matched == null) {
                    mutableState.value = mutableState.value.copy(feedback = TrackingMessage.EnhancedNoMatch)
                } else {
                    repository.insert(matched)
                    replaceTrack(trackerId, matched, TrackingMessage.Bound)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    error = error.toTrackingMessage(TrackingMessage.LoadFailed),
                )
            }
        }
    }

    suspend fun search(trackerId: Long, query: String): List<TrackSearchResult> {
        if (query.isBlank()) failArgument(TrackingMessage.SearchTitleEmpty)
        return service(trackerId).requireAvailableAndLoggedIn().search(query.trim())
    }

    suspend fun bind(trackerId: Long, result: TrackSearchResult): Track = operationMutex.withLock {
        val mangaId = mangaId ?: failArgument(TrackingMessage.MangaRequired)
        val service = service(trackerId).requireAvailableAndLoggedIn()
        val persisted = if (
            service is TrackerProviderService &&
            service.configuration.id in TrackerProviderCatalog.publicProviderIds
        ) {
            val hasReadChapters = chapterRepository.getChapterByMangaId(mangaId).any { it.read }
            service.bind(mangaId, result, hasReadChapters)
        } else {
            service.bind(mangaId, result)
        }
        repository.insert(persisted)
        replaceTrack(trackerId, persisted, TrackingMessage.Bound)
        persisted
    }

    suspend fun update(trackerId: Long, edit: TrackEdit): Track = operationMutex.withLock {
        val item = item(trackerId)
        val track = item.track ?: failArgument(TrackingMessage.NotBound)
        validateEdit(item, track, edit)
        val updated = service(trackerId).requireAvailableAndLoggedIn().update(track, edit)
        repository.insert(updated)
        replaceTrack(trackerId, updated, TrackingMessage.Updated)
        updated
    }

    suspend fun unbind(trackerId: Long, removeRemoteTrack: Boolean = false) = operationMutex.withLock {
        val mangaId = mangaId ?: failArgument(TrackingMessage.MangaRequired)
        val track = item(trackerId).track ?: failArgument(TrackingMessage.NotBound)
        repository.delete(mangaId, trackerId)
        replaceTrack(trackerId, null, TrackingMessage.Removed)
        val service = service(trackerId)
        if (
            removeRemoteTrack &&
            service is TrackerProviderService &&
            service.configuration.supportsDelete
        ) {
            service.execute(TrackerProviderRequest.Delete(track)).trackOrThrow()
        }
    }

    suspend fun logout(trackerId: Long) = operationMutex.withLock {
        val service = service(trackerId)
        service.logout()
        replaceProfile(trackerId, service.profile.value, TrackingMessage.LoggedOut)
    }

    fun reportError(error: Throwable, fallback: TrackingMessage) {
        mutableState.value = mutableState.value.copy(error = error.toTrackingMessage(fallback))
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(error = null, feedback = null)
    }

    private fun validateEdit(item: TrackingServiceState, track: Track, edit: TrackEdit) {
        edit.status?.let { status ->
            if (item.statuses.none { it.first == status }) failArgument(TrackingMessage.UnsupportedStatus(item.profile.name))
        }
        edit.score?.let { score ->
            if (item.scores.none { it == score }) failArgument(TrackingMessage.UnsupportedScore(item.profile.name))
        }
        edit.lastChapterRead?.let { chapter ->
            if (chapter < 0.0) failArgument(TrackingMessage.NegativeChapter)
            val maximum = totalChapters?.takeIf { it > 0 } ?: track.totalChapters.takeIf { it > 0 }
            if (maximum != null && chapter > maximum.toDouble()) {
                failArgument(TrackingMessage.ChapterOutOfRange(maximum))
            }
        }
    }

    private fun replaceTrack(trackerId: Long, track: Track?, feedback: TrackingMessage) {
        mutableState.value = mutableState.value.copy(
            services = mutableState.value.services.map { if (it.profile.id == trackerId) it.copy(track = track) else it },
            error = null,
            feedback = feedback,
        )
    }

    private fun replaceProfile(trackerId: Long, profile: TrackerProfile, feedback: TrackingMessage) {
        mutableState.value = mutableState.value.copy(
            services = mutableState.value.services.map { if (it.profile.id == trackerId) it.copy(profile = profile) else it },
            error = null,
            feedback = feedback,
        )
    }

    private fun item(trackerId: Long) = state.value.services.firstOrNull { it.profile.id == trackerId }
        ?: failState(TrackingMessage.UnknownService)

    private fun service(trackerId: Long) = registry.services.firstOrNull { it.profile.value.id == trackerId }
        ?: failState(TrackingMessage.UnknownService)

    private fun TrackerService.requireAvailableAndLoggedIn(): TrackerService {
        profile.value.unavailableReason?.let { reason ->
            failState(if (reason.isBlank()) TrackingMessage.ServiceUnavailable else TrackingMessage.External(reason))
        }
        if (!profile.value.loggedIn) failState(TrackingMessage.LoginRequired)
        return this
    }

    private fun TrackerService.toState(track: Track?) = TrackingServiceState(
        profile = profile.value,
        statuses = statuses,
        scores = scores,
        track = track,
        providerConfiguration = (this as? TrackerProviderService)?.configuration,
    )

    private fun Throwable.toTrackingMessage(fallback: TrackingMessage) = when (this) {
        is TrackingMessageException -> trackingMessage
        is TrackerProviderException -> when (kind) {
            TrackerProviderErrorKind.AUTHENTICATION -> TrackingMessage.LoginRequired
            else -> message?.takeIf(String::isNotBlank)?.let(TrackingMessage::External) ?: fallback
        }
        else -> message?.takeIf(String::isNotBlank)?.let(TrackingMessage::External) ?: fallback
    }

    private fun failArgument(message: TrackingMessage): Nothing = throw TrackingArgumentException(message)
    private fun failState(message: TrackingMessage): Nothing = throw TrackingStateException(message)
}
