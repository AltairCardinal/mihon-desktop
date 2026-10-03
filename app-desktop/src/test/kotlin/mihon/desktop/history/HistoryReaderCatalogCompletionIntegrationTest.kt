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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.inMemoryDesktopPreferenceStore
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
import kotlin.coroutines.coroutineContext

@Isolated
@OptIn(ExperimentalComposeUiApi::class)
class HistoryReaderCatalogCompletionIntegrationTest {
    @Test
    fun `delayed catalog leaves sparse reader readable then adds adjacent chapters in the same session`(@TempDir folder: File) = runBlocking {
        scenario(folder)
    }

    @Test
    fun `directory parser failures and timeout leave sparse mounted reader readable without retry`(@TempDir folder: File) = runBlocking {
        listOf("http403", "http429", "http500", "empty", "malformed", "timeout").forEach { mode ->
            scenario(folder.resolve(mode).apply { mkdirs() }, failureMode = mode)
        }
    }

    @Test
    fun `directory released after reader closes cannot revive the old screen or session`(@TempDir folder: File) = runBlocking {
        scenario(folder, closeBeforeRelease = true)
    }

    private suspend fun scenario(folder: File, failureMode: String? = null, closeBeforeRelease: Boolean = false) {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
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
            fixture.execute("hold")
            failureMode?.let { fixture.execute("mode", it) }
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
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { it.isOpen && loaded(it, middle.id) } == true && fixture.imageCalls.get() > 0 }
            val entry = destination as DesktopReaderScreen
            assertEquals(1, entry.initialPage)
            assertNotNull(entry.resumeSnapshot)
            assertNull(entry.progressTracker)
            assertEquals(1, ProductionReaderTestModeBridge.snapshot()?.currentPage)
            assertTrue(fixture.imageCalls.get() > 0)
            assertEquals(listOf(middle.id), requireNotNull(ProductionReaderTestModeBridge.snapshot()).chapterIds)
            val binding = ProductionReaderTestModeBridge.binding
            val baseline = requireNotNull(ProductionReaderTestModeBridge.snapshot()).resumeHeadIds
            assertEquals(200, post(base, "/test/reader/go_to_page", """{"page":2}""").statusCode())
            assertEquals(2, ProductionReaderTestModeBridge.snapshot()?.currentPage)
            settle(scene) { fixture.chapterCalls.get() == 1 }
            assertEquals(1, fixture.snapshot().getValue("chapterCount").jsonPrimitive.int)
            if (closeBeforeRelease) {
                assertEquals(200, post(base, "/test/reader/close").statusCode())
                settle(scene) { destination is HistoryRootScreen }
                val closed = ProductionReaderTestModeBridge.snapshot()
                fixture.execute("release")
                settle(scene) { fixture.snapshot().getValue("chapterCount").jsonPrimitive.int == 3 }
                assertTrue(destination is HistoryRootScreen)
                assertNull(ProductionReaderTestModeBridge.binding)
                assertEquals(closed, ProductionReaderTestModeBridge.snapshot())
                assertEquals(1, fixture.chapterCalls.get())
                return
            }
            fixture.execute("release")
            if (failureMode != null) {
                withTimeout(40_000) {
                    settle(scene, timeoutMillis = 38_000) {
                        Injekt.get<SaveSourceMangaForDetails>().refreshStates.value.values.any { it is mihon.desktop.domain.SourceMangaRefreshState.Failure }
                    }
                }
                assertTrue(binding === ProductionReaderTestModeBridge.binding)
                assertEquals(baseline, ProductionReaderTestModeBridge.snapshot()?.resumeHeadIds)
                assertEquals(2, ProductionReaderTestModeBridge.snapshot()?.currentPage)
                assertTrue(loaded(requireNotNull(ProductionReaderTestModeBridge.snapshot()), middle.id))
                assertEquals(listOf(middle.id), ProductionReaderTestModeBridge.snapshot()?.chapterIds)
                assertEquals(1, fixture.snapshot().getValue("chapterCount").jsonPrimitive.int)
                assertEquals(1, fixture.chapterCalls.get())
                assertEquals(200, post(base, "/test/reader/go_to_page", """{"page":1}""").statusCode())
                assertEquals(1, ProductionReaderTestModeBridge.snapshot()?.currentPage)
                assertEquals(200, post(base, "/test/reader/close").statusCode())
                settle(scene) { destination is HistoryRootScreen }
                assertEquals(1, fixture.chapterCalls.get())
                return
            }
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.chapterIds?.size == 3 }
            assertTrue(binding === ProductionReaderTestModeBridge.binding)
            assertEquals(baseline, ProductionReaderTestModeBridge.snapshot()?.resumeHeadIds)
            assertEquals(2, ProductionReaderTestModeBridge.snapshot()?.currentPage)
            val prepared = fixture.snapshot()
            assertEquals(3, prepared.getValue("chapterCount").jsonPrimitive.int)
            assertEquals(before.getValue("historyId"), prepared.getValue("historyId"))
            assertEquals(middle.id, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single { it.url == middle.url }.id)
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
            // Exercise the production Desktop dispatcher without replacing Dispatchers.Main.
            assertEquals(200, post(base, "/test/reader/go_to_page", """{"page":2}""").statusCode())
            assertEquals(2, ProductionReaderTestModeBridge.snapshot()?.currentPage)
            assertEquals(200, post(base, "/test/reader/prev_page").statusCode())
            assertEquals(1, ProductionReaderTestModeBridge.snapshot()?.currentPage)
            assertEquals(200, post(base, "/test/reader/next_page").statusCode())
            assertEquals(2, ProductionReaderTestModeBridge.snapshot()?.currentPage)
            assertEquals(200, post(base, "/test/reader/prev_chapter").statusCode())
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { loaded(it, middle.id) } == true }
            assertEquals(200, post(base, "/test/reader/next_chapter").statusCode())
            settle(scene) { ProductionReaderTestModeBridge.snapshot()?.let { loaded(it, last.id) } == true }
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
        } finally {
            fixture.execute("release")
            scene.close()
            controller.close()
            HistoryTestModeBridge.clear(controller)
            TestNavigationController.reset()
            ProductionReaderTestModeBridge.reset()
            server.stop(0, 0)
            context.closeAndJoin()
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
    private fun post(base: String, path: String, body: String = "{}") = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    private suspend fun settle(scene: ImageComposeScene, timeoutMillis: Long = 15_000, predicate: suspend () -> Boolean) = withTimeout(timeoutMillis) {
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
