package mihon.desktop.tracking

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.ui.tracking.TrackingScreenModel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderException
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderService
import java.time.Instant
import java.util.TimeZone

class DesktopProviderTrackerServiceTest {
    @Test
    fun `production profiles expose every fixed original client configuration`() {
        val registry = DesktopTrackerServiceRegistry.production(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            credentialStore = DesktopCredentialStore(MemoryBackend()),
        )

        assertTrue(registry.services.filter { it.profile.value.id in 1L..5L }.all { it.profile.value.unavailableReason == null })
        assertEquals(null, registry.services.single { it.profile.value.id == 7L }.profile.value.unavailableReason)
    }

    @Test
    fun `production clients preserve fixed auth token callback and secret contracts across separate hosts`() = runTest {
        MockWebServer().also { it.start() }.use { api ->
            MockWebServer().also { it.start() }.use { oauth ->
                val registry = DesktopTrackerServiceRegistry.production(
                    client = OkHttpClient(),
                    json = Json { ignoreUnknownKeys = true },
                    credentialStore = DesktopCredentialStore(MemoryBackend()),
                    endpoints = DesktopTrackerEndpoints.all(api.url("/").toString()).copy(
                        myAnimeListOAuth = oauth.url("/").toString(),
                        aniListOAuth = oauth.url("/").toString(),
                        shikimoriOAuth = oauth.url("/").toString(),
                        bangumiOAuth = oauth.url("/").toString(),
                    ),
                )
                val oauthProviders = listOf(
                    Triple("MyAnimeList", DesktopTrackerOAuthProvider.MY_ANIME_LIST, "code"),
                    Triple("AniList", DesktopTrackerOAuthProvider.ANI_LIST, "token"),
                    Triple("Shikimori", DesktopTrackerOAuthProvider.SHIKIMORI, "code"),
                    Triple("Bangumi", DesktopTrackerOAuthProvider.BANGUMI, "code"),
                )
                val clientIds = mapOf(
                    DesktopTrackerOAuthProvider.MY_ANIME_LIST to "c46c9e24640a64dad5be5ca7a1a53a0f",
                    DesktopTrackerOAuthProvider.ANI_LIST to "16329",
                    DesktopTrackerOAuthProvider.SHIKIMORI to "PB9dq8DzI405s7wdtwTdirYqHiyVMh--djnP7lBUqSA",
                    DesktopTrackerOAuthProvider.BANGUMI to "bgm291665acbd06a4c28",
                )
                oauthProviders.forEach { (name, provider, responseType) ->
                    val auth = service(registry, name).authorizationUrl(provider.redirectUri, "state-${provider.trackerId}").toHttpUrl()
                    assertEquals(oauth.hostName, auth.host)
                    assertEquals(clientIds.getValue(provider), auth.queryParameter("client_id"))
                    assertEquals(responseType, auth.queryParameter("response_type"))
                    assertEquals("state-${provider.trackerId}", auth.queryParameter("state"))
                    assertEquals(
                        provider.redirectUri.takeIf { provider in setOf(DesktopTrackerOAuthProvider.SHIKIMORI, DesktopTrackerOAuthProvider.BANGUMI) },
                        auth.queryParameter("redirect_uri"),
                    )
                    if (provider == DesktopTrackerOAuthProvider.MY_ANIME_LIST) {
                        assertTrue(auth.queryParameter("code_challenge")!!.isNotBlank())
                        assertEquals(null, auth.queryParameter("code_challenge_method"))
                    }
                }

                oauth.enqueue(MockResponse(body = tokenFixture()))
                service(registry, "MyAnimeList").finishOAuth(
                    "mal-code",
                    DesktopTrackerOAuthProvider.MY_ANIME_LIST.redirectUri,
                )
                oauth.takeRequest().also { request ->
                    assertEquals("/v1/oauth2/token", request.url.encodedPath)
                    val body = request.body!!.utf8()
                    assertTrue(body.contains("client_id=c46c9e24640a64dad5be5ca7a1a53a0f"))
                    assertTrue(body.contains("code_verifier="))
                    assertFalse(body.contains("redirect_uri="))
                    assertFalse(body.contains("client_secret="))
                }

                api.enqueue(MockResponse(body = """{"data":{"Viewer":{"id":22,"mediaListOptions":{"scoreFormat":"POINT_100"}}}}"""))
                service(registry, "AniList").finishOAuth(
                    "ani-token",
                    DesktopTrackerOAuthProvider.ANI_LIST.redirectUri,
                )
                api.takeRequest().also { request ->
                    assertEquals(api.hostName, request.url.host)
                    assertTrue(request.body!!.utf8().contains("Viewer"))
                }

                api.enqueue(MockResponse(body = tokenFixture()))
                service(registry, "Kitsu").login("user", "password")
                api.takeRequest().also { request ->
                    assertEquals("/api/oauth/token", request.url.encodedPath)
                    val body = request.body!!.utf8()
                    assertTrue(body.contains("client_id=dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"))
                    assertTrue(body.contains("client_secret=54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"))
                    assertTrue(body.contains("grant_type=password"))
                }

                oauth.enqueue(MockResponse(body = tokenFixture()))
                api.enqueue(MockResponse(body = """{"id":77}"""))
                service(registry, "Shikimori").finishOAuth(
                    "shiki-code",
                    DesktopTrackerOAuthProvider.SHIKIMORI.redirectUri,
                )
                oauth.takeRequest().also { request ->
                    assertEquals("/oauth/token", request.url.encodedPath)
                    val body = request.body!!.utf8()
                    assertTrue(body.contains("client_secret=NajpZcOBKB9sJtgNcejf8OB9jBN1OYYoo-k4h2WWZus"))
                    assertTrue(body.contains("redirect_uri=mihon%3A%2F%2Fshikimori-auth"))
                }
                assertEquals("/api/users/whoami", api.takeRequest().url.encodedPath)

                oauth.enqueue(MockResponse(body = tokenFixture()))
                api.enqueue(MockResponse(body = """{"username":"reader"}"""))
                service(registry, "Bangumi").finishOAuth(
                    "bangumi-code",
                    DesktopTrackerOAuthProvider.BANGUMI.redirectUri,
                )
                oauth.takeRequest().also { request ->
                    assertEquals("/oauth/access_token", request.url.encodedPath)
                    val body = request.body!!.utf8()
                    assertTrue(body.contains("client_secret=43e5ce36b207de16e5d3cfd3e79118db"))
                    assertTrue(body.contains("redirect_uri=mihon%3A%2F%2Fbangumi-auth"))
                }
                assertEquals("/v0/me", api.takeRequest().url.encodedPath)
            }
        }
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
                server.enqueue(
                    MockResponse(body = if (name == "Shikimori") """{"id":77}""" else """{"username":"reader"}"""),
                )
                oauth.finishOAuth("code", "http://127.0.0.1/callback")
                val body = server.takeRequest().body!!.utf8()
                assertTrue(body.contains("grant_type=authorization_code"))
                assertTrue(body.contains("client_secret=test-${name.lowercase()}-secret"))
                assertTrue(body.contains("redirect_uri=http%3A%2F%2F127.0.0.1%2Fcallback"))
                server.takeRequest()
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
    fun `Bangumi refresh keeps fixed redirect while Shikimori refresh does not add one`() = runTest {
        MockWebServer().also { it.start() }.use { api ->
            MockWebServer().also { it.start() }.use { oauth ->
                val backend = MemoryBackend().apply {
                    listOf(4L, 5L).forEach { id ->
                        save(
                            "tracker.$id.account.default.session.v1",
                            """
                                {
                                  "accessToken":"expired",
                                  "refreshToken":"refresh-secret",
                                  "username":"reader",
                                  "expiresAtEpochSeconds":0
                                }
                            """.trimIndent().toCharArray(),
                        )
                    }
                }
                val registry = registry(api, oauth, backend, "default")

                listOf(
                    "Bangumi" to """{"data":[]}""",
                    "Shikimori" to "[]",
                ).forEach { (name, searchFixture) ->
                    oauth.enqueue(MockResponse(body = tokenFixture()))
                    api.enqueue(MockResponse(body = searchFixture))

                    service(registry, name).search("refresh")

                    val refreshBody = oauth.takeRequest().body!!.utf8()
                    if (name == "Bangumi") {
                        assertTrue(
                            refreshBody.contains(
                                "redirect_uri=mihon%3A%2F%2Fbangumi-auth",
                            ),
                            refreshBody,
                        )
                    } else {
                        assertFalse(refreshBody.contains("redirect_uri="), refreshBody)
                    }
                    api.takeRequest()
                }
            }
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
            server.enqueue(MockResponse(body = """{"num_chapters":12,"my_list_status":null}"""))
            server.enqueue(MockResponse(body = """{"status":"plan_to_read","score":0,"num_chapters_read":0}"""))
            val bound = mal.bind(99, tachiyomi.domain.track.service.TrackSearchResult(11, "MAL", 12))
            server.takeRequest()
            server.takeRequest()
            server.enqueue(
                MockResponse(
                    body = """{"num_chapters":12,"my_list_status":{"status":"reading","score":0,"num_chapters_read":0,"is_rereading":false}}""",
                ),
            )
            server.enqueue(
                MockResponse(
                    body = """{"status":"reading","score":8,"num_chapters_read":3,"is_rereading":false}""",
                ),
            )
            val updated = mal.update(bound, TrackEdit(status = 1, score = 8.0, lastChapterRead = 3.0))
            val refreshRequest = server.takeRequest()
            val updateRequest = server.takeRequest()
            assertTrue(refreshRequest.url.encodedPath.endsWith("/v2/manga/11"))
            assertEquals("PUT", updateRequest.method)
            assertTrue(updateRequest.url.encodedPath.contains("/v2/manga/11/my_list_status"))
            assertEquals(3.0, updated.lastChapterRead)

            val aniList = registry.services.single { it.profile.value.name == "AniList" }
            server.enqueue(MockResponse(body = """{"data":{"Page":{"mediaList":[]}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44,"status":"PLANNING","progress":0}}}"""))
            val aniBound = aniList.bind(100, tachiyomi.domain.track.service.TrackSearchResult(12, "AL", 13))
            server.takeRequest()
            val aniBindBody = server.takeRequest().body!!.utf8()
            assertEquals(44, aniBound.libraryId)
            assertEquals(5, aniBound.status)
            assertTrue(aniBindBody.contains("SaveMediaListEntry"))
            assertTrue(aniBindBody.contains("PLANNING"))
            val datedAniListTrack = aniBound.copy(
                startDate = 1_704_153_600_000,
                finishDate = 1_741_046_400_000,
            )
            assertEquals(2024, java.time.Instant.ofEpochMilli(datedAniListTrack.startDate).atZone(java.time.ZoneId.systemDefault()).year)
            server.enqueue(
                MockResponse(
                    body = """{"data":{"MediaList":{"id":44,"status":"PLANNING","score":0,"progress":0,"private":false,"startedAt":{"year":2024,"month":1,"day":2},"completedAt":{"year":2025,"month":3,"day":4},"media":{"chapters":13}}}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44,"status":"CURRENT","progress":5}}}"""))
            aniList.update(
                datedAniListTrack,
                TrackEdit(status = 1, score = 80.0, lastChapterRead = 5.0),
            )
            server.takeRequest()
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
            server.enqueue(MockResponse(body = """{"data":[],"included":[]}"""))
            server.enqueue(MockResponse(body = """{"data":{"id":"91","type":"libraryEntries"}}"""))
            val kitsuBound = kitsu.bind(101, tachiyomi.domain.track.service.TrackSearchResult(13, "Kitsu", 14))
            assertEquals("/api/edge/users", server.takeRequest().url.encodedPath)
            assertEquals("/api/edge/library-entries", server.takeRequest().url.encodedPath)
            assertEquals("POST", server.takeRequest().method)
            assertEquals(91, kitsuBound.libraryId)
            server.enqueue(
                MockResponse(
                    body = """{"data":[{"id":"91","attributes":{"status":"planned","progress":0,"ratingTwenty":null,"private":false,"startedAt":null,"finishedAt":null}}],"included":[{"id":"13","attributes":{"chapterCount":14}}]}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"id":"91"}}"""))
            kitsu.update(kitsuBound, TrackEdit(status = 1, score = 8.0, lastChapterRead = 2.0))
            server.takeRequest().also {
                assertEquals("/api/edge/library-entries", it.url.encodedPath)
                assertEquals("91", it.url.queryParameter("filter[id]"))
            }
            assertEquals("/api/edge/library-entries/91", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `Bangumi rejects an unknown status before sending an update request`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend().apply {
                save(
                    "tracker.5.account.default.session.v1",
                    """{"accessToken":"access-secret"}""".toCharArray(),
                )
            }
            val bangumi = service(registry(server, backend), "Bangumi")
            val bound = providerTrack().copy(
                mangaId = 102,
                trackerId = 5,
                remoteId = 15,
                libraryId = null,
                title = "BGM",
                totalChapters = 16,
            )

            assertTrue(bangumi.profile.value.loggedIn)
            val failure = assertThrows(tachiyomi.domain.track.service.TrackerProviderResultException::class.java) {
                kotlinx.coroutines.runBlocking {
                    bangumi.update(bound, TrackEdit(status = Long.MAX_VALUE))
                }
            }
            assertEquals(TrackerProviderErrorKind.INVALID_REQUEST, failure.error.kind)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `public provider executes shared chapter workflow and fixed main remote delete`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend().apply {
                save("tracker.1.account.default.session.v1", """{"accessToken":"access-secret"}""".toCharArray())
            }
            val service = service(registry(server, backend), "MyAnimeList") as TrackerProviderService
            server.enqueue(
                MockResponse(
                    body = """{"num_chapters":10,"my_list_status":{"status":"reading","score":0,"num_chapters_read":9,"is_rereading":false}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"status":"completed","num_chapters_read":10}"""))

            val edited = service.execute(
                TrackerProviderRequest.Edit(
                    providerTrack(),
                    TrackEdit(lastChapterRead = 10.0, didReadChapter = true),
                ),
            ) as TrackerProviderResult.Success

            val refresh = server.takeRequest()
            val update = server.takeRequest()
            assertEquals("/v2/manga/11", refresh.url.encodedPath)
            assertEquals("PUT", update.method)
            assertTrue(update.body!!.utf8().contains("status=completed"))
            assertEquals(2L, edited.track!!.status)
            assertTrue(edited.track!!.finishDate > 0)

            server.enqueue(MockResponse(code = 204))
            assertTrue(
                service.execute(TrackerProviderRequest.Delete(edited.track!!)) is TrackerProviderResult.Success,
            )
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/v2/manga/11/my_list_status", delete.url.encodedPath)
        }
    }

    @Test
    fun `Kitsu and MangaUpdates failures use shared stable error mapping`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend().apply {
                listOf(3L, 7L).forEach {
                    save("tracker.$it.account.default.session.v1", """{"accessToken":"access-secret"}""".toCharArray())
                }
            }
            val registry = registry(server, backend)

            listOf("Kitsu", "MangaUpdates").forEach { name ->
                server.enqueue(MockResponse(code = 429, body = "{}"))
                val error = assertThrows(TrackerProviderException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        registry.services.single { it.profile.value.name == name }.search("rate limited")
                    }
                }
                assertEquals(TrackerProviderErrorKind.RATE_LIMITED, error.kind)
                assertEquals(429, error.statusCode)
                server.takeRequest()
            }
        }
    }

    @Test
    fun `fixed main register dates and provider request fields survive Desktop adapters`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend().apply {
                mapOf(1L to "mal", 3L to "kitsu", 4L to "77", 5L to "reader", 7L to "mu").forEach { (id, user) ->
                    save(
                        "tracker.$id.account.default.session.v1",
                        """{"accessToken":"access-secret","username":"$user"}""".toCharArray(),
                    )
                }
            }
            val registry = registry(server, backend)

            server.enqueue(MockResponse(body = """{"num_chapters":12,"my_list_status":null}"""))
            server.enqueue(MockResponse(body = """{"status":"plan_to_read","score":0,"num_chapters_read":0}"""))
            val mal = registry.services.single { it.profile.value.id == 1L }
            val malBound = mal.bind(1, tachiyomi.domain.track.service.TrackSearchResult(11, "MAL", 12))
            server.takeRequest()
            assertEquals("PUT", server.takeRequest().method)
            server.enqueue(
                MockResponse(
                    body = """{"num_chapters":12,"my_list_status":{"status":"reading","score":0,"num_chapters_read":1,"is_rereading":false}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"status":"reading","score":0,"num_chapters_read":1}"""))
            mal.update(
                malBound.copy(startDate = 1_704_153_600_000, finishDate = 1_741_046_400_000),
                TrackEdit(status = 1),
            )
            server.takeRequest()
            val malUpdate = server.takeRequest().body!!.utf8()
            assertTrue(malUpdate.contains("start_date=2024-01-02"), malUpdate)
            assertTrue(malUpdate.contains("&finish_date=2025-03-04"), malUpdate)

            val kitsu = registry.services.single { it.profile.value.id == 3L }
            val kitsuTrack = providerTrack().copy(
                trackerId = 3,
                remoteId = 13,
                libraryId = 91,
                startDate = 1_704_153_600_000,
                finishDate = 1_741_046_400_000,
            )
            server.enqueue(
                MockResponse(
                    body = """{"data":[{"id":"91","attributes":{"status":"planned","progress":0,"ratingTwenty":null,"private":false,"startedAt":null,"finishedAt":null}}],"included":[{"id":"13","attributes":{"chapterCount":14}}]}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"id":"91"}}"""))
            kitsu.update(kitsuTrack, TrackEdit(status = 1))
            server.takeRequest()
            val kitsuUpdate = server.takeRequest().body!!.utf8()
            assertTrue(kitsuUpdate.contains("\"startedAt\":"), kitsuUpdate)
            assertTrue(kitsuUpdate.contains("\"finishedAt\":"), kitsuUpdate)

            val shikimori = registry.services.single { it.profile.value.id == 4L }
            server.enqueue(MockResponse(body = """{"id":14,"name":"Shiki","chapters":15}"""))
            server.enqueue(MockResponse(body = "[]"))
            server.enqueue(MockResponse(body = """{"id":401}"""))
            val shikiBound = shikimori.bind(1, tachiyomi.domain.track.service.TrackSearchResult(14, "Shiki", 15))
            server.takeRequest()
            server.takeRequest()
            val shikiAdd = server.takeRequest().body!!.utf8()
            assertTrue(shikiAdd.contains("\"user_id\":77"), shikiAdd)
            assertEquals(401, shikiBound.libraryId)

            val bangumi = registry.services.single { it.profile.value.id == 5L }
            server.enqueue(MockResponse(code = 404))
            server.enqueue(MockResponse(code = 202))
            val bgmBound = bangumi.bind(1, tachiyomi.domain.track.service.TrackSearchResult(15, "BGM", 16))
            server.takeRequest()
            val bgmAdd = server.takeRequest()
            assertEquals("POST", bgmAdd.method)
            assertTrue(bgmAdd.body!!.utf8().contains("\"ep_status\":0"))

            server.enqueue(
                MockResponse(
                    body = """{"type":1,"rate":0,"ep_status":0,"private":true,"subject":{"eps":16}}""",
                ),
            )
            server.enqueue(MockResponse(code = 204))
            val bgmUpdated = bangumi.update(bgmBound, TrackEdit(status = 3, private = true))
            val bgmRefresh = server.takeRequest()
            val bgmUpdate = server.takeRequest()
            assertTrue(bgmRefresh.url.encodedPath.contains("/v0/users/reader/collections/15"))
            assertEquals("PATCH", bgmUpdate.method)
            assertTrue(bgmUpdate.body!!.utf8().contains("\"private\":true"))
            assertEquals(16, bgmUpdated.totalChapters)
            assertTrue(bgmUpdated.private)

            val mangaUpdates = registry.services.single { it.profile.value.id == 7L } as TrackerProviderService
            server.enqueue(MockResponse(code = 404))
            server.enqueue(MockResponse(code = 200))
            val muBound = mangaUpdates.bind(1, tachiyomi.domain.track.service.TrackSearchResult(16, "MU", 0))
            server.takeRequest()
            val muAdd = server.takeRequest().body!!.utf8()
            assertTrue(muAdd.startsWith("[{\"series\":{\"id\":16},\"list_id\":"), muAdd)

            server.enqueue(MockResponse(body = """{"list_id":1,"status":{"chapter":0}}"""))
            server.enqueue(MockResponse(body = """{"rating":7.5}"""))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse())
            mangaUpdates.update(muBound.copy(score = 7.5), TrackEdit(lastChapterRead = 2.0))
            server.takeRequest()
            server.takeRequest()
            val muUpdate = server.takeRequest().body!!.utf8()
            val muRating = server.takeRequest()
            assertTrue(muUpdate.startsWith("[{\"series\":{\"id\":16},\"list_id\":"), muUpdate)
            assertEquals("PUT", muRating.method)
            assertTrue(muRating.url.encodedPath.endsWith("/v1/series/16/rating"))

            server.enqueue(MockResponse(code = 204))
            mangaUpdates.execute(TrackerProviderRequest.Delete(muBound))
            assertEquals("[16]", server.takeRequest().body!!.utf8())
        }
    }

    @Test
    fun `fixed main refresh preserves exact MAL AniList and Kitsu dates`() = runTest {
        val previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            MockWebServer().also { it.start() }.use { server ->
                val backend = MemoryBackend().apply {
                    listOf(1L, 2L, 3L).forEach { id ->
                        save("tracker.$id.account.default.session.v1", """{"accessToken":"access-secret"}""".toCharArray())
                    }
                }
                val registry = registry(server, backend)

                val mal = registry.services.single { it.profile.value.id == 1L }
                server.enqueue(
                    MockResponse(
                        body = """{"num_chapters":12,"my_list_status":{"status":"reading","score":8,"num_chapters_read":3,"is_rereading":false,"start_date":"2024-01-02","finish_date":"2025-03-04"}}""",
                    ),
                )
                server.enqueue(MockResponse(body = """{"status":"reading","score":8,"num_chapters_read":3}"""))
                val malDated = mal.update(providerTrack(), TrackEdit(status = 1))
                server.takeRequest()
                val malDatedBody = server.takeRequest().body!!.utf8()
                assertEquals(1_704_153_600_000, malDated.startDate)
                assertEquals(1_741_046_400_000, malDated.finishDate)
                assertTrue(malDatedBody.contains("start_date=2024-01-02"), malDatedBody)
                assertTrue(malDatedBody.contains("&finish_date=2025-03-04"), malDatedBody)

                server.enqueue(
                    MockResponse(
                        body = """{"num_chapters":12,"my_list_status":{"status":"reading","score":8,"num_chapters_read":3,"is_rereading":false,"start_date":"2024-01-02","finish_date":"2025-03-04"}}""",
                    ),
                )
                server.enqueue(MockResponse(body = """{"status":"reading","score":8,"num_chapters_read":3}"""))
                mal.update(malDated, TrackEdit(startDate = 0, finishDate = 0))
                server.takeRequest()
                val malClearedBody = server.takeRequest().body!!.utf8()
                assertTrue(malClearedBody.contains("start_date=&"), malClearedBody)
                assertTrue(malClearedBody.endsWith("&finish_date="), malClearedBody)

                val aniList = registry.services.single { it.profile.value.id == 2L }
                server.enqueue(
                    MockResponse(
                        body = """{"data":{"MediaList":{"id":44,"status":"CURRENT","score":80,"progress":3,"private":false,"startedAt":{"year":2024,"month":1,"day":2},"completedAt":{"year":2025,"month":3,"day":4},"media":{"chapters":13}}}}""",
                    ),
                )
                server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
                val aniDated = aniList.update(
                    providerTrack().copy(trackerId = 2, remoteId = 12, totalChapters = 13),
                    TrackEdit(status = 1),
                )
                val aniRefresh = server.takeRequest().body!!.utf8()
                val aniUpdate = server.takeRequest().body!!.utf8()
                assertTrue(aniRefresh.contains("startedAt { year month day }"), aniRefresh)
                assertTrue(aniRefresh.contains("completedAt { year month day }"), aniRefresh)
                assertEquals(1_704_153_600_000, aniDated.startDate)
                assertEquals(1_741_046_400_000, aniDated.finishDate)
                assertTrue(aniUpdate.contains("\"startedAt\":{\"year\":2024,\"month\":1,\"day\":2}"), aniUpdate)
                assertTrue(aniUpdate.contains("\"completedAt\":{\"year\":2025,\"month\":3,\"day\":4}"), aniUpdate)

                val kitsu = registry.services.single { it.profile.value.id == 3L }
                server.enqueue(
                    MockResponse(
                        body = """{"data":[{"id":"91","attributes":{"status":"current","progress":3,"ratingTwenty":16,"private":false,"startedAt":"2024-01-02T03:04:05.006Z","finishedAt":"2025-03-04T05:06:07.008Z"}}],"included":[{"id":"13","attributes":{"chapterCount":14}}]}""",
                    ),
                )
                server.enqueue(MockResponse(body = """{"data":{"id":"91"}}"""))
                val kitsuDated = kitsu.update(
                    providerTrack().copy(trackerId = 3, remoteId = 13, libraryId = 91, totalChapters = 14),
                    TrackEdit(status = 1),
                )
                val kitsuRefresh = server.takeRequest()
                val kitsuUpdate = server.takeRequest().body!!.utf8()
                assertEquals("/api/edge/library-entries", kitsuRefresh.url.encodedPath)
                assertEquals("91", kitsuRefresh.url.queryParameter("filter[id]"))
                assertEquals(Instant.parse("2024-01-02T03:04:05.006Z").toEpochMilli(), kitsuDated.startDate)
                assertEquals(Instant.parse("2025-03-04T05:06:07.008Z").toEpochMilli(), kitsuDated.finishDate)
                assertTrue(kitsuUpdate.contains("\"startedAt\":\"2024-01-02T03:04:05.006Z\""), kitsuUpdate)
                assertTrue(kitsuUpdate.contains("\"finishedAt\":\"2025-03-04T05:06:07.008Z\""), kitsuUpdate)
            }
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun `public bind queries fixed main existing entries before update`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = authenticatedBackend()
            val registry = registry(server, backend)
            val result = tachiyomi.domain.track.service.TrackSearchResult(11, "Remote manga", 12)

            val mal = registry.get(1) as TrackerProviderService
            server.enqueue(
                MockResponse(
                    body = """{"num_chapters":12,"my_list_status":{"status":"on_hold","score":8,"num_chapters_read":4,"is_rereading":false,"start_date":"2024-01-02","finish_date":"2025-03-04"}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"status":"reading","score":8,"num_chapters_read":4}"""))
            val malBound = mal.bind(101, result, hasReadChapters = true)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("PUT", server.takeRequest().method)
            assertEquals(11, malBound.remoteId)
            assertEquals(4.0, malBound.lastChapterRead)
            assertEquals(8.0, malBound.score)
            assertEquals(1, malBound.status)
            assertTrue(malBound.startDate > 0)
            assertTrue(malBound.finishDate > 0)

            val aniList = registry.get(2) as TrackerProviderService
            server.enqueue(
                MockResponse(
                    body = """{"data":{"Page":{"mediaList":[{"id":44,"status":"PAUSED","scoreRaw":80,"progress":4,"private":true,"startedAt":{"year":2024,"month":1,"day":2},"completedAt":{"year":2025,"month":3,"day":4},"media":{"chapters":12}}]}}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
            val aniBound = aniList.bind(102, result.copy(remoteId = 12), hasReadChapters = true)
            assertTrue(server.takeRequest().body!!.utf8().contains("mediaList"))
            assertTrue(server.takeRequest().body!!.utf8().contains("\"listId\":44"))
            assertEquals(44, aniBound.libraryId)
            assertEquals(4.0, aniBound.lastChapterRead)
            assertEquals(80.0, aniBound.score)
            assertEquals(1, aniBound.status)
            assertFalse(aniBound.private)
            assertTrue(aniBound.startDate > 0)
            assertTrue(aniBound.finishDate > 0)

            val kitsu = registry.get(3) as TrackerProviderService
            server.enqueue(MockResponse(body = """{"data":[{"id":"7"}]}"""))
            server.enqueue(
                MockResponse(
                    body = """{"data":[{"id":"91","attributes":{"status":"on_hold","progress":4,"ratingTwenty":16,"private":true,"startedAt":"2024-01-02T03:04:05.006Z","finishedAt":"2025-03-04T05:06:07.008Z"}}],"included":[{"id":"13","attributes":{"chapterCount":12}}]}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"id":"91"}}"""))
            val kitsuBound = kitsu.bind(103, result.copy(remoteId = 13), hasReadChapters = true)
            server.takeRequest().also {
                assertEquals("/api/edge/users", it.url.encodedPath)
                assertEquals("true", it.url.queryParameter("filter[self]"))
            }
            assertEquals("13", server.takeRequest().url.queryParameter("filter[manga_id]"))
            assertEquals("PATCH", server.takeRequest().method)
            assertEquals(91, kitsuBound.libraryId)
            assertEquals(4.0, kitsuBound.lastChapterRead)
            assertEquals(8.0, kitsuBound.score)
            assertEquals(1, kitsuBound.status)
            assertFalse(kitsuBound.private)
            assertTrue(kitsuBound.startDate > 0)
            assertTrue(kitsuBound.finishDate > 0)

            val shikimori = registry.get(4) as TrackerProviderService
            server.enqueue(MockResponse(body = """{"id":14,"name":"Shiki","chapters":12}"""))
            server.enqueue(
                MockResponse(
                    body = """[{"id":401,"status":"on_hold","score":8,"chapters":4}]""",
                ),
            )
            server.enqueue(MockResponse(body = """{"id":401}"""))
            val shikiBound = shikimori.bind(104, result.copy(remoteId = 14), hasReadChapters = true)
            assertEquals("/api/mangas/14", server.takeRequest().url.encodedPath)
            assertEquals("/api/v2/user_rates", server.takeRequest().url.encodedPath)
            assertEquals("POST", server.takeRequest().method)
            assertEquals(401, shikiBound.libraryId)
            assertEquals(4.0, shikiBound.lastChapterRead)
            assertEquals(8.0, shikiBound.score)
            assertEquals(1, shikiBound.status)

            val bangumi = registry.get(5) as TrackerProviderService
            server.enqueue(
                MockResponse(
                    body = """{"type":3,"rate":8,"ep_status":4,"private":true,"subject":{"eps":12}}""",
                ),
            )
            server.enqueue(MockResponse(code = 204))
            val bangumiBound = bangumi.bind(105, result.copy(remoteId = 15), hasReadChapters = true)
            server.takeRequest().also {
                assertEquals("/v0/users/reader/collections/15", it.url.encodedPath)
                assertEquals("no-cache", it.headers["Cache-Control"])
            }
            assertEquals("PATCH", server.takeRequest().method)
            assertEquals(4.0, bangumiBound.lastChapterRead)
            assertEquals(8.0, bangumiBound.score)
            assertEquals(3, bangumiBound.status)
            assertFalse(bangumiBound.private)

            val mangaUpdates = registry.get(7) as TrackerProviderService
            server.enqueue(MockResponse(body = """{"list_id":4,"status":{"chapter":4}}"""))
            server.enqueue(MockResponse(body = """{"rating":8.0}"""))
            val muBound = mangaUpdates.bind(106, result.copy(remoteId = 16), hasReadChapters = true)
            assertEquals("/v1/lists/series/16", server.takeRequest().url.encodedPath)
            assertEquals("/v1/series/16/rating", server.takeRequest().url.encodedPath)
            assertEquals(4.0, muBound.lastChapterRead)
            assertEquals(8.0, muBound.score)
            assertEquals(4, muBound.status)
        }
    }

    @Test
    fun `existing completed and rereading statuses survive has read bind`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val registry = registry(server, authenticatedBackend())
            val result = tachiyomi.domain.track.service.TrackSearchResult(11, "Existing", 12)
            val mal = registry.get(1) as TrackerProviderService

            server.enqueue(
                MockResponse(
                    body = """{"num_chapters":12,"my_list_status":{"status":"completed","score":8,"num_chapters_read":12,"is_rereading":false}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"status":"completed","score":8,"num_chapters_read":12}"""))
            assertEquals(2, mal.bind(501, result, hasReadChapters = true).status)
            server.takeRequest()
            server.takeRequest()

            server.enqueue(
                MockResponse(
                    body = """{"num_chapters":12,"my_list_status":{"status":"reading","score":8,"num_chapters_read":4,"is_rereading":true}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"status":"reading","score":8,"num_chapters_read":4,"is_rereading":true}"""))
            assertEquals(7, mal.bind(502, result, hasReadChapters = true).status)
            server.takeRequest()
            server.takeRequest()

            val aniList = registry.get(2) as TrackerProviderService
            server.enqueue(
                MockResponse(
                    body = """{"data":{"Page":{"mediaList":[{"id":44,"status":"REPEATING","scoreRaw":80,"progress":4,"private":true,"startedAt":{"year":2024,"month":1,"day":2},"completedAt":{"year":null,"month":null,"day":null},"media":{"chapters":12}}]}}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
            val aniBound = aniList.bind(503, result.copy(remoteId = 12), hasReadChapters = true)
            assertEquals(6, aniBound.status)
            assertFalse(aniBound.private)
            server.takeRequest()
            server.takeRequest()

            val shikimori = registry.get(4) as TrackerProviderService
            server.enqueue(MockResponse(body = """{"id":14,"name":"Shiki","chapters":12}"""))
            server.enqueue(MockResponse(body = """[{"id":401,"status":"rewatching","score":8,"chapters":4}]"""))
            server.enqueue(MockResponse(body = """{"id":401}"""))
            assertEquals(
                6,
                shikimori.bind(504, result.copy(remoteId = 14), hasReadChapters = true).status,
            )
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
        }
    }

    @Test
    fun `public bind creates fixed main initial status from has read chapters`() = runTest {
        suspend fun verify(hasReadChapters: Boolean, expected: Map<Long, Long>) {
            MockWebServer().also { it.start() }.use { server ->
                val registry = registry(server, authenticatedBackend())
                for ((id, expectedStatus) in expected) {
                    when (id) {
                        1L -> {
                            server.enqueue(MockResponse(body = """{"num_chapters":12,"my_list_status":null}"""))
                            server.enqueue(
                                MockResponse(
                                    body = """{"status":"${if (hasReadChapters) "reading" else "plan_to_read"}","score":0,"num_chapters_read":0}""",
                                ),
                            )
                        }
                        2L -> {
                            server.enqueue(MockResponse(body = """{"data":{"Page":{"mediaList":[]}}}"""))
                            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
                        }
                        3L -> {
                            server.enqueue(MockResponse(body = """{"data":[{"id":"7"}]}"""))
                            server.enqueue(MockResponse(body = """{"data":[],"included":[]}"""))
                            server.enqueue(MockResponse(body = """{"data":{"id":"91"}}"""))
                        }
                        4L -> {
                            server.enqueue(MockResponse(body = """{"id":14,"name":"Shiki","chapters":12}"""))
                            server.enqueue(MockResponse(body = "[]"))
                            server.enqueue(MockResponse(body = """{"id":401}"""))
                        }
                        5L -> {
                            server.enqueue(MockResponse(code = 404))
                            server.enqueue(MockResponse(code = 202))
                        }
                        7L -> {
                            server.enqueue(MockResponse(code = 404))
                            server.enqueue(MockResponse(code = 200))
                        }
                    }
                    val bound = (registry.get(id) as TrackerProviderService).bind(
                        mangaId = 200 + id,
                        result = tachiyomi.domain.track.service.TrackSearchResult(10 + id, "New $id", 12),
                        hasReadChapters = hasReadChapters,
                    )
                    assertEquals(expectedStatus, bound.status, "provider $id")
                    if (id == 7L) assertEquals(1.0, bound.lastChapterRead)
                    repeat(
                        when (id) {
                            3L, 4L -> 3
                            else -> 2
                        },
                    ) { server.takeRequest() }
                }
            }
        }

        verify(
            hasReadChapters = true,
            expected = mapOf(1L to 1L, 2L to 1L, 3L to 1L, 4L to 1L, 5L to 3L, 7L to 0L),
        )
        verify(
            hasReadChapters = false,
            expected = mapOf(1L to 6L, 2L to 5L, 3L to 5L, 4L to 5L, 5L to 1L, 7L to 1L),
        )
    }

    @Test
    fun `Kitsu null score clears stale value and updates as JSON null`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val kitsu = registry(server, authenticatedBackend()).get(3)!!
            server.enqueue(
                MockResponse(
                    body = """{"data":[{"id":"91","attributes":{"status":"current","progress":4,"ratingTwenty":null,"private":false,"startedAt":null,"finishedAt":null}}],"included":[{"id":"13","attributes":{"chapterCount":12}}]}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"id":"91"}}"""))

            val updated = kitsu.update(
                providerTrack().copy(trackerId = 3, remoteId = 13, libraryId = 91, score = 9.0),
                TrackEdit(),
            )

            server.takeRequest()
            val body = server.takeRequest().body!!.utf8()
            assertEquals(0.0, updated.score)
            assertTrue(body.contains("\"ratingTwenty\":null"), body)
        }
    }

    @Test
    fun `MAL invalid content and retry after use shared typed failures`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val mal = registry(server, authenticatedBackend()).get(1) as TrackerProviderService
            server.enqueue(MockResponse(body = """{"num_chapters":12,"my_list_status":null}"""))
            server.enqueue(MockResponse(code = 400, body = """{"message":"Invalid content","error":"invalid_content"}"""))
            val rejected = assertThrows(TrackerProviderException::class.java) {
                kotlinx.coroutines.runBlocking {
                    mal.bind(
                        301,
                        tachiyomi.domain.track.service.TrackSearchResult(11, "Unapproved", 12),
                        hasReadChapters = false,
                    )
                }
            }
            assertEquals(TrackerProviderErrorKind.TITLE_NOT_APPROVED, rejected.kind)

            listOf(429 to "37", 503 to "120").forEach { (status, retryAfter) ->
                server.enqueue(
                    MockResponse(
                        code = status,
                        headers = okhttp3.Headers.headersOf("Retry-After", retryAfter),
                        body = "{}",
                    ),
                )
                val failure = assertThrows(TrackerProviderException::class.java) {
                    kotlinx.coroutines.runBlocking { mal.search("rate") }
                }
                assertEquals(retryAfter.toLong(), failure.retryAfterSeconds)
                server.takeRequest()
            }
            listOf("-1", "later").forEach { retryAfter ->
                server.enqueue(
                    MockResponse(
                        code = 429,
                        headers = okhttp3.Headers.headersOf("Retry-After", retryAfter),
                        body = "{}",
                    ),
                )
                val failure = assertThrows(TrackerProviderException::class.java) {
                    kotlinx.coroutines.runBlocking { mal.search("invalid retry") }
                }
                assertEquals(null, failure.retryAfterSeconds)
                server.takeRequest()
            }
        }
    }

    @Test
    fun `API OAuth endpoint separation and account sessions cannot cross`() = runTest {
        MockWebServer().also { it.start() }.use { api ->
            MockWebServer().also { it.start() }.use { oauth ->
                val backend = MemoryBackend()
                val first = registry(api, oauth, backend, "first")
                val second = registry(api, oauth, backend, "second")
                val firstAniList = service(first, "AniList")
                val secondAniList = service(second, "AniList")
                assertEquals(
                    oauth.hostName,
                    firstAniList.authorizationUrl("http://127.0.0.1/callback", "state").toHttpUrl().host,
                )
                api.enqueue(MockResponse(body = """{"data":{"Viewer":{"id":21,"mediaListOptions":{"scoreFormat":"POINT_100"}}}}"""))
                firstAniList.finishOAuth("first-token", "http://127.0.0.1/callback")
                assertTrue(api.takeRequest().body!!.utf8().contains("Viewer"))
                api.enqueue(MockResponse(body = """{"data":{"Viewer":{"id":22,"mediaListOptions":{"scoreFormat":"POINT_100"}}}}"""))
                secondAniList.finishOAuth("second-token", "http://127.0.0.1/callback")
                assertTrue(api.takeRequest().body!!.utf8().contains("Viewer"))
                api.enqueue(MockResponse(body = """{"data":{"Page":{"media":[]}}}"""))
                api.enqueue(MockResponse(body = """{"data":{"Page":{"media":[]}}}"""))

                firstAniList.search("one")
                secondAniList.search("two")

                assertEquals("Bearer first-token", api.takeRequest().headers["Authorization"])
                assertEquals("Bearer second-token", api.takeRequest().headers["Authorization"])
                assertTrue(backend.loadedKeys.contains("tracker.2.account.first.session.v1"))
                assertTrue(backend.loadedKeys.contains("tracker.2.account.second.session.v1"))

                oauth.enqueue(MockResponse(body = tokenFixture()))
                service(first, "MyAnimeList").finishOAuth("code", "http://127.0.0.1/callback")
                assertEquals("/v1/oauth2/token", oauth.takeRequest().url.encodedPath)

                oauth.enqueue(MockResponse(body = tokenFixture()))
                api.enqueue(MockResponse(body = """{"username":"reader"}"""))
                service(first, "Bangumi").finishOAuth("code", "http://127.0.0.1/callback")
                assertEquals("/oauth/access_token", oauth.takeRequest().url.encodedPath)
                assertEquals("/v0/me", api.takeRequest().url.encodedPath)
            }
        }
    }

    @Test
    fun `AniList legacy token lazily resolves Viewer before fixed bind lookup`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend().apply {
                save("tracker.2.account.default.session.v1", """{"accessToken":"legacy-token"}""".toCharArray())
            }
            val aniList = registry(server, backend).get(2) as TrackerProviderService
            server.enqueue(MockResponse(body = """{"data":{"Viewer":{"id":22,"mediaListOptions":{"scoreFormat":"POINT_100"}}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"Page":{"mediaList":[]}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))

            aniList.bind(
                401,
                tachiyomi.domain.track.service.TrackSearchResult(12, "Ani", 12),
                hasReadChapters = false,
            )

            assertTrue(server.takeRequest().body!!.utf8().contains("Viewer"))
            val lookup = server.takeRequest().body!!.utf8()
            assertTrue(lookup.contains("\u0024userId"), lookup)
            assertTrue(lookup.contains("\u0024mediaId"), lookup)
            assertTrue(lookup.contains("\"userId\":22"), lookup)
            assertTrue(lookup.contains("\"mediaId\":12"), lookup)
            server.takeRequest()
        }
    }

    @Test
    fun `production model passes real chapter read state into public adapter`() = runTest {
        for (hasRead in listOf(false, true)) {
            MockWebServer().also { it.start() }.use { server ->
                val registry = registry(server, authenticatedBackend())
                val chapters = FakeChapterRepository().apply {
                    seed(
                        tachiyomi.domain.chapter.model.Chapter.create().copy(
                            id = 1,
                            mangaId = 42,
                            read = hasRead,
                        ),
                    )
                }
                val tracks = MemoryTrackRepository()
                val model = TrackingScreenModel(
                    mangaId = 42,
                    mangaTitle = "Manga",
                    totalChapters = 12,
                    getTracks = GetTracks(tracks),
                    insertTrack = InsertTrack(tracks),
                    deleteTrack = DeleteTrack(tracks),
                    getChaptersByMangaId = GetChaptersByMangaId(chapters),
                    registry = registry,
                ).also { it.load() }
                server.enqueue(MockResponse(body = """{"num_chapters":12,"my_list_status":null}"""))
                server.enqueue(
                    MockResponse(
                        body = """{"status":"${if (hasRead) "reading" else "plan_to_read"}","score":0,"num_chapters_read":0}""",
                    ),
                )

                model.bind(1, tachiyomi.domain.track.service.TrackSearchResult(11, "MAL", 12))

                server.takeRequest()
                val update = server.takeRequest().body!!.utf8()
                assertTrue(
                    update.contains("status=${if (hasRead) "reading" else "plan_to_read"}"),
                    update,
                )
            }
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
                    val error = assertThrows(TrackerProviderException::class.java) {
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
                        server.enqueue(
                            MockResponse(
                                body = """{"data":{"Viewer":{"id":22,"mediaListOptions":{"scoreFormat":"POINT_100"}}}}""",
                            ),
                        )
                        service.finishOAuth("access-secret", "http://127.0.0.1/callback")
                        server.takeRequest()
                    } else {
                        server.enqueue(
                            MockResponse(
                                body = """{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600,"token_type":"Bearer"}""",
                            ),
                        )
                        if (raw.profile.value.name == "Shikimori") {
                            server.enqueue(MockResponse(body = """{"id":77}"""))
                        } else if (raw.profile.value.name == "Bangumi") {
                            server.enqueue(MockResponse(body = """{"username":"reader"}"""))
                        }
                        service.finishOAuth("code", "http://127.0.0.1/callback")
                        server.takeRequest()
                        if (raw.profile.value.name in setOf("Shikimori", "Bangumi")) server.takeRequest()
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

    private fun registry(
        api: MockWebServer,
        oauth: MockWebServer,
        backend: MemoryBackend,
        account: String,
    ) = DesktopTrackerServiceRegistry.production(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        credentialStore = DesktopCredentialStore(backend),
        account = account,
        endpoints = DesktopTrackerEndpoints.all(api.url("/").toString()).copy(
            myAnimeListOAuth = oauth.url("/").toString(),
            aniListOAuth = oauth.url("/").toString(),
            shikimoriOAuth = oauth.url("/").toString(),
            bangumiOAuth = oauth.url("/").toString(),
        ),
        clientConfig = DesktopTrackerClientConfig.forTesting(),
    )

    private fun authenticatedBackend() = MemoryBackend().apply {
        mapOf(
            1L to "mal",
            2L to "22",
            3L to "kitsu",
            4L to "77",
            5L to "reader",
            7L to "mu",
        ).forEach { (id, username) ->
            save(
                "tracker.$id.account.default.session.v1",
                """{"accessToken":"access-secret","username":"$username"}""".toCharArray(),
            )
        }
    }

    private fun service(registry: DesktopTrackerServiceRegistry, name: String) =
        registry.services.single { it.profile.value.name == name } as DesktopAuthenticatingTrackerService

    private fun tokenFixture() = """{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600}"""

    private fun emptyFixture(name: String) = when (name) {
        "AniList" -> """{"data":{"Page":{"media":[]}}}"""
        "Shikimori" -> "[]"
        "MangaUpdates" -> """{"results":[]}"""
        else -> """{"data":[]}"""
    }

    private fun providerTrack() = Track(
        id = 1,
        mangaId = 99,
        trackerId = 1,
        remoteId = 11,
        libraryId = 44,
        title = "MAL",
        lastChapterRead = 9.0,
        totalChapters = 10,
        status = 1,
        score = 0.0,
        remoteUrl = "https://myanimelist.net/manga/11",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    private class MemoryTrackRepository : TrackRepository {
        private val rows = mutableListOf<Track>()

        override suspend fun getTrackById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = rows.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow() = kotlinx.coroutines.flow.flowOf(rows.toList())
        override fun getTracksByMangaIdAsFlow(mangaId: Long) =
            kotlinx.coroutines.flow.flowOf(rows.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) {
            rows.removeAll { it.mangaId == mangaId && it.trackerId == trackerId }
        }
        override suspend fun insert(track: Track) {
            rows.removeAll { it.mangaId == track.mangaId && it.trackerId == track.trackerId }
            rows += track
        }
        override suspend fun insertAll(tracks: List<Track>) = tracks.forEach { insert(it) }
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
