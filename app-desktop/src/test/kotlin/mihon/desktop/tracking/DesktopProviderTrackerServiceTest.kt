package mihon.desktop.tracking

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerAuthentication

class DesktopProviderTrackerServiceTest {
    @Test
    fun `production profiles do not claim login when desktop client configuration is unavailable`() {
        val registry = DesktopTrackerServiceRegistry.production(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            credentialStore = DesktopCredentialStore(MemoryBackend()),
        )

        assertTrue(registry.services.filter { it.profile.value.id in 1L..5L }.all { it.profile.value.unavailableReason != null })
        assertEquals(null, registry.services.single { it.profile.value.id == 7L }.profile.value.unavailableReason)
    }

    @Test
    fun `provider authorization and token grants match original client contracts`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val registry = registry(server, MemoryBackend())
            val aniList = service(registry, "AniList")
            assertEquals("token", aniList.authorizationUrl("http://127.0.0.1/callback", "state").toHttpUrl().queryParameter("response_type"))

            val kitsu = service(registry, "Kitsu")
            server.enqueue(MockResponse(body = tokenFixture()))
            kitsu.login("user", "pass")
            val kitsuLogin = server.takeRequest().body!!.utf8()
            assertTrue(kitsuLogin.contains("grant_type=password"))
            assertTrue(kitsuLogin.contains("client_secret=test-kitsu-secret"))

