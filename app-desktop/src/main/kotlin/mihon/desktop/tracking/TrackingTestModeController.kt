package mihon.desktop.tracking

import mihon.desktop.ui.tracking.TrackingScreenModel
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerServiceRegistry

data class TrackingTestState(
    val trackerId: Long,
    val loggedIn: Boolean,
    val resultCount: Int,
    val track: Track?,
)

/** Test-mode adapter over the same repository, services, and validation used by production UI. */
class TrackingTestModeController(
    private val repository: TrackRepository,
    private val registry: TrackerServiceRegistry,
) {
    private var model: TrackingScreenModel? = null
    private val results = mutableMapOf<Long, List<TrackSearchResult>>()

    suspend fun execute(action: String, params: Map<String, String>): TrackingTestState {
        val trackerId = params["trackerId"]?.toLongOrNull() ?: error("trackerId is required")
        val service = registry.services.firstOrNull { it.profile.value.id == trackerId }
            ?: error("Unknown tracking service $trackerId")
        when (action) {
            "tracking_login" -> {
                val authenticating = service as? DesktopAuthenticatingTrackerService
                if (authenticating == null) {
                    check(service.profile.value.loggedIn) {
                        service.profile.value.unavailableReason ?: "Source session is unavailable"
                    }
                } else {
                    when (service.profile.value.authentication) {
                        TrackerAuthentication.USERNAME_PASSWORD -> authenticating.login(
                            params["username"].orEmpty(),
                            params["password"].orEmpty(),
                        )
                        TrackerAuthentication.API_KEY -> authenticating.loginWithApiKey(params["apiKey"].orEmpty())
                        TrackerAuthentication.OAUTH -> authenticating.finishOAuth(
                            params["code"] ?: error("OAuth code is required"),
                            params["redirectUri"] ?: error("OAuth redirectUri is required"),
                        )
                    }
                }
            }
            "tracking_logout" -> currentModel(params).logout(trackerId)
            "tracking_search" -> results[trackerId] = currentModel(params).search(
                trackerId,
                params["title"] ?: error("title is required"),
            )
            "tracking_bind" -> {
                val matches = results[trackerId] ?: error("Search before binding")
                val index = params["resultIndex"]?.toIntOrNull() ?: 0
                currentModel(params).bind(trackerId, matches.getOrElse(index) { error("Unknown result index $index") })
            }
            "tracking_update" -> currentModel(params).update(
                trackerId,
                TrackEdit(
                    status = params["status"]?.toLongOrNull(),
                    score = params["score"]?.toDoubleOrNull(),
                    lastChapterRead = params["chapter"]?.toDoubleOrNull(),
                ),
            )
            "tracking_cancel" -> results.remove(trackerId)
            else -> error("Unsupported tracking action $action")
        }
        val mangaId = params["mangaId"]?.toLongOrNull()
        val track = mangaId?.let { repository.getTracksByMangaId(it).firstOrNull { row -> row.trackerId == trackerId } }
        return TrackingTestState(trackerId, service.profile.value.loggedIn, results[trackerId].orEmpty().size, track)
    }

    private suspend fun currentModel(params: Map<String, String>): TrackingScreenModel {
        val mangaId = params["mangaId"]?.toLongOrNull()
        val existing = model
        if (existing != null && existing.mangaId == mangaId) return existing
        return TrackingScreenModel(
            mangaId = mangaId,
            mangaTitle = params["title"],
            totalChapters = params["totalChapters"]?.toLongOrNull(),
            repository = repository,
            registry = registry,
        ).also {
            it.load()
            model = it
        }
    }
}
