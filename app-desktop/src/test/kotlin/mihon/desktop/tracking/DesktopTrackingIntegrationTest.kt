package mihon.desktop.tracking

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.ui.tracking.oauthLogin
import mihon.desktop.ui.tracking.TrackingScreenModel
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.DesktopOAuthCallbackServer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DesktopTrackingIntegrationTest {
    private val client = OkHttpClient()

    @Test
    fun `tracking OAuth login uses fixed broker redirect and injected opener without loopback browser`() = runTest {
        val broker = DesktopTrackerOAuthCallbackBroker { "desktop-state" }
        val service = mockk<DesktopAuthenticatingTrackerService>(relaxed = true)
        every { service.profile } returns MutableStateFlow(
            TrackerProfile(
                id = 1,
                name = "MyAnimeList",
                authentication = TrackerAuthentication.OAUTH,
                loggedIn = false,
            ),
        )
        every { service.oauthProvider } returns DesktopTrackerOAuthProvider.MY_ANIME_LIST
        every { service.authorizationUrl(any(), any()) } answers {
            assertEquals(DesktopTrackerOAuthProvider.MY_ANIME_LIST.redirectUri, firstArg())
            assertEquals("desktop-state", secondArg())
            "https://oauth.example/authorize"
        }

        oauthLogin(
            service = service,
            callbackBroker = broker,
            timeout = Duration.ofSeconds(2),
            openUrl = {
                assertEquals("https://oauth.example/authorize", it)
                assertEquals(
                    DesktopTrackerOAuthCallbackBroker.Outcome.DELIVERED,
                    broker.handle(
                        "mihon://myanimelist-auth?code=desktop-code&state=desktop-state",
                    ).outcome,
                )
                Result.success(Unit)
            },
        )

        coVerify(exactly = 1) {
            service.finishOAuth(
                "desktop-code",
                DesktopTrackerOAuthProvider.MY_ANIME_LIST.redirectUri,
            )
        }
    }

    @Test
    fun `oauth callback accepts matching state on loopback`() = runTest {
        DesktopOAuthCallbackServer().use { server ->
            val session = server.start("expected", Duration.ofSeconds(2))
            val result = async { session.awaitCode() }

            client.newCall(
                Request.Builder().url("${session.redirectUri}?code=abc&state=expected").build(),
            ).execute().use { assertEquals(200, it.code) }

            assertEquals("abc", result.await())
            assertTrue(session.redirectUri.startsWith("http://127.0.0.1:"))
        }
    }

    @Test
    fun `oauth callback rejects wrong state without consuming valid callback`() = runTest {
        DesktopOAuthCallbackServer().use { server ->
            val session = server.start("expected", Duration.ofSeconds(2))
            val result = async { session.awaitCode() }

            client.newCall(
                Request.Builder().url("${session.redirectUri}?code=attacker&state=wrong").build(),
            ).execute().use { assertEquals(400, it.code) }
            client.newCall(
                Request.Builder().url("${session.redirectUri}?code=valid&state=expected").build(),
            ).execute().close()

            assertEquals("valid", result.await())
        }
    }

    @Test
    fun `oauth callback times out and releases port`() = runTest {
        lateinit var executor: ExecutorService
        val server = DesktopOAuthCallbackServer(
            executorFactory = { Executors.newSingleThreadExecutor().also { executor = it } },
        )
        val session = server.start("state", Duration.ofMillis(30))
        val port = session.redirectUri.substringAfterLast(':').substringBefore('/').toInt()

        assertThrows(TimeoutCancellationException::class.java) {
            kotlinx.coroutines.runBlocking { session.awaitCode() }
        }
        assertTrue(executor.isShutdown)
        server.close()

        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { assertTrue(it.isBound) }
    }

    @Test
    fun `oauth server rejects non loopback binding and occupied port`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOAuthCallbackServer(InetAddress.getByName("0.0.0.0"))
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            lateinit var executor: ExecutorService
            assertThrows(Exception::class.java) {
                DesktopOAuthCallbackServer(
                    port = occupied.localPort,
                    executorFactory = { Executors.newSingleThreadExecutor().also { executor = it } },
                ).start("state")
            }
            assertTrue(executor.isShutdown)
        }
    }

    @Test
    fun `oauth callback close shuts down its executor`() {
        lateinit var executor: ExecutorService
        val server = DesktopOAuthCallbackServer(
            executorFactory = { Executors.newSingleThreadExecutor().also { executor = it } },
        )
        server.start("state")

        server.close()

        assertTrue(executor.isShutdown)
    }

    @Test
    fun `credential store delegates secrets to OS backend and supports logout`() {
        val backend = MemoryCredentialBackend()
        val store = DesktopCredentialStore(backend)

        store.save("anilist", "refresh-token")
        assertEquals("refresh-token", store.load("anilist"))
        assertFalse(store.toString().contains("refresh-token"))

        store.delete("anilist")
        assertEquals(null, store.load("anilist"))
        assertEquals(listOf("save:anilist", "load:anilist", "delete:anilist", "load:anilist"), backend.calls)
    }

    @Test
    fun `screen model edit reaches production registry with every fixed original field`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryCredentialBackend().apply {
                save(
                    "tracker.2.account.default.session.v1",
                    """{"accessToken":"access-secret","username":"22"}""".toCharArray(),
                )
            }
            val registry = DesktopTrackerServiceRegistry.production(
                client = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                credentialStore = DesktopCredentialStore(backend),
                endpoints = DesktopTrackerEndpoints.all(server.url("/").toString()),
                clientConfig = DesktopTrackerClientConfig.forTesting(),
            )
            val original = Track(
                id = 1,
                mangaId = 42,
                trackerId = 2,
                remoteId = 12,
                libraryId = 44,
                title = "AniList manga",
                lastChapterRead = 3.0,
                totalChapters = 10,
                status = 1,
                score = 70.0,
                remoteUrl = "",
                startDate = 0,
                finishDate = 0,
                private = false,
            )
            val repository = MemoryTrackRepository(mutableListOf(original))
            val model = TrackingScreenModel(
                mangaId = 42,
                mangaTitle = "Manga",
                totalChapters = 10,
                getTracks = GetTracks(repository),
                insertTrack = InsertTrack(repository),
                deleteTrack = DeleteTrack(repository),
                getChaptersByMangaId = GetChaptersByMangaId(FakeChapterRepository()),
                registry = registry,
            ).also { it.load() }
            val edit = TrackEdit(
                status = 1,
                score = 80.0,
                lastChapterRead = 4.0,
                startDate = 1_704_153_600_000,
                finishDate = 1_741_046_400_000,
                private = true,
            )
            server.enqueue(
                MockResponse(
                    body = """{"data":{"MediaList":{"id":44,"status":"CURRENT","score":70,"progress":3,"private":false,"startedAt":null,"completedAt":null,"media":{"chapters":10}}}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))

            model.update(2, edit)

            server.takeRequest()
            val updateBody = server.takeRequest().body!!.utf8()
            assertTrue(updateBody.contains("\"status\":\"CURRENT\""), updateBody)
            assertTrue(updateBody.contains("\"progress\":4"), updateBody)
            assertTrue(updateBody.contains("\"scoreRaw\":80"), updateBody)
            assertTrue(updateBody.contains("\"private\":true"), updateBody)
            assertTrue(updateBody.contains("\"startedAt\":{\"year\":2024,\"month\":1,\"day\":2}"), updateBody)
            assertTrue(updateBody.contains("\"completedAt\":{\"year\":2025,\"month\":3,\"day\":4}"), updateBody)
            assertEquals(edit.startDate, repository.rows.single().startDate)
            assertEquals(edit.finishDate, repository.rows.single().finishDate)
            assertTrue(repository.rows.single().private)
        }
    }

    private class MemoryCredentialBackend : CredentialBackend {
        val calls = mutableListOf<String>()
        private val values = mutableMapOf<String, CharArray>()

        override fun save(account: String, secret: CharArray) {
            calls += "save:$account"
            values[account] = secret.copyOf()
        }

        override fun load(account: String): CharArray? {
            calls += "load:$account"
            return values[account]?.copyOf()
        }

        override fun delete(account: String) {
            calls += "delete:$account"
            values.remove(account)?.fill('\u0000')
        }
    }

    private class MemoryTrackRepository(
        val rows: MutableList<Track>,
    ) : TrackRepository {
        override suspend fun getTrackById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = rows.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow() = flowOf(rows.toList())
        override fun getTracksByMangaIdAsFlow(mangaId: Long) = flowOf(rows.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) {
            rows.removeAll { it.mangaId == mangaId && it.trackerId == trackerId }
        }
        override suspend fun insert(track: Track) {
            rows.removeAll { it.mangaId == track.mangaId && it.trackerId == track.trackerId }
            rows += track
        }
        override suspend fun insertAll(tracks: List<Track>) = tracks.forEach { insert(it) }
    }
}
