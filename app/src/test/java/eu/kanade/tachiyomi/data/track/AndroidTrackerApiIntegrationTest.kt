package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiInterceptor
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.KitsuApi
import eu.kanade.tachiyomi.data.track.kitsu.KitsuInterceptor
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriInterceptor
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.network.HttpException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.service.TrackerProviderProtocols
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class AndroidTrackerApiIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val passThrough = Interceptor { chain -> chain.proceed(chain.request()) }

    @Test
    fun `AniList production bind and update use shared protocol with full fields`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val api = AnilistApi(OkHttpClient(), passThrough, json, server.url("/").toString())
            val manga = track(2, 5).apply {
                last_chapter_read = 2.0
                private = true
            }
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))

            api.addLibManga(manga)

            val bind = TrackerProviderProtocols.aniList.bind(42, 2, "PLANNING", true)
            val bindPayload = requestJson(server)
            assertEquals(44, manga.library_id)
            assertEquals(bind.query, bindPayload.getValue("query").jsonPrimitive.content)
            assertJsonVariables(
                bindPayload,
                "mediaId" to "42",
                "progress" to "2",
                "status" to "PLANNING",
                "private" to "true",
            )

            manga.status = 1
            manga.score = 88.0
            manga.last_chapter_read = 7.0
            manga.started_reading_date = Instant.parse("2024-01-02T00:00:00Z").toEpochMilli()
            manga.finished_reading_date = Instant.parse("2025-03-04T00:00:00Z").toEpochMilli()
            server.enqueue(MockResponse())

            api.updateLibManga(manga)

            val updatePayload = requestJson(server)
            val variables = updatePayload.getValue("variables").jsonObject
            assertEquals("44", variables.getValue("listId").jsonPrimitive.content)
            assertEquals("7", variables.getValue("progress").jsonPrimitive.content)
            assertEquals("CURRENT", variables.getValue("status").jsonPrimitive.content)
            assertEquals("88", variables.getValue("scoreRaw").jsonPrimitive.content)
            assertEquals("true", variables.getValue("private").jsonPrimitive.content)
            assertEquals("2024", variables.getValue("startedAt").jsonObject.getValue("year").jsonPrimitive.content)
            assertEquals("2025", variables.getValue("completedAt").jsonObject.getValue("year").jsonPrimitive.content)
        }
    }

    @Test
    fun `Kitsu production bind update login and refresh execute exact shared grants`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val base = server.url("/").toString()
            val tokenUrl = server.url("/oauth/token").toString()
            val api = KitsuApi(OkHttpClient(), passThrough, json, base, tokenUrl)
            val manga = track(3, 1).apply { private = true }
            server.enqueue(MockResponse(body = """{"data":{"id":91}}"""))

            api.addLibManga(manga, "user-7")

            val bind = server.takeRequest()
            assertEquals("POST", bind.method)
            assertEquals("/library-entries", bind.url.encodedPath)
            assertTrue(bind.headers["Content-Type"].orEmpty().startsWith("application/vnd.api+json"))
            assertEquals(91, manga.library_id)
            val bindData = json.parseToJsonElement(bind.body!!.utf8()).jsonObject.getValue("data").jsonObject
            assertEquals("libraryEntries", bindData.getValue("type").jsonPrimitive.content)
            val bindAttributes = bindData.getValue("attributes").jsonObject
            assertEquals("current", bindAttributes.getValue("status").jsonPrimitive.content)
            assertEquals("2", bindAttributes.getValue("progress").jsonPrimitive.content)
            assertEquals("true", bindAttributes.getValue("private").jsonPrimitive.content)
            val relationships = bindData.getValue("relationships").jsonObject
            assertRelationship(relationships, "user", "user-7", "users")
            assertRelationship(relationships, "media", "42", "manga")
            server.enqueue(MockResponse())

            api.updateLibManga(manga)

            val update = server.takeRequest()
            assertEquals("PATCH", update.method)
            assertEquals("/library-entries/91", update.url.encodedPath)
            val attributes = json.parseToJsonElement(update.body!!.utf8()).jsonObject
                .getValue("data").jsonObject.getValue("attributes").jsonObject
            assertEquals("current", attributes.getValue("status").jsonPrimitive.content)
            assertEquals("2", attributes.getValue("progress").jsonPrimitive.content)
            assertEquals("16", attributes.getValue("ratingTwenty").jsonPrimitive.content)

            server.enqueue(MockResponse(body = kitsuToken("login-access", "login-refresh")))
            assertEquals("login-access", api.login("alice", "secret").accessToken)
            assertEquals(
                mapOf(
                    "username" to "alice",
                    "password" to "secret",
                    "grant_type" to "password",
                    "client_id" to KITSU_CLIENT_ID,
                    "client_secret" to KITSU_CLIENT_SECRET,
                ),
                form(server.takeRequest().body!!.utf8()),
            )

            val kitsu = mockk<Kitsu>()
            every { kitsu.restoreToken() } returns KitsuOAuth("old", "Bearer", 0, 1, "refresh-value")
            every { kitsu.saveToken(any()) } just Runs
            val refreshClient = OkHttpClient.Builder()
                .addInterceptor(KitsuInterceptor(kitsu, json, tokenUrl))
                .build()
            server.enqueue(MockResponse(body = kitsuToken("new-access", "new-refresh")))
            server.enqueue(MockResponse(body = "ok"))

            refreshClient.newCall(Request.Builder().url(server.url("/resource")).build()).execute().use {
                assertEquals(200, it.code)
            }

            val refresh = server.takeRequest()
            assertEquals("/oauth/token", refresh.url.encodedPath)
            assertEquals(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to "refresh-value",
                    "client_id" to KITSU_CLIENT_ID,
                    "client_secret" to KITSU_CLIENT_SECRET,
                ),
                form(refresh.body!!.utf8()),
            )
            assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `Shikimori production code exchange and refresh execute exact shared grants`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val tokenUrl = server.url("/oauth/token").toString()
            val api = ShikimoriApi(4, OkHttpClient(), passThrough, json, server.url("/api").toString(), tokenUrl)
            server.enqueue(MockResponse(body = shikimoriToken("code-access", "code-refresh")))

            assertEquals("code-access", api.accessToken("auth-code").accessToken)
            assertEquals(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to SHIKIMORI_CLIENT_ID,
                    "client_secret" to SHIKIMORI_CLIENT_SECRET,
                    "code" to "auth-code",
                    "redirect_uri" to "mihon://shikimori-auth",
                ),
                form(server.takeRequest().body!!.utf8()),
            )

            val shikimori = mockk<Shikimori>()
            every { shikimori.restoreToken() } returns SMOAuth("old", "Bearer", 0, 1, "refresh-value")
            every { shikimori.saveToken(any()) } just Runs
            val refreshClient = OkHttpClient.Builder()
                .addInterceptor(ShikimoriInterceptor(shikimori, json, tokenUrl))
                .build()
            server.enqueue(MockResponse(body = shikimoriToken("new-access", "new-refresh")))
            server.enqueue(MockResponse(body = "ok"))

            refreshClient.newCall(Request.Builder().url(server.url("/resource")).build()).execute().close()

            val refresh = server.takeRequest()
            assertEquals(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to SHIKIMORI_CLIENT_ID,
                    "client_secret" to SHIKIMORI_CLIENT_SECRET,
                    "refresh_token" to "refresh-value",
                ),
                form(refresh.body!!.utf8()),
            )
            assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `Bangumi production code exchange and refresh execute exact shared grants`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val tokenUrl = server.url("/oauth/access_token").toString()
            val api = BangumiApi(5, OkHttpClient(), passThrough, json, server.url("/api").toString(), tokenUrl)
            server.enqueue(MockResponse(body = bangumiToken("code-access", "code-refresh")))

            assertEquals("code-access", api.accessToken("auth-code").accessToken)
            assertEquals(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to BANGUMI_CLIENT_ID,
                    "client_secret" to BANGUMI_CLIENT_SECRET,
                    "code" to "auth-code",
                    "redirect_uri" to "mihon://bangumi-auth",
                ),
                form(server.takeRequest().body!!.utf8()),
            )

            val bangumi = mockk<Bangumi>()
            every { bangumi.restoreToken() } returns BGMOAuth("old", "Bearer", 0, 1, "refresh-value", 7)
            every { bangumi.saveToken(any()) } just Runs
            val refreshClient = OkHttpClient.Builder()
                .addInterceptor(BangumiInterceptor(bangumi, json, tokenUrl))
                .build()
            server.enqueue(MockResponse(body = bangumiToken("new-access", "new-refresh")))
            server.enqueue(MockResponse(body = "ok"))

            refreshClient.newCall(Request.Builder().url(server.url("/resource")).build()).execute().close()

            val refresh = server.takeRequest()
            assertEquals(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to BANGUMI_CLIENT_ID,
                    "client_secret" to BANGUMI_CLIENT_SECRET,
                    "refresh_token" to "refresh-value",
                    "redirect_uri" to "mihon://bangumi-auth",
                ),
                form(refresh.body!!.utf8()),
            )
            assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `modified API parsing paths reject empty missing malformed and HTTP errors`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val base = server.url("/").toString()
            val root = base.removeSuffix("/")
            val tokenUrl = server.url("/oauth/token").toString()
            val calls = listOf<suspend () -> Any>(
                { AnilistApi(OkHttpClient(), passThrough, json, base).addLibManga(track(2, 5)) },
                { KitsuApi(OkHttpClient(), passThrough, json, base, tokenUrl).addLibManga(track(3, 1), "user-7") },
                { KitsuApi(OkHttpClient(), passThrough, json, base, tokenUrl).login("alice", "secret") },
                { ShikimoriApi(4, OkHttpClient(), passThrough, json, root, tokenUrl).accessToken("code") },
                { BangumiApi(5, OkHttpClient(), passThrough, json, root, tokenUrl).accessToken("code") },
            )

            calls.forEach { call -> assertParsingMatrix(server, call) }
        }
    }

    @Test
    fun `modified update paths reject 403 429 and 500`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val base = server.url("/").toString()
            val ani = track(2, 1).apply { library_id = 44 }
            val kitsu = track(3, 1).apply { library_id = 91 }
            val calls = listOf<suspend () -> Any>(
                { AnilistApi(OkHttpClient(), passThrough, json, base).updateLibManga(ani) },
                {
                    KitsuApi(
                        OkHttpClient(),
                        passThrough,
                        json,
                        base,
                        server.url("/oauth/token").toString(),
                    ).updateLibManga(kitsu)
                },
            )
            calls.forEach { call ->
                listOf(403, 429, 500).forEach { status ->
                    server.enqueue(MockResponse(code = status, body = "{}"))
                    val error = assertThrows(HttpException::class.java) { runBlocking { call() } }
                    assertEquals(status, error.code)
                }
            }
        }
    }

    @Test
    fun `provider refresh paths reject malformed tokens and preserve old token on HTTP errors`() {
        MockWebServer().also { it.start() }.use { server ->
            val tokenUrl = server.url("/oauth/token").toString()
            val factories = listOf<() -> OkHttpClient>(
                {
                    val service = mockk<Kitsu>()
                    every { service.restoreToken() } returns KitsuOAuth("old", "Bearer", 0, 1, "refresh")
                    every { service.saveToken(any()) } just Runs
                    OkHttpClient.Builder().addInterceptor(KitsuInterceptor(service, json, tokenUrl)).build()
                },
                {
                    val service = mockk<Shikimori>()
                    every { service.restoreToken() } returns SMOAuth("old", "Bearer", 0, 1, "refresh")
                    every { service.saveToken(any()) } just Runs
                    OkHttpClient.Builder().addInterceptor(ShikimoriInterceptor(service, json, tokenUrl)).build()
                },
                {
                    val service = mockk<Bangumi>()
                    every { service.restoreToken() } returns BGMOAuth("old", "Bearer", 0, 1, "refresh", 7)
                    every { service.saveToken(any()) } just Runs
                    OkHttpClient.Builder().addInterceptor(BangumiInterceptor(service, json, tokenUrl)).build()
                },
            )

            factories.forEach { factory ->
                listOf("", "{}", "not-json").forEach { body ->
                    server.enqueue(MockResponse(body = body))
                    assertThrows(Exception::class.java) {
                        factory().newCall(Request.Builder().url(server.url("/resource")).build()).execute().close()
                    }
                    server.takeRequest()
                }
                listOf(403, 429, 500).forEach { status ->
                    server.enqueue(MockResponse(code = status, body = "{}"))
                    server.enqueue(MockResponse(body = "ok"))
                    factory().newCall(Request.Builder().url(server.url("/resource")).build()).execute().use {
                        assertEquals(200, it.code)
                    }
                    server.takeRequest()
                    assertEquals("Bearer old", server.takeRequest().headers["Authorization"])
                }
            }
        }
    }

    private suspend fun assertParsingMatrix(server: MockWebServer, call: suspend () -> Any) {
        listOf("", "{}", "not-json").forEach { body ->
            server.enqueue(MockResponse(body = body))
            assertThrows(SerializationException::class.java) { runBlocking { call() } }
        }
        listOf(403, 429, 500).forEach { status ->
            server.enqueue(MockResponse(code = status, body = "{}"))
            val error = assertThrows(HttpException::class.java) { runBlocking { call() } }
            assertEquals(status, error.code)
        }
    }

    private fun assertRelationship(
        relationships: kotlinx.serialization.json.JsonObject,
        name: String,
        id: String,
        type: String,
    ) {
        val data = relationships.getValue(name).jsonObject.getValue("data").jsonObject
        assertEquals(id, data.getValue("id").jsonPrimitive.content)
        assertEquals(type, data.getValue("type").jsonPrimitive.content)
    }

    private fun requestJson(server: MockWebServer) =
        json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    private fun assertJsonVariables(
        payload: kotlinx.serialization.json.JsonObject,
        vararg expected: Pair<String, String>,
    ) {
        val variables = payload.getValue("variables").jsonObject
        expected.forEach { (name, value) -> assertEquals(value, variables.getValue(name).jsonPrimitive.content) }
    }

    private fun form(body: String): Map<String, String> = body.split('&')
        .filter(String::isNotEmpty)
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }

    private fun kitsuToken(access: String, refresh: String) =
        """{"access_token":"$access","token_type":"Bearer","created_at":1,"expires_in":3600,"refresh_token":"$refresh"}"""

    private fun shikimoriToken(access: String, refresh: String) =
        """{"access_token":"$access","token_type":"Bearer","created_at":1,"expires_in":3600,"refresh_token":"$refresh"}"""

    private fun bangumiToken(access: String, refresh: String) =
        """{"access_token":"$access","token_type":"Bearer","created_at":1,"expires_in":3600,"refresh_token":"$refresh","user_id":7}"""

    private fun track(trackerId: Long, statusValue: Long) = Track.create(trackerId).apply {
        manga_id = 1
        remote_id = 42
        title = "Manga"
        last_chapter_read = 2.0
        total_chapters = 12
        score = 8.0
        status = statusValue
        started_reading_date = 0
        finished_reading_date = 0
        tracking_url = ""
        private = false
    }

    private companion object {
        const val KITSU_CLIENT_ID = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
        const val KITSU_CLIENT_SECRET = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"
        const val SHIKIMORI_CLIENT_ID = "PB9dq8DzI405s7wdtwTdirYqHiyVMh--djnP7lBUqSA"
        const val SHIKIMORI_CLIENT_SECRET = "NajpZcOBKB9sJtgNcejf8OB9jBN1OYYoo-k4h2WWZus"
        const val BANGUMI_CLIENT_ID = "bgm291665acbd06a4c28"
        const val BANGUMI_CLIENT_SECRET = "43e5ce36b207de16e5d3cfd3e79118db"
    }
}
