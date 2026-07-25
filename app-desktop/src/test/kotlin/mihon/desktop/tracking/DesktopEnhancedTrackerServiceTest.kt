package mihon.desktop.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.service.EnhancedTrackerContext
import tachiyomi.domain.track.service.EnhancedTrackerContextProvider
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerProviderException

class DesktopEnhancedTrackerServiceTest {
    @Test
    fun `enhanced trackers expose an unavailable reason until a configured source exists`() = runTest {
        val contexts = MutableStateFlow(emptyList<EnhancedTrackerContext>())
        val registry = registry(contexts)

        assertEquals(listOf("Komga", "Kavita", "Suwayomi"), registry.services.takeLast(3).map { it.profile.value.name })
        assertTrue(registry.services.takeLast(3).all { !it.profile.value.loggedIn })
        assertTrue(registry.services.takeLast(3).all { it.profile.value.unavailableReason?.contains("source") == true })
        assertNull(registry.get(6))

        contexts.value = listOf(context(6, "Komga", "http://127.0.0.1:1/"))

        assertTrue(registry.services.single { it.profile.value.id == 6L }.profile.value.loggedIn)
        assertEquals(null, registry.services.single { it.profile.value.id == 6L }.profile.value.unavailableReason)
        assertEquals(6L, registry.get(6)?.profile?.value?.id)

        contexts.value = emptyList()
        assertFalse(registry.services.single { it.profile.value.id == 6L }.profile.value.loggedIn)
        assertNull(registry.get(6))

        contexts.value = listOf(context(8, "Kavita", "http://127.0.0.1:1/api"))
        assertTrue(registry.services.single { it.profile.value.id == 8L }.profile.value.unavailableReason?.contains("configured") == true)
    }

