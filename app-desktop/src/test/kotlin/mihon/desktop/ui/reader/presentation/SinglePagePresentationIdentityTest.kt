package mihon.desktop.ui.reader.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.NavigationMode
import mihon.desktop.ui.reader.ReaderDisplayUnitCompositionIdentityKey
import mihon.desktop.ui.reader.ReaderDisplayUnitIdKey
import mihon.desktop.ui.reader.ReaderDisplayUnitLoadStateKey
import mihon.desktop.ui.reader.SinglePageDisplayUnitContainer
import mihon.desktop.ui.reader.SinglePageSettledVisiblePageReporter
import mihon.desktop.ui.reader.ZoomablePagerViewer
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class SinglePagePresentationIdentityTest {

    @Test
    fun `mounted container keeps identity while loading ready and error content changes in place`() = runTest {
        var unit by mutableStateOf(unit(ReaderPageLoadState.Queued))
        var retries = 0
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    SinglePageDisplayUnitContainer(
                        unit = unit,
                        onRetry = { retries++ },
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }

            scene.render()
            val loadingNode = displayUnitNode(scene)
            val identity = loadingNode.config[ReaderDisplayUnitCompositionIdentityKey]
            assertEquals(ReaderPageLoadState.Queued, loadingNode.config[ReaderDisplayUnitLoadStateKey])

            unit = unit(ReaderPageLoadState.Ready, imageUrl = "https://example.test/page.jpg")
            scene.render()
            val readyNode = displayUnitNode(scene)
            assertSame(identity, readyNode.config[ReaderDisplayUnitCompositionIdentityKey])
            assertEquals(ReaderPageLoadState.Ready, readyNode.config[ReaderDisplayUnitLoadStateKey])

            unit = unit(ReaderPageLoadState.Error(AppError.Network()))
            scene.render()
            val errorNode = displayUnitNode(scene)
            assertSame(identity, errorNode.config[ReaderDisplayUnitCompositionIdentityKey])
            assertEquals(unit.id, errorNode.config[ReaderDisplayUnitIdKey])
            assertEquals(unit.slots.single().page?.loadState, errorNode.config[ReaderDisplayUnitLoadStateKey])
            val retryAction = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }
                .config[SemanticsActions.OnClick]
                .action
            assertTrue(requireNotNull(retryAction).invoke())
            assertEquals(1, retries)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `production single-page selector mounts the SPI display unit`() = runTest {
        var chapter by mutableStateOf(
            readerChapterSession(chapterId.value, 11, pageCount = 1) {
                ReaderPageLoadState.Error(AppError.Network())
            },
        )
        var retries = 0
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ZoomablePagerViewer(
                        chapter = chapter,
                        currentPage = 0,
                        isRtl = false,
                        isDualPage = false,
                        zoomState = ZoomState(),
                        navigationMode = NavigationMode.RightAndLeft,
                        onPageChange = {},
                        onZoomChange = {},
                        onRetryPage = { retries++ },
                    )
                }
            }

            scene.render()

            val errorNode = displayUnitNode(scene)
            val unitId = errorNode.config[ReaderDisplayUnitIdKey]
            val identity = errorNode.config[ReaderDisplayUnitCompositionIdentityKey]
            assertEquals(ReaderPresentationMode.SINGLE_PAGED, unitId.mode)
            assertEquals(pageId, unitId.slots.single().pageId)
            assertEquals(ReaderPageLoadState.Error(AppError.Network()), errorNode.config[ReaderDisplayUnitLoadStateKey])

            val retryAction = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }
                .config[SemanticsActions.OnClick]
                .action
            assertTrue(requireNotNull(retryAction).invoke())
            assertEquals(1, retries)

            chapter = readerChapterSession(
                chapterId = chapterId.value,
                generation = 11,
                pageCount = 1,
                pageLoadState = { ReaderPageLoadState.Queued },
            )
            scene.render()
            val retryLoadingNode = displayUnitNode(scene)
            assertSame(identity, retryLoadingNode.config[ReaderDisplayUnitCompositionIdentityKey])
            assertEquals(ReaderPageLoadState.Queued, retryLoadingNode.config[ReaderDisplayUnitLoadStateKey])
        } finally {
            scene.close()
        }
    }

    @Test
    fun `visible-page reporting waits for the settled pager index`() = runTest {
        val request = ReaderPresentationRequest(
            chapter = readerChapterSession(chapterId.value, 11, pageCount = 2),
            direction = ReaderDirection.LTR,
        )
        val presentation = SinglePagedPresentation.present(request)
        var transientCurrentIndex by mutableStateOf(0)
        var settledIndex by mutableStateOf(0)
        val reports = mutableListOf<VisiblePageSet>()
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                transientCurrentIndex // Force recomposition while a drag changes currentPage.
                SinglePageSettledVisiblePageReporter(
                    presentation = presentation,
                    isRtl = false,
                    settledPagerIndex = { settledIndex },
                    onVisiblePagesChanged = reports::add,
                )
            }

            scene.render()
            yield()
            assertEquals(listOf(setOf(request.chapter.pages[0].id)), reports.map(VisiblePageSet::pageIds))

            transientCurrentIndex = 1
            scene.render()
            yield()
            assertEquals(1, reports.size, "A transient current page must not be reported before pager settlement")

            settledIndex = 1
            scene.render()
            yield()
            assertEquals(
                listOf(setOf(request.chapter.pages[0].id), setOf(request.chapter.pages[1].id)),
                reports.map(VisiblePageSet::pageIds),
            )
        } finally {
            scene.close()
        }
    }

    private fun unit(loadState: ReaderPageLoadState, imageUrl: String? = null): DisplayUnit {
        val chapter = ReaderChapterSession(
            id = chapterId,
            generation = 11,
            loadState = ReaderChapterLoadState.Loaded,
            pages = listOf(
                ReaderPageSession(
                    id = pageId,
                    url = "/page/0",
                    imageUrl = imageUrl,
                    encodedPageRef = null,
                    loadState = loadState,
                ),
            ),
        )
        return SinglePagedPresentation.present(
            ReaderPresentationRequest(chapter = chapter, direction = ReaderDirection.LTR),
        ).displayUnits.single()
    }

    private fun displayUnitNode(scene: ImageComposeScene): SemanticsNode =
        nodes(scene).single { it.config.contains(ReaderDisplayUnitCompositionIdentityKey) }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private companion object {
        val chapterId = ReaderChapterId(41L)
        val pageId = ReaderPageId(chapterId, 0)
    }
}
