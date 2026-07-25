package mihon.desktop.ui.tracking

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerProviderCatalog
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry

data class TrackingServiceState(
    val profile: TrackerProfile,
    val statuses: List<Pair<Long, String>>,
    val scores: List<Double>,
    val track: Track?,
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
) : ScreenModel {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    suspend fun load() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        runCatching {
            registry.refresh()
            val tracks = mangaId?.let { repository.getTracksByMangaId(it) }.orEmpty().associateBy(Track::trackerId)
            registry.services.map { service -> service.toState(tracks[service.profile.value.id]) }
        }.onSuccess { services ->
            mutableState.value = mutableState.value.copy(loading = false, services = services)
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(loading = false, error = error.toTrackingMessage(TrackingMessage.LoadFailed))
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

    suspend fun unbind(trackerId: Long) = operationMutex.withLock {
        val mangaId = mangaId ?: failArgument(TrackingMessage.MangaRequired)
        repository.delete(mangaId, trackerId)
        replaceTrack(trackerId, null, TrackingMessage.Removed)
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

    private fun TrackerService.toState(track: Track?) = TrackingServiceState(profile.value, statuses, scores, track)

    private fun Throwable.toTrackingMessage(fallback: TrackingMessage) = when (this) {
        is TrackingMessageException -> trackingMessage
        else -> message?.takeIf(String::isNotBlank)?.let(TrackingMessage::External) ?: fallback
    }

    private fun failArgument(message: TrackingMessage): Nothing = throw TrackingArgumentException(message)
    private fun failState(message: TrackingMessage): Nothing = throw TrackingStateException(message)
}