    @Test
    fun `komga matches source url and updates read progress using original REST shape`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val contexts = MutableStateFlow(listOf(context(6, "Komga", server.url("/").toString())))
            val service = registry(contexts).services.single { it.profile.value.id == 6L }
            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","summary":"summary","status":"ONGOING"}}"""))
            server.enqueue(MockResponse(body = """{"booksCount":10,"booksReadCount":2,"booksUnreadCount":8,"lastReadContinuousNumberSort":2.0,"maxNumberSort":10.0}"""))

            val result = service.search(server.url("/api/v1/series/series-1").toString()).single()
            val track = service.bind(44, result)
            assertEquals("Komga title", track.title)
            assertEquals(2.0, track.lastChapterRead)
            assertEquals(2L, track.status)
            server.takeRequest()
            server.takeRequest()

            server.enqueue(MockResponse())
            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","summary":"summary","status":"ONGOING"}}"""))
            server.enqueue(MockResponse(body = """{"booksCount":10,"booksReadCount":3,"booksUnreadCount":7,"lastReadContinuousNumberSort":3.0,"maxNumberSort":10.0}"""))
            val updated = service.update(track, TrackEdit(lastChapterRead = 3.0))
            val update = server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            assertEquals("PUT", update.method)
            assertTrue(update.url.encodedPath.endsWith("/api/v2/series/series-1/read-progress/tachiyomi"))
            assertEquals(3.0, updated.lastChapterRead)
        }
    }

    @Test
    fun `kavita authenticates with source api key and uses source server endpoints`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val base = server.url("/api/").toString().removeSuffix("/")
            val contexts = MutableStateFlow(listOf(context(8, "Kavita", base, "source-api-key")))
            val service = registry(contexts).services.single { it.profile.value.id == 8L }
            server.enqueue(MockResponse(body = """{"token":"jwt-secret"}"""))
            server.enqueue(MockResponse(body = """{"id":5,"name":"Kavita title","pages":100,"pagesRead":20,"thumbnail_url":"cover"}"""))
            server.enqueue(MockResponse(body = """[{"chapters":[{"number":"1"},{"number":"2"}]}]"""))
            server.enqueue(MockResponse(body = """{"number":"2"}"""))

            val result = service.search("$base/Series/5").single()
            assertEquals("Kavita title", result.title)
            assertEquals("source-api-key", server.takeRequest().url.queryParameter("apiKey"))
            assertEquals("Bearer jwt-secret", server.takeRequest().headers["Authorization"])
            server.takeRequest()
            server.takeRequest()
            val bound = service.bind(45, result)
            assertEquals(2L, bound.status)

            server.enqueue(MockResponse(body = """{"token":"jwt-secret"}"""))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse(body = """{"token":"jwt-secret"}"""))
            server.enqueue(MockResponse(body = """{"id":5,"name":"Kavita title","pages":100,"pagesRead":30,"thumbnail_url":"cover"}"""))
            server.enqueue(MockResponse(body = """[{"chapters":[{"number":"1"},{"number":"3"}]}]"""))
            server.enqueue(MockResponse(body = """{"number":"3"}"""))
            val updated = service.update(bound, TrackEdit(lastChapterRead = 3.0))
            server.takeRequest()
            val updateRequest = server.takeRequest()
            assertTrue(updateRequest.url.encodedPath.endsWith("/api/Tachiyomi/mark-chapter-until-as-read"))
            assertEquals(3.0, updated.lastChapterRead)
        }
    }

    @Test
    fun `suwayomi maps GraphQL result and surfaces GraphQL errors`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val contexts = MutableStateFlow(listOf(context(9, "Suwayomi", server.url("/").toString())))
            val service = registry(contexts).services.single { it.profile.value.id == 9L }
            server.enqueue(
                MockResponse(
                    body = """{"data":{"manga":{"id":7,"title":"Suwayomi title","thumbnailUrl":"thumb","description":"summary","status":"ONGOING","chapters":{"totalCount":8},"latestReadChapter":{"chapterNumber":3.0},"unreadCount":5}}}""",
                ),
            )
            val result = service.search("7").single()
            assertEquals("Suwayomi title", result.title)
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("GetManga"))

            val bound = service.bind(46, result)
            server.enqueue(MockResponse(body = """{"data":{"chapters":{"nodes":[{"id":31,"chapterNumber":3.0},{"id":40,"chapterNumber":4.0}]}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"updateChapters":{"__typename":"UpdateChaptersPayload"}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"trackProgress":{"__typename":"TrackProgressPayload"}}}"""))
            server.enqueue(
                MockResponse(
                    body = """{"data":{"manga":{"id":7,"title":"Suwayomi title","thumbnailUrl":"thumb","description":"summary","status":"ONGOING","chapters":{"totalCount":8},"latestReadChapter":{"chapterNumber":3.0},"unreadCount":5}}}""",
                ),
            )
            val updated = service.update(bound, TrackEdit(lastChapterRead = 3.0))
            assertEquals(3.0, updated.lastChapterRead)
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("GetMangaUnreadChapters"))
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("MarkChaptersRead"))
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("TrackManga"))
            server.takeRequest()

            server.enqueue(MockResponse(body = """{"errors":[{"message":"server rejected query"}]}"""))
            val error = runCatching { service.search("7") }.exceptionOrNull()
            assertTrue(error?.message?.contains("server rejected query") == true)
        }
    }

    @Test
    fun `all enhanced providers preserve malformed and HTTP failures instead of returning empty matches`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val contexts = MutableStateFlow(
                listOf(
                    context(6, "Komga", server.url("/").toString()),
                    context(8, "Kavita", server.url("/api").toString(), "private-key"),
                    context(9, "Suwayomi", server.url("/").toString()),
                ),
            )
            val registry = registry(contexts)
            val queries = mapOf(6L to server.url("/api/v1/series/1").toString(), 8L to server.url("/api/Series/1").toString(), 9L to "1")

            for (id in listOf(6L, 8L, 9L)) {
                val service = registry.services.single { it.profile.value.id == id }
                for (status in listOf(401, 403, 429, 500)) {
                    server.enqueue(
                        MockResponse(
                            code = status,
                            headers = if (status == 429) {
                                okhttp3.Headers.headersOf("Retry-After", "19")
                            } else {
                                okhttp3.Headers.EMPTY
                            },
                            body = "{}",
                        ),
                    )
                    val error = assertThrows(TrackerProviderException::class.java) {
                        kotlinx.coroutines.runBlocking { service.search(queries.getValue(id)) }
                    }
                    assertEquals(status, error.statusCode)
                    assertEquals(if (status == 429) 19 else null, error.retryAfterSeconds)
                    server.takeRequest()
                }

                if (id == 8L) server.enqueue(MockResponse(body = """{"token":"jwt"}"""))
                server.enqueue(MockResponse(body = "not-json"))
                assertThrows(Exception::class.java) {
                    kotlinx.coroutines.runBlocking { service.search(queries.getValue(id)) }
                }
                if (id == 8L) server.takeRequest()
                server.takeRequest()
            }

            assertFalse(contexts.value.joinToString().contains("private-key"))
        }
    }

    private fun registry(contexts: MutableStateFlow<List<EnhancedTrackerContext>>) =
        DesktopTrackerServiceRegistry.production(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            credentialStore = DesktopCredentialStore(MemoryBackend()),
            enhancedContextProvider = object : EnhancedTrackerContextProvider {
                override val contexts = contexts
            },
        )

    private fun context(id: Long, name: String, baseUrl: String, apiKey: String? = null) =
        EnhancedTrackerContext(
            trackerId = id,
            sourceId = id * 100,
            sourceClassName = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
        )

    private class MemoryBackend : CredentialBackend {
        private val values = mutableMapOf<String, CharArray>()
        override fun save(account: String, secret: CharArray) { values[account] = secret.copyOf() }
        override fun load(account: String): CharArray? = values[account]?.copyOf()
        override fun delete(account: String) { values.remove(account)?.fill('\u0000') }
    }
}
