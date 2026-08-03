package mihon.desktop.ui.reader.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import mihon.desktop.reader.ZoomState
import mihon.desktop.ui.reader.DualPageDisplayUnitCompositionIdentityKey
import mihon.desktop.ui.reader.DualPageDisplayUnitFrame
import mihon.desktop.ui.reader.DualPageDisplayUnitIdKey
import mihon.desktop.ui.reader.DualPagePhysicalSlot
import mihon.desktop.ui.reader.DualPagePhysicalSlotKey
import mihon.desktop.ui.reader.DualPageSlotIdKey
import mihon.desktop.ui.reader.NavigationMode
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
class DualPagePresentationIdentityTest {

    @Test
    fun `mounted cover keeps a centered two-slot frame with the page in the physical left slot`() = runTest {
        for (ambientDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (readerDirection in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
                val unit = snapshot(readerDirection, ReaderPageLoadState.Ready).displayUnits.first()
                val scene = ImageComposeScene(1_600, 900, coroutineContext = currentCoroutineContext()) {}
                try {
                    scene.setContent {
                        MaterialTheme {
                            CompositionLocalProvider(LocalLayoutDirection provides ambientDirection) {
                                DualPageDisplayUnitFrame(unit = unit, onRetry = {}) { _, modifier ->
                                    Box(modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    scene.render()

                    val frame = node(scene) { it.config.contains(DualPageDisplayUnitIdKey) }.boundsInRoot
                    val left = slotNode(scene, DualPagePhysicalSlot.LEFT)
                    val right = slotNode(scene, DualPagePhysicalSlot.RIGHT)
                    assertCentered(frame, viewportWidth = 1_600f)
                    assertTrue(frame.left > 0f)
                    assertTrue(frame.right < 1_600f)
                    assertEquals(frame.left, left.boundsInRoot.left)
                    assertEquals(frame.center.x, left.boundsInRoot.right)
                    assertEquals(frame.center.x, right.boundsInRoot.left)
                    assertEquals(frame.right, right.boundsInRoot.right)
                    assertEquals(pageId(0), left.config[DualPageSlotIdKey].pageId)
                    assertEquals(null, right.config[DualPageSlotIdKey].pageId)
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun `pair frame identity survives either page loading ready and error changes`() = runTest {
        var unit by mutableStateOf(pairUnit(ReaderPageLoadState.Queued, ReaderPageLoadState.Queued))
        val retriedPageIds = mutableListOf<ReaderPageId>()
        val scene = ImageComposeScene(1_600, 900, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    DualPageDisplayUnitFrame(unit = unit, onRetry = retriedPageIds::add) { _, modifier ->
                        Box(modifier.fillMaxSize())
                    }
                }
            }
            scene.render()
            val loadingNode = node(scene) { it.config.contains(DualPageDisplayUnitCompositionIdentityKey) }
            val identity = loadingNode.config[DualPageDisplayUnitCompositionIdentityKey]
            val id = loadingNode.config[DualPageDisplayUnitIdKey]
            val slotIds = unit.slots.map(DisplaySlot::id)

            unit = pairUnit(ReaderPageLoadState.Ready, ReaderPageLoadState.Queued)
            scene.render()
            assertSame(identity, node(scene) { it.config.contains(DualPageDisplayUnitCompositionIdentityKey) }
                .config[DualPageDisplayUnitCompositionIdentityKey])
            assertEquals(id, node(scene) { it.config.contains(DualPageDisplayUnitIdKey) }.config[DualPageDisplayUnitIdKey])
            assertEquals(slotIds, unit.slots.map(DisplaySlot::id))

            unit = pairUnit(ReaderPageLoadState.Ready, ReaderPageLoadState.Error(AppError.Network()))
            scene.render()
            assertSame(identity, node(scene) { it.config.contains(DualPageDisplayUnitCompositionIdentityKey) }
                .config[DualPageDisplayUnitCompositionIdentityKey])
            assertEquals(slotIds, unit.slots.map(DisplaySlot::id))
            clickSingleRetry(scene)
            assertEquals(listOf(pageId(2)), retriedPageIds)

            unit = pairUnit(ReaderPageLoadState.Error(AppError.Network()), ReaderPageLoadState.Ready)
            scene.render()
            assertSame(identity, node(scene) { it.config.contains(DualPageDisplayUnitCompositionIdentityKey) }
                .config[DualPageDisplayUnitCompositionIdentityKey])
            assertEquals(slotIds, unit.slots.map(DisplaySlot::id))
            clickSingleRetry(scene)
            assertEquals(listOf(pageId(2), pageId(1)), retriedPageIds)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `production dual selector mounts registry display units`() = runTest {
        val scene = ImageComposeScene(1_600, 900, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ZoomablePagerViewer(
                        chapterId = chapterId.value,
                        loadGeneration = 19,
                        pageUrls = listOf(""),
                        currentPage = 0,
                        isRtl = true,
                        isDualPage = true,
                        zoomState = ZoomState(),
                        navigationMode = NavigationMode.RightAndLeft,
                        onPageChange = {},
                        onZoomChange = {},
                    )
                }
            }
            scene.render()

            val unitId = node(scene) { it.config.contains(DualPageDisplayUnitIdKey) }.config[DualPageDisplayUnitIdKey]
            assertEquals(ReaderPresentationMode.DUAL_PAGED, unitId.mode)
            assertEquals(pageId(0), unitId.slots.first().pageId)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `visible-page reporting waits for settled pager and reports both pair pages`() = runTest {
        val presentation = snapshot(ReaderDirection.LTR, ReaderPageLoadState.Ready)
        var transientCurrent by mutableStateOf(0)
        var settled by mutableStateOf(0)
        val reports = mutableListOf<VisiblePageSet>()
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                transientCurrent
                mihon.desktop.ui.reader.DualPageSettledVisiblePageReporter(
                    presentation = presentation,
                    isRtl = false,
                    settledPagerIndex = { settled },
                    onVisiblePagesChanged = reports::add,
                )
            }
            scene.render()
            yield()
            assertEquals(listOf(setOf(pageId(0))), reports.map(VisiblePageSet::pageIds))

            transientCurrent = 1
            scene.render()
            yield()
            assertEquals(1, reports.size)

            settled = 1
            scene.render()
            yield()
            assertEquals(setOf(pageId(1), pageId(2)), reports.last().pageIds)
            assertEquals(pageId(2), reports.last().activePageId)
        } finally {
            scene.close()
        }
    }

    private fun snapshot(
        direction: ReaderDirection,
        state: ReaderPageLoadState,
    ): ReaderPresentationSnapshot = snapshot(direction) { state }

    private fun snapshot(
        direction: ReaderDirection,
        stateAt: (Int) -> ReaderPageLoadState,
    ): ReaderPresentationSnapshot = DualPagedPresentation.present(
        ReaderPresentationRequest(
            chapter = ReaderChapterSession(
                chapterId,
                19,
                ReaderChapterLoadState.Loaded,
                List(5) { index ->
                    val state = stateAt(index)
                    ReaderPageSession(
                        pageId(index),
                        "/page/$index",
                        "ready-$index".takeIf { state == ReaderPageLoadState.Ready },
                        null,
                        state,
                    )
                },
            ),
            direction = direction,
            dualPagedOptions = DualPagedPresentationOptions(),
        ),
    )

    private fun pairUnit(
        leftState: ReaderPageLoadState,
        rightState: ReaderPageLoadState,
    ): DisplayUnit = snapshot(ReaderDirection.LTR) { index ->
        when (index) {
            1 -> leftState
            2 -> rightState
            else -> ReaderPageLoadState.Ready
        }
    }.displayUnits[1]

    private fun clickSingleRetry(scene: ImageComposeScene) {
        val retry = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }
            .config[SemanticsActions.OnClick]
            .action
        assertTrue(requireNotNull(retry).invoke())
    }

    private fun slotNode(scene: ImageComposeScene, slot: DualPagePhysicalSlot): SemanticsNode =
        node(scene) { it.config.contains(DualPagePhysicalSlotKey) && it.config[DualPagePhysicalSlotKey] == slot }

    private fun node(scene: ImageComposeScene, predicate: (SemanticsNode) -> Boolean): SemanticsNode =
        nodes(scene).single(predicate)

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun assertCentered(bounds: Rect, viewportWidth: Float) {
        assertEquals(viewportWidth / 2f, bounds.center.x, 0.5f)
    }

    private fun pageId(index: Int) = ReaderPageId(chapterId, index)

    private companion object {
        val chapterId = ReaderChapterId(89L)
    }
}
