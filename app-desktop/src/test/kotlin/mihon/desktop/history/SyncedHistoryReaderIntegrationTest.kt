package mihon.desktop.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.network.NetworkHelper
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.test.http.HistoryCatalogTestFixture
import mihon.desktop.test.http.HistoryCatalogTestSource
import mihon.desktop.test.http.HistoryTestModeBridge
import mihon.desktop.test.http.HistoryTestModeController
import mihon.desktop.test.http.ProductionReaderTestModeBridge
import mihon.desktop.test.http.testHttpServer
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.history.HistoryRootScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SyncedHistoryReaderIntegrationTest {
    @Test
    fun `projection during source wait selects coherent latest resume and metadata never revives cleared history`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-history-inflight-sync-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        val base = "http://127.0.0.1:${server.resolvedConnectors().single().port}"
        val fixture = HistoryCatalogTestFixture(folder, Injekt.get<DatabaseHandler>(), Injekt.get<SyncRuntime>(), Injekt.get<NetworkHelper>().client, base, verifyProfile = { check(folder.isDirectory) })
        val controller = HistoryTestModeController(HistoryScreenModelFactory.create(), fixture)
        HistoryTestModeBridge.install(controller)
        try {
            fixture.execute("seed")
            val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val source = object : eu.kanade.tachiyomi.source.Source by fixture.source {
                override suspend fun getMangaUpdate(manga: eu.kanade.tachiyomi.source.model.SManga, chapters: List<eu.kanade.tachiyomi.source.model.SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    reached.complete(Unit)
                    release.await()
                    return fixture.source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
                }
            }
            val owner = Injekt.get<SaveSourceMangaForDetails>()
            val chapters = Injekt.get<ChapterRepository>()
            val model = HistoryScreenModel(Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get(), prepareDirectory = { owner.awaitPrepared(source, it) })
            model.loadHistory()
            val original = model.state.value.items.single()
            val pending = kotlinx.coroutines.coroutineScope {
                val request = async { model.readerRequestFor(original) }
                reached.await()
                fixture.execute("advance")
                chapters.update(tachiyomi.domain.chapter.model.ChapterUpdate(original.chapterId, bookmark = true))
                val categories = Injekt.get<tachiyomi.domain.category.repository.CategoryRepository>()
                categories.insert(tachiyomi.domain.category.model.Category(1, "Acceptance category", 0, 0))
                Injekt.get<MangaRepository>().updateMembershipsAtomically(
                    listOf(
                        tachiyomi.domain.manga.repository.LibraryMembershipUpdate(
                            original.mangaId,
                            true,
                            999,
                            listOf(categories.getAll().single { it.name == "Acceptance category" }.id),
                            viewerFlags = 42,
                            notes = "Retained notes",
                        ),
                    ),
                )
                val outgoing = fixture.snapshot().getValue("outgoingUserEvents")
                release.complete(Unit)
                val entry = requireNotNull(request.await())
                assertEquals(outgoing, fixture.snapshot().getValue("outgoingUserEvents"))
                entry
            }
            val current = requireNotNull(Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>().resumePosition(original.mangaId))
            assertEquals(current.chapterId, pending.chapterId)
            assertEquals(2, pending.initialPage)
            assertEquals(current.snapshot, pending.resumeSnapshot)
            assertEquals(42, pending.mangaViewerFlags)
            assertEquals(3, pending.chapters.size)
            assertTrue(requireNotNull(chapters.getChapterById(original.chapterId)).bookmark)
            assertEquals(listOf("Acceptance category"), Injekt.get<tachiyomi.domain.category.repository.CategoryRepository>().getCategoriesByMangaId(original.mangaId).map { it.name })
            assertEquals("Retained notes", Injekt.get<MangaRepository>().getMangaById(original.mangaId).notes)
            assertTrue(Injekt.get<MangaRepository>().getMangaById(original.mangaId).favorite)
            model.loadHistory()
            assertEquals(original.readDuration, Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().await(original.mangaId).single { it.id == original.id }.readDuration)
            model.clearAllHistory()
            val manga = Injekt.get<MangaRepository>().getMangaById(original.mangaId)
            assertTrue(owner.awaitPrepared(fixture.source, manga) is mihon.desktop.extension.SourceCallResult.Success)
            model.loadHistory()
            assertTrue(model.state.value.items.isEmpty())
            assertEquals(1, fixture.chapterCalls.get())
        } finally {
            controller.close()
            HistoryTestModeBridge.clear(controller)
            server.stop(0, 0)
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `two file databases project sparse history then actual reader loads adjacent pages and holds session baseline`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-history-sync-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        val base = "http://127.0.0.1:${server.resolvedConnectors().single().port}"
        val fixture = HistoryCatalogTestFixture(folder, Injekt.get<DatabaseHandler>(), Injekt.get<SyncRuntime>(), Injekt.get<NetworkHelper>().client, base, verifyProfile = { check(folder.isDirectory) })
        val controller = HistoryTestModeController(HistoryScreenModelFactory.create(), fixture)
        HistoryTestModeBridge.install(controller)
        val scene = ImageComposeScene(800, 640, coroutineContext = coroutineContext) {}
        var destination: cafe.adriel.voyager.core.screen.Screen? = null
        try {
            Injekt.get<ReaderPreferences>().apply {
                readingMode = ReadingMode.LTR
                isDualPage = false
                skipReadChapters = false
                skipFilteredChapters = false
                skipDuplicateChapters = false
            }
            fixture.execute("seed")
            assertTrue(folder.resolve("history-catalog-fixture/sender.db").isFile)
            val manga = requireNotNull(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(HistoryCatalogTestSource.MANGA_URL, HistoryCatalogTestSource.SOURCE_ID))
            val middle = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single()
            assertEquals("/chapter/2", middle.url)
            assertEquals(1, middle.lastPageRead)
            assertEquals(0, middle.dateFetch)
            val before = fixture.snapshot()
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    Navigator(HistoryRootScreen()) { nav ->
                        destination = nav.lastItem
                        val pending by TestNavigationController.pendingScreenRequest.collectAsState()
                        LaunchedEffect(pending?.id) { pending?.screen?.let(nav::push) }
                        CurrentScreen()
                    }
                }
            }
            settle(scene) { nodes(scene).any { it.config.contains(SemanticsActions.OnClick) && labels(it).contains(manga.title) } }
            click(scene, manga.title)
            Injekt.get<SaveSourceMangaForDetails>().awaitPrepared(fixture.source, manga)
            val prepared = fixture.snapshot()
            assertEquals(3, prepared.getValue("chapterCount").jsonPrimitive.int)
            assertEquals(before.getValue("historyId"), prepared.getValue("historyId"))
            assertEquals(before.getValue("outgoingUserEvents"), prepared.getValue("outgoingUserEvents"))
            assertEquals(middle.id, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single { it.url == middle.url }.id)
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { it.isOpen && loaded(it, middle.id) } == true && fixture.imageCalls.get() > 0 }
            val entry = destination as DesktopReaderScreen
            assertEquals(1, entry.initialPage)
            assertNotNull(entry.resumeSnapshot)
            assertNull(entry.progressTracker)
            assertEquals(1, ProductionReaderTestModeBridge.snapshot()?.currentPage)
            assertTrue(fixture.imageCalls.get() > 0)
            assertEquals(1, fixture.chapterCalls.get())
            val first = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single { it.url == "/chapter/1" }
            val last = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single { it.url == "/chapter/3" }
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(400f, 320f), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(400f, 320f), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            settle(scene) { nodes(scene).any { labels(it).contains(MR.strings.desktop_ui_previous_chapter.localized()) } }
            click(scene, MR.strings.desktop_ui_previous_chapter.localized())
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { loaded(it, first.id) } == true }
            assertFalse(requireNotNull(ProductionReaderTestModeBridge.snapshot()).hasPrevChapter)
            revealControls(scene, MR.strings.desktop_ui_next_chapter.localized())
            click(scene, MR.strings.desktop_ui_next_chapter.localized())
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { loaded(it, middle.id) } == true }
            // A received newer candidate must not redirect an already-open production session.
            fixture.execute("advance")
            repeat(5) {
                scene.render().close()
                delay(10)
            }
            assertEquals(middle.id, ProductionReaderTestModeBridge.snapshot()?.currentChapterId)
            assertEquals(entry.resumeSnapshot, entry.initialContext().resumeSnapshot)
            // The HTTP action is bound to the same mounted session, not synthetic state.
            val response = post(base, "/test/reader/next_chapter")
            assertEquals(200, response.statusCode())
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { loaded(it, last.id) } == true }
            assertFalse(requireNotNull(ProductionReaderTestModeBridge.snapshot()).hasNextChapter)
            assertTrue(fixture.pageCalls.get() >= 3)
            // Real key events at the beginning of the chapter consume the existing transition unit.
            scene.sendKeyEvent(composeKeyEvent(Key.MoveHome, KeyEventType.KeyDown))
            repeat(3) {
                scene.render().close()
                delay(10)
            }
            scene.sendKeyEvent(composeKeyEvent(Key.DirectionLeft, KeyEventType.KeyDown))
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { loaded(it, middle.id) } == true }
            scene.sendKeyEvent(composeKeyEvent(Key.MoveEnd, KeyEventType.KeyDown))
            repeat(3) {
                scene.render().close()
                delay(10)
            }
            scene.sendKeyEvent(composeKeyEvent(Key.DirectionRight, KeyEventType.KeyDown))
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { loaded(it, last.id) } == true }
            assertEquals(1, fixture.chapterCalls.get())
            assertEquals(200, post(base, "/test/reader/close").statusCode())
            settle(scene) { destination is HistoryRootScreen }
            controller.hydrate()
            val latestResume = requireNotNull(Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>().resumePosition(manga.id))
            assertTrue(controller.execute("history_select", mapOf("index" to "0")).success)
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { it.isOpen && loaded(it, latestResume.chapterId) } == true }
            assertEquals(latestResume.chapterId, (destination as DesktopReaderScreen).chapterId)
            assertEquals(latestResume.pageIndex, (destination as DesktopReaderScreen).initialPage)
            assertEquals(latestResume.snapshot, (destination as DesktopReaderScreen).resumeSnapshot)
            assertEquals(1, fixture.chapterCalls.get())
        } finally {
            scene.close()
            controller.close()
            HistoryTestModeBridge.clear(controller)
            TestNavigationController.reset()
            ProductionReaderTestModeBridge.reset()
            server.stop(0, 0)
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private fun loaded(state: mihon.desktop.test.http.ProductionReaderSnapshot, id: Long) = state.currentChapterId == id && state.activeChapterId == id && state.totalPages == 4 && state.loadState == "Loaded"
    private suspend fun revealControls(scene: ImageComposeScene, label: String) {
        repeat(3) {
            scene.render().close()
            delay(10)
        }
        if (nodes(scene).none { labels(it).contains(label) }) {
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(400f, 320f), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(400f, 320f), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        }
        settle(scene) { nodes(scene).any { labels(it).contains(label) } }
    }
    private fun post(base: String, path: String) = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
    private suspend fun settle(scene: ImageComposeScene, predicate: () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) {
            scene.render().close()
            delay(10)
        }
        scene.render().close()
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val target = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child -> labels(child).contains(label) } }
        assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode) = (if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()) + (if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList())
    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType").getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp").invoke(null)
        val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return androidx.compose.ui.input.key.KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null))
    }
}
