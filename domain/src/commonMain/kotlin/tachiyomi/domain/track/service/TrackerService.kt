package tachiyomi.domain.track.service

import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.track.model.Track

enum class TrackerAuthentication {
    OAUTH,
    USERNAME_PASSWORD,
    API_KEY,
}

data class TrackSearchResult(
    val remoteId: Long,
    val title: String,
    val totalChapters: Long,
    val remoteUrl: String = "",
    val coverUrl: String = "",
    val summary: String = "",
    /** Enhanced trackers return the source server's current personal progress with the match. */
    val status: Long? = null,
    val lastChapterRead: Double = 0.0,
)

data class TrackerProfile(
    val id: Long,
    val name: String,
    val authentication: TrackerAuthentication,
    val loggedIn: Boolean,
    val username: String? = null,
    /** Why this tracker cannot currently be used. Null means it is available. */
    val unavailableReason: String? = null,
)

data class TrackerProviderConfiguration(
    val id: Long,
    val authentication: TrackerAuthentication,
    val readingStatus: Long,
    val completionStatus: Long,
    val rereadingStatus: Long? = null,
    val supportsReadingDates: Boolean = false,
    val supportsPrivateTracking: Boolean = false,
    val supportsDelete: Boolean = false,
    val chapterReadPolicy: TrackerChapterReadPolicy = TrackerChapterReadPolicy.AUTO_COMPLETE,
)

enum class TrackerChapterReadPolicy {
    INITIAL_ONLY,
    AUTO_COMPLETE,
    ALWAYS_READING,
}

data class TrackerProviderSession(
    val trackerId: Long,
    val loggedIn: Boolean,
    val username: String? = null,
)

/**
 * Platform-neutral description of an installed source that can back an enhanced tracker.
 *
 * The source implementation and its HTTP client stay in the platform source set. [sourceId]
 * is the opaque handle used by that platform to select the matching client. Secrets are never
 * persisted by the shared tracker layer and are deliberately omitted from [toString].
 */
class EnhancedTrackerContext(
    val trackerId: Long,
    val sourceId: Long,
    val sourceClassName: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val deleteDownloadsOnServer: Boolean = false,
) {
    val configured: Boolean
        get() = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")

    override fun toString(): String =
        "EnhancedTrackerContext(trackerId=$trackerId, sourceId=$sourceId, sourceClassName=$sourceClassName, configured=$configured)"
}

/** Live source/configuration view supplied by Android or Desktop. */
interface EnhancedTrackerContextProvider {
    val contexts: StateFlow<List<EnhancedTrackerContext>>
    fun refresh() = Unit
}

data class TrackEdit(
    val status: Long? = null,
    val score: Double? = null,
    val lastChapterRead: Double? = null,
    val startDate: Long? = null,
    val finishDate: Long? = null,
    val private: Boolean? = null,
    val didReadChapter: Boolean = false,
)

/** Platform-neutral tracker contract consumed by Android and Desktop presentation/domain code. */
interface TrackerService {
    val profile: StateFlow<TrackerProfile>
    val statuses: List<Pair<Long, String>>
    val scores: List<Double>

    suspend fun search(query: String): List<TrackSearchResult>
    suspend fun bind(mangaId: Long, result: TrackSearchResult): Track
    suspend fun update(track: Track, edit: TrackEdit): Track
    suspend fun logout()
}

interface TrackerServiceRegistry {
    val services: List<TrackerService>
    fun get(id: Long): TrackerService? = services.firstOrNull { it.profile.value.id == id }
    fun refresh() = Unit
}
