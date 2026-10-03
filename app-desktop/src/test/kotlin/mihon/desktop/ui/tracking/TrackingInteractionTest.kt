package mihon.desktop.ui.tracking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import eu.kanade.domain.track.model.AutoTrackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.di.isolatedDesktopPreferenceStore
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.tracking.DesktopManualTracking
import mihon.desktop.tracking.DesktopTrackerClientConfig
import mihon.desktop.tracking.DesktopTrackerEndpoints
import mihon.desktop.tracking.DesktopTrackerServiceRegistry
import mihon.desktop.tracking.DesktopTrackerSyncScheduler
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.library.MangaDetailScreenModel
import mihon.desktop.ui.library.ProvideMangaDetailScreenModelFactory
import mihon.desktop.ui.theme.DesktopTheme
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.SyncReadingProgressWithTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.EnhancedTrackerContext
import tachiyomi.domain.track.service.EnhancedTrackerContextProvider
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@Isolated
class TrackingInteractionTest {
    @Test
    fun `actual unbound tracker query starts at manga title and searches through provider HTTP`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, bound = false) { scene, server, manga ->
            scene.click("AniList")
            scene.renderUntil { scene.nodes().any { it.config.contains(SemanticsActions.SetText) } }
            val field = scene.nodes().single { it.config.contains(SemanticsActions.SetText) }
            assertEquals(manga.title, field.config[SemanticsProperties.EditableText].text)
            server.enqueue(MockResponse(body = searchBody))
            scene.click(MR.strings.action_search.localized())
            scene.renderUntil { scene.text().contains("Replacement title") }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertTrue(request.body!!.utf8().contains(manga.title))
            assertTrue(Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).isEmpty())
        }
    }

    @Test
    fun `actual bound tracker can search replacement and cancel without unbinding`(@TempDir root: File) = runBlocking {
        withTracking(root) { scene, server, manga ->
            val original = Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single()
            scene.click("AniList")
            scene.renderUntil { scene.text().any { it.contains("Native tracking title") } }
            assertTrue(
                scene.nodes().any {
                    MR.strings.action_search.localized() in labels(it) && it.config.contains(SemanticsActions.OnClick)
                },
                "a bound track must retain the actual search replacement entrance",
            )
            scene.click(MR.strings.action_search.localized())
            scene.renderUntil { scene.nodes().any { it.config.contains(SemanticsActions.SetText) } }
            server.enqueue(MockResponse(body = searchBody))
            scene.click(MR.strings.action_search.localized())
            scene.renderUntil { "Replacement title" in scene.text() }
            server.takeRequest(2, TimeUnit.SECONDS)!!
            scene.click(MR.strings.action_close.localized())
            scene.renderUntil { scene.nodes().none { it.config.contains(SemanticsActions.SetText) } }
            assertEquals(listOf(original), Injekt.get<TrackRepository>().getTracksByMangaId(manga.id))
            scene.click("AniList")
            scene.renderUntil { scene.text().any { it.contains("Native tracking title") } }
            scene.click(MR.strings.action_search.localized())
            scene.renderUntil { scene.nodes().any { it.config.contains(SemanticsActions.SetText) } }
            server.enqueue(MockResponse(body = searchBody))
            scene.click(MR.strings.action_search.localized())
            scene.renderUntil { "Replacement title" in scene.text() }
            server.takeRequest(2, TimeUnit.SECONDS)!!
            server.enqueue(MockResponse(body = """{"data":{"Page":{"mediaList":[]}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":55}}}"""))
            scene.click(MR.strings.action_track.localized())
            scene.renderUntil {
                Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single().remoteId == 22L
            }
            val changed = Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single()
            assertEquals(manga.id, changed.mangaId)
            assertEquals(original.trackerId, changed.trackerId)
            assertEquals(22L, changed.remoteId)
            assertEquals(55L, changed.libraryId)
            assertEquals("Replacement title", changed.title)
            assertEquals(1, Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).size)
        }
    }

    @Test
    fun `actual tracker refresh consumes remote raw roles into SQL and visible progress`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root) { scene, server, manga ->
            server.enqueue(MockResponse(body = refreshedBody))
            assertTrue(
                scene.nodes().any {
                    MR.strings.action_webview_refresh.localized() in labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
                "the actual tracker page must expose remote refresh rather than registry reload",
            )
            scene.click(MR.strings.action_webview_refresh.localized())
            scene.renderUntil {
                Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single().lastChapterRead ==
                    80.0
            }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertTrue(request.body!!.utf8().contains("POINT_100"))
            val saved = Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single()
            assertEquals(90.0, saved.score)
            assertEquals(100L, saved.totalChapters)
            scene.click("AniList")
            scene.renderUntil { scene.text().any { it.contains("80.0 / 100") } }
        }
    }

    @Test
    fun `actual service stepper clamps a fractional last step to remote total without local limit`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, progress = 9.5, remoteTotal = 10) { scene, _, _ ->
            scene.click("AniList")
            scene.renderUntil { scene.text().any { it.contains("9.5") } }
            val plus = scene.nodes().first { "+" in labels(it) && it.config.contains(SemanticsActions.OnClick) }
            assertFalse(
                plus.config.contains(SemanticsProperties.Disabled),
                "service maximum 10 permits progress after 9.5 despite local total 2",
            )
            scene.click("+")
            scene.renderUntil { scene.text().any { it.contains("10.0 / 10") } }
            assertFalse(scene.text().any { it.contains("10.5") })
        }
    }

    @Test
    fun `remote refresh HTTP errors and invalid data keep SQL binding and allow visible retry`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root) { scene, server, manga ->
            val repository = Injekt.get<TrackRepository>()
            val original = repository.getTracksByMangaId(manga.id).single()
            val failures = listOf(
                MockResponse(code = 403),
                MockResponse(code = 429),
                MockResponse(code = 500),
                MockResponse(body = """{"data":{"MediaList":null}}"""),
                MockResponse(body = """{"data":{}}"""),
                MockResponse(body = "{broken"),
            )
            for (response in failures) {
                server.enqueue(response)
                scene.click(MR.strings.action_webview_refresh.localized())
                scene.renderUntil {
                    scene.nodes().any { node ->
                        node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tracking-error" &&
                            labels(node).any(String::isNotBlank)
                    }
                }
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertTrue(request.body!!.utf8().contains("RefreshManga"))
                assertEquals(listOf(original), repository.getTracksByMangaId(manga.id))
            }
            server.enqueue(MockResponse(body = refreshedBody))
            scene.click(MR.strings.action_webview_refresh.localized())
            scene.renderUntil { repository.getTracksByMangaId(manga.id).single().lastChapterRead == 80.0 }
            assertEquals(90.0, repository.getTracksByMangaId(manga.id).single().score)
            assertFalse(
                scene.nodes().any {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tracking-error"
                },
            )
        }
    }

    @Test
    fun `detail tracking entrance shows only live supported bindings and changes after logout`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, detail = true) { scene, _, manga ->
            val label = MR.strings.pref_category_tracking.localized()
            scene.renderUntil { scene.nodes().any { it.config.contains(SemanticsActions.ScrollToIndex) } }
            val info = scene.nodes().filter {
                it.config.contains(SemanticsActions.ScrollToIndex)
            }.minBy { it.boundsInRoot.left }
            assertTrue(requireNotNull(info.config[SemanticsActions.ScrollToIndex].action).invoke(1))
            scene.renderUntil { scene.nodes().any { label in labels(it) } }
            val entrance = scene.nodes().first { label in labels(it) }
            val subtree = flatten(entrance.parent ?: entrance)
            assertTrue(
                subtree.flatMap(::labels).contains("1"),
                "actual detail tracking entrance must expose its effective binding count",
            )
            Injekt.get<TrackerServiceRegistry>().get(2)!!.logout()
            scene.renderUntil {
                !flatten(scene.nodes().first { label in labels(it) }.parent!!).flatMap(::labels).contains("1")
            }
            assertEquals(1, Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).size)
        }
    }

    @Test
    fun `manual read success refreshes actual remote state and advances through existing sync workflow`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { scene, server, manga ->
            val chapters = Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0, name = "Chapter 9", url = "/9")),
            )
            scene.renderUntil {
                scene.detailModel?.state?.value?.chapters?.any { it.id == chapters.single().id } == true
            }
            server.enqueue(MockResponse(body = refreshedBody.replace("\"progress\":80", "\"progress\":1")))
            server.enqueue(MockResponse(body = refreshedBody.replace("\"progress\":80", "\"progress\":1")))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
            val result = scene.detailModel!!.markSelectedRead(chapters, true)
            assertEquals(chapters.map { it.id }, result.succeededIds)
            assertTrue(Injekt.get<ChapterRepository>().getChapterById(chapters.single().id)!!.read)
            val request = server.takeRequest(500, TimeUnit.MILLISECONDS)
            assertTrue(request != null, "a successful manual read must use the existing remote refresh and sync chain")
            scene.renderUntil {
                Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single().lastChapterRead ==
                    9.0
            }
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body!!.utf8().contains("RefreshManga"))
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body!!.utf8().contains("SaveMediaListEntry"))
        }
    }

    @Test
    fun `manual Never avoids remote IO while Ask keeps local read and cancel never writes progress`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { scene, server, manga ->
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0, name = "Chapter 9", url = "/9")),
            ).single()
            val preferences = Injekt.get<DesktopAppPreferences>()
            preferences.autoUpdateTrackOnMarkRead.set(AutoTrackState.NEVER)
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            assertTrue(Injekt.get<ChapterRepository>().getChapterById(chapter.id)!!.read)
            assertEquals(0, server.requestCount)
            preferences.autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            server.enqueue(MockResponse(body = refreshedBody.replace("\"progress\":80", "\"progress\":1")))
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            scene.renderUntil { true }
            assertTrue(
                scene.text().contains(MR.strings.confirm_tracker_update.localized(java.util.Locale.getDefault(), 9)),
                "Ask must expose the actual owner's confirmation after remote refresh",
            )
            scene.click(MR.strings.action_cancel.localized())
            scene.renderUntil { scene.detailModel!!.manualTracking!!.prompts.value.isEmpty() }
            assertEquals(1, server.requestCount)
            assertEquals(1.0, Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single().lastChapterRead)
            assertTrue(Injekt.get<ChapterRepository>().getChapterById(chapter.id)!!.read)
        }
    }

    @Test
    fun `Library actual factory manual read shares remote update rather than only local SQL`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { _, server, manga ->
            Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0, name = "Chapter 9", url = "/9")),
            )
            server.enqueue(MockResponse(body = refreshedBody.replace("\"progress\":80", "\"progress\":1")))
            server.enqueue(MockResponse(body = refreshedBody.replace("\"progress\":80", "\"progress\":1")))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
            val model = LibraryScreenModelFactory.create()
            try {
                assertTrue(model.markMangaRead(manga.id, true))
                assertTrue(
                    server.takeRequest(500, TimeUnit.MILLISECONDS) != null,
                    "the real Library factory must wire the same manual tracking consumer",
                )
                assertEquals(9.0, Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single().lastChapterRead)
            } finally {
                model.onDispose()
            }
        }
    }

    @Test
    fun `real add to Library automatically matches accepted enhanced source after membership succeeds`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, bound = false, detail = true, enhanced = true) { scene, server, manga ->
            server.enqueue(
                MockResponse(body = """{"metadata":{"title":"Komga title","summary":"summary","status":"ONGOING"}}"""),
            )
            server.enqueue(
                MockResponse(
                    body = """
{"booksCount":10,
"booksReadCount":2,
"booksUnreadCount":8,
"lastReadContinuousNumberSort":2.0,
"maxNumberSort":10.0}
                    """.trimIndent(),
                ),
            )
            scene.showDetailActions()
            scene.click(MR.strings.add_to_library.localized())
            scene.renderUntil { Injekt.get<MangaRepository>().getMangaById(manga.id)!!.favorite }
            scene.renderUntil { server.requestCount > 0 }
            assertTrue(
                server.takeRequest(500, TimeUnit.MILLISECONDS) != null,
                "actual successful Add to Library must consume EnhancedTrackerWorkflow",
            )
            scene.renderUntil { Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).isNotEmpty() }
            val saved = Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single()
            assertEquals(6L, saved.trackerId)
            assertEquals("Komga title", saved.title)
            val label = MR.strings.pref_category_tracking.localized()
            scene.renderUntil {
                scene.nodes().any {
                    label in labels(it) &&
                        flatten(it.parent ?: it).flatMap(::labels).contains("1")
                }
            }
        }
    }

    @Test
    fun `empty manual batch performs no remote IO after no local successes`(@TempDir root: File) = runBlocking {
        withTracking(root, detail = true) { scene, server, _ ->
            server.enqueue(MockResponse(body = refreshedBody))
            assertTrue(scene.detailModel!!.markSelectedRead(emptyList(), true).succeededIds.isEmpty())
            assertEquals(0, server.requestCount, "no successful local chapters must not trigger tracker refresh")
        }
    }

    @Test
    fun `manual progress feedback reports actual service cap rather than raw source number`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, detail = true, progress = 1.0, remoteTotal = 10) { scene, server, manga ->
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(mangaId = manga.id, chapterNumber = 50.0, name = "Chapter 50", url = "/50"),
                ),
            ).single()
            val response = refreshedBody.replace(
                "\"progress\":80",
                "\"progress\":1",
            ).replace("\"chapters\":100", "\"chapters\":10")
            server.enqueue(MockResponse(body = response))
            server.enqueue(MockResponse(body = response))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            assertEquals(10.0, Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single().lastChapterRead)
            assertEquals(
                MR.strings.trackers_updated_summary.localized(java.util.Locale.getDefault(), 10),
                scene.detailModel!!.manualTracking!!.feedback.value,
            )
        }
    }

    @Test
    fun `late manual refresh preserves rematched SQL identity and never prompts new binding`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { scene, server, manga ->
            val repository = Injekt.get<TrackRepository>()
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0, name = "Chapter 9", url = "/9"),
                ),
            ).single()
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            server.enqueue(
                MockResponse.Builder().headersDelay(300, TimeUnit.MILLISECONDS)
                    .body(refreshedBody.replace("\"progress\":80", "\"progress\":1")).build(),
            )
            val original = repository.getTracksByMangaId(manga.id).single()
            val operation = async(Dispatchers.Default) { scene.detailModel!!.markSelectedRead(listOf(chapter), true) }
            try {
                assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)
                repository.insert(original.copy(remoteId = 22, libraryId = 55, title = "New binding"))
                operation.await()
                assertEquals(
                    22L,
                    repository.getTracksByMangaId(manga.id).single().remoteId,
                    "the late response must not replace a newer confirmed match",
                )
                assertTrue(scene.detailModel!!.manualTracking!!.prompts.value.isEmpty())
                assertEquals(1, server.requestCount)
            } finally {
                operation.cancel()
            }
        }
    }

    @Test
    fun `Ask confirms persisted post refresh identity and ignores a later rematch`(@TempDir root: File) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { scene, server, manga ->
            val repository = Injekt.get<TrackRepository>()
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0, name = "Chapter 9", url = "/9"),
                ),
            ).single()
            val controller = scene.detailModel!!.manualTracking!!
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            val initialId = repository.getTracksByMangaId(manga.id).single().id
            val response = refreshedBody.replace("\"progress\":80", "\"progress\":1")
            server.enqueue(MockResponse(body = response))
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            val prompt = controller.prompts.value.single()
            assertTrue(prompt.tracks.single().id != initialId)
            assertEquals(repository.getTracksByMangaId(manga.id).single().id, prompt.tracks.single().id)
            server.enqueue(MockResponse(body = response))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
            scene.renderUntil { MR.strings.action_ok.localized() in scene.text() }
            scene.click(MR.strings.action_ok.localized())
            scene.renderUntil { controller.prompts.value.isEmpty() }
            assertEquals(9.0, repository.getTracksByMangaId(manga.id).single().lastChapterRead)
            server.enqueue(MockResponse(body = response))
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            val stale = controller.prompts.value.single()
            repository.insert(repository.getTracksByMangaId(manga.id).single().copy(remoteId = 22, libraryId = 55))
            assertTrue(controller.confirm(stale))
            assertEquals(4, server.requestCount)
            assertEquals(22L, repository.getTracksByMangaId(manga.id).single().remoteId)
            assertTrue(controller.prompts.value.isEmpty())
        }
    }

    @Test
    fun `Ask accepted identity cannot target a rematch before shared sync reload`(@TempDir root: File) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { _, server, manga ->
            val repository = Injekt.get<TrackRepository>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val realSync = Injekt.get<ReadingProgressTrackSync>()
            val controller = manualController(
                ReadingProgressTrackSync { request ->
                    entered.complete(Unit)
                    release.await()
                    realSync.sync(request)
                },
            )
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            val chapter = Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0)
            val response = refreshedBody.replace("\"progress\":80", "\"progress\":1")
            server.enqueue(MockResponse(body = response))
            controller.afterRead(manga.id, listOf(chapter))
            val prompt = controller.prompts.value.single()
            server.enqueue(MockResponse(body = response))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":55}}}"""))
            val operation = async(Dispatchers.Default) { controller.confirm(prompt) }
            try {
                withTimeout(3000) { entered.await() }
                repository.insert(prompt.tracks.single().copy(remoteId = 22, libraryId = 55, title = "New binding"))
                val replacement = repository.getTracksByMangaId(manga.id).single()
                release.complete(Unit)
                withTimeout(3000) { operation.await() }
                assertEquals(replacement, repository.getTracksByMangaId(manga.id).single())
                assertEquals(1, server.requestCount, "the old accepted Ask must not send progress to a new match")
                assertTrue(Injekt.get<DesktopTrackerSyncScheduler>().getItems().isEmpty())
            } finally {
                release.complete(Unit)
                operation.cancel()
            }
        }
    }

    @Test
    fun `Ask provider completion cannot replace a rematched SQL library identity`(@TempDir root: File) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { _, server, manga ->
            val repository = Injekt.get<TrackRepository>()
            val registry = Injekt.get<TrackerServiceRegistry>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val original = registry.get(2) as TrackerProviderService
            val guarded = object : TrackerProviderService by original {
                override suspend fun execute(request: TrackerProviderRequest): TrackerProviderResult {
                    val response = original.execute(request)
                    entered.complete(Unit)
                    release.await()
                    return response
                }
            }
            val controlledRegistry = object : TrackerServiceRegistry {
                override val services = registry.services.map { if (it.profile.value.id == 2L) guarded else it }
            }
            val sync =
                SyncReadingProgressWithTrack(repository, controlledRegistry, Injekt.get<DesktopTrackerSyncScheduler>())
            val controller = manualController(sync)
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            val response = refreshedBody.replace("\"progress\":80", "\"progress\":1")
            server.enqueue(MockResponse(body = response))
            controller.afterRead(manga.id, listOf(Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0)))
            val prompt = controller.prompts.value.single()
            server.enqueue(MockResponse(body = response))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":44}}}"""))
            val operation = async(Dispatchers.Default) { controller.confirm(prompt) }
            try {
                withTimeout(3000) { entered.await() }
                repository.insert(prompt.tracks.single().copy(libraryId = 55, title = "New library binding"))
                val replacement = repository.getTracksByMangaId(manga.id).single()
                release.complete(Unit)
                withTimeout(3000) { operation.await() }
                assertEquals(replacement, repository.getTracksByMangaId(manga.id).single())
                assertEquals(3, server.requestCount)
                assertTrue(Injekt.get<DesktopTrackerSyncScheduler>().getItems().isEmpty())
                assertEquals(MR.strings.desktop_tracking_update_failed.localized(), controller.feedback.value)
            } finally {
                release.complete(Unit)
                operation.cancel()
            }
        }
    }

    private fun manualController(sync: ReadingProgressTrackSync) = DesktopManualTracking(
        Injekt.get<DesktopAppPreferences>(),
        Injekt.get<TrackerServiceRegistry>(),
        Injekt.get<GetManga>(),
        Injekt.get<GetTracks>(),
        Injekt.get<InsertTrack>(),
        sync,
        Injekt.get<DesktopTrackerSyncScheduler>(),
    )

    @Test
    fun `global manual three state preference is persisted separately from Reader auto sync`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, global = true) { scene, _, _ ->
            val preferences = Injekt.get<DesktopAppPreferences>()
            preferences.autoUpdateTrack.set(false)
            val title = MR.strings.pref_auto_update_manga_on_mark_read.localized()
            assertTrue(
                scene.nodes().any { title in labels(it) && it.config.contains(SemanticsActions.OnClick) },
                "global Tracking settings must expose the manual mark-read policy",
            )
            for (choice in listOf(AutoTrackState.ASK, AutoTrackState.NEVER, AutoTrackState.ALWAYS)) {
                scene.click(title)
                scene.click(choice.titleRes.localized())
                assertEquals(choice, preferences.autoUpdateTrackOnMarkRead.get())
                assertEquals(false, preferences.autoUpdateTrack.get())
                assertTrue(preferences.autoUpdateTrackOnMarkRead.isSet())
            }
            scene.click(title)
            scene.click(MR.strings.action_cancel.localized())
            assertEquals(AutoTrackState.ALWAYS, preferences.autoUpdateTrackOnMarkRead.get())
        }
    }

    @Test
    fun `manual multiple providers continue actual SQL refresh after the first provider fails`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, enhanced = true, multiple = true, detail = true, progress = 1.0) { scene, server, manga ->
            val repository = Injekt.get<TrackRepository>()
            repository.insert(Track(0, manga.id, 6, 33, null, manga.title, 1.0, 10, 2, 0.0, manga.url, 0, 0, false))
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(mangaId = manga.id, chapterNumber = 9.0, name = "Chapter 9", url = "/9"),
                ),
            ).single()
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            server.enqueue(MockResponse(code = 403, body = "denied"))
            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","status":"ONGOING"}}"""))
            server.enqueue(
                MockResponse(
                    body = """
{"booksCount":10,
"booksReadCount":2,
"booksUnreadCount":8,
"lastReadContinuousNumberSort":2.0,
"maxNumberSort":10.0}
                    """.trimIndent(),
                ),
            )
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            assertEquals(2.0, repository.getTracksByMangaId(manga.id).first { it.trackerId == 6L }.lastChapterRead)
            assertEquals(1.0, repository.getTracksByMangaId(manga.id).first { it.trackerId == 2L }.lastChapterRead)
            assertEquals(
                listOf(2L, 6L),
                scene.detailModel!!.manualTracking!!.prompts.value.single().tracks.map {
                    it.trackerId
                },
            )
            assertEquals(3, server.requestCount)
            val controller = scene.detailModel!!.manualTracking!!
            val persistence = Injekt.get<mihon.desktop.tracking.DesktopTrackerSyncScheduler>()
            controller.cancel(controller.prompts.value.single())
            assertTrue(persistence.getItems().isEmpty(), "ASK cancel must not queue failed refresh intentions")
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ALWAYS)
            server.enqueue(MockResponse(code = 403, body = "denied"))
            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","status":"ONGOING"}}"""))
            server.enqueue(
                MockResponse(body = """{"booksCount":10,"lastReadContinuousNumberSort":2.0,"maxNumberSort":10.0}"""),
            )
            server.enqueue(MockResponse(code = 403, body = "denied"))
            server.enqueue(MockResponse(body = """{"metadata":{"title":"Komga title","status":"ONGOING"}}"""))
            server.enqueue(
                MockResponse(body = """{"booksCount":10,"lastReadContinuousNumberSort":9.0,"maxNumberSort":10.0}"""),
            )
            controller.afterRead(manga.id, listOf(chapter))
            val pending = persistence.getItems().single()
            assertEquals(2L, pending.trackerId)
            assertEquals(9.0, pending.lastChapterRead)
            assertEquals(9.0, repository.getTracksByMangaId(manga.id).first { it.trackerId == 6L }.lastChapterRead)
            assertEquals(
                MR.strings.desktop_tracking_updates_pending.localized(),
                controller.feedback.value,
                "another provider succeeding must not hide the durable failed intention",
            )
        }
    }

    @Test
    fun `Ask never prompts after fresh service cap already reached`(@TempDir root: File) = runBlocking {
        withTracking(root, detail = true, progress = 10.0, remoteTotal = 10) { scene, server, manga ->
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(mangaId = manga.id, chapterNumber = 50.0, name = "Chapter 50", url = "/50"),
                ),
            ).single()
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            server.enqueue(
                MockResponse(
                    body = refreshedBody.replace(
                        "\"progress\":80",
                        "\"progress\":10",
                    ).replace("\"chapters\":100", "\"chapters\":10"),
                ),
            )
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            assertTrue(
                scene.detailModel!!.manualTracking!!.prompts.value.isEmpty(),
                "no forward service progress means no confirmation",
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `bound tracking modal at 320 font200 has reachable scrolling controls and preserves SQL on cancel`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, progress = 9.5, remoteTotal = 10) { scene, _, manga ->
            scene.resize(320, 680)
            scene.fontScale = 2f
            Injekt.get<DesktopAppPreferences>().themeMode.set(mihon.desktop.settings.ThemeMode.LIGHT)
            scene.renderUntil { true }
            val saved = Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single()
            val refresh = scene.activeNodes().first {
                MR.strings.action_webview_refresh.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick)
            }.boundsInRoot.center
            scene.click("AniList")
            scene.pointerClick(refresh)
            scene.renderUntil { true }
            assertEquals(0, scene.serverRequestCount(), "a real pointer on the blocked background does not refresh")
            assertEquals(listOf(saved), Injekt.get<TrackRepository>().getTracksByMangaId(manga.id))
            if (scene.activeNodes().none { MR.strings.action_close.localized() in labels(it) }) scene.click("AniList")
            assertTrue(
                scene.activeNodes().any { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) },
                "bound service controls need a real bounded scroll surface at 320/font2",
            )
            val close = scene.activeNodes().first {
                MR.strings.action_close.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(
                close.boundsInRoot.width > 0 && close.boundsInRoot.bottom <= 680 && close.boundsInRoot.right <= 320,
            )
            val visited = mutableSetOf<String>()
            repeat(25) {
                scene.key(Key.Tab)
                visited += flatten(scene.focused()).flatMap(::labels)
            }
            assertTrue(MR.strings.action_close.localized() in visited, "actual focused control texts: $visited")
            repeat(25) {
                scene.key(Key.Tab, true)
                assertTrue(scene.activeNodes().any { it.id == scene.focused().id })
            }
            scene.savePng(visualFile(root, "ri11-tracking-320-font200-light.png"))
            scene.key(Key.Escape)
            assertEquals(listOf(saved), Injekt.get<TrackRepository>().getTracksByMangaId(manga.id))
        }
    }

    @Test
    fun `tracking modal Escape closes one owner and returns focus to actual service trigger`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root) { scene, _, manga ->
            val saved = Injekt.get<TrackRepository>().getTracksByMangaId(manga.id)
            scene.click("AniList")
            val close = scene.activeNodes().first {
                MR.strings.action_close.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.RequestFocus)
            }
            close.config[SemanticsActions.RequestFocus].action!!.invoke()
            scene.key(Key.Escape)
            scene.renderUntil { true }
            assertTrue(
                scene.nodes().none {
                    MR.strings.desktop_tracking_update.localized() in labels(it)
                },
                "Escape closes the actual edit dialog",
            )
            assertTrue("AniList" in labels(scene.focused()), "focus returns to the real service entrance")
            assertEquals(saved, Injekt.get<TrackRepository>().getTracksByMangaId(manga.id))
        }
    }

    @Test
    fun `detail tracking navigation bind cancel and unbind update the same owner's effective count`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, bound = false, detail = true) { scene, server, manga ->
            val owner = scene.detailModel
            val label = MR.strings.pref_category_tracking.localized()
            suspend fun count(expected: String) {
                scene.renderUntil {
                    scene.nodes().any {
                        label in labels(it) &&
                            flatten(it.parent ?: it).flatMap(::labels).contains(expected)
                    }
                }
            }
            scene.showDetailActions()
            count("0")
            scene.click(label)
            assertInstanceOf(TrackingSettingsScreen::class.java, scene.navigator.lastItem)
            assertFalse((scene.navigator.lastItem as Any) is Tab)
            scene.click("AniList")
            server.enqueue(MockResponse(body = searchBody))
            scene.click(MR.strings.action_search.localized())
            scene.renderUntil { "Replacement title" in scene.text() }
            scene.click(MR.strings.action_close.localized())
            assertTrue(Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).isEmpty())
            scene.navigator.pop()
            scene.renderUntil { scene.navigator.lastItem is MangaDetailScreen }
            assertTrue(scene.detailModel === owner)
            count("0")
            scene.click(label)
            scene.click("AniList")
            server.enqueue(MockResponse(body = searchBody))
            scene.click(MR.strings.action_search.localized())
            scene.renderUntil { "Replacement title" in scene.text() }
            server.enqueue(MockResponse(body = """{"data":{"Page":{"mediaList":[]}}}"""))
            server.enqueue(MockResponse(body = """{"data":{"SaveMediaListEntry":{"id":55}}}"""))
            scene.click(MR.strings.action_track.localized())
            scene.renderUntil { Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).isNotEmpty() }
            scene.navigator.pop()
            scene.renderUntil { scene.navigator.lastItem is MangaDetailScreen }
            count("1")
            scene.click(label)
            scene.click("AniList")
            scene.click(MR.strings.action_remove.localized())
            scene.click(MR.strings.action_remove.localized())
            scene.renderUntil { Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).isEmpty() }
            scene.navigator.pop()
            scene.renderUntil { scene.navigator.lastItem is MangaDetailScreen }
            count("0")
            assertTrue(scene.detailModel === owner)
        }
    }

    @Test
    fun `bound remote link opens through production Result port and remains retryable`(
        @TempDir root: File,
    ) = runBlocking {
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val share = mihon.desktop.platform.DesktopShareService(
            clipboardPort = object : mihon.desktop.platform.DesktopClipboardPort {
                override fun copyText(text: String) {
                    copied += text
                }
                override fun copyImage(image: java.awt.image.BufferedImage) = error("text only")
            },
            isHeadless = { false },
        )
        var reject = true
        withTracking(root, dependencies = { current ->
            current.copy(shareService = share, externalUrlOpener = { url ->
                opened += url
                if (reject) Result.failure(java.io.IOException("browser rejected")) else Result.success(Unit)
            })
        }) { scene, _, _ ->
            scene.click("AniList")
            val label = MR.strings.action_open_in_browser.localized()
            assertTrue(
                scene.activeNodes().any { label in labels(it) && it.config.contains(SemanticsActions.OnClick) },
                "a bound remote entry exposes its actual link",
            )
            scene.click(label)
            assertEquals(listOf("https://anilist.co/manga/11"), opened)
            assertTrue(scene.text().any { it.contains("browser rejected") })
            reject = false
            scene.click(label)
            assertTrue(MR.strings.desktop_link_opened.localized() in scene.text())
            scene.click(MR.strings.action_copy_link.localized())
            assertEquals(listOf("https://anilist.co/manga/11"), copied)
            assertTrue(MR.strings.copied_to_clipboard_plain.localized() in scene.text())
        }
    }

    @Test
    fun `manual Ask native modal traps both Tab directions Escape preserves read and restores detail Back`(
        @TempDir root: File,
    ) = runBlocking {
        withTracking(root, detail = true, progress = 1.0) { scene, server, manga ->
            scene.resize(320, 680)
            scene.fontScale = 2f
            Injekt.get<DesktopAppPreferences>().autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
            Injekt.get<DesktopAppPreferences>().themeMode.set(mihon.desktop.settings.ThemeMode.DARK)
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        chapterNumber = 9.0,
                        name = "Chapter 9",
                        url = "/9",
                    ),
                ),
            ).single()
            server.enqueue(MockResponse(body = refreshedBody.replace("\"progress\":80", "\"progress\":1")))
            scene.detailModel!!.markSelectedRead(listOf(chapter), true)
            scene.renderUntil { scene.detailModel!!.manualTracking!!.prompts.value.isNotEmpty() }
            repeat(8) {
                scene.key(Key.Tab)
                assertTrue(scene.activeNodes().any { it.id == scene.focused().id })
            }
            repeat(8) {
                scene.key(Key.Tab, true)
                assertTrue(scene.activeNodes().any { it.id == scene.focused().id })
            }
            val cancel = scene.activeNodes().first {
                MR.strings.action_cancel.localized() in labels(it) &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(
                cancel.boundsInRoot.width > 0 && cancel.boundsInRoot.right <= 320 && cancel.boundsInRoot.bottom <= 680,
            )
            scene.savePng(visualFile(root, "ri11-manual-ask-320-font200-dark.png"))
            scene.key(Key.Escape)
            scene.renderUntil { scene.detailModel!!.manualTracking!!.prompts.value.isEmpty() }
            assertTrue(
                MR.strings.action_bar_up_description.localized() in flatten(scene.focused()).flatMap(::labels),
                "the actual detail Back receives focus after the modal owner is removed",
            )
            assertTrue(Injekt.get<ChapterRepository>().getChapterById(chapter.id)!!.read)
            assertEquals(1.0, Injekt.get<TrackRepository>().getTracksByMangaId(manga.id).single().lastChapterRead)
            assertEquals(1, server.requestCount)
        }
    }

    private suspend fun withTracking(
        root: File,
        bound: Boolean = true,
        detail: Boolean = false,
        enhanced: Boolean = false,
        global: Boolean = false,
        multiple: Boolean = false,
        progress: Double = 40.0,
        remoteTotal: Long = 100,
        dependencies: (DesktopUiDependencies) -> DesktopUiDependencies = { it },
        block: suspend (NativeScene, MockWebServer, Manga) -> Unit,
    ) {
        MockWebServer().also { it.start() }.use { server ->
            val backend = MemoryBackend().apply {
                save(
                    "tracker.2.account.default.session.v1",
                    """{"accessToken":"isolated-test-token","username":"22"}""".toCharArray(),
                )
            }
            val production = DesktopTrackerServiceRegistry.production(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true },
                DesktopCredentialStore(backend),
                endpoints = DesktopTrackerEndpoints.all(server.url("/").toString()),
                clientConfig = DesktopTrackerClientConfig.forTesting(),
                enhancedContextProvider = if (enhanced) {
                    object : EnhancedTrackerContextProvider {
                        override val contexts = MutableStateFlow(
                            listOf(
                                EnhancedTrackerContext(
                                    6,
                                    42,
                                    "eu.kanade.tachiyomi.extension.all.komga.Komga",
                                    server.url("/").toString(),
                                ),
                            ),
                        )
                    }
                } else {
                    null
                },
            )
            val registry = object : TrackerServiceRegistry {
                override val services = production.services.filter {
                    if (multiple) {
                        it.profile.value.id in setOf(2L, 6L)
                    } else {
                        it.profile.value.id ==
                            if (enhanced) 6L else 2L
                    }
                }
            }
            val context =
                initDesktopDIForTest(root, isolatedDesktopPreferenceStore(), trackerServiceRegistry = registry)
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val scene = NativeScene(kotlin.coroutines.coroutineContext)
            scene.serverRequestCount = { server.requestCount }
            try {
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 42,
                            url = if (enhanced) server.url("/api/v1/series/series-1").toString() else "/tracking",
                            title = "Native tracking title",
                            initialized = true,
                        ),
                    ),
                ).single()
                if (bound) {
                    Injekt.get<TrackRepository>().insert(
                        Track(
                            0,
                            manga.id,
                            2,
                            11,
                            44,
                            manga.title,
                            progress,
                            remoteTotal,
                            1,
                            80.0,
                            "https://anilist.co/manga/11",
                            0,
                            0,
                            false,
                        ),
                    )
                }
                val destination: Screen = when {
                    global -> TrackingSettingsScreen()
                    detail -> MangaDetailScreen(manga.id)
                    else -> TrackingSettingsScreen(manga.id, manga.title, 2)
                }
                assertInstanceOf(Screen::class.java, destination)
                assertFalse((destination as Any) is Tab)
                scene.setContent {
                    CompositionLocalProvider(
                        LocalDesktopUiDependencies provides dependencies(DesktopUiDependencies.fromInjekt()),
                    ) {
                        DesktopTheme {
                            ProvideMangaDetailScreenModelFactory({ id ->
                                MangaDetailScreenModelFactory.create(id).also {
                                    scene.detailModel =
                                        it
                                }
                            }) {
                                CompositionLocalProvider(LocalDensity provides Density(1f, scene.fontScale)) {
                                    Navigator(destination) {
                                        scene.navigator = it
                                        CurrentScreen()
                                    }
                                }
                            }
                        }
                    }
                }
                scene.renderUntil {
                    if (detail) {
                        scene.detailModel?.state?.value?.manga != null
                    } else {
                        "AniList" in
                            scene.text()
                    }
                }
                block(scene, server, manga)
            } finally {
                scene.close()
                context.closeAndJoin()
                Dispatchers.resetMain()
            }
        }
    }

    private class NativeScene(context: CoroutineContext) : AutoCloseable {
        var detailModel: MangaDetailScreenModel? = null
        lateinit var navigator: Navigator
        var serverRequestCount: () -> Int = { 0 }
        var fontScale by mutableFloatStateOf(1f)
        private var windowSize by mutableStateOf(IntSize(900, 760))
        private val owners = linkedSetOf<SemanticsOwner>()
        private val bitmap = ImageBitmap(1200, 900)
        private val canvas = Canvas(bitmap)
        private val scene = CanvasLayersComposeScene(
            size = windowSize,
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize get() = windowSize
                    override val containerDpSize get() = DpSize(windowSize.width.dp, windowSize.height.dp)
                }
                override val inputModeManager = object : InputModeManager {
                    override val inputMode = InputMode.Keyboard
                    override fun requestInputMode(inputMode: InputMode) = true
                }
                override fun requestFocus() = true
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(owner: SemanticsOwner) {
                        owners += owner
                    }
                    override fun onSemanticsOwnerRemoved(owner: SemanticsOwner) {
                        owners -= owner
                    }
                    override fun onSemanticsChange(owner: SemanticsOwner) = Unit
                    override fun onLayoutChange(owner: SemanticsOwner, nodeId: Int) = Unit
                }
            },
            invalidate = {},
        )
        fun resize(width: Int, height: Int) {
            windowSize = IntSize(width, height)
            scene.size = windowSize
        }
        fun pointerClick(position: androidx.compose.ui.geometry.Offset) {
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Press,
                position,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true),
                button = androidx.compose.ui.input.pointer.PointerButton.Primary,
            )
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Release,
                position,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(),
                button = androidx.compose.ui.input.pointer.PointerButton.Primary,
            )
        }
        fun savePng(file: File) {
            file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val pixels = javax.imageio.ImageIO.read(
                    java.io.ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes),
                )
                javax.imageio.ImageIO.write(pixels.getSubimage(0, 0, windowSize.width, windowSize.height), "png", file)
            }
        }
        suspend fun key(key: Key, shift: Boolean = false) {
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") &&
                    !it.name.endsWith("\$default")
            }
            for (name in listOf("access\$getKeyDown\$cp", "access\$getKeyUp\$cp")) {
                scene.sendKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(
                        factory.invoke(
                            null,
                            key.keyCode,
                            type.getMethod(
                                name,
                            ).invoke(null),
                            key.nativeKeyLocation, false, false, false, shift, null,
                        ),
                    ),
                )
            }
            renderUntil { true }
        }
        fun activeNodes() = owners.lastOrNull()?.let { flatten(it.rootSemanticsNode) }.orEmpty()
        fun focused() = activeNodes().single { it.config.getOrElse(SemanticsProperties.Focused) { false } }
        fun setContent(content: @Composable () -> Unit) = scene.setContent(content)
        suspend fun renderUntil(predicate: suspend () -> Boolean) = withTimeout(5_000) {
            do {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                scene.render(canvas, System.nanoTime())
                delay(10)
            } while (!predicate())
        }
        suspend fun showDetailActions() {
            renderUntil { nodes().any { it.config.contains(SemanticsActions.ScrollToIndex) } }
            val info = nodes().filter {
                it.config.contains(SemanticsActions.ScrollToIndex)
            }.minBy { it.boundsInRoot.left }
            assertTrue(requireNotNull(info.config[SemanticsActions.ScrollToIndex].action).invoke(1))
            renderUntil { nodes().any { MR.strings.pref_category_tracking.localized() in labels(it) } }
        }
        fun nodes(): List<SemanticsNode> = owners.flatMap { flatten(it.rootSemanticsNode) }
        fun text(): List<String> = nodes().flatMap(::labels)
        suspend fun click(label: String) {
            renderUntil { activeNodes().any { label in labels(it) && it.config.contains(SemanticsActions.OnClick) } }
            val button = activeNodes().first { label in labels(it) && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
            renderUntil { true }
        }
        override fun close() = scene.close()
    }

    private class MemoryBackend : CredentialBackend {
        private val values = mutableMapOf<String, CharArray>()
        override fun save(account: String, secret: CharArray) {
            values[account] = secret.copyOf()
        }
        override fun load(account: String): CharArray? = values[account]?.copyOf()
        override fun delete(account: String) {
            values.remove(account)
        }
    }

    companion object {
        private fun visualFile(root: File, name: String) = File(
            System.getenv("MIHON_RI11_VISUAL_DIR")?.let(::File) ?: File(root, "visuals"),
            name,
        )
        private val searchBody = """
            {"data":{"Page":{"media":[{"id":22,
            "title":{"userPreferred":"Replacement title"},"chapters":100}]}}}
        """.trimIndent()
        private val refreshedBody = """
{"data":{"MediaList":{"id":44,
"status":"CURRENT",
"score":90,
"progress":80,
"private":false,
"media":{"chapters":100}}}}
        """.trimIndent()
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        private fun labels(node: SemanticsNode): List<String> = buildList {
            if (node.config.contains(
                    SemanticsProperties.Text,
                )
            ) {
                addAll(node.config[SemanticsProperties.Text].map(AnnotatedString::text))
            }
            if (node.config.contains(
                    SemanticsProperties.ContentDescription,
                )
            ) {
                addAll(node.config[SemanticsProperties.ContentDescription])
            }
        }
    }
}
