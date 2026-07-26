package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mihon.desktop.test.state.applicationState
import mihon.desktop.tracking.DesktopAuthenticatingTrackerService
import mihon.desktop.tracking.TrackingTestModeController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class TrackingTestModeHttpTest {
    @Test
    fun `tracking login dispatches through production controller and is visible in test state`() = runBlocking {
        val service = RecordingService(1L, TrackerAuthentication.USERNAME_PASSWORD)
        val controller = controller(service)
        TrackingTestBridge.controller = controller
        try {
            withServer { baseUrl ->
                val response = post(
                    baseUrl,
                    "/test/action/tracking_login",
                    """{"trackerId":"1","username":"reader","password":"dispatch-secret"}""",
                )

                assertEquals(200, response.statusCode())
                assertEquals("reader" to "dispatch-secret", service.usernamePassword)
                assertTrue(response.body().contains(""""trackerId":1"""))
                assertTrue(response.body().contains(""""loggedIn":true"""))
                val state = get(baseUrl, "/test/state").body()
                assertTrue(state.contains(""""tracking":{"""))
                assertTrue(state.contains(""""trackerId":1"""))
            }
        } finally {
            TrackingTestBridge.controller = null
        }
    }

    @Test
    fun `tracking login secrets are dispatched raw but redacted from both histories`() = runBlocking {
        val password = RecordingService(1L, TrackerAuthentication.USERNAME_PASSWORD)
        val apiKey = RecordingService(2L, TrackerAuthentication.API_KEY)
        val oauth = RecordingService(3L, TrackerAuthentication.OAUTH)
        val controller = controller(password, apiKey, oauth)
        TrackingTestBridge.controller = controller
        applicationState.reset()
        try {
            withServer { baseUrl ->
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/tracking_login",
                        """{"trackerId":"1","username":"reader","password":"password-176-secret"}""",
                    ).statusCode(),
                )
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/tracking_login",
                        """{"trackerId":"2","apiKey":"api-key-176-secret"}""",
                    ).statusCode(),
                )
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/tracking_login",
                        """{"trackerId":"3","code":"oauth-code-176-secret","redirectUri":"mihon://oauth/callback"}""",
                    ).statusCode(),
                )

                assertEquals("reader" to "password-176-secret", password.usernamePassword)
                assertEquals("api-key-176-secret", apiKey.apiKey)
                assertEquals("oauth-code-176-secret" to "mihon://oauth/callback", oauth.oauth)
                val history = applicationState.actionHistory.value.filter { it.action == "tracking_login" }
                assertEquals(3, history.size)
                assertEquals("<redacted>", history[0].params["password"])
                assertEquals("<redacted>", history[1].params["apiKey"])
                assertEquals("<redacted>", history[2].params["code"])
                assertEquals("mihon://oauth/callback", history[2].params["redirectUri"])
                val inMemory = history.joinToString { it.params.toString() }
                val overHttp = get(baseUrl, "/test/history").body()
                listOf("password-176-secret", "api-key-176-secret", "oauth-code-176-secret").forEach { secret ->
                    assertFalse(secret in inMemory)
                    assertFalse(secret in overHttp)
                }
            }
        } finally {
            applicationState.reset()
            TrackingTestBridge.controller = null
        }
    }

    @Test
    fun `tracking invalid unavailable and missing owners return typed HTTP failures`() = runBlocking {
        val controller = controller(RecordingService(1L, TrackerAuthentication.USERNAME_PASSWORD))
        TrackingTestBridge.controller = controller
        try {
            withServer { baseUrl ->
                assertFailure(
                    post(baseUrl, "/test/action/tracking_login", """{"trackerId":"not-a-number"}"""),
                    400,
                    "INVALID_PARAMETER",
                )
                assertFailure(
                    post(baseUrl, "/test/action/tracking_login", """{"trackerId":"99"}"""),
                    503,
                    "SERVICE_UNAVAILABLE",
                )
                TrackingTestBridge.controller = null
                assertFailure(
                    post(baseUrl, "/test/action/tracking_login", """{"trackerId":"1"}"""),
                    503,
                    "TRACKING_OWNER_UNAVAILABLE",
                )
            }
        } finally {
            TrackingTestBridge.controller = null
        }
    }

    @Test
    fun `tracking login rejects blank authentication values before service dispatch`() = runBlocking {
        val password = RecordingService(1L, TrackerAuthentication.USERNAME_PASSWORD)
        val apiKey = RecordingService(2L, TrackerAuthentication.API_KEY)
        val oauth = RecordingService(3L, TrackerAuthentication.OAUTH)
        val controller = controller(password, apiKey, oauth)
        TrackingTestBridge.controller = controller
        try {
            withServer { baseUrl ->
                assertFailure(
                    post(
                        baseUrl,
                        "/test/action/tracking_login",
                        """{"trackerId":"1","username":" ","password":" "}""",
                    ),
                    400,
                    "INVALID_PARAMETER",
                )
                assertFailure(
                    post(baseUrl, "/test/action/tracking_login", """{"trackerId":"2","apiKey":" "}"""),
                    400,
                    "INVALID_PARAMETER",
                )
                assertFailure(
                    post(
                        baseUrl,
                        "/test/action/tracking_login",
                        """{"trackerId":"3","code":" ","redirectUri":" "}""",
                    ),
                    400,
                    "INVALID_PARAMETER",
                )
                assertEquals(null, password.usernamePassword)
                assertEquals(null, apiKey.apiKey)
                assertEquals(null, oauth.oauth)
            }
        } finally {
            TrackingTestBridge.controller = null
        }
    }

    @Test
    fun `tracking update rejects malformed numbers before production update`() = runBlocking {
        val service = RecordingService(1L, TrackerAuthentication.USERNAME_PASSWORD)
        service.login("reader", "ready")
        val controller = controller(service, repository = boundRepository())
        TrackingTestBridge.controller = controller
        try {
            withServer { baseUrl ->
                listOf(
                    """"status":"not-a-status"""",
                    """"score":"not-a-score"""",
                    """"chapter":"not-a-chapter"""",
                ).forEach { malformed ->
                    assertFailure(
                        post(
                            baseUrl,
                            "/test/action/tracking_update",
                            """{"trackerId":"1","mangaId":"42","title":"Manga","totalChapters":"12",$malformed}""",
                        ),
                        400,
                        "INVALID_PARAMETER",
                    )
                }
                assertEquals(0, service.updateCalls)
            }
        } finally {
            TrackingTestBridge.controller = null
        }
    }

    @Test
    fun `closed tracking owner returns typed failure without executing service`() = runBlocking {
        val service = RecordingService(1L, TrackerAuthentication.USERNAME_PASSWORD)
        val controller = controller(service)
        TrackingTestBridge.controller = controller
        try {
            controller.javaClass.getMethod("close").invoke(controller)
            withServer { baseUrl ->
                assertFailure(
                    post(
                        baseUrl,
                        "/test/action/tracking_login",
                        """{"trackerId":"1","username":"reader","password":"must-not-dispatch"}""",
                    ),
                    503,
                    "OWNER_CLOSED",
                )
                assertEquals(null, service.usernamePassword)
                assertTrue(get(baseUrl, "/test/state").body().contains(""""closed":true"""))
            }
        } finally {
            TrackingTestBridge.controller = null
        }
    }

    private fun controller(
        vararg services: TrackerService,
        repository: TrackRepository = mockk(relaxed = true),
    ) = TrackingTestModeController(
        repository = repository,
        chapterRepository = mockk<ChapterRepository>(relaxed = true),
        registry = object : TrackerServiceRegistry {
            override val services = services.toList()
        },
    )

    private fun boundRepository(): TrackRepository {
        val track = Track(1, 42, 1, 176, null, "Manga", 0.0, 12, 1, 0.0, "", 0, 0, false)
        return mockk<TrackRepository>().also { repository ->
            coEvery { repository.getTracksByMangaId(42) } returns listOf(track)
            every { repository.getTracksByMangaIdAsFlow(42) } returns flowOf(listOf(track))
            every { repository.getTracksAsFlow() } returns flowOf(listOf(track))
            coEvery { repository.insert(any()) } returns Unit
        }
    }

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private fun post(base: String, path: String, body: String) =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(base: String, path: String) =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun assertFailure(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode())
        assertTrue(response.body().contains(""""success":false"""))
        assertTrue(response.body().contains(""""error":"$code""""))
    }

    private class RecordingService(
        id: Long,
        authentication: TrackerAuthentication,
    ) : DesktopAuthenticatingTrackerService {
        override val profile = MutableStateFlow(TrackerProfile(id, "Tracking $id", authentication, false))
        override val statuses = listOf(1L to "Reading")
        override val scores = listOf(0.0, 10.0)
        var usernamePassword: Pair<String, String>? = null
        var apiKey: String? = null
        var oauth: Pair<String, String>? = null
        var updateCalls: Int = 0

        override fun authorizationUrl(redirectUri: String, state: String) = redirectUri

        override suspend fun finishOAuth(code: String, redirectUri: String) {
            oauth = code to redirectUri
            loggedIn()
        }

        override suspend fun login(username: String, password: String) {
            usernamePassword = username to password
            loggedIn(username)
        }

        override suspend fun loginWithApiKey(apiKey: String) {
            this.apiKey = apiKey
            loggedIn()
        }

        override suspend fun search(query: String) = emptyList<TrackSearchResult>()

        override suspend fun bind(mangaId: Long, result: TrackSearchResult) =
            Track(0, mangaId, profile.value.id, result.remoteId, null, result.title, 0.0, 0, 0, 0.0, "", 0, 0, false)

        override suspend fun update(track: Track, edit: TrackEdit): Track {
            updateCalls++
            return track
        }

        override suspend fun logout() {
            profile.value = profile.value.copy(loggedIn = false)
        }

        private fun loggedIn(username: String? = null) {
            profile.value = profile.value.copy(loggedIn = true, username = username)
        }
    }
}
