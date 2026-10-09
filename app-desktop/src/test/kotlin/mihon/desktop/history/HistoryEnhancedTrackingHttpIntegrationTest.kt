package mihon.desktop.history

import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.tracking.DesktopTrackerServiceRegistry
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.service.EnhancedTrackerContext
import tachiyomi.domain.track.service.EnhancedTrackerContextProvider
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Isolated
class HistoryEnhancedTrackingHttpIntegrationTest {
    @Test
    fun `preexisting track still coordinates remote progress when history adds favorite`(@TempDir directory: File) = bind(directory, false, preexisting = true)

    @Test
    fun `unconfigured or unrelated production source skips binding`(@TempDir directory: File) {
        listOf("logged_out", "unaccepted").forEach { bind(directory.resolve(it), false, skipMode = it) }
    }

    @Test
    fun `production no match leaves favorite and no track`(@TempDir directory: File) = bind(directory, false, skipMode = "no_match")

    @Test
    fun `category chooser starts production binding and cancellation does not undo it`(@TempDir directory: File) = bind(directory, false, true, chooser = true)

    @Test
    fun `history keeps favorite when production tracking HTTP responses fail`(@TempDir directory: File) {
        listOf("403", "429", "500", "missing", "empty", "malformed").forEach { mode -> bind(directory.resolve(mode), false, failureMode = mode) }
    }

    @Test
    fun `failure of one enhanced service still runs the next production provider`(@TempDir directory: File) = bind(directory, false, failFirst = true)

    @Test
    fun `history binding projects remote read chapters and keeps provider status`(@TempDir directory: File) = bind(directory, false)

    @Test
    fun `history binding uploads greater continuous local progress`(@TempDir directory: File) = bind(directory, true)

    @Test
    fun `late tracker response preserves newer page bookmark and source metadata`(@TempDir directory: File) = bind(directory, false, true)

