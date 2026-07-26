package mihon.desktop.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mihon.desktop.ui.tracking.TrackingScreenModel
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerServiceRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

@Serializable
data class TrackingTestState(
    val trackerId: Long? = null,
    val loggedIn: Boolean = false,
    val resultCount: Int = 0,
    val bound: Boolean = false,
    val closed: Boolean = false,
)

@Serializable
enum class TrackingTestFailureCode {
    INVALID_PARAMETER,
    SERVICE_UNAVAILABLE,
    OPERATION_IN_PROGRESS,
    OPERATION_REJECTED,
    OWNER_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class TrackingTestActionResult(
    val success: Boolean,
    val snapshot: TrackingTestState,
    val failureCode: TrackingTestFailureCode? = null,
)

/** Test-mode adapter over the same repository, services, and validation used by production UI. */
class TrackingTestModeController(
    private val repository: TrackRepository,
    private val chapterRepository: ChapterRepository,
    private val registry: TrackerServiceRegistry,
) {
    private val closed = AtomicBoolean(false)
    private val finalized = AtomicBoolean(false)
    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + Dispatchers.Default)
    private val currentState = AtomicReference(TrackingTestState())
    private val activeOperation = AtomicReference<Deferred<TrackingTestActionResult>?>()
    private val finalization = CountDownLatch(1)
    private var model: TrackingScreenModel? = null
    private val results = mutableMapOf<Long, List<TrackSearchResult>>()

    fun snapshot(): TrackingTestState = currentState.get()

