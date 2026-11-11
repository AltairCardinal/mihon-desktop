package mihon.desktop.ui.reader

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopChapterPairingCoordinator
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderChapterOpeningMode
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.concurrent.CopyOnWriteArraySet
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderInvalidSyncResumeExposureTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mounted first pair exposes and draws only the ready partner before explicit resume confirmation`(
        matching: Boolean,
    ) = runTest {
        val root = Preferences.userRoot().node("/mihon/invalid-resume-exposure/${System.nanoTime()}")
        val preferences = ReaderPreferences(DesktopPreferenceStore(root.node("current")), root.node("legacy")).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            autoSplitPages = false
            isAutoSpreadMatching = matching
        }
        val initial = desktopReaderSessionState(chapterId = 7L, pageCount = 2, initialPage = 10) { index ->
            if (index == 0) ReaderPageLoadState.Queued else ReaderPageLoadState.Ready
        }
        val reader = initial.copy(context = initial.context.copy(mangaId = 1))
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long) = ChapterPairingSnapshot(
                ChapterPairingRecord(2, 2, emptySet(), openingMode = ReaderChapterOpeningMode.FIRST_PAIR),
                1,
            )
            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot = error("Opening an existing first pair cannot write its pairing")
        }
        val recorded = mutableListOf<Pair<Set<ReaderPageId>, ReaderPageId>>()
        val model = ReaderScreenModel(
            initialSessionState = reader,
            dualPageOverride = true,
            prefs = preferences,
            pairingCoordinator = DesktopChapterPairingCoordinator(repository, backgroundScope),
            onViewportSettled = { pages, active -> recorded += pages to active },
        )
        val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        val content = DesktopReaderPageContentOwner(this, { byteArrayOf(1) }, reporter)
        val decoded = CopyOnWriteArraySet<Int>()
        val presented = CopyOnWriteArraySet<ReaderPageId>()
        val pipeline = DesktopReaderPageImagePipeline(
            this,
            content,
            reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decoded += key.pageIndex
                check(key.pageIndex == 1) { "The queued logical target cannot have decoded pixels" }
                val bitmap = Bitmap().apply { allocN32Pixels(40, 60) }
                Canvas(bitmap).use { it.clear(MAGENTA) }
                DesktopReaderImageAsset(
                    bitmap.asComposeImageBitmap(),
                    40,
                    60,
                    9600L,
                    false,
                    disposer = bitmap::close,
                )
            },
        )
        val owner = DesktopReaderPresentationImageOwner(
            this,
            pipeline,
            ReaderPageIoObserver(reporter) { page, _ -> presented += page },
        )
        val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {}
        var time = 0L
        var partnerPixels = false
        fun frame() {
            time += 16_000_000L
            scene.render(time).use { image ->
                val bitmap = image.toComposeImageBitmap().asSkiaBitmap()
                try {
                    partnerPixels = bitmap.getColor(100, 200) == MAGENTA ||
                        bitmap.getColor(300, 200) == MAGENTA
                } finally {
                    bitmap.close()
                }
            }
            runCurrent()
            advanceTimeBy(16)
            Thread.sleep(2)
        }
        val target = reader.snapshot.activeChapter.pages[0].id
        val partner = reader.snapshot.activeChapter.pages[1].id
        try {
            runCurrent()
            reader.snapshot.activeChapter.pages.forEach { page ->
                assertTrue(
                    model.acceptDualFact(
                        requireNotNull(model.captureDualFact(page.id)),
                        ReaderPageFact.dimensions(40, 60),
                    ),
                )
            }
            owner.beginGeneration(reader.snapshot.generation)
            scene.setContent {
                val state by model.state.collectAsState()
                val binding = remember(model, state.dualGeometryRevision) {
                    ReaderPagerPresentationBinding(Any(), model)
                }
                DualPagePagerViewer(
                    presentation = state.dualPresentationSnapshot(false, false),
                    pagerPresentationBinding = binding,
                    currentPageId = target,
                    currentDisplayUnitId = state.currentDisplayUnitId,
                    isRtl = true,
                    zoomState = state.zoomState,
                    presentationImageOwner = owner,
                    onVisiblePagesChanged = {},
                    onZoomChange = model::setZoomState,
                    onRetryPage = model::retryPage,
                    generation = state.session.generation,
                    pageTurnAnimation = false,
                    allowAdjacentViewport = false,
                )
            }
            repeat(80) { frame() }
            val presenters = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .filter { it.config.contains(ReaderPagerNativePresentationKey) }
                .map { it.config[ReaderPagerNativePresentationKey] }
            assertTrue(
                presenters.any {
                    "exposed=true" in it.describe()
                },
                "Real layout must expose the shared native mount",
            )
            assertTrue(partnerPixels, "The mounted production image must paint the partner's magenta pixels")
            assertEquals(setOf(1), decoded)
            assertEquals(setOf(partner), presented)
            assertEquals(ReaderPageLoadState.Queued, model.state.value.session.activeChapter.pages[0].loadState)
            assertEquals(setOf(partner), model.state.value.visiblePageIds)
            assertTrue(model.state.value.resumePageUnavailable)
            assertTrue(recorded.isEmpty(), "Drawing a fallback cannot silently overwrite synchronized progress")
            repeat(8) {
                frame()
                assertTrue(partnerPixels, "Every stable frame before confirmation must retain the partner pixels")
                assertEquals(ReaderPageLoadState.Queued, model.state.value.session.activeChapter.pages[0].loadState)
                assertEquals(setOf(partner), presented)
                assertTrue(recorded.isEmpty())
            }
            assertTrue(model.confirmSyncResumePosition(0))
            assertEquals(listOf(setOf(partner) to partner), recorded)
            repeat(8) {
                frame()
                assertTrue(partnerPixels, "Every stable frame after confirmation must retain the partner pixels")
                assertEquals(ReaderPageLoadState.Queued, model.state.value.session.activeChapter.pages[0].loadState)
                assertEquals(setOf(partner), presented)
                assertEquals(listOf(setOf(partner) to partner), recorded, "Later idle draws cannot replay confirmation")
            }
        } finally {
            scene.close()
            owner.close()
            pipeline.close()
            content.close()
            model.onDispose()
            root.removeNode()
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private companion object {
        const val MAGENTA = -65281
    }
}
