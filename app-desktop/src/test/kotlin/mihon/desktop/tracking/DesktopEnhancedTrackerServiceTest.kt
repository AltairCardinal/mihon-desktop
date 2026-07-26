package mihon.desktop.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.ui.tracking.TrackingMessage
import mihon.desktop.ui.tracking.TrackingScreenModel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.EnhancedTrackerContext
import tachiyomi.domain.track.service.EnhancedTrackerContextProvider
import tachiyomi.domain.track.service.EnhancedTrackerManga
import tachiyomi.domain.track.service.EnhancedTrackerService
import tachiyomi.domain.track.service.EnhancedTrackerWorkflow
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderException
import tachiyomi.domain.track.service.TrackerProviderPort

class DesktopEnhancedTrackerServiceTest {
    @Test
    fun `production enhanced context lookup requires exact source class source id and provider configuration`() {
        val contexts = listOf(
            context(6, "Komga", "https://komga.example", sourceId = 600),
            context(8, "Kavita", "https://kavita.example/api", "key", sourceId = 800),
            context(9, "Suwayomi", "https://suwayomi.example", sourceId = 900),
        )

        assertEquals(6L, desktopEnhancedTrackerId(contexts[0].sourceClassName))
        assertEquals(8L, desktopEnhancedTrackerId(contexts[1].sourceClassName))
        assertEquals(9L, desktopEnhancedTrackerId(contexts[2].sourceClassName))
        assertNull(desktopEnhancedTrackerId("mihon.desktop.compat.NotAnOriginalSource"))
        contexts.forEach { context ->
            assertEquals(
                context.sourceId,
                desktopEnhancedTrackerContext(contexts, context.trackerId, context.sourceId)?.sourceId,
            )
            assertNull(desktopEnhancedTrackerContext(contexts, context.trackerId, context.sourceId + 1))
        }
        assertNull(
            desktopEnhancedTrackerContext(
                listOf(context(8, "Kavita", "https://kavita.example/api", apiKey = null, sourceId = 801)),
                8,
                801,
            ),
        )
    }