    private fun bind(directory: File, localAhead: Boolean, pause: Boolean = false, failureMode: String? = null, failFirst: Boolean = false, skipMode: String? = null, chooser: Boolean = false, preexisting: Boolean = false) = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val sourceId = 600L
            val contexts = MutableStateFlow(
                listOf(
                    EnhancedTrackerContext(
                        6,
                        sourceId,
                        "eu.kanade.tachiyomi.extension.all.komga.Komga",
                        server.url("/").toString(),
                    ),
                ),
            )
            if (skipMode == "logged_out") contexts.value = emptyList()
            val registry = DesktopTrackerServiceRegistry.production(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true },
                DesktopCredentialStore(object : CredentialBackend {
                    override fun save(account: String, secret: CharArray) = Unit
                    override fun load(account: String): CharArray? = null
                    override fun delete(account: String) = Unit
                }),
                enhancedContextProvider = object : EnhancedTrackerContextProvider {
                    override val contexts = contexts
                },
            )
            val selectedRegistry = if (failFirst) {
                val failing = io.mockk.mockk<tachiyomi.domain.track.service.EnhancedTrackerService>(relaxed = true)
                io.mockk.every { failing.profile } returns MutableStateFlow(tachiyomi.domain.track.service.TrackerProfile(99, "Unavailable fixture", tachiyomi.domain.track.service.TrackerAuthentication.API_KEY, true))
                io.mockk.every { failing.accept(any()) } returns true
                io.mockk.coEvery { failing.match(any()) } throws java.io.IOException("First provider failed")
                DesktopTrackerServiceRegistry(listOf(failing) + registry.services)
            } else {
                registry
            }
            val context = initDesktopDIForTest(directory, inMemoryDesktopPreferenceStore(), trackerServiceRegistry = selectedRegistry)
            val model = HistoryScreenModelFactory.create()
            try {
                val manga = Injekt.get<SaveSourceMangaForDetails>().await(
                    SManga.create().apply {
                        url = server.url("/api/v1/series/series-1").toString()
                        title = "History binding"
                    },
                    if (skipMode == "unaccepted") sourceId + 1 else sourceId,
                    (4 downTo 1).map {
                        SChapter.create().apply {
                            url = "/$it"
                            name = "Chapter $it"
                        }
                    },
                )
                val chapters = Injekt.get<ChapterRepository>()
                if (preexisting) {
                    Injekt.get<tachiyomi.domain.track.interactor.InsertTrack>().awaitOrThrow(
                        tachiyomi.domain.track.model.Track(0, manga.id, 6, 123, null, "Old binding", 0.0, 4, 1, 0.0, manga.url, 0, 0, false),
                    )
                }
                if (skipMode != null) {
                    if (skipMode == "no_match") server.enqueue(MockResponse(code = 404, body = "{}"))
                    model.controller.addFavorite(manga.id)
                    assertEquals(if (skipMode == "no_match") 1 else 0, server.requestCount)
                    assertTrue(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                    assertEquals(emptyList<tachiyomi.domain.track.model.Track>(), Injekt.get<GetTracks>().awaitOrThrow(manga.id))
                    assertTrue(chapters.getChapterByMangaId(manga.id).none { it.read })
                    return@use
                }
                if (chooser) Injekt.get<tachiyomi.domain.category.repository.CategoryRepository>().insert(tachiyomi.domain.category.model.Category(0, "Chooser", 0, 0))
                if (failureMode != null) {
                    val before = chapters.getChapterByMangaId(manga.id)
                    server.enqueue(
                        if (failureMode.toIntOrNull() != null) {
                            MockResponse(code = failureMode.toInt(), body = "{}")
                        } else {
                            MockResponse(
                                body = if (failureMode == "missing") {
                                    "{}"
                                } else if (failureMode == "empty") {
                                    ""
                                } else {
                                    "{"
                                },
                            )
                        },
                    )
                    if (failureMode == "missing") server.enqueue(MockResponse(body = """{"booksCount":4,"booksReadCount":0,"booksUnreadCount":4,"lastReadContinuousNumberSort":0,"maxNumberSort":4}"""))
                    model.controller.addFavorite(manga.id)
                    assertEquals(if (failureMode == "missing") 2 else 1, server.requestCount)
                    assertTrue(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                    assertEquals(emptyList<tachiyomi.domain.track.model.Track>(), Injekt.get<GetTracks>().awaitOrThrow(manga.id))
                    assertEquals(before, chapters.getChapterByMangaId(manga.id))
                    assertEquals(tachiyomi.domain.history.service.HistoryEvent.InternalError, withTimeout(5_000) { model.controller.events.first() })
                    return@use
                }
                if (localAhead) {
                    chapters.getChapterByMangaId(manga.id).filter { it.chapterNumber <= 3 }.forEach {
                        chapters.update(ChapterUpdate(it.id, read = true))
                    }
                }
                fun metadata() = MockResponse(body = """{"metadata":{"title":"Komga title","status":"ONGOING"}}""")
                fun progress(number: Int) = MockResponse(body = """{"booksCount":4,"booksReadCount":$number,"booksUnreadCount":${4 - number},"lastReadContinuousNumberSort":$number,"maxNumberSort":4}""")
                if (pause) {
                    val entered = CompletableDeferred<Unit>()
                    val release = java.util.concurrent.CountDownLatch(1)
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            if (request.method == "PUT") {
                                entered.complete(Unit)
                                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                return MockResponse()
                            }
                            return if (request.url.encodedPath.endsWith("/series-1")) metadata() else progress(2)
                        }
                    }
                    val binding = launch(Dispatchers.Default) { model.controller.addFavorite(manga.id) }
                    try {
                        withTimeout(5_000) { entered.await() }
                        if (chooser) {
                            assertTrue(model.controller.state.value.dialog is tachiyomi.domain.history.service.HistoryDialog.ChangeCategory)
                            model.controller.setDialog(null)
                            assertTrue(!requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                        } else {
                            val current = chapters.getChapterByMangaId(manga.id).single { it.chapterNumber == 1.0 }
                            chapters.update(ChapterUpdate(current.id, bookmark = true, lastPageRead = 17, dateFetch = 9000, name = "New source title", sourceOrder = 42, scanlator = "New scanlator"))
                        }
                    } finally {
                        release.countDown()
                    }
                    binding.join()
                    if (!chooser) {
                        val current = chapters.getChapterByMangaId(manga.id).single { it.chapterNumber == 1.0 }
                        assertEquals(17L, current.lastPageRead)
                        assertTrue(current.bookmark)
                        assertEquals(9000L, current.dateFetch)
                        assertEquals("New source title", current.name)
                        assertEquals(42L, current.sourceOrder)
                        assertEquals("New scanlator", current.scanlator)
                    }
                } else {
                    server.enqueue(metadata())
                    server.enqueue(progress(2))
                    // The existing enhanced provider port writes then reads back its remote result.
                    server.enqueue(MockResponse())
                    server.enqueue(metadata())
                    server.enqueue(progress(if (localAhead) 3 else 2))
                    model.controller.addFavorite(manga.id)
                }
                val persisted = chapters.getChapterByMangaId(manga.id).associateBy { it.chapterNumber.toInt() }
                assertTrue(requireNotNull(persisted[1]).read)
                assertTrue(requireNotNull(persisted[2]).read)
                assertEquals(localAhead, requireNotNull(persisted[3]).read)
                assertEquals(false, requireNotNull(persisted[4]).read)
                val track = Injekt.get<GetTracks>().awaitOrThrow(manga.id).single()
                assertEquals(if (localAhead) 3.0 else 2.0, track.lastChapterRead)
                assertEquals(2L, track.status)
                assertEquals(!chooser, requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                assertEquals(5, server.requestCount)
                val requests = (1..5).map { requireNotNull(server.takeRequest()) }
                val update = requests.single { it.method == "PUT" }
                assertTrue(update.body?.utf8().orEmpty().contains("\"lastBookNumberSortRead\":${if (localAhead) "3.0" else "2.0"}"))
                if (failFirst) assertEquals(tachiyomi.domain.history.service.HistoryEvent.InternalError, withTimeout(5_000) { model.controller.events.first() })
            } finally {
                model.onDispose()
                context.closeAndJoin()
            }
        }
    }
}