    suspend fun execute(action: String, params: Map<String, String>): TrackingTestActionResult {
        if (closed.get()) return failure(TrackingTestFailureCode.OWNER_CLOSED)
        val operation = ownerScope.async(start = CoroutineStart.LAZY) {
            executeOwned(action, params)
        }
        if (!activeOperation.compareAndSet(null, operation)) {
            operation.cancel()
            return failure(TrackingTestFailureCode.OPERATION_IN_PROGRESS)
        }
        operation.invokeOnCompletion {
            activeOperation.compareAndSet(operation, null)
            finalizeClosedOwner()
        }
        if (closed.get()) {
            operation.cancel()
            return failure(TrackingTestFailureCode.OWNER_CLOSED)
        }
        operation.start()
        return try {
            operation.await()
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) {
                    operation.cancelAndJoin()
                }
                throw error
            }
            if (closed.get()) {
                failure(TrackingTestFailureCode.OWNER_CLOSED)
            } else {
                failure(TrackingTestFailureCode.OPERATION_REJECTED)
            }
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            currentState.updateAndGet { it.copy(closed = true) }
            ownerJob.cancel()
        }
        finalizeClosedOwner()
    }

    internal fun closeAndWait() {
        close()
        finalization.await()
    }

    private suspend fun executeOwned(
        action: String,
        params: Map<String, String>,
    ): TrackingTestActionResult {
        return try {
            executeProduction(action, params)
        } catch (error: TrackingTestFailureException) {
            failure(error.code)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            failure(TrackingTestFailureCode.INVALID_PARAMETER)
        } catch (_: IllegalStateException) {
            failure(TrackingTestFailureCode.OPERATION_REJECTED)
        } catch (_: Exception) {
            failure(TrackingTestFailureCode.SERVICE_UNAVAILABLE)
        }
    }

    private suspend fun executeProduction(
        action: String,
        params: Map<String, String>,
    ): TrackingTestActionResult {
        if (action !in SUPPORTED_ACTIONS) fail(TrackingTestFailureCode.UNSUPPORTED_ACTION)
        val trackerId = params["trackerId"]?.toLongOrNull() ?: fail(TrackingTestFailureCode.INVALID_PARAMETER)
        val service = registry.services.firstOrNull { it.profile.value.id == trackerId }
            ?: fail(TrackingTestFailureCode.SERVICE_UNAVAILABLE)
        when (action) {
            "tracking_login" -> {
                val authenticating = service as? DesktopAuthenticatingTrackerService
                if (authenticating == null) {
                    if (!service.profile.value.loggedIn) fail(TrackingTestFailureCode.SERVICE_UNAVAILABLE)
                } else {
                    when (service.profile.value.authentication) {
                        TrackerAuthentication.USERNAME_PASSWORD -> authenticating.login(
                            params.requiredNonBlank("username"),
                            params.requiredNonBlank("password"),
                        )
                        TrackerAuthentication.API_KEY -> authenticating.loginWithApiKey(
                            params.requiredNonBlank("apiKey"),
                        )
                        TrackerAuthentication.OAUTH -> authenticating.finishOAuth(
                            params.requiredNonBlank("code"),
                            params.requiredNonBlank("redirectUri"),
                        )
                    }
                }
            }
            "tracking_logout" -> currentModel(params).logout(trackerId)
            "tracking_search" -> results[trackerId] = currentModel(params).search(
                trackerId,
                params["title"] ?: fail(TrackingTestFailureCode.INVALID_PARAMETER),
            )
            "tracking_bind" -> {
                val matches = results[trackerId] ?: fail(TrackingTestFailureCode.OPERATION_REJECTED)
                val index = params["resultIndex"]?.toIntOrNull() ?: 0
                val match = matches.getOrNull(index) ?: fail(TrackingTestFailureCode.INVALID_PARAMETER)
                currentModel(params).bind(trackerId, match)
            }
            "tracking_update" -> currentModel(params).update(
                trackerId,
                TrackEdit(
                    status = params.optionalLong("status"),
                    score = params.optionalFiniteDouble("score"),
                    lastChapterRead = params.optionalFiniteDouble("chapter"),
                ),
            )
            "tracking_cancel" -> results.remove(trackerId)
        }
        val mangaId = params["mangaId"]?.toLongOrNull()
        val track = mangaId?.let { repository.getTracksByMangaId(it).firstOrNull { row -> row.trackerId == trackerId } }
        if (closed.get()) return failure(TrackingTestFailureCode.OWNER_CLOSED)
        val state = TrackingTestState(
            trackerId = trackerId,
            loggedIn = service.profile.value.loggedIn,
            resultCount = results[trackerId].orEmpty().size,
            bound = track != null,
        )
        val published = currentState.updateAndGet { existing ->
            if (existing.closed || closed.get()) state.copy(closed = true) else state
        }
        return if (published.closed) {
            TrackingTestActionResult(false, published, TrackingTestFailureCode.OWNER_CLOSED)
        } else {
            TrackingTestActionResult(true, published)
        }
    }

    private suspend fun currentModel(params: Map<String, String>): TrackingScreenModel {
        val mangaId = params["mangaId"]?.toLongOrNull()
        val existing = model
        if (existing != null && existing.mangaId == mangaId) return existing
        return TrackingScreenModel(
            mangaId = mangaId,
            mangaTitle = params["title"],
            totalChapters = params["totalChapters"]?.toLongOrNull(),
            getTracks = GetTracks(repository),
            insertTrack = InsertTrack(repository),
            deleteTrack = DeleteTrack(repository),
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            registry = registry,
        ).also {
            it.load()
            model = it
        }
    }

    private fun failure(code: TrackingTestFailureCode) = TrackingTestActionResult(false, snapshot(), code)

    private fun finalizeClosedOwner() {
        if (!closed.get() || activeOperation.get() != null) return
        if (!finalized.compareAndSet(false, true)) return
        try {
            model?.onDispose()
            model = null
            results.clear()
        } finally {
            currentState.updateAndGet { it.copy(closed = true) }
            finalization.countDown()
        }
    }

    private fun fail(code: TrackingTestFailureCode): Nothing = throw TrackingTestFailureException(code)

    private fun Map<String, String>.requiredNonBlank(key: String): String =
        get(key)?.takeIf(String::isNotBlank) ?: fail(TrackingTestFailureCode.INVALID_PARAMETER)

    private fun Map<String, String>.optionalLong(key: String): Long? {
        val value = get(key) ?: return null
        return value.toLongOrNull() ?: fail(TrackingTestFailureCode.INVALID_PARAMETER)
    }

    private fun Map<String, String>.optionalFiniteDouble(key: String): Double? {
        val value = get(key) ?: return null
        return value.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: fail(TrackingTestFailureCode.INVALID_PARAMETER)
    }

    private class TrackingTestFailureException(val code: TrackingTestFailureCode) : IllegalStateException()

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            "tracking_login",
            "tracking_logout",
            "tracking_search",
            "tracking_bind",
            "tracking_update",
            "tracking_cancel",
        )
    }
}
