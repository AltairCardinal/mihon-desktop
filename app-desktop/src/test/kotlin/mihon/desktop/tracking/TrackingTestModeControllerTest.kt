package mihon.desktop.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.desktop.domain.fakes.FakeChapterRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TrackingTestModeControllerTest {
    @Test
    fun `test actions drive login search bind update and logout state`() = runTest {
        val repository = MemoryRepository()
        val service = TestService()
        val controller = TrackingTestModeController(
            repository = repository,
            chapterRepository = FakeChapterRepository(),
            registry = registry(service),
        )

        controller.execute("tracking_login", mapOf("trackerId" to "1", "username" to "user", "password" to "pass"))
        controller.execute("tracking_search", mapOf("trackerId" to "1", "mangaId" to "42", "title" to "Manga", "totalChapters" to "12"))
        controller.execute("tracking_bind", mapOf("trackerId" to "1", "mangaId" to "42", "resultIndex" to "0"))
        controller.execute("tracking_update", mapOf("trackerId" to "1", "mangaId" to "42", "status" to "2", "score" to "10", "chapter" to "12"))

        assertTrue(service.profile.value.loggedIn)
        assertEquals(2, repository.rows.single().status)
        assertEquals(12.0, repository.rows.single().lastChapterRead)

        controller.execute("tracking_logout", mapOf("trackerId" to "1"))
        assertFalse(service.profile.value.loggedIn)
    }

    @Test
    fun `external close cancels only owner operation and caller continues with typed result`() = runBlocking {
        val searchStarted = CompletableDeferred<Unit>()
        val searchFinished = CompletableDeferred<Unit>()
        val service = TestService(
            searchHandler = {
                searchStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    emptyList()
                } finally {
                    searchFinished.complete(Unit)
                }
            },
        )
        val controller = TrackingTestModeController(
            repository = MemoryRepository(),
            chapterRepository = FakeChapterRepository(),
            registry = registry(service),
        )
        controller.execute(
            "tracking_login",
            mapOf("trackerId" to "1", "username" to "user", "password" to "pass"),
        )
        var callerContinued = false
        val caller = async {
            val result = controller.execute(
                "tracking_search",
                mapOf("trackerId" to "1", "title" to "Manga"),
            )
            callerContinued = true
            result
        }
        searchStarted.await()
        controller.close()
        val result = withTimeout(1_000) { caller.await() }

        assertTrue(callerContinued)
        assertFalse(result.success)
        assertEquals(TrackingTestFailureCode.OWNER_CLOSED, result.failureCode)
        assertTrue(searchFinished.isCompleted)
        assertTrue(controller.snapshot().closed)
    }

    @Test
    fun `production callback can close owner on its operation thread without self join`() = runBlocking {
        lateinit var controller: TrackingTestModeController
        val service = TestService(
            searchHandler = {
                controller.close()
                emptyList()
            },
        )
        controller = TrackingTestModeController(
            repository = MemoryRepository(),
            chapterRepository = FakeChapterRepository(),
            registry = registry(service),
        )
        controller.execute(
            "tracking_login",
            mapOf("trackerId" to "1", "username" to "user", "password" to "pass"),
        )
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tracking-owner-self-close").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val callerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val caller = callerScope.async {
                controller.execute(
                    "tracking_search",
                    mapOf("trackerId" to "1", "title" to "Manga"),
                )
            }
            val result = withTimeoutOrNull(500) { caller.await() }

            assertNotNull(result)
            assertFalse(result!!.success)
            assertEquals(TrackingTestFailureCode.OWNER_CLOSED, result.failureCode)
            assertTrue(controller.snapshot().closed)
        } finally {
            callerScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `closed snapshot never regresses after close returns while operation completes`() = runBlocking {
        val searchReturned = CompletableDeferred<Unit>()
        val service = TestService(
            searchHandler = {
                searchReturned.complete(Unit)
                emptyList()
            },
        )
        val controller = TrackingTestModeController(
            repository = MemoryRepository(),
            chapterRepository = FakeChapterRepository(),
            registry = registry(service),
        )
        controller.execute(
            "tracking_login",
            mapOf("trackerId" to "1", "username" to "user", "password" to "pass"),
        )
        val caller = async {
            controller.execute(
                "tracking_search",
                mapOf("trackerId" to "1", "title" to "Manga"),
            )
        }
        searchReturned.await()
        delay(10)
        controller.close()

        while (!caller.isCompleted) {
            assertTrue(controller.snapshot().closed)
            kotlinx.coroutines.yield()
        }
        val result = caller.await()
        assertTrue(result.success || result.failureCode == TrackingTestFailureCode.OWNER_CLOSED)
        assertTrue(controller.snapshot().closed)
    }

    @Test
    fun `caller cancellation cancels owned operation joins cleanup and is rethrown`() = runBlocking {
        val searchStarted = CompletableDeferred<Unit>()
        val operationFinished = CompletableDeferred<Unit>()
        val blockFirstSearch = AtomicBoolean(true)
        val service = TestService(
            searchHandler = {
                if (blockFirstSearch.getAndSet(false)) {
                    searchStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                        emptyList()
                    } finally {
                        operationFinished.complete(Unit)
                    }
                } else {
                    emptyList()
                }
            },
        )
        val controller = TrackingTestModeController(
            repository = MemoryRepository(),
            chapterRepository = FakeChapterRepository(),
            registry = registry(service),
        )
        controller.execute(
            "tracking_login",
            mapOf("trackerId" to "1", "username" to "user", "password" to "pass"),
        )
        val caller = async {
            controller.execute(
                "tracking_search",
                mapOf("trackerId" to "1", "title" to "Manga"),
            )
        }
        searchStarted.await()
        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        assertTrue(operationFinished.isCompleted)
        val next = controller.execute(
            "tracking_search",
            mapOf("trackerId" to "1", "title" to "Retry"),
        )
        assertTrue(next.success)
    }

    private fun registry(service: TrackerService) = object : TrackerServiceRegistry { override val services = listOf(service) }

    private class MemoryRepository : TrackRepository {
        val rows = mutableListOf<Track>()
        override suspend fun getTrackById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = rows.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(rows)
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> = flowOf(rows.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) { rows.removeAll { it.mangaId == mangaId && it.trackerId == trackerId } }
        override suspend fun insert(track: Track) { rows.removeAll { it.mangaId == track.mangaId && it.trackerId == track.trackerId }; rows += track }
        override suspend fun insertAll(tracks: List<Track>) { tracks.forEach { insert(it) } }
    }

    private class TestService(
        private val searchHandler: suspend (String) -> List<TrackSearchResult> = { query ->
            listOf(TrackSearchResult(10, query, 12))
        },
    ) : DesktopAuthenticatingTrackerService {
        override val profile = MutableStateFlow(TrackerProfile(1, "Test", TrackerAuthentication.USERNAME_PASSWORD, false))
        override val statuses = listOf(1L to "Reading", 2L to "Completed")
        override val scores = listOf(0.0, 10.0)
        override fun authorizationUrl(redirectUri: String, state: String) = ""
        override suspend fun finishOAuth(code: String, redirectUri: String) = Unit
        override suspend fun login(username: String, password: String) { profile.value = profile.value.copy(loggedIn = true, username = username) }
        override suspend fun loginWithApiKey(apiKey: String) { profile.value = profile.value.copy(loggedIn = true) }
        override suspend fun search(query: String): List<TrackSearchResult> = searchHandler(query)
        override suspend fun bind(mangaId: Long, result: TrackSearchResult) = Track(0, mangaId, 1, result.remoteId, null, result.title, 0.0, 12, 1, 0.0, "", 0, 0, false)
        override suspend fun update(track: Track, edit: TrackEdit) = track.copy(status = edit.status ?: track.status, score = edit.score ?: track.score, lastChapterRead = edit.lastChapterRead ?: track.lastChapterRead)
        override suspend fun logout() { profile.value = profile.value.copy(loggedIn = false, username = null) }
    }
}