    @Test
    fun `production screen model runs accepted Komga match bind and resumes from persisted checkpoint`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val sourceId = 600L
            val contexts = MutableStateFlow(
                listOf(context(6, "Komga", server.url("/").toString(), sourceId = sourceId)),
            )
            val sourceClientLookups = mutableListOf<Long>()
            val sourceClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("X-Source-Session", sourceId.toString()).build())
                }
                .build()
            val registry = registry(contexts) { requestedSourceId ->
                sourceClientLookups += requestedSourceId
                sourceClient.takeIf { requestedSourceId == sourceId }
            }
            val mangaUrl = server.url("/api/v1/series/series-1").toString()
            val mangaRepository = FakeMangaRepository().apply {
                seed(Manga.create().copy(id = 42, source = sourceId, url = mangaUrl, title = "Must not be queried"))
            }
            val trackRepository = MemoryTrackRepository()
            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","summary":"summary","status":"ONGOING"}}"""))
            server.enqueue(MockResponse(body = """{"booksCount":10,"booksReadCount":2,"booksUnreadCount":8,"lastReadContinuousNumberSort":2.0,"maxNumberSort":10.0}"""))
            val model = TrackingScreenModel(
                mangaId = 42,
                mangaTitle = "Must not be queried",
                totalChapters = 10,
                getTracks = GetTracks(trackRepository),
                insertTrack = InsertTrack(trackRepository),
                deleteTrack = DeleteTrack(trackRepository),
                getChaptersByMangaId = GetChaptersByMangaId(FakeChapterRepository()),
                registry = registry,
                getManga = GetManga(mangaRepository),
            )

            model.load()

            assertEquals("Komga title", trackRepository.rows.single().title)
            assertEquals(mangaUrl, trackRepository.rows.single().remoteUrl)
            assertEquals(trackRepository.rows.single(), model.state.value.services.single { it.profile.id == 6L }.track)
            assertEquals(TrackingMessage.Bound, model.state.value.feedback)
            val metadata = server.takeRequest()
            val discovery = server.takeRequest()
            assertEquals("/api/v1/series/series-1", metadata.url.encodedPath)
            assertEquals(sourceId.toString(), metadata.headers["X-Source-Session"])
            assertTrue(discovery.url.encodedPath.endsWith("/api/v2/series/series-1/read-progress/tachiyomi"))
            assertTrue(sourceClientLookups.isNotEmpty())
            assertTrue(sourceClientLookups.all { it == sourceId })
            assertFalse(metadata.url.toString().contains("Must%20not%20be%20queried"))

            val requestCount = server.requestCount
            TrackingScreenModel(
                mangaId = 42,
                mangaTitle = "Changed title",
                totalChapters = 10,
                getTracks = GetTracks(trackRepository),
                insertTrack = InsertTrack(trackRepository),
                deleteTrack = DeleteTrack(trackRepository),
                getChaptersByMangaId = GetChaptersByMangaId(FakeChapterRepository()),
                registry = registry,
                getManga = GetManga(mangaRepository),
            ).load()
            assertEquals(requestCount, server.requestCount)
        }
    }

    @Test
    fun `enhanced workflow matches Kavita and Suwayomi only for accepted source contexts`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val contexts = MutableStateFlow(
                listOf(
                    context(8, "Kavita", server.url("/api").toString(), "source-api-key", sourceId = 800),
                    context(9, "Suwayomi", server.url("/").toString(), sourceId = 900),
                ),
            )
            val registry = registry(contexts)
            val workflow = EnhancedTrackerWorkflow()
            val kavita = registry.services.single { it.profile.value.id == 8L } as EnhancedTrackerService
            val kavitaManga = EnhancedTrackerManga(45, 800, server.url("/api/Series/5").toString(), "Wrong title")
            assertTrue(kavita.accept(kavitaManga))
            assertFalse(kavita.accept(kavitaManga.copy(sourceId = 801)))
            contexts.value = contexts.value + context(
                8,
                "Kavita",
                server.url("/api").toString(),
                apiKey = null,
                sourceId = 801,
            )
            assertFalse(kavita.accept(kavitaManga.copy(sourceId = 801)))
            assertEquals(null, workflow.bindIfMatched(kavita, kavitaManga.copy(sourceId = 801)))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse(body = """{"token":"jwt-secret"}"""))
            server.enqueue(MockResponse(body = """{"id":5,"name":"Kavita title","pages":100,"pagesRead":20,"thumbnail_url":"cover"}"""))
            server.enqueue(MockResponse(body = """[{"chapters":[{"number":"1"},{"number":"2"}]}]"""))
            server.enqueue(MockResponse(body = """{"number":"2"}"""))
            assertEquals("Kavita title", workflow.bindIfMatched(kavita, kavitaManga)!!.title)
            repeat(4) { server.takeRequest() }

            val suwayomi = registry.services.single { it.profile.value.id == 9L } as EnhancedTrackerService
            val suwayomiManga = EnhancedTrackerManga(46, 900, "/manga/7", "Wrong title")
            server.enqueue(
                MockResponse(
                    body = """{"data":{"manga":{"id":7,"title":"Suwayomi title","thumbnailUrl":"thumb","description":"summary","status":"ONGOING","chapters":{"totalCount":8},"latestReadChapter":{"chapterNumber":3.0},"unreadCount":5}}}""",
                ),
            )
            assertEquals("Suwayomi title", workflow.bindIfMatched(suwayomi, suwayomiManga)!!.title)
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("\"mangaId\":7"))
        }
    }

    @Test
    fun `enhanced match distinguishes no match authentication and service failures`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val contexts = MutableStateFlow(
                listOf(
                    context(6, "Komga", server.url("/").toString(), sourceId = 600),
                    context(8, "Kavita", server.url("/api").toString(), "source-api-key", sourceId = 800),
                    context(9, "Suwayomi", server.url("/").toString(), sourceId = 900),
                ),
            )
            val registry = registry(contexts)
            val workflow = EnhancedTrackerWorkflow()
            val komga = registry.services.single { it.profile.value.id == 6L } as EnhancedTrackerService
            server.enqueue(MockResponse(code = 404))
            assertEquals(
                null,
                workflow.bindIfMatched(
                    komga,
                    EnhancedTrackerManga(44, 600, server.url("/api/v1/series/missing").toString(), "Wrong title"),
                ),
            )
            server.takeRequest()

            val kavita = registry.services.single { it.profile.value.id == 8L } as EnhancedTrackerService
            server.enqueue(MockResponse(code = 401))
            val authentication = assertThrows(TrackerProviderException::class.java) {
                kotlinx.coroutines.runBlocking {
                    workflow.bindIfMatched(
                        kavita,
                        EnhancedTrackerManga(45, 800, server.url("/api/Series/5").toString(), "Wrong title"),
                    )
                }
            }
            assertEquals(TrackerProviderErrorKind.AUTHENTICATION, authentication.kind)
            server.takeRequest()

            val suwayomi = registry.services.single { it.profile.value.id == 9L } as EnhancedTrackerService
            server.enqueue(MockResponse(code = 500))
            val serviceFailure = assertThrows(TrackerProviderException::class.java) {
                kotlinx.coroutines.runBlocking {
                    workflow.bindIfMatched(suwayomi, EnhancedTrackerManga(46, 900, "/manga/7", "Wrong title"))
                }
            }
            assertEquals(TrackerProviderErrorKind.SERVER, serviceFailure.kind)
            server.takeRequest()
        }
    }

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
            val discovery = server.takeRequest()
            assertTrue(discovery.url.encodedPath.endsWith("/api/v2/series/series-1/read-progress/tachiyomi"))

            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","summary":"summary","status":"ONGOING"}}"""))
            server.enqueue(MockResponse(body = """{"booksCount":10,"booksReadCount":2,"booksUnreadCount":8,"lastReadContinuousNumberSort":2.0,"maxNumberSort":10.0}"""))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","summary":"summary","status":"ONGOING"}}"""))
            server.enqueue(MockResponse(body = """{"booksCount":10,"booksReadCount":3,"booksUnreadCount":7,"lastReadContinuousNumberSort":3.0,"maxNumberSort":10.0}"""))
            val updated = service.update(track, TrackEdit(lastChapterRead = 3.0))
            server.takeRequest()
            server.takeRequest()
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
            server.enqueue(MockResponse(body = """{"id":5,"name":"Kavita title","pages":100,"pagesRead":20,"thumbnail_url":"cover"}"""))
            server.enqueue(MockResponse(body = """[{"chapters":[{"number":"1"},{"number":"2"}]}]"""))
            server.enqueue(MockResponse(body = """{"number":"2"}"""))
            server.enqueue(MockResponse(body = """{"token":"jwt-secret"}"""))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse(body = """{"token":"jwt-secret"}"""))
            server.enqueue(MockResponse(body = """{"id":5,"name":"Kavita title","pages":100,"pagesRead":30,"thumbnail_url":"cover"}"""))
            server.enqueue(MockResponse(body = """[{"chapters":[{"number":"1"},{"number":"3"}]}]"""))
            server.enqueue(MockResponse(body = """{"number":"3"}"""))
            val updated = service.update(bound, TrackEdit(lastChapterRead = 3.0))
            repeat(4) { server.takeRequest() }
            server.takeRequest()
            val updateRequest = server.takeRequest()
            assertTrue(updateRequest.url.encodedPath.endsWith("/api/Tachiyomi/mark-chapter-until-as-read"))
            assertEquals(3.0, updated.lastChapterRead)
        }
    }

    @Test
    fun `suwayomi maps GraphQL result and surfaces GraphQL errors`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            val contexts = MutableStateFlow(
                listOf(context(9, "Suwayomi", server.url("/").toString(), deleteDownloadsOnServer = true)),
            )
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
            val originalMangaUrl = "/manga/7"
            val originalTrack = bound.copy(remoteUrl = originalMangaUrl)
            server.enqueue(
                MockResponse(
                    body = """{"data":{"manga":{"id":7,"title":"Suwayomi title","thumbnailUrl":"thumb","description":"summary","status":"ONGOING","chapters":{"totalCount":8},"latestReadChapter":{"chapterNumber":3.0},"unreadCount":5}}}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"data":{"chapters":{"nodes":[{"id":31,"chapterNumber":3.0},{"id":40,"chapterNumber":4.0}]}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"updateChapters":{"__typename":"UpdateChaptersPayload"}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"trackProgress":{"__typename":"TrackProgressPayload"}}}"""))
            server.enqueue(
                MockResponse(
                    body = """{"data":{"manga":{"id":7,"title":"Suwayomi title","thumbnailUrl":"thumb","description":"summary","status":"ONGOING","chapters":{"totalCount":8},"latestReadChapter":{"chapterNumber":3.0},"unreadCount":5}}}""",
                ),
            )
            val updated = service.update(originalTrack, TrackEdit(lastChapterRead = 3.0))
            assertEquals(3.0, updated.lastChapterRead)
            assertEquals(originalMangaUrl, updated.remoteUrl)
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("GetManga"))
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("GetMangaUnreadChapters"))
            val markRequest = server.takeRequest().body?.utf8().orEmpty()
            assertTrue(markRequest.contains("MarkChaptersRead"))
            assertTrue(markRequest.contains("deleteDownloadedChapters"))
            assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("TrackManga"))
            server.takeRequest()

            server.enqueue(
                MockResponse(
                    body = """{"data":{"manga":{"id":7,"title":"Suwayomi title","thumbnailUrl":"thumb","description":"summary","status":"ONGOING","chapters":{"totalCount":8},"latestReadChapter":{"chapterNumber":3.0},"unreadCount":5}}}""",
                ),
            )
            assertEquals(originalMangaUrl, (service as TrackerProviderPort).refresh(updated).remoteUrl)
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

    private fun registry(
        contexts: MutableStateFlow<List<EnhancedTrackerContext>>,
        sourceClient: (Long) -> OkHttpClient? = { null },
    ) =
        DesktopTrackerServiceRegistry.production(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            credentialStore = DesktopCredentialStore(MemoryBackend()),
            enhancedContextProvider = object : EnhancedTrackerContextProvider {
                override val contexts = contexts
            },
            sourceClient = sourceClient,
        )

    private fun context(
        id: Long,
        name: String,
        baseUrl: String,
        apiKey: String? = null,
        deleteDownloadsOnServer: Boolean = false,
        sourceId: Long = id * 100,
    ) =
        EnhancedTrackerContext(
            trackerId = id,
            sourceId = sourceId,
            sourceClassName = when (id) {
                6L -> "eu.kanade.tachiyomi.extension.all.komga.Komga"
                8L -> "eu.kanade.tachiyomi.extension.all.kavita.Kavita"
                9L -> "eu.kanade.tachiyomi.extension.all.tachidesk.Tachidesk"
                else -> name
            },
            baseUrl = baseUrl,
            apiKey = apiKey,
            deleteDownloadsOnServer = deleteDownloadsOnServer,
        )

    private class MemoryBackend : CredentialBackend {
        private val values = mutableMapOf<String, CharArray>()
        override fun save(account: String, secret: CharArray) { values[account] = secret.copyOf() }
        override fun load(account: String): CharArray? = values[account]?.copyOf()
        override fun delete(account: String) { values.remove(account)?.fill('\u0000') }
    }

    private class MemoryTrackRepository : TrackRepository {
        val rows = mutableListOf<Track>()
        override suspend fun getTrackById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = rows.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(rows.toList())
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            flowOf(rows.filter { it.mangaId == mangaId })
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
