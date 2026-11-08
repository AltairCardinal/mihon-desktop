package mihon.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
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
    fun `first composition without published page list stays on original path when it later arrives`(
        @TempDir dir: File,
    ) =
        screenScenario(dir, loadedInitially = false)

    private fun screenScenario(dir: File, loadedInitially: Boolean) = runTest {
        val legacy = Preferences.userRoot().node("/mihon/first-composition/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply { readingMode = ReadingMode.RTL }
        val context = desktopReaderSessionState(chapterId = 41, pageCount = 187, initialPage = 4).context.copy(
            isDownloaded = true,
            mangaId = 7,
        )
        val core = ReaderSessionCore(ReaderChapterId(41), sessionId = "first-composition-screen")
        val reporter = ReaderIoReporter(clock = ReaderMonotonicClock(System::nanoTime))
        val png = ByteArrayOutputStream().also { out ->
            ImageIO.write(
                BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply {
                    createGraphics().apply {
                        color = Color.BLUE
                        fillRect(0, 0, 8, 8)
                        dispose()
                    }
                },
                "png",
                out,
            )
        }.toByteArray()
        val contentOwner = DesktopReaderPageContentOwner(this, { png }, reporter)
        val pipeline = DesktopReaderPageImagePipeline(this, contentOwner, reporter)
        val owner = DesktopReaderPresentationImageOwner(this, pipeline, ReaderPageIoObserver(reporter))
        val store = DesktopReaderEncodedPageStore(dir.resolve("encoded"))
        val progress = mutableListOf<Int>()
        val session = DesktopReaderSession(
            initialContext = context,
            core = core,
            encodedPageStore = store,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort {
                    List(187) { ReaderPageDescriptor(it, url = "/page/$it", imageUrl = "https://fixture.test/$it.png") }
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, descriptor ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(
                        request: ReaderPageFetchRequest,
                    ) = requireNotNull(request.imageUrl)
                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null
                    override suspend fun fetchEncodedPage(
                        request: ReaderPageFetchRequest,
                    ) = EncodedPageRef("fixture:${descriptor.sourcePageIndex}")
                }
            },
            progressPort = DesktopReaderProgressPort { _, effect -> progress += effect.lastPageRead },
            parentScope = this,
            onGenerationPublished = owner::beginGeneration,
        )
        val runtime = DesktopReaderRuntime(prefs, pipeline, owner, contentOwner, session, store, Job())
        val realFactory = DesktopReaderRuntimeFactory(
            prefs,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            null,
            dir.resolve("factory-cache"),
        )
        var model: ReaderScreenModel? = null
        val entry = DesktopReaderScreenEntry(runtime, runtime::close) {
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
            initialPage = 4,
            isRtl = true,
        )
        val observation = ReaderStartupObservation()
        val previous = Injekt
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            session.start()
            if (loadedInitially) {
                runCurrent()
                assertEquals(187, session.state.value.snapshot.activeChapter.pages.size)
            }
            ReaderStartupTrace.install(observation)
            patchInjekt()
            Injekt.addSingleton(factory)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { screen.Content() }
                }
            }
            scene.render(System.nanoTime()).close()
            repeat(50) {
                scene.render(System.nanoTime()).close()
                runCurrent()
                Thread.sleep(2)
            }
            val events = observation.snapshot().events
            val applied = events.indexOfFirst { it.stage == "COMPOSE_SLOT_APPLY" && it.pageIndex == 4 }
            val fullModel = events.indexOfFirst { it.stage == "COMPOSE_MODEL_CREATE" }
            if (loadedInitially) {
                val success = events.single { it.stage == "FIRST_COMPOSITION_SNAPSHOT_READY" }
                assertEquals(4, success.pageIndex)
                assertEquals(session.state.value.snapshot.generation, success.generation)
                assertTrue(
                    applied >= 0 && fullModel > applied,
                    "Actual target holder must apply before actual full model constructor",
                )
            } else {
                assertEquals(1, events.count { it.stage == "FIRST_COMPOSITION_FALLBACK" })
                assertEquals(0, events.count { it.stage == "FIRST_COMPOSITION_SNAPSHOT_READY" })
                assertEquals(0, events.count { it.stage == "STARTUP_SINGLE_VIEWPORT_APPLY" })
                assertTrue(
                    fullModel >= 0 && applied > fullModel,
                    "Unready first composition must keep the original model-first path",
                )
            }
            assertEquals(1, events.count { it.stage == "HOLDER_CREATE_BEGIN" && it.pageIndex == 4 })
            assertEquals(
                1,
                events.filter {
                    it.stage == "COMPOSE_HOLDER_BOUND" && it.pageIndex == 4
                }.map { it.detail }.distinct().size,
            )
            assertEquals(session.state.value.snapshot.generation, owner.firstPresentedGeneration.value)
            assertEquals(4, requireNotNull(model).state.value.currentPage)
            assertTrue(progress.contains(4), "Actual draw must keep normal progress reporting")
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
        val entry = DesktopReaderScreenEntry(mockk(), { unclaimedCloses++ }) {
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
