package mihon.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.reader.DesktopChapterPairingCoordinator
import mihon.desktop.reader.DesktopReaderChapterContentPortFactory
import mihon.desktop.reader.DesktopReaderEncodedPageStore
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageFetchPortFactory
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderProgressPort
import mihon.desktop.reader.DesktopReaderRuntime
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.DesktopReaderScreenEntry
import mihon.desktop.reader.DesktopReaderSession
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReaderStartupObservation
import mihon.desktop.reader.ReaderStartupTrace
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderSessionCore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderFirstCompositionIntegrationTest {
    @Test
    fun `real screen consumes published fifth page before full model and keeps holder through first draw`(
        @TempDir dir: File,
    ) =
        screenScenario(dir, loadedInitially = true)

    @Test
    fun `late page list stays on lightweight path until the actual target draw`(
        @TempDir dir: File,
    ) =
        screenScenario(dir, loadedInitially = false)

    @Test
    fun `final selection consumes same generation page list published after initial snapshot before full model`(
        @TempDir dir: File,
    ) = screenScenario(dir, loadedInitially = false, publishDuringInitialProjection = true)

    @Test
    fun `reread rejects a different active chapter generation published during initial projection`(
        @TempDir dir: File,
    ) = screenScenario(dir, loadedInitially = false, publishDuringInitialProjection = true, switchGeneration = true)

    @Test
    fun `reread rejects presentation settings changed after the initial projection`(
        @TempDir dir: File,
    ) = screenScenario(dir, loadedInitially = false, publishDuringInitialProjection = true, changeSettings = true)

    @Test
    fun `late pairing preserves target pixels until visual handoff`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = false, delayedPairing = true)

    @Test
    fun `early fullscreen and repeated page keys are replayed once through the production reader`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = true, earlyKeyboard = true)

    @Test
    fun `early navigation waits for the real late page table before consuming its restore target`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = false, earlyKeyboard = true, pendingInput = true)

    @Test
    fun `last page opening intent resolves only after the real late page table`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = false, restorePage = ReaderInitialPage.LAST)

    @Test
    fun `invalid saved page enters the full recovery reader`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = false, restorePage = 999)

    @Test
    fun `page table failure reaches the existing error reader without waiting for first draw`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = false, pageTableFailure = true)

    @Test
    fun `closing during page table wait releases runtime without full model`(
        @TempDir dir: File,
    ) =
        screenScenario(dir, loadedInitially = false, closePending = true)

    @Test
    fun `online webtoon dual automatic and split modes retain the full screen entry`(@TempDir dir: File) {
        screenScenario(dir.resolve("online"), loadedInitially = false, downloaded = false)
        screenScenario(dir.resolve("webtoon"), loadedInitially = false, mode = ReadingMode.WEBTOON)
        screenScenario(dir.resolve("dual"), loadedInitially = false, dual = true)
        screenScenario(dir.resolve("auto"), loadedInitially = false, mode = ReadingMode.AUTO)
        screenScenario(dir.resolve("split"), loadedInitially = false, split = true)
    }

    @Test
    fun `active pinch and pan retain the actual screen holder across first draw until release`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = true, activeGesture = true)

    @Test
    fun `early controls run before the page table and survive its failure`(@TempDir dir: File) {
        listOf(Key.F11, Key.Spacebar, Key.Equals).forEachIndexed { index, key ->
            screenScenario(dir.resolve("success-$index"), loadedInitially = false, pendingControl = key)
            screenScenario(
                dir.resolve("failure-$index"),
                loadedInitially = false,
                pendingControl = key,
                pageTableFailure = true,
            )
        }
    }

    @Test
    fun `deferred early turns cannot replay into a replacement chapter`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = false, pendingControl = Key.F11, replacePendingChapter = true)

    @Test
    fun `closing after an early control discards deferred turns`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = false, pendingControl = Key.F11, closeAfterControl = true)

    @Test
    fun `encoded commit failure keeps the existing page error without reporting progress`(@TempDir dir: File) =
        screenScenario(dir, loadedInitially = true, encodedFailure = true)

    @Test
    fun `Ready target with a missing committed cache uses original holder failure without progress`(
        @TempDir dir: File,
    ) =
        screenScenario(dir, loadedInitially = true, contentMissing = true)

    private fun screenScenario(
        dir: File,
        loadedInitially: Boolean,
        publishDuringInitialProjection: Boolean = false,
        switchGeneration: Boolean = false,
        changeSettings: Boolean = false,
        delayedPairing: Boolean = false,
        earlyKeyboard: Boolean = false,
        pendingInput: Boolean = false,
        pendingControl: Key? = null,
        replacePendingChapter: Boolean = false,
        closeAfterControl: Boolean = false,
        restorePage: Int = 4,
        pageTableFailure: Boolean = false,
        closePending: Boolean = false,
        downloaded: Boolean = true,
        mode: ReadingMode = ReadingMode.RTL,
        dual: Boolean = false,
        split: Boolean = false,
        activeGesture: Boolean = false,
        encodedFailure: Boolean = false,
        contentMissing: Boolean = false,
    ) = runTest {
        val expectedPage = if (restorePage == ReaderInitialPage.LAST) 186 else restorePage.takeIf { it in 0..186 } ?: 0
        val expectStartup = !pageTableFailure &&
            (restorePage == ReaderInitialPage.LAST || restorePage in 0..186) && downloaded &&
            mode in setOf(ReadingMode.RTL, ReadingMode.LTR) && !dual && !split
        val legacy = Preferences.userRoot().node("/mihon/first-composition/${System.nanoTime()}")
        val prefs = spyk(
            ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
                readingMode = mode
                isDualPage = dual
                autoSplitPages = split
            },
        )
        var publishPageList: (() -> Unit)? = null
        if (publishDuringInitialProjection) {
            every { prefs.readingMode } answers {
                publishPageList?.also { publishPageList = null }?.invoke()
                ReadingMode.RTL
            }
        }
        if (changeSettings) {
            every { prefs.skipDuplicateChapters } answers {
                val original = callOriginal() as Boolean
                prefs.autoSplitPages = true
                original
            }
        }
        val context = desktopReaderSessionState(
            chapterId = 41,
            pageCount = 187,
            initialPage = restorePage,
        ).context.copy(
            isDownloaded = downloaded,
            mangaId = 7,
        )
        val core = ReaderSessionCore(ReaderChapterId(41), sessionId = "first-composition-screen")
        val reporter = ReaderIoReporter(clock = ReaderMonotonicClock(System::nanoTime))
        fun pageColor(index: Int) = if (index ==
            4
        ) {
            Color.BLUE
        } else {
            Color((index * 73) % 255, (index * 151) % 255, (index * 197) % 255)
        }
        fun png(index: Int) = ByteArrayOutputStream().also { out ->
            ImageIO.write(
                BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply {
                    createGraphics().apply {
                        color = pageColor(index)
                        fillRect(0, 0, 8, 8)
                        dispose()
                    }
                },
                "png",
                out,
            )
        }.toByteArray()
        val store = DesktopReaderEncodedPageStore(dir.resolve("encoded"))
        val encodedReads = java.util.concurrent.ConcurrentHashMap<EncodedPageRef, Int>()
        val contentOwner = DesktopReaderPageContentOwner(this, { ref ->
            encodedReads.merge(ref, 1) { a, b -> a + b }
            store.read(ref)
        }, reporter, store::readPrefix)
        val pipeline = DesktopReaderPageImagePipeline(this, contentOwner, reporter)
        val owner = DesktopReaderPresentationImageOwner(this, pipeline, ReaderPageIoObserver(reporter))
        val progress = mutableListOf<Int>()
        val pageTableRelease = CompletableDeferred<Unit>()
        val session = DesktopReaderSession(
            initialContext = context,
            core = core,
            encodedPageStore = store,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort {
                    if (pendingInput || closePending || pendingControl != null) pageTableRelease.await()
                    if (pageTableFailure) error("page table fixture failed")
                    List(187) { ReaderPageDescriptor(it, url = "/page/$it", imageUrl = "https://fixture.test/$it.png") }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(
                        request: ReaderPageFetchRequest,
                    ) = requireNotNull(request.imageUrl)
                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? {
                        val ref = store.cacheRef(request.pageId, "fixture:${descriptor.sourcePageIndex}")
                        return ref.takeIf { store.contains(it) }
                    }
                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        val ref = store.cacheRef(request.pageId, "fixture:${descriptor.sourcePageIndex}")
                        val bytes = png(descriptor.sourcePageIndex)
                        store.store(ref) {
                            store.destinationFile(ref).writeBytes(bytes)
                            bytes.size.toLong() +
                                if (encodedFailure && descriptor.sourcePageIndex == expectedPage) 1L else 0L
                        }
                        if (contentMissing && descriptor.sourcePageIndex == expectedPage) {
                            store.destinationFile(ref).delete()
                        }
                        return ref
                    }
                }
            },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect.lastPageRead },
            parentScope = this,
            onGenerationPublished = owner::beginGeneration,
        )
        val runtime =
            DesktopReaderRuntime(prefs, pipeline, owner, contentOwner, session, store, Job(), presentationScope = this)
        val pairingRelease = CompletableDeferred<Unit>()
        val pairingRepository = mockk<ChapterPairingRepository> {
            coEvery { load(any(), any()) } coAnswers {
                pairingRelease.await()
                ChapterPairingSnapshot(null, 0)
            }
        }
        val realFactory = DesktopReaderRuntimeFactory(
            prefs,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            null,
            dir.resolve("factory-cache"),
            pairingCoordinator = if (delayedPairing) {
                DesktopChapterPairingCoordinator(
                    pairingRepository,
                    backgroundScope,
                )
            } else {
                null
            },
        )
        var model: ReaderScreenModel? = null
        val entry = DesktopReaderScreenEntry(runtime, runtime::close) {
            if (!switchGeneration && !changeSettings && !earlyKeyboard && pendingControl == null && expectStartup) {
                check(owner.firstPresentedGeneration.value == session.state.value.snapshot.generation) {
                    "Full model must start after actual target draw"
                }
                check(progress.contains(expectedPage)) { "Full model must start after normal progress callback" }
            }
            realFactory.createModel(runtime, false, 0L, null).also { model = it }
        }
        val factory = mockk<DesktopReaderRuntimeFactory> {
            every { createScreenEntry(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns entry
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
            every { notificationService } returns DesktopNotificationService()
        }
        val screen = DesktopReaderScreen(
            "Chapter 41",
            mangaTitle = "Fixture",
            chapterId = 41,
            mangaId = 7,
            initialPage = restorePage,
            isRtl = true,
        )
        var activeScreen by mutableStateOf(screen)
        val observation = ReaderStartupObservation()
        val previous = Injekt
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        var initialDemandChecks = 0
        if (pendingControl != null) {
            session.installInitialEncodedDemand(
                mihon.desktop.reader.DesktopReaderInitialEncodedDemand(41, restorePage) {
                    initialDemandChecks++
                    true
                },
            )
        }
        try {
            session.start()
            if (loadedInitially) {
                runCurrent()
                assertEquals(187, session.state.value.snapshot.activeChapter.pages.size)
            }
            if (publishDuringInitialProjection) {
                publishPageList = {
                    assertTrue(session.state.value.snapshot.activeChapter.pages.isEmpty())
                    runCurrent()
                    assertEquals(187, session.state.value.snapshot.activeChapter.pages.size)
                    if (switchGeneration) {
                        session.activate(context.copy(chapterId = 42, chapterUrl = "/chapter/42"))
                        runCurrent()
                        assertEquals(42, session.state.value.context.chapterId)
                    }
                }
            }
            ReaderStartupTrace.install(observation)
            patchInjekt()
            Injekt.addSingleton(factory)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { activeScreen.Content() }
                }
            }
            scene.render(System.nanoTime()).close()
            fun pointer(id: Long, x: Float, pressed: Boolean = true) = ComposeScenePointer(
                id = PointerId(id),
                position = Offset(x, 240f),
                pressed = pressed,
                type = PointerType.Touch,
            )
            fun nodes(): List<SemanticsNode> {
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            }
            val gestureHolder = if (activeGesture) {
                nodes().single {
                    it.config.contains(ReaderPresentationHolderKey)
                }.config[ReaderPresentationHolderKey]
            } else {
                null
            }
            if (activeGesture) {
                scene.sendPointerEvent(PointerEventType.Press, listOf(pointer(1, 200f), pointer(2, 400f)))
                scene.render().close()
                scene.sendPointerEvent(PointerEventType.Move, listOf(pointer(1, 100f), pointer(2, 500f)))
                scene.render().close()
                scene.sendPointerEvent(PointerEventType.Release, listOf(pointer(1, 100f, false), pointer(2, 500f)))
                scene.render().close()
                scene.sendPointerEvent(PointerEventType.Move, listOf(pointer(2, 530f)))
                scene.render().close()
            }
            if (closePending) {
                runCurrent()
                assertFalse(entry.hasModel)
                entry.onDispose()
                scene.close()
                assertTrue(owner.isClosed())
                assertFalse(entry.hasModel)
                return@runTest
            }
            if (pendingControl != null) {
                runCurrent()
                scene.sendKeyEvent(keyEvent(pendingControl, ctrl = pendingControl == Key.Equals))
                repeat(2) { scene.sendKeyEvent(keyEvent(Key.DirectionLeft)) }
                fun assertControl() {
                    val current = requireNotNull(model).state.value
                    when (pendingControl) {
                        Key.F11 -> assertTrue(current.isFullscreen, "F11 must run without the page table")
                        Key.Spacebar -> assertTrue(current.showUI, "Space must run without the page table")
                        Key.Equals -> assertTrue(
                            current.zoomState.scale > 1f,
                            "Ctrl zoom must run without the page table",
                        )
                    }
                }
                repeat(4) {
                    scene.render().close()
                    runCurrent()
                }
                assertTrue(session.state.value.snapshot.activeChapter.pages.isEmpty())
                assertControl()
                assertEquals(0, model!!.state.value.currentPage)
                if (closeAfterControl) {
                    entry.onDispose()
                    scene.close()
                    pageTableRelease.complete(Unit)
                    runCurrent()
                    assertTrue(owner.isClosed())
                    assertEquals(0, model!!.state.value.currentPage)
                    assertTrue(progress.isEmpty())
                    return@runTest
                }
                if (replacePendingChapter) {
                    session.activate(context.copy(chapterId = 42))
                    repeat(4) {
                        scene.render().close()
                        runCurrent()
                    }
                }
                pageTableRelease.complete(Unit)
                repeat(100) {
                    scene.render().close()
                    runCurrent()
                    Thread.sleep(2)
                }
                assertEquals(0, initialDemandChecks, "Claiming the full model must withdraw the pending initial demand")
                if (pendingControl != Key.Equals || pageTableFailure) assertControl()
                if (pageTableFailure) {
                    assertTrue(
                        model!!.state.value.session.activeChapter.loadState is
                            mihon.domain.reader.session.ReaderChapterLoadState.Error,
                    )
                } else {
                    assertEquals(if (replacePendingChapter) 42L else 41L, model!!.state.value.context.chapterId)
                    assertEquals(
                        if (replacePendingChapter) 4 else 6,
                        model!!.state.value.currentPage,
                        "Deferred turns must use their original restore target exactly once",
                    )
                }
                entry.onDispose()
                scene.close()
                assertTrue(owner.isClosed())
                return@runTest
            }
            if (earlyKeyboard) {
                runCurrent()
                listOf(Key.F11, Key.DirectionLeft, Key.DirectionLeft).forEach { key ->
                    scene.sendKeyEvent(keyEvent(key))
                }
                if (pendingInput) {
                    scene.render().close()
                    runCurrent()
                    assertTrue(session.state.value.snapshot.activeChapter.pages.isEmpty())
                    assertTrue(
                        entry.hasModel,
                        "Early controls must initialize the full reader without waiting for page table",
                    )
                    pageTableRelease.complete(Unit)
                }
            }
            var hadTargetPixels = false
            var heldPairingFrames = 0
            var heldGestureFrames = 0
            repeat(100) {
                scene.render(System.nanoTime()).use { image ->
                    val pixels = image.toComposeImageBitmap().asSkiaBitmap()
                    val blue = pixels.getColor(320, 240) == pageColor(if (earlyKeyboard) 6 else expectedPage).rgb
                    if (hadTargetPixels && expectStartup) {
                        assertTrue(
                            blue,
                            "Target pixels must survive model initialization and viewport handoff",
                        )
                    }
                    hadTargetPixels = hadTargetPixels || blue
                }
                runCurrent()
                if (activeGesture && model != null && heldGestureFrames < 4) {
                    assertTrue(nodes().any { it.config.contains(ReaderStartupSingleViewportKey) })
                    org.junit.jupiter.api.Assertions.assertSame(
                        gestureHolder,
                        nodes().single {
                            it.config.contains(ReaderPresentationHolderKey)
                        }.config[ReaderPresentationHolderKey],
                    )
                    heldGestureFrames++
                    if (heldGestureFrames == 4) {
                        scene.sendPointerEvent(PointerEventType.Release, listOf(pointer(2, 530f, false)))
                    }
                }
                if (delayedPairing && model != null && !pairingRelease.isCompleted) {
                    assertEquals(PairingLoad.LOADING, model!!.state.value.pairingLoad)
                    assertTrue(hadTargetPixels)
                    heldPairingFrames++
                    if (heldPairingFrames ==
                        2
                    ) {
                        activeScreen = screen.copy(chapterTitle = "Recomposed while pairing waits")
                    }
                    if (heldPairingFrames == 4) pairingRelease.complete(Unit)
                }
                Thread.sleep(2)
            }
            if (contentMissing) {
                assertTrue(
                    session.state.value.snapshot.activeChapter.pages[expectedPage].loadState is
                        mihon.domain.reader.session.ReaderPageLoadState.Ready,
                )
                val failed = nodes().single { it.config.contains(ReaderPresentationHolderKey) }
                    .config[ReaderPresentationHolderKey]
                assertTrue(
                    failed.state.value is mihon.desktop.reader.DesktopReaderPresentationImageState.Failed,
                    "Missing committed content must reach the original failure state: ${failed.state.value}",
                )
                assertTrue(progress.isEmpty())
                assertFalse(entry.hasModel)
                return@runTest
            }
            if (encodedFailure) {
                assertTrue(
                    session.state.value.snapshot.activeChapter.pages[expectedPage].loadState is
                        mihon.domain.reader.session.ReaderPageLoadState.Error,
                )
                assertTrue(progress.isEmpty())
                assertTrue(encodedReads.isEmpty())
                assertFalse(entry.hasModel)
                return@runTest
            }
            if (delayedPairing) assertEquals(4, heldPairingFrames)
            if (activeGesture) {
                assertEquals(4, heldGestureFrames)
                assertFalse(nodes().any { it.config.contains(ReaderStartupSingleViewportKey) })
                assertTrue(model!!.state.value.zoomState.scale > 1f)
                assertTrue(model!!.state.value.zoomState.offsetX != 0f)
            }
            if (!expectStartup && !pageTableFailure && restorePage != 999) {
                assertTrue(entry.hasModel)
                assertTrue(observation.snapshot().events.none { it.stage == "FIRST_COMPOSITION_TARGET_ADMITTED" })
                return@runTest
            }
            if (pageTableFailure) {
                val loadState = model!!.state.value.session.activeChapter.loadState
                assertTrue(loadState is mihon.domain.reader.session.ReaderChapterLoadState.Error)
                return@runTest
            }
            if (restorePage == 999) {
                assertTrue(model!!.state.value.resumePageUnavailable)
                assertEquals(0, model!!.state.value.currentPage)
                return@runTest
            }
            val events = observation.snapshot().events
            val applied = events.indexOfFirst { it.stage == "COMPOSE_SLOT_APPLY" && it.pageIndex == expectedPage }
            val fullModel = events.indexOfFirst { it.stage == "COMPOSE_MODEL_CREATE" }
            if (!switchGeneration && !changeSettings && !earlyKeyboard) {
                assertTrue(applied >= 0 && fullModel > applied, "Target holder must precede full model")
                assertTrue(events.any { it.stage == "STARTUP_SINGLE_VIEWPORT_APPLY" })
            } else {
                assertTrue(fullModel >= 0, "An invalid opening must reach the original full reader")
            }
            val modelBegin = events.single { it.stage == "COMPOSE_MODEL_CREATE" }
            val modelEnd = events.single { it.stage == "COMPOSE_MODEL_CREATE_END" }
            assertTrue(modelEnd.monotonicNanos >= modelBegin.monotonicNanos)
            assertEquals(session.state.value.snapshot.generation, modelBegin.generation)
            assertEquals(modelBegin.generation, modelEnd.generation)
            assertEquals(1, events.count { it.stage == "HOLDER_CREATE_BEGIN" && it.pageIndex == expectedPage })
            assertEquals(
                1,
                events.filter {
                    it.stage == "COMPOSE_HOLDER_BOUND" && it.pageIndex == expectedPage
                }.map { it.detail }.distinct().size,
            )
            assertEquals(session.state.value.snapshot.generation, owner.firstPresentedGeneration.value)
            assertEquals(if (earlyKeyboard) 6 else expectedPage, requireNotNull(model).state.value.currentPage)
            if (earlyKeyboard) assertTrue(model!!.state.value.isFullscreen)
            assertTrue(
                progress.contains(if (earlyKeyboard) 6 else expectedPage),
                "Actual draw must keep normal progress reporting",
            )
            if (!earlyKeyboard) {
                val ref = store.cacheRef(
                    session.state.value.snapshot.activeChapter.pages[expectedPage].id,
                    "fixture:$expectedPage",
                )
                assertTrue(store.contains(ref))
                assertEquals(1, encodedReads[ref], "Visual handoff must reuse the original encoded read and decode")
            }
            entry.onDispose()
            scene.close()
            assertTrue(owner.isClosed())
        } finally {
            runCatching(scene::close)
            entry.onDispose()
            runtime.close()
            Injekt = previous
            ReaderStartupTrace.clear(observation)
            legacy.removeNode()
        }
    }

    private fun keyEvent(key: Key, ctrl: Boolean = false): KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod("access\$getKeyDown\$cp").invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
        }
        return KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, ctrl, false, false, false, null))
    }

    @Test
    fun `cancel before full model closes unclaimed runtime exactly once and cannot revive`() {
        var creations = 0
        var closes = 0
        val entry = DesktopReaderScreenEntry(mockk(), { closes++ }) {
            creations++
            error("Cancelled entry must not construct a model")
        }
        assertFalse(entry.hasModel)
        entry.onDispose()
        entry.onDispose()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { entry.model() }
        assertEquals(0, creations)
        assertEquals(1, closes)
    }

    @Test
    fun `full model claims the same runtime entry and owns later disposal`() {
        val model = mockk<ReaderScreenModel>(relaxed = true)
        var creations = 0
        var unclaimedCloses = 0
        val entry = DesktopReaderScreenEntry(mockk(relaxed = true), { unclaimedCloses++ }) {
            creations++
            model
        }
        org.junit.jupiter.api.Assertions.assertSame(model, entry.model())
        org.junit.jupiter.api.Assertions.assertSame(model, entry.model())
        assertTrue(entry.hasModel)
        entry.onDispose()
        entry.onDispose()
        assertEquals(1, creations)
        assertEquals(0, unclaimedCloses)
        verify(exactly = 1) { model.onDispose() }
    }

    @Test
    fun `reread guard rejects a changed restore target or opening identity even in the same generation`() {
        val legacy = Preferences.userRoot().node("/mihon/reread-identity/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply { readingMode = ReadingMode.RTL }
        val reader = desktopReaderSessionState(pageCount = 187, initialPage = 4).let {
            it.copy(context = it.context.copy(isDownloaded = true))
        }
        val before = reader.copy(
            snapshot = reader.snapshot.copy(
                activeChapter = reader.snapshot.activeChapter.copy(
                    pages = emptyList(),
                    loadState = mihon.domain.reader.session.ReaderChapterLoadState.LoadingPageList,
                ),
            ),
        )
        val initial = initialReaderState(prefs, before, false, 0, null, false)
        try {
            org.junit.jupiter.api.Assertions.assertNotNull(
                rereadFirstCompositionState(initial, reader, prefs, false, 0, null),
            )
            listOf(
                reader.copy(context = reader.context.copy(initialPage = 186)),
                reader.copy(context = reader.context.copy(mangaId = 99)),
                reader.copy(context = reader.context.copy(chapterUrl = "/changed")),
                reader.copy(context = reader.context.copy(sourceId = 99)),
            ).forEach { latest ->
                org.junit.jupiter.api.Assertions.assertNull(
                    rereadFirstCompositionState(initial, latest, prefs, false, 0, null),
                )
            }
        } finally {
            legacy.removeNode()
        }
    }

    @Test
    fun `snapshot eligibility requires a resolved downloaded non split single page`() {
        val reader = desktopReaderSessionState(pageCount = 187, initialPage = 4)
        val state = ReaderState(
            context = reader.context.copy(isDownloaded = true),
            session = reader.snapshot,
            currentPage = 4,
            readingMode = ReadingMode.RTL,
            automaticLayout = false,
            dualPageMode = false,
        )
        assertTrue(firstCompositionSnapshotReady(state))
        assertTrue(
            firstCompositionSnapshotReady(
                state.copy(currentPage = 186, context = state.context.copy(initialPage = 186)),
            ),
        )
        listOf(
            state.copy(context = state.context.copy(isDownloaded = false)),
            state.copy(context = state.context.copy(initialPage = 187), resumePageUnavailable = true),
            state.copy(currentPage = 0), state.copy(automaticLayout = true), state.copy(dualPageMode = true),
            state.copy(autoSplitPages = true), state.copy(readingMode = ReadingMode.WEBTOON),
            state.copy(
                session = state.session.copy(activeChapter = state.session.activeChapter.copy(pages = emptyList())),
            ),
            state.copy(
                session = state.session.copy(
                    activeChapter = state.session.activeChapter.copy(
                        loadState = mihon.domain.reader.session.ReaderChapterLoadState.LoadingPageList,
                        pages = emptyList(),
                    ),
                ),
            ),
        ).forEach {
            assertFalse(firstCompositionSnapshotReady(it), "Unresolved snapshot must use the original path: $it")
        }
    }
}
