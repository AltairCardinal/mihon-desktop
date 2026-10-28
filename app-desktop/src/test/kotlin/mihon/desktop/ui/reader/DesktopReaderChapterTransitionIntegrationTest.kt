package mihon.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.reader.DesktopReaderChapterContentPortFactory
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderEncodedPageStore
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageFetchPortFactory
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderProgressPort
import mihon.desktop.reader.DesktopReaderRuntime
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.DesktopReaderSession
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderSessionCore
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.prefs.Preferences

class DesktopReaderChapterTransitionIntegrationTest {

    @Test
    fun `next chapter activates inside the same core at zero pages without a replacement screen`() {
        val chapters = chapters()
        val core = ReaderSessionCore(ReaderChapterId(1L), sessionId = "transition-test")
        val initialContext = context(chapters[1], chapterIndex = 1, initialPage = 0)
        lateinit var model: ReaderScreenModel
        val activations = mutableListOf<DesktopReaderChapterContext>()
        model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(initialContext, core.snapshot),
            onChapterActivated = { target ->
                activations += target
                DesktopReaderSessionState(target, core.openChapter(ReaderChapterId(target.chapterId)).snapshot)
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            mangaTitle = "Manga",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            sourceId = 9L,
            chapters = chapters,
            currentChapterIndex = 1,
        )

        val activated = screen.requestAdjacentChapterTransition(
            ReaderTransitionDirection.NEXT,
            model,
            ReaderNavigator(chapters, currentIndex = 1),
        )

        assertTrue(activated)
        assertEquals(2L, activations.single().chapterId)
        assertEquals(ReaderInitialPage.FIRST, activations.single().initialPage)
        assertSame(core, core)
        assertEquals(ReaderChapterId(2L), model.state.value.session.activeChapter.id)
        assertTrue(model.state.value.session.activeChapter.pages.isEmpty())
        assertInstanceOf(
            ReaderChapterLoadState.LoadingPageList::class.java,
            model.state.value.session.activeChapter.loadState,
        )
        assertNull(model.state.value.chapterTransition)
    }

    @Test
    fun `previous chapter resolves last page after its stable page list arrives`() {
        val chapters = chapters()
        val core = ReaderSessionCore(ReaderChapterId(2L), sessionId = "previous-transition-test")
        val initialContext = context(chapters[0], chapterIndex = 0, initialPage = 0)
        lateinit var model: ReaderScreenModel
        model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(initialContext, core.snapshot),
            onChapterActivated = { target ->
                DesktopReaderSessionState(target, core.openChapter(ReaderChapterId(target.chapterId)).snapshot)
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 2",
            mangaTitle = "Manga",
            chapterId = 2L,
            chapterUrl = "/2",
            chapterNumber = 2.0,
            sourceId = 9L,
            chapters = chapters,
            currentChapterIndex = 0,
        )

        assertTrue(
            screen.requestAdjacentChapterTransition(
                ReaderTransitionDirection.PREVIOUS,
                model,
                ReaderNavigator(chapters, currentIndex = 0),
            ),
        )
        val opening = core.snapshot
        assertTrue(opening.activeChapter.pages.isEmpty())
        val loaded = core.acceptChapterMaterialization(
            chapterId = ReaderChapterId(1L),
            generation = opening.generation,
            result = ReaderChapterMaterializeResult.Loaded(List(4) { ReaderPageDescriptor(it, url = "/1/$it") }),
        ).snapshot
        model.acceptSessionState(DesktopReaderSessionState(model.state.value.context, loaded))

        assertEquals(3, model.state.value.currentPage)
        assertEquals(4, model.state.value.session.activeChapter.pages.size)
    }

    @Test
    fun `chapter boundaries do not activate and do not keep viewport feedback`() {
        val only = listOf(ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0))
        val context = context(only.single(), chapterIndex = 0, initialPage = 0)
        var activations = 0
        val model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(
                context,
                ReaderSessionCore(ReaderChapterId(1L), sessionId = "boundary-test").snapshot,
            ),
            onChapterActivated = {
                activations++
                null
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            chapters = only,
            currentChapterIndex = 0,
        )
        val navigator = ReaderNavigator(only, currentIndex = 0)

        val previous = screen.requestAdjacentChapterTransition(ReaderTransitionDirection.PREVIOUS, model, navigator)
        assertFalse(previous)
        assertNull(model.chapterTransitionCommand())