            for (name in listOf("Shikimori", "Bangumi")) {
                val oauth = service(registry, name)
                server.enqueue(MockResponse(body = tokenFixture()))
                oauth.finishOAuth("code", "http://127.0.0.1/callback")
                val body = server.takeRequest().body!!.utf8()
                assertTrue(body.contains("grant_type=authorization_code"))
                assertTrue(body.contains("client_secret=test-${name.lowercase()}-secret"))
                assertTrue(body.contains("redirect_uri=http%3A%2F%2F127.0.0.1%2Fcallback"))
            }
        }
    }

    @Test
    fun `oauth provider refreshes expired credential before search`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val registry = registry(server, MemoryBackend())
            val mal = registry.services.first() as DesktopAuthenticatingTrackerService
            server.enqueue(
                MockResponse(
                    body = """{"access_token":"old","refresh_token":"refresh-secret","expires_in":0,"token_type":"Bearer"}""",
                ),
            )
            mal.finishOAuth("code", "http://127.0.0.1/callback")
            server.takeRequest()
            server.enqueue(
                MockResponse(
                    body = """{"access_token":"new","refresh_token":"refresh-next","expires_in":3600,"token_type":"Bearer"}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":[]}"""))

            assertTrue(mal.search("none").isEmpty())

            val refresh = server.takeRequest()
            assertEquals("refresh_token", refresh.body?.utf8()?.substringAfter("grant_type=")?.substringBefore('&'))
            assertEquals("Bearer new", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `production registry contains every public Android tracker with isolated credentials`() = runTest {
        val backend = MemoryBackend()
        val registry = DesktopTrackerServiceRegistry.production(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            credentialStore = DesktopCredentialStore(backend),
        )

        assertEquals(
            listOf("MyAnimeList", "AniList", "Kitsu", "Shikimori", "Bangumi", "MangaUpdates"),
            registry.services.map { it.profile.value.name },
        )
        assertEquals(
            listOf(
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.USERNAME_PASSWORD,
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.OAUTH,
                TrackerAuthentication.USERNAME_PASSWORD,
            ),
            registry.services.map { it.profile.value.authentication },
        )
        assertTrue(registry.services.none { it.profile.value.loggedIn })
        assertEquals(6, backend.loadedKeys.toSet().size)
        assertTrue(backend.loadedKeys.all { it.matches(Regex("tracker\\.\\d+\\.account\\.default\\.session\\.v1")) })
    }

    @Test
    fun `logout deletes only provider and account isolated credential`() = runTest {
        val backend = MemoryBackend()
        MockWebServer().also { it.start() }.use { server ->
            val registry = registry(server, backend)
            val mal = registry.services.first() as DesktopAuthenticatingTrackerService
            server.enqueue(MockResponse(body = """{"access_token":"access","expires_in":3600}"""))
            mal.finishOAuth("code", "http://127.0.0.1/callback")
            server.takeRequest()

            mal.logout()

            assertEquals(listOf("tracker.1.account.default.session.v1"), backend.deletedKeys)
            assertFalse(mal.profile.value.loggedIn)
        }
    }

    @Test
    fun `provider searches parse original response shapes and updates use original endpoints`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend()
            val registry = registry(server, backend)

            loginAll(registry, server)

            val fixtures = listOf(
                "MyAnimeList" to """{"data":[{"node":{"id":11,"title":"MAL","synopsis":"s","num_chapters":12,"main_picture":{"large":"mal.jpg"},"media_type":"manga"}}]}""",
                "AniList" to """{"data":{"Page":{"media":[{"id":12,"title":{"userPreferred":"AL"},"chapters":13,"description":"s","coverImage":{"large":"al.jpg"}}]}}}""",
                "Kitsu" to """{"data":[{"id":"13","attributes":{"canonicalTitle":"Kitsu","chapterCount":14,"synopsis":"s","posterImage":{"original":"kitsu.jpg"},"slug":"kitsu"}}]}""",
                "Shikimori" to """[{"id":14,"name":"Shiki","chapters":15,"description":"s","image":{"preview":"/shiki.jpg"},"url":"/mangas/14"}]""",
                "Bangumi" to """{"data":[{"id":15,"name":"BGM","name_cn":"Bangumi","summary":"s","volumes":16,"images":{"large":"bgm.jpg"}}]}""",
                "MangaUpdates" to """{"results":[{"record":{"series_id":16,"title":"MU","description":"s","url":"https://www.mangaupdates.com/series/16","image":{"url":{"original":"mu.jpg"}}}}]}""",
            )

            fixtures.forEach { (name, body) ->
                server.enqueue(MockResponse(body = body))
                val result = registry.services.single { it.profile.value.name == name }.search("title").single()
                assertTrue(result.remoteId in 11L..16L)
                assertTrue(result.title.isNotBlank())
                server.takeRequest()
            }

            val mal = registry.services.first()
            val bound = mal.bind(99, tachiyomi.domain.track.service.TrackSearchResult(11, "MAL", 12))
            server.enqueue(
                MockResponse(
                    body = """{"status":"reading","score":8,"num_chapters_read":3,"is_rereading":false}""",
                ),
            )
            val updated = mal.update(bound, TrackEdit(status = 1, score = 8.0, lastChapterRead = 3.0))
            val updateRequest = server.takeRequest()
            assertEquals("PUT", updateRequest.method)
            assertTrue(updateRequest.url.encodedPath.contains("/v2/manga/11/my_list_status"))
            assertEquals(3.0, updated.lastChapterRead)

            val aniList = registry.services.single { it.profile.value.name == "AniList" }
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44,"status":"PLANNING","progress":0}}}"""))
            val aniBound = aniList.bind(100, tachiyomi.domain.track.service.TrackSearchResult(12, "AL", 13))
            val aniBindBody = server.takeRequest().body!!.utf8()
            assertEquals(44, aniBound.libraryId)
            assertEquals(5, aniBound.status)
            assertTrue(aniBindBody.contains("SaveMediaListEntry"))
            assertTrue(aniBindBody.contains("PLANNING"))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44,"status":"CURRENT","progress":5}}}"""))
            val datedAniListTrack = aniBound.copy(
                startDate = 1_704_153_600_000,
                finishDate = 1_741_046_400_000,
            )
            assertEquals(2024, java.time.Instant.ofEpochMilli(datedAniListTrack.startDate).atZone(java.time.ZoneId.systemDefault()).year)
            aniList.update(
                datedAniListTrack,
                TrackEdit(status = 1, score = 80.0, lastChapterRead = 5.0),
            )
            val aniBody = server.takeRequest().body!!.utf8()
            assertTrue(aniBody.contains("\u0024progress: Int"))
            assertTrue(aniBody.contains("progress: \u0024progress"))
            assertTrue(aniBody.contains("status: \u0024status"))
            assertTrue(aniBody.contains("scoreRaw: \u0024scoreRaw"))
            assertTrue(aniBody.contains("startedAt: \u0024startedAt"))
            assertTrue(aniBody.contains("completedAt: \u0024completedAt"))
            assertTrue(aniBody.contains("\"year\":2024"), aniBody)
            assertTrue(aniBody.contains("\"year\":2025"), aniBody)

            val kitsu = registry.services.single { it.profile.value.name == "Kitsu" }
            server.enqueue(MockResponse(body = """{"data":[{"id":"7"}]}"""))
            server.enqueue(MockResponse(body = """{"data":{"id":"91","type":"libraryEntries"}}"""))
            val kitsuBound = kitsu.bind(101, tachiyomi.domain.track.service.TrackSearchResult(13, "Kitsu", 14))
            assertEquals("/api/edge/users", server.takeRequest().url.encodedPath)
            assertEquals("POST", server.takeRequest().method)
            assertEquals(91, kitsuBound.libraryId)
            server.enqueue(MockResponse(body = """{"data":{"id":"91"}}"""))
            kitsu.update(kitsuBound, TrackEdit(status = 1, score = 8.0, lastChapterRead = 2.0))
            assertEquals("/api/edge/library-entries/91", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `each provider maps empty malformed and http errors without leaking credentials`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend()
            val registry = registry(server, backend)
            loginAll(registry, server)

            registry.services.forEach { service ->
                server.enqueue(MockResponse(body = emptyFixture(service.profile.value.name)))
                assertTrue(service.search("none").isEmpty())
                server.takeRequest()

                server.enqueue(MockResponse(body = "not-json"))
                assertThrows(Exception::class.java) {
                    kotlinx.coroutines.runBlocking { service.search("bad") }
                }
                server.takeRequest()

                for (status in listOf(401, 403, 429, 500)) {
                    server.enqueue(MockResponse(code = status, body = "{}"))
                    val error = assertThrows(mihon.desktop.tracking.api.TrackerHttpException::class.java) {
                        kotlinx.coroutines.runBlocking { service.search("error") }
                    }
                    assertEquals(status, error.statusCode)
                    server.takeRequest()
                }
            }

            val dump = registry.services.joinToString() + backend.toString()
            assertFalse(dump.contains("access-secret"))
            assertFalse(dump.contains("password-secret"))
        }
    }

    private suspend fun loginAll(registry: DesktopTrackerServiceRegistry, server: MockWebServer) {
        registry.services.forEach { raw ->
            val service = raw as DesktopAuthenticatingTrackerService
            when (raw.profile.value.authentication) {
                TrackerAuthentication.OAUTH -> {
                    if (raw.profile.value.name == "AniList") {
                        service.finishOAuth("access-secret", "http://127.0.0.1/callback")
                    } else {
                        server.enqueue(
                            MockResponse(
                                body = """{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600,"token_type":"Bearer"}""",
                            ),
                        )
                        service.finishOAuth("code", "http://127.0.0.1/callback")
                        server.takeRequest()
                    }
                }
                TrackerAuthentication.USERNAME_PASSWORD -> {
                    server.enqueue(
                        MockResponse(
                            body = if (raw.profile.value.name == "Kitsu") {
                                """{"access_token":"access-secret","refresh_token":"refresh-secret","created_at":1,"expires_in":3600,"token_type":"Bearer"}"""
                            } else {
                                """{"context":{"session_token":"access-secret","uid":7}}"""
                            },
                        ),
                    )
                    service.login("user", "password-secret")
                    server.takeRequest()
                }
                TrackerAuthentication.API_KEY -> error("unexpected")
            }
            assertTrue(raw.profile.value.loggedIn)
        }
    }

    private fun registry(server: MockWebServer, backend: MemoryBackend) = DesktopTrackerServiceRegistry.production(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        credentialStore = DesktopCredentialStore(backend),
        endpoints = DesktopTrackerEndpoints.all(server.url("/").toString()),
        clientConfig = DesktopTrackerClientConfig.forTesting(),
    )

    private fun service(registry: DesktopTrackerServiceRegistry, name: String) =
        registry.services.single { it.profile.value.name == name } as DesktopAuthenticatingTrackerService

    private fun tokenFixture() = """{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600}"""

    private fun emptyFixture(name: String) = when (name) {
        "AniList" -> """{"data":{"Page":{"media":[]}}}"""
        "Shikimori" -> "[]"
        "MangaUpdates" -> """{"results":[]}"""
        else -> """{"data":[]}"""
    }

    private class MemoryBackend : CredentialBackend {
        private val values = mutableMapOf<String, CharArray>()
        val loadedKeys = mutableListOf<String>()
        val deletedKeys = mutableListOf<String>()

        override fun save(account: String, secret: CharArray) {
            values[account] = secret.copyOf()
        }

        override fun load(account: String): CharArray? {
            loadedKeys += account
            return values[account]?.copyOf()
        }

        override fun delete(account: String) {
            deletedKeys += account
            values.remove(account)?.fill('\u0000')
        }

        override fun toString() = "MemoryBackend(keys=${values.keys})"
    }
}
