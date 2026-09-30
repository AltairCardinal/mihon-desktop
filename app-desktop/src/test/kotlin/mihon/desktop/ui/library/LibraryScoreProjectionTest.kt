package mihon.desktop.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.DesktopTestDIContext
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.DesktopTrackerSessionProvider
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.tracking.DesktopAuthenticatingTrackerService
import mihon.desktop.tracking.DesktopTrackerEndpoints
import mihon.desktop.tracking.DesktopTrackerOAuthProvider
import mihon.desktop.tracking.DesktopTrackerServiceRegistry
import mihon.desktop.ui.theme.DesktopTheme
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerProviderPort
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class LibraryScoreProjectionTest {
    @Test
    fun `actual provider responses persist raw scores and factory averages normalized values including zero`(
        @TempDir root: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/score-${UUID.randomUUID()}")
        var context: DesktopTestDIContext? = null
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val client = OkHttpClient()
        try {
            MockWebServer().also { it.start() }.use { server ->
                val backend = object : CredentialBackend {
                    private val values = mutableMapOf<String, CharArray>()
                    override fun save(account: String, secret: CharArray) {
                        values[account] = secret.copyOf()
                    }
                    override fun load(account: String) = values[account]?.copyOf()
                    override fun delete(account: String) {
                        values.remove(account)
                    }
                }
                backend.save(
                    "tracker.3.account.default.session.v1",
                    """
{
  "accessToken": "test-token"
}
""".toCharArray(),
                )
                val registry = DesktopTrackerServiceRegistry.production(
                    client,
                    Json { ignoreUnknownKeys = true },
                    DesktopCredentialStore(backend),
                    endpoints = DesktopTrackerEndpoints.all(server.url("/").toString()),
                )
                context = initDesktopDIForTest(
                    root,
                    DesktopPreferenceStore(node),
                    startDownloadWorker = false,
                    trackerServiceRegistry = registry,
                )
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(Manga.create().copy(source = 0, url = "/rated", title = "Rated")),
                ).single()
                Injekt.get<MangaRepository>().updateMembershipsAtomically(
                    listOf(LibraryMembershipUpdate(manga.id, true, 1, emptyList())),
                )
                val tracks = Injekt.get<TrackRepository>()
                val ani = registry.services.single { it.profile.value.id == 2L }
                val kitsu = registry.services.single { it.profile.value.id == 3L }
                val model = LibraryScreenModelFactory.create()
                val job = launch(start = CoroutineStart.UNDISPATCHED) { model.libraryMangaFlow(true).collect {} }
                try {
                    for (format in listOf("POINT_100", "POINT_10", "POINT_5", "POINT_3", "POINT_10_DECIMAL")) {
                        server.enqueue(
                            MockResponse(
                                body = """
{
  "data": {
    "Viewer": {
      "id": 22,
      "mediaListOptions": {
        "scoreFormat": "$format"
      }
    }
  }
}
""",
                            ),
                        )
                        (ani as DesktopAuthenticatingTrackerService).finishOAuth(
                            "test-token",
                            DesktopTrackerOAuthProvider.ANI_LIST.redirectUri,
                        )
                        assertTrue(server.takeRequest().body!!.utf8().contains("Viewer"))
                        server.enqueue(
                            MockResponse(
                                body = """
{
  "data": {
    "MediaList": {
      "id": 44,
      "status": "CURRENT",
      "score": 80,
      "progress": 1,
      "media": {
        "chapters": 12
      }
    }
  }
}
""",
                            ),
                        )
                        val aniTrack = (ani as TrackerProviderPort).refresh(track(manga.id, 2))
                        assertTrue(server.takeRequest().body!!.utf8().contains("score(format: POINT_100)"))
                        server.enqueue(
                            MockResponse(
                                body = """
{
  "data": [
    {
      "id": "91",
      "attributes": {
        "status": "current",
        "ratingTwenty": 16,
        "progress": 1
      }
    }
  ],
  "included": [
    {
      "id": "13",
      "attributes": {
        "chapterCount": 12
      }
    }
  ]
}
""",
                            ),
                        )
                        val kitsuTrack = (kitsu as TrackerProviderPort).refresh(track(manga.id, 3))
                        server.takeRequest()
                        tracks.insertAll(listOf(aniTrack, kitsuTrack))
                        withTimeout(5_000) {
                            while (model.state.value.trackerIdsByManga[manga.id]?.size != 2) yield()
                        }
                        assertEquals(
                            8.0,
                            model.state.value.trackerMeansByManga[manga.id],
                            "account format $format does not change stored POINT_100",
                        )
                        assertEquals(80.0, tracks.getTracksByMangaId(manga.id).single { it.trackerId == 2L }.score)
                        assertEquals(8.0, tracks.getTracksByMangaId(manga.id).single { it.trackerId == 3L }.score)
                    }
                    val extra = Injekt.get<MangaRepository>().insertNetworkManga(
                        listOf(
                            Manga.create().copy(source = 0, url = "/zero", title = "Zero work"),
                            Manga.create().copy(source = 0, url = "/unrated", title = "Unrated work"),
                        ),
                    )
                    Injekt.get<MangaRepository>().updateMembershipsAtomically(
                        extra.map {
                            LibraryMembershipUpdate(it.id, true, 1, emptyList())
                        },
                    )
                    server.enqueue(
                        MockResponse(
                            body = """
{
  "data": [
    {
      "id": "92",
      "attributes": {
        "status": "current",
        "ratingTwenty": null,
        "progress": 0
      }
    }
  ],
  "included": [
    {
      "id": "13",
      "attributes": {
        "chapterCount": 12
      }
    }
  ]
}
""",
                        ),
                    )
                    val zeroTrack = (kitsu as TrackerProviderPort).refresh(track(extra.first().id, 3))
                    server.takeRequest()
                    assertEquals(0.0, zeroTrack.score)
                    tracks.insert(zeroTrack)
                    val scene = ImageComposeScene(1200, 900, coroutineContext = Dispatchers.Unconfined) {}
                    val dependencies = DesktopUiDependencies.fromInjekt()
                    lateinit var rootModel: LibraryScreenModel
                    try {
                        scene.setContent {
                            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                                ProvideLibraryScreenModelFactory({
                                    LibraryScreenModelFactory.create().also {
                                        rootModel =
                                            it
                                    }
                                }) {
                                    DesktopTheme { Navigator(LibraryRootScreen()) { CurrentScreen() } }
                                }
                            }
                        }
                        render(scene)
                        click(scene, MR.strings.action_filter.localized())
                        render(scene)
                        assertTrue("AniList" in labels(scene), "multi-service panel must use real registry names")
                        assertTrue("Kitsu" in labels(scene))
                        click(scene, MR.strings.action_sort.localized())
                        render(scene)
                        click(scene, MR.strings.action_sort_tracker_score.localized())
                        render(scene)
                        click(scene, MR.strings.action_close.localized())
                        render(scene)
                        assertEquals(8.0, rootModel.state.value.trackerMeansByManga[manga.id])
                        for (mode in tachiyomi.domain.library.model.LibraryDisplayMode.values) {
                            Injekt.get<LibraryPreferences>().displayMode().set(mode)
                            render(scene)
                            assertTrue("8.0 / 10" in labels(scene), "$mode must draw normalized actual rating")
                            assertTrue("0.0 / 10" in labels(scene), "$mode must distinguish stored zero")
                            assertTrue(
                                MR.strings.desktop_library_unrated.localized() in labels(scene),
                                "$mode must show absent rating explicitly",
                            )
                        }
                        click(scene, MR.strings.action_filter.localized())
                        render(scene)
                        click(scene, "AniList")
                        render(scene)
                        click(scene, MR.strings.action_close.localized())
                        render(scene)
                        assertTrue(
                            labels(scene).any {
                                it.contains("AniList") &&
                                    it.contains(MR.strings.desktop_ui_filter_include.localized())
                            },
                            "active tracking must appear in the real toolbar",
                        )
                        assertEquals(listOf(manga.id), rootModel.visibleItems().map { it.id })
                        tracks.insert(track(manga.id, 3).copy(score = 0.0))
                        render(scene)
                        assertEquals(
                            4.0,
                            rootModel.state.value.trackerMeansByManga[manga.id],
                            "SOURCE averaging keeps zero",
                        )
                        ani.logout()
                        render(scene)
                        click(scene, MR.strings.action_filter.localized())
                        render(scene)
                        assertTrue(
                            MR.strings.action_filter_tracked.localized() in labels(scene),
                            "one remaining service uses the SOURCE Tracked caption",
                        )
                        assertTrue("AniList" !in labels(scene), "logged out provider cannot retain an active chip")
                        click(scene, MR.strings.action_close.localized())
                        render(scene)
                    } finally {
                        scene.close()
                    }
                    server.enqueue(
                        MockResponse(
                            body = """
{
  "data": [
    {
      "id": "91",
      "attributes": {
        "status": "current",
        "ratingTwenty": null,
        "progress": 0
      }
    }
  ],
  "included": [
    {
      "id": "13",
      "attributes": {
        "chapterCount": 12
      }
    }
  ]
}
""",
                        ),
                    )
                    tracks.insert((kitsu as TrackerProviderPort).refresh(track(manga.id, 3)))
                    server.takeRequest()
                    withTimeout(5_000) { while (model.state.value.trackerMeansByManga[manga.id] == 8.0) yield() }
                    assertEquals(0.0, model.state.value.trackerMeansByManga[manga.id])
                    ani.logout()
                    withTimeout(5_000) { while (2L in model.state.value.availableTrackerIds) yield() }
                    assertEquals(0.0, model.state.value.trackerMeansByManga[manga.id])
                    kitsu.logout()
                    withTimeout(5_000) { while (model.state.value.trackerMeansByManga.isNotEmpty()) yield() }
                    assertTrue(model.state.value.trackerMeansByManga.isEmpty())
                } finally {
                    job.cancelAndJoin()
                    model.onDispose()
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            context?.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private suspend fun render(scene: ImageComposeScene) {
        repeat(12) {
            scene.render(System.nanoTime())
            delay(15)
        }
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun text(node: SemanticsNode): List<String> =
        (
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].map {
                    it.text
                }
            } else {
                emptyList()
            }
            ) +
            (
                if (node.config.contains(
                        SemanticsProperties.ContentDescription,
                    )
                ) {
                    node.config[SemanticsProperties.ContentDescription]
                } else {
                    emptyList()
                }
                )
    private fun labels(scene: ImageComposeScene) = nodes(scene).flatMap(::text)
    private fun click(scene: ImageComposeScene, label: String) = requireNotNull(
        nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && label in text(it)
        }.config[SemanticsActions.OnClick].action,
    ).invoke()

    private fun track(mangaId: Long, trackerId: Long) = Track(
        id = -1, mangaId = mangaId, trackerId = trackerId, remoteId = 13, libraryId = 44,
        title = "Rated", lastChapterRead = 0.0, totalChapters = 12, status = 1, score = 0.0,
        remoteUrl = "https://example.invalid/manga/13", startDate = 0, finishDate = 0, private = false,
    )
}