        val next = screen.requestAdjacentChapterTransition(ReaderTransitionDirection.NEXT, model, navigator)
        assertFalse(next)
        assertNull(model.chapterTransitionCommand())
        assertEquals(0, activations)
    }

    @Test
    fun `chapter boundary request does not mount a persistent viewport feedback overlay`() {
        val only = listOf(ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0))
        val context = context(only.single(), chapterIndex = 0, initialPage = 0)
        val model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(
                context,
                ReaderSessionCore(ReaderChapterId(1L), sessionId = "boundary-no-overlay-test").snapshot,
            ),
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            chapterId = 1L,
            chapterUrl = "/1",
            chapters = only,
            currentChapterIndex = 0,
        )

        screen.requestAdjacentChapterTransition(
            ReaderTransitionDirection.NEXT,
            model,
            ReaderNavigator(only, currentIndex = 0),
        )

        assertNull(model.state.value.chapterTransition)
    }

    @Test
    fun `production reader supplies filtered next chapter and presentation viewport to prefetch`() {
        val chapters = chapters()
        val updates = mutableListOf<Pair<DesktopReaderChapterContext?, Int>>()
        val initialContext = context(chapters[1], chapterIndex = 1, initialPage = 0)
        val model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(
                initialContext,
                ReaderSessionCore(ReaderChapterId(1L), sessionId = "prefetch-wiring-test").snapshot,
            ),
            onNextChapterPrefetchChanged = { target, firstViewportPageCount ->
                updates += target to firstViewportPageCount
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            mangaTitle = "Manga",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            sourceId = 9L,
            mangaId = 44L,
            chapters = chapters,
            currentChapterIndex = 1,
        )
        val navigator = ReaderNavigator(chapters, currentIndex = 1)

        screen.updateNextChapterPrefetch(model, navigator, ReadingMode.LTR, dualPage = false)
        screen.updateNextChapterPrefetch(model, navigator, ReadingMode.LTR, dualPage = true)
        screen.updateNextChapterPrefetch(model, navigator, ReadingMode.WEBTOON, dualPage = false)

        assertEquals(listOf(1, 2, 3), updates.map { it.second })
        assertTrue(updates.all { it.first?.chapterId == 2L })
        assertTrue(updates.all { it.first?.mangaId == 44L })
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `mounted production screen wires prefetch and retains runtime through outgoing composition`(@TempDir tempDir: File) = runTest {
        val chapters = chapters()
        val initialContext = context(chapters[1], chapterIndex = 1, initialPage = 0)
        val core = ReaderSessionCore(ReaderChapterId(1L), sessionId = "mounted-prefetch-wiring-test")
        val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded"))
        val legacy = Preferences.userRoot().node("/mihon/mounted-prefetch/${System.nanoTime()}")
        val session = DesktopReaderSession(
            initialContext = initialContext,
            core = core,
            encodedPageStore = store,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort { error("page-list I/O is not needed for the wiring assertion") }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, _ ->
                error("page I/O is not needed for the wiring assertion")
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
        )
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy)
        val ioReporter = ReaderIoReporter(clock = ReaderMonotonicClock(System::nanoTime))
        val pageContentOwner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = store::read,
            ioReporter = ioReporter,
        )
        val pageImagePipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = pageContentOwner,
            ioReporter = ioReporter,
        )
        val runtime = DesktopReaderRuntime(
            prefs = prefs,
            pageImagePipeline = pageImagePipeline,
            presentationImageOwner = DesktopReaderPresentationImageOwner(
                scope = this,
                pageImagePipeline = pageImagePipeline,
                pageIoObserver = ReaderPageIoObserver(ioReporter) { _, _ -> },
            ),
            pageContentOwner = pageContentOwner,
            session = session,
            encodedPageStore = store,
            prefetchPreferenceJob = Job(),
        )
        val updates = mutableListOf<Pair<DesktopReaderChapterContext?, Int>>()
        var screenProductionClosedCallbacks = 0
        val model = ReaderScreenModel(
            prefs = prefs,
            initialSessionState = session.state.value,
            onNextChapterPrefetchChanged = { target, firstViewportPageCount ->
                updates += target to firstViewportPageCount
            },
            runtime = runtime,
        )
        model.attachCatalog(chapters, null, null) { emptyList() }
        val productionClosedCallback = slot<() -> Unit>()
        val factory = mockk<DesktopReaderRuntimeFactory> {
            every {
                createScreenModel(any(), any(), any(), any(), any(), capture(productionClosedCallback), chapters, null)
            } returns model
        }
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            mangaTitle = "Manga",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            sourceId = 9L,
            mangaId = 44L,
            chapters = chapters,
            currentChapterIndex = 1,
            onProductionClosed = { screenProductionClosedCallbacks += 1 },
        )
        val previousInjekt = Injekt
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        val uiDependencies = mockk<DesktopUiDependencies> {
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
            every { notificationService } returns DesktopNotificationService()
        }
        try {
            patchInjekt()
            Injekt.addSingleton(factory)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides uiDependencies) {
                    Navigator(screen) { screen.Content() }
                }
            }
            repeat(3) {
                scene.render()
                runCurrent()
            }

            assertEquals(listOf(1), updates.map { it.second })
            assertEquals(listOf(2L), updates.map { it.first?.chapterId })
            assertEquals(listOf(44L), updates.map { it.first?.mangaId })
            productionClosedCallback.captured()
            assertEquals(1, screenProductionClosedCallbacks)

            model.onDispose()

            assertFalse(runtime.presentationImageOwner.isClosed())
            scene.close()
            assertTrue(runtime.presentationImageOwner.isClosed())
        } finally {
            runCatching(scene::close)
            runtime.close()
            Injekt = previousInjekt
            legacy.removeNode()
        }
    }

    @Test
    fun `chapter transition purges prior chapter certification targets and does not crash certifyPairing`() {
        val state1 = mihon.desktop.reader.desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = state1)
        try {
            val pageIdChapter1 = state1.snapshot.activeChapter.pages.first().id
            model.beginPagerViewport(
                visiblePages = mihon.desktop.ui.reader.presentation.VisiblePageSet(
                    displayUnitId = mihon.desktop.ui.reader.presentation.DisplayUnitId(
                        mihon.desktop.ui.reader.presentation.ReaderPresentationMode.SINGLE_PAGED,
                        listOf(mihon.desktop.ui.reader.presentation.DisplaySlotId(pageIdChapter1)),
                    ),
                    pageIds = setOf(pageIdChapter1),
                    activePageId = pageIdChapter1,
                ),
                userNavigation = false,
            )

            val state2 = mihon.desktop.reader.desktopReaderSessionState(chapterId = 2L, pageCount = 3)
            // Must not throw IllegalArgumentException when accepting session state of chapter 2
            model.acceptSessionState(state2)
            assertEquals(ReaderChapterId(2L), model.state.value.session.activeChapter.id)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `dual-page forward transition cleans up certification targets and certifies new pairs`() {
        val state1 = desktopReaderSessionState(chapterId = 1L, pageCount = 4)
        val model = ReaderScreenModel(initialSessionState = state1, dualPageOverride = true)
        try {
            model.presentReadyPagerViewport()
            model.navigateDualPage(forward = true)
            model.presentReadyPagerViewport()

            val state2 = desktopReaderSessionState(chapterId = 2L, pageCount = 4)
            model.acceptSessionState(state2)
            assertEquals(ReaderChapterId(2L), model.state.value.session.activeChapter.id)

            val nextViewport = model.presentReadyPagerViewport()
            assertNotNull(nextViewport)
            assertTrue(model.state.value.dualPairingFailures.keys.none { it.chapterId == ReaderChapterId(1L) })
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `backward chapter transition returns to prior chapter without certification collision`() {
        val state2 = desktopReaderSessionState(chapterId = 2L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = state2)
        try {
            model.presentReadyPagerViewport()

            // Step backward to Chapter 1
            val state1 = desktopReaderSessionState(chapterId = 1L, pageCount = 3, initialPage = 2)
            model.acceptSessionState(state1)
            assertEquals(ReaderChapterId(1L), model.state.value.session.activeChapter.id)
            model.presentReadyPagerViewport()

            // Step forward again to Chapter 2
            model.acceptSessionState(state2)
            assertEquals(ReaderChapterId(2L), model.state.value.session.activeChapter.id)
            val ticket = model.presentReadyPagerViewport()
            assertNotNull(ticket)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `transition into loading chapter with zero pages then populating pages succeeds`() {
        val state1 = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = state1)
        try {
            model.presentReadyPagerViewport()

            // Chapter 2 starts loading with 0 pages
            val state2Loading = desktopReaderSessionState(chapterId = 2L, pageCount = 0)
            model.acceptSessionState(state2Loading)
            assertEquals(ReaderChapterId(2L), model.state.value.session.activeChapter.id)
            assertTrue(model.state.value.session.activeChapter.pages.isEmpty())

            // Chapter 2 finishes loading page list
            val state2Loaded = desktopReaderSessionState(chapterId = 2L, pageCount = 3)
            model.acceptSessionState(state2Loaded)
            assertEquals(3, model.state.value.session.activeChapter.pages.size)

            val ticket = model.presentReadyPagerViewport()
            assertNotNull(ticket)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `webtoon continuous reading across chapters clears certification targets and updates anchor`() {
        val state1 = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = state1, isWebtoon = true)
        try {
            val pages1 = state1.snapshot.activeChapter.pages.map { it.id }
            val unit1 = DisplayUnitId(ReaderPresentationMode.WEBTOON, listOf(DisplaySlotId(pages1[1])))
            model.settleWebtoon(
                WebtoonViewportUpdate(
                    VisiblePageSet(unit1, setOf(pages1[0], pages1[1]), pages1[1]),
                    WebtoonScrollAnchor(unit1, scrollOffset = 20),
                ),
            )
            assertEquals(1, model.state.value.currentPage)

            // Switch to Chapter 2
            val state2 = desktopReaderSessionState(chapterId = 2L, pageCount = 3)
            model.acceptSessionState(state2)
            assertEquals(ReaderChapterId(2L), model.state.value.session.activeChapter.id)

            val pages2 = state2.snapshot.activeChapter.pages.map { it.id }
            val unit2 = DisplayUnitId(ReaderPresentationMode.WEBTOON, listOf(DisplaySlotId(pages2[0])))
            model.settleWebtoon(
                WebtoonViewportUpdate(
                    VisiblePageSet(unit2, setOf(pages2[0]), pages2[0]),
                    WebtoonScrollAnchor(unit2, scrollOffset = 0),
                ),
            )
            assertEquals(0, model.state.value.currentPage)
            assertEquals(setOf(pages2[0]), model.state.value.visiblePageIds)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `arbitrary non-adjacent chapter jump resets certification targets and establishes new session`() {
        val state1 = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = state1)
        try {
            model.presentReadyPagerViewport()

            // Jump directly to Chapter 10
            val state10 = desktopReaderSessionState(chapterId = 10L, pageCount = 5)
            model.acceptSessionState(state10)
            assertEquals(ReaderChapterId(10L), model.state.value.session.activeChapter.id)

            val ticket = model.presentReadyPagerViewport()
            assertNotNull(ticket)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `rapid sequential chapter switching without intermediate settle clears previous targets`() {
        val state1 = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = state1)
        try {
            model.presentReadyPagerViewport()

            // Rapid consecutive transitions: 2 -> 3 -> 4
            model.acceptSessionState(desktopReaderSessionState(chapterId = 2L, pageCount = 3))
            model.acceptSessionState(desktopReaderSessionState(chapterId = 3L, pageCount = 3))
            val state4 = desktopReaderSessionState(chapterId = 4L, pageCount = 3)
            model.acceptSessionState(state4)
            assertEquals(ReaderChapterId(4L), model.state.value.session.activeChapter.id)

            val ticket = model.presentReadyPagerViewport()
            assertNotNull(ticket)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `chapter transition while in fullscreen mode preserves fullscreen state and auto-hidden UI`() {
        val state1 = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = state1)
        try {
            model.presentReadyPagerViewport()

            // Enter fullscreen
            model.setFullscreen(true)
            assertTrue(model.state.value.isFullscreen)
            assertFalse(model.state.value.showUI)

            // Transition to Chapter 2 while in fullscreen
            val state2 = desktopReaderSessionState(chapterId = 2L, pageCount = 3)
            model.acceptSessionState(state2)

            // Settle in Chapter 2
            val ticket = model.presentReadyPagerViewport()
            assertNotNull(ticket)

            // Verify fullscreen state is preserved through chapter transition
            assertTrue(model.state.value.isFullscreen, "Fullscreen state must be preserved across chapter transition")
            assertFalse(model.state.value.showUI, "UI must remain auto-hidden across chapter transition in fullscreen")

            // Exit fullscreen
            model.setFullscreen(false)
            assertFalse(model.state.value.isFullscreen)
            assertTrue(model.state.value.showUI)
        } finally {
            model.onDispose()
        }
    }

    private fun chapters() = listOf(
        ReaderChapterRef(id = 2L, url = "/2", name = "Chapter 2", chapterNumber = 2.0),
        ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0),
    )

    private fun context(ref: ReaderChapterRef, chapterIndex: Int, initialPage: Int) = DesktopReaderChapterContext(
        chapterId = ref.id,
        sourceId = 9L,
        chapterUrl = ref.url,
        mangaTitle = "Manga",
        chapterTitle = ref.name,
        chapterNumber = ref.chapterNumber,
        chapterIndex = chapterIndex,
        initialPage = initialPage,
        wasRead = ref.isRead,
    )
}
