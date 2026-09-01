@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package mihon.desktop.ui.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderChapterContentPortFactory
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
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.partial.PartialReaderPageCandidate
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.session.ReaderSessionCore
import mihon.domain.reader.session.ReaderSessionSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences

class DesktopReaderPartialFeedbackTest {

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `mounted production reader renders localized partial download snackbar once`(@TempDir tempDir: File) = runTest {
        val fixture = partialSession(
            chapterId = 7L,
            readerGeneration = 1L,
            attemptGeneration = 11L,
            localPages = 2,
            totalPages = 5,
        )
        val core = ReaderSessionCore(ReaderChapterId(7L), sessionId = "partial-feedback-ui")
        val opening = core.openChapter(ReaderChapterId(7L))
        core.acceptChapterMaterialization(
            chapterId = ReaderChapterId(7L),
            generation = opening.snapshot.generation,
            result = ReaderChapterMaterializeResult.Loaded(
                fixture.snapshot.activeChapter.pages.map { page ->
                    ReaderPageDescriptor(
                        sourcePageIndex = page.id.sourcePageIndex,
                        url = page.url,
                        imageUrl = page.imageUrl,
                        partialPageCandidate = page.partialPageCandidate,
                    )
                },
            ),
        )
        val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded"))
        val session = DesktopReaderSession(
            initialContext = fixture.context,
            core = core,
            encodedPageStore = store,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort { error("The mounted fixture already owns a stable page table") }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, _ ->
                error("The UI feedback assertion does not settle or fetch a page")
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
        )
        val legacy = Preferences.userRoot().node("/mihon/partial-feedback-ui/${System.nanoTime()}")
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
        val model = ReaderScreenModel(
            prefs = prefs,
            initialSessionState = session.state.value,
            runtime = runtime,
        )
        val factory = mockk<DesktopReaderRuntimeFactory> {
            every { createScreenModel(any(), any(), any(), any(), any(), any()) } returns model
        }
        val screen = DesktopReaderScreen(
            chapterTitle = fixture.context.chapterTitle,
            mangaTitle = fixture.context.mangaTitle,
            sourceId = fixture.context.sourceId,
            chapterUrl = fixture.context.chapterUrl,
            chapterId = fixture.context.chapterId,
        )
        val expected = MR.strings.desktop_ui_reader_partial_download.localized(Locale.getDefault(), 2, 5)
        val previousInjekt = Injekt
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            patchInjekt()
            Injekt.addSingleton(factory)
            scene.setContent { Navigator(screen) { screen.Content() } }

            render(scene)
            assertEquals(1, texts(scene).count { it == expected })

            val dismiss = nodes(scene).single { it.config.contains(SemanticsActions.Dismiss) }
            assertTrue(requireNotNull(dismiss.config[SemanticsActions.Dismiss].action).invoke())
            render(scene)
            assertNull(model.state.value.partialDownloadNotice)
            assertTrue(expected !in texts(scene))

            model.acceptSessionState(session.state.value)
            render(scene)
            assertTrue(expected !in texts(scene), "The same chapter-generation must not remount the Snackbar")
        } finally {
            runCatching(scene::close)
            runtime.close()
            Injekt = previousInjekt
            legacy.removeNode()
        }
    }

    @Test
    fun `partial progress is emitted once for one chapter generation and does not reappear after dismissal`() {
        val initial = partialSession(chapterId = 7L, readerGeneration = 3L, attemptGeneration = 11L, localPages = 2, totalPages = 5)
        val model = ReaderScreenModel(initialSessionState = initial)

        val notice = checkNotNull(model.state.value.partialDownloadNotice)
        assertEquals(2, notice.downloadedPages)
        assertEquals(5, notice.totalPages)
        assertEquals(11L, notice.attemptGeneration)

        model.acceptSessionState(initial.copy(snapshot = initial.snapshot.copy()))
        assertEquals(notice, model.state.value.partialDownloadNotice)

        model.dismissPartialDownloadNotice(notice.id)
        assertNull(model.state.value.partialDownloadNotice)

        model.acceptSessionState(initial)
        assertNull(model.state.value.partialDownloadNotice, "The same chapter-generation must not emit the prompt twice")
    }

    @Test
    fun `chapter activation can emit one new partial progress prompt without collecting filesystem state`() {
        val first = partialSession(chapterId = 7L, readerGeneration = 3L, attemptGeneration = 11L, localPages = 1, totalPages = 4)
        val second = partialSession(chapterId = 8L, readerGeneration = 4L, attemptGeneration = 12L, localPages = 3, totalPages = 6)
        val model = ReaderScreenModel(initialSessionState = first)
        model.dismissPartialDownloadNotice(checkNotNull(model.state.value.partialDownloadNotice).id)

        model.acceptSessionState(second)

        val notice = checkNotNull(model.state.value.partialDownloadNotice)
        assertEquals(8L, notice.chapterId)
        assertEquals(3, notice.downloadedPages)
        assertEquals(6, notice.totalPages)
    }

    @Test
    fun `fully local chapter does not claim that remaining pages need the network`() {
        val complete = partialSession(
            chapterId = 9L,
            readerGeneration = 5L,
            attemptGeneration = 13L,
            localPages = 4,
            totalPages = 4,
        )

        assertNull(ReaderScreenModel(initialSessionState = complete).state.value.partialDownloadNotice)
    }

    @Test
    fun `stable reader generation evaluates its immutable page table only once`() {
        val online = partialSession(
            chapterId = 10L,
            readerGeneration = 6L,
            attemptGeneration = 14L,
            localPages = 0,
            totalPages = 180,
        )
        val model = ReaderScreenModel(initialSessionState = online)

        model.acceptSessionState(
            partialSession(
                chapterId = 10L,
                readerGeneration = 6L,
                attemptGeneration = 14L,
                localPages = 90,
                totalPages = 180,
            ),
        )
        assertNull(model.state.value.partialDownloadNotice)

        model.acceptSessionState(
            partialSession(
                chapterId = 10L,
                readerGeneration = 7L,
                attemptGeneration = 14L,
                localPages = 90,
                totalPages = 180,
            ),
        )
        assertEquals(90, checkNotNull(model.state.value.partialDownloadNotice).downloadedPages)
    }

    private fun partialSession(
        chapterId: Long,
        readerGeneration: Long,
        attemptGeneration: Long,
        localPages: Int,
        totalPages: Int,
    ): DesktopReaderSessionState {
        val chapter = ReaderChapterId(chapterId)
        val pages = (0 until totalPages).map { ordinal ->
            ReaderPageSession(
                id = ReaderPageId(chapter, ordinal),
                url = "/chapter/$chapterId/page/$ordinal",
                imageUrl = "https://fixture.invalid/$chapterId/$ordinal.jpg",
                encodedPageRef = null,
                loadState = ReaderPageLoadState.Queued,
                partialPageCandidate = if (ordinal < localPages) {
                    PartialReaderPageCandidate(
                        attemptGeneration = attemptGeneration,
                        readerOrdinal = ordinal,
                        sourcePageIndex = ordinal,
                        opaqueLocation = "opaque://partial/$chapterId/$ordinal",
                        committedRevision = ordinal + 1L,
                    )
                } else {
                    null
                },
            )
        }
        return DesktopReaderSessionState(
            context = DesktopReaderChapterContext(
                chapterId = chapterId,
                sourceId = 42L,
                chapterUrl = "/chapter/$chapterId",
                mangaTitle = "Manga",
                chapterTitle = "Chapter $chapterId",
                chapterNumber = chapterId.toDouble(),
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            snapshot = ReaderSessionSnapshot(
                generation = readerGeneration,
                activeChapter = ReaderChapterSession(
                    id = chapter,
                    generation = readerGeneration,
                    loadState = ReaderChapterLoadState.Loaded,
                    pages = pages,
                ),
            ),
        )
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.render(scene: ImageComposeScene) {
        repeat(6) {
            scene.render()
            runCurrent()
        }
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
