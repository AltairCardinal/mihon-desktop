package mihon.desktop.ui.tracking

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry

data class TrackingServiceState(
    val profile: TrackerProfile,
    val statuses: List<Pair<Long, String>>,
    val scores: List<Double>,
    val track: Track?,
)

data class TrackingState(
    val loading: Boolean = true,
    val services: List<TrackingServiceState> = emptyList(),
    val error: String? = null,
    val feedback: String? = null,
)

class TrackingScreenModel(
    val mangaId: Long?,
    val mangaTitle: String?,
    val totalChapters: Long?,
    private val repository: TrackRepository,
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
            mutableState.value = mutableState.value.copy(loading = false, error = error.message ?: "Unable to load tracking")
        }
    }

    suspend fun search(trackerId: Long, query: String): List<TrackSearchResult> {
        require(query.isNotBlank()) { "Search title cannot be empty" }
        return service(trackerId).requireAvailableAndLoggedIn().search(query.trim())
    }

    suspend fun bind(trackerId: Long, result: TrackSearchResult): Track = operationMutex.withLock {
        val mangaId = requireNotNull(mangaId) { "Manga tracking requires a manga" }
        val service = service(trackerId).requireAvailableAndLoggedIn()
        val persisted = service.bind(mangaId, result)
        repository.insert(persisted)
        replaceTrack(trackerId, persisted, "Tracking bound")
        persisted
    }

    suspend fun update(trackerId: Long, edit: TrackEdit): Track = operationMutex.withLock {
        val item = item(trackerId)
        val track = requireNotNull(item.track) { "This service is not bound" }
        validateEdit(item, track, edit)
        val updated = service(trackerId).requireAvailableAndLoggedIn().update(track, edit)
        repository.insert(updated)
        replaceTrack(trackerId, updated, "Tracking updated")
        updated
    }

    suspend fun unbind(trackerId: Long) = operationMutex.withLock {
        val mangaId = requireNotNull(mangaId) { "Manga tracking requires a manga" }
        repository.delete(mangaId, trackerId)
        replaceTrack(trackerId, null, "Tracking removed")
    }

    suspend fun logout(trackerId: Long) = operationMutex.withLock {
        val service = service(trackerId)
        service.logout()
        replaceProfile(trackerId, service.profile.value, "Logged out")
    }

    fun reportError(error: Throwable, fallback: String) {
        mutableState.value = mutableState.value.copy(error = error.message ?: fallback)
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(error = null, feedback = null)
    }

    private fun validateEdit(item: TrackingServiceState, track: Track, edit: TrackEdit) {
        edit.status?.let { status ->
            require(item.statuses.any { it.first == status }) { "Status is not supported by ${item.profile.name}" }
        }
        edit.score?.let { score ->
            require(item.scores.any { it == score }) { "Score is not supported by ${item.profile.name}" }
        }
        edit.lastChapterRead?.let { chapter ->
            require(chapter >= 0.0) { "Chapter cannot be negative" }
            val maximum = totalChapters?.takeIf { it > 0 } ?: track.totalChapters.takeIf { it > 0 }
            require(maximum == null || chapter <= maximum.toDouble()) { "Chapter must be between 0 and $maximum" }
        }
    }

    private fun replaceTrack(trackerId: Long, track: Track?, feedback: String) {
        mutableState.value = mutableState.value.copy(
            services = mutableState.value.services.map { if (it.profile.id == trackerId) it.copy(track = track) else it },
            error = null,
            feedback = feedback,
        )
    }

    private fun replaceProfile(trackerId: Long, profile: TrackerProfile, feedback: String) {
        mutableState.value = mutableState.value.copy(
            services = mutableState.value.services.map { if (it.profile.id == trackerId) it.copy(profile = profile) else it },
            error = null,
            feedback = feedback,
        )
    }

    private fun item(trackerId: Long) = state.value.services.firstOrNull { it.profile.id == trackerId }
        ?: error("Unknown tracking service $trackerId")

    private fun service(trackerId: Long) = registry.services.firstOrNull { it.profile.value.id == trackerId }
        ?: error("Unknown tracking service $trackerId")

    private fun TrackerService.requireAvailableAndLoggedIn(): TrackerService {
        check(profile.value.unavailableReason == null) { profile.value.unavailableReason ?: "Service is unavailable" }
        check(profile.value.loggedIn) { "Log in to ${profile.value.name} first" }
        return this
    }

    private fun TrackerService.toState(track: Track?) = TrackingServiceState(profile.value, statuses, scores, track)
}
