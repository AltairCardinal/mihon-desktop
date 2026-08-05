package mihon.desktop.ui.reader.presentation

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.WebtoonDisplayUnitCompositionIdentityKey
import mihon.desktop.ui.reader.WebtoonDisplayUnitContainer
import mihon.desktop.ui.reader.WebtoonDisplayUnitIdKey
import mihon.desktop.ui.reader.WebtoonDisplayUnitLoadStateKey
import mihon.desktop.ui.reader.WebtoonDisplayUnitList
import mihon.desktop.ui.reader.WebtoonPresentationViewer
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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class WebtoonPresentationIdentityTest {

    @Test
    fun `webtoon item keeps composition identity while loading ready error and retry change in place`() = runTest {
        var unit by mutableStateOf(unit(ReaderPageLoadState.Queued))
        var retries = 0
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    WebtoonDisplayUnitContainer(
                        unit = unit,
                        sidePadding = WebtoonSidePadding.NONE,
                        onRetry = { retries++ },
                    ) { _, modifier ->
                        Box(modifier.fillMaxSize())
                    }
                }
            }

            scene.render()
            val loading = unitNode(scene)
            val identity = loading.config[WebtoonDisplayUnitCompositionIdentityKey]

            unit = unit(ReaderPageLoadState.Ready, "https://example.test/page.jpg")
            scene.render()
            assertSame(identity, unitNode(scene).config[WebtoonDisplayUnitCompositionIdentityKey])

            unit = unit(ReaderPageLoadState.Error(AppError.Network()))
            scene.render()
            val error = unitNode(scene)
            assertSame(identity, error.config[WebtoonDisplayUnitCompositionIdentityKey])
            assertEquals(ReaderPageLoadState.Error(AppError.Network()), error.config[WebtoonDisplayUnitLoadStateKey])
            val retry = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }
                .config[SemanticsActions.OnClick]
                .action
            assertTrue(requireNotNull(retry).invoke())
            assertEquals(1, retries)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `production webtoon selector mounts registry display units with stable lazy identities`() = runTest {
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    WebtoonPresentationViewer(
                        chapter = readerChapterSession(
                            chapterId = chapterId.value,
                            generation = 3,
                            pageCount = 2,
                            pageLoadState = { ReaderPageLoadState.Queued },
                        ),
                        currentPage = 0,
                        currentDisplayUnitId = null,
                        initialAnchor = null,
                        onViewportChanged = {},
                    )
                }
            }

            scene.render()
            val unitIds = nodes(scene)
                .filter { it.config.contains(WebtoonDisplayUnitIdKey) }
                .map { it.config[WebtoonDisplayUnitIdKey] }

            assertTrue(unitIds.isNotEmpty())
            assertTrue(unitIds.all { it.mode == ReaderPresentationMode.WEBTOON })
            assertEquals(pageId, unitIds.first().slots.single().pageId)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted list restores relative anchor when ready content changes item geometry`() = runTest {
        val presentation = snapshot(split = false)
        val anchor = WebtoonScrollAnchor(presentation.displayUnits.first().id, scrollOffset = 900, itemSize = 1_200)
        var firstHeight by mutableIntStateOf(1_200)
        var latestUpdate: WebtoonViewportUpdate? = null
        val listState = LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 900)
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    WebtoonDisplayUnitList(
                        presentation = presentation,
                        currentPageId = pageId,
                        currentDisplayUnitId = presentation.displayUnits.first().id,
                        initialAnchor = anchor,
                        sidePadding = WebtoonSidePadding.NONE,
                        autoScroll = false,
                        autoScrollSpeed = mihon.desktop.ui.reader.WebtoonAutoScrollSpeed.Normal,
                        listStateOverride = listState,
                        onViewportChanged = { latestUpdate = it },
                        onRetryPage = {},
                    ) { slot, modifier ->
                        val height = if (slot.page?.id == pageId) firstHeight else 600
                        Box(modifier.height(height.dp))
                    }
                }
            }

            scene.render()
            advanceUntilIdle()
            scene.render()
            firstHeight = 300
            repeat(4) {
                scene.render()
                advanceUntilIdle()
            }

            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(225, listState.firstVisibleItemScrollOffset)
            assertEquals(pageId, latestUpdate?.anchor?.displayUnitId?.slots?.single()?.pageId)
            assertEquals(225, latestUpdate?.anchor?.scrollOffset)
            assertEquals(300, latestUpdate?.anchor?.itemSize)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted list keeps logical page and bounded offset when split anchor merges`() = runTest {
        var presentation by mutableStateOf(snapshot(split = true))
        val splitAnchor = WebtoonScrollAnchor(presentation.displayUnits[1].id, scrollOffset = 900, itemSize = 1_200)
        var latestUpdate: WebtoonViewportUpdate? = null
        val listState = LazyListState(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 900)
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    WebtoonDisplayUnitList(
                        presentation = presentation,
                        currentPageId = pageId,
                        currentDisplayUnitId = splitAnchor.displayUnitId,
                        initialAnchor = splitAnchor,
                        sidePadding = WebtoonSidePadding.NONE,
                        autoScroll = false,
                        autoScrollSpeed = mihon.desktop.ui.reader.WebtoonAutoScrollSpeed.Normal,
                        listStateOverride = listState,
                        onViewportChanged = { latestUpdate = it },
                        onRetryPage = {},
                    ) { slot, modifier ->
                        Box(modifier.height(if (slot.splitHalf == null) 300.dp else 1_200.dp))
                    }
                }
            }

            scene.render()
            advanceUntilIdle()
            scene.render()
            presentation = snapshot(split = false)
            repeat(4) {
                scene.render()
                advanceUntilIdle()
            }

            assertEquals(0, listState.firstVisibleItemIndex)
            assertTrue(listState.firstVisibleItemScrollOffset in 0..225)
            assertEquals(pageId, latestUpdate?.anchor?.displayUnitId?.slots?.single()?.pageId)
            assertEquals(listState.firstVisibleItemScrollOffset, latestUpdate?.anchor?.scrollOffset)
            assertEquals(300, latestUpdate?.anchor?.itemSize)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted list restores relative offset when a merged anchor splits`() = runTest {
        var presentation by mutableStateOf(snapshot(split = false))
        val mergedAnchor = WebtoonScrollAnchor(presentation.displayUnits.first().id, scrollOffset = 225, itemSize = 300)
        val listState = LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 225)
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    WebtoonDisplayUnitList(
                        presentation = presentation,
                        currentPageId = pageId,
                        currentDisplayUnitId = mergedAnchor.displayUnitId,
                        initialAnchor = mergedAnchor,
                        sidePadding = WebtoonSidePadding.NONE,
                        autoScroll = false,
                        autoScrollSpeed = mihon.desktop.ui.reader.WebtoonAutoScrollSpeed.Normal,
                        listStateOverride = listState,
                        onViewportChanged = {},
                        onRetryPage = {},
                    ) { slot, modifier ->
                        Box(modifier.height(if (slot.splitHalf == null) 300.dp else 1_200.dp))
                    }
                }
            }

            scene.render()
            advanceUntilIdle()
            presentation = snapshot(split = true)
            repeat(4) {
                scene.render()
                advanceUntilIdle()
            }

            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(900, listState.firstVisibleItemScrollOffset)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted auto scroll loop pauses for drag and fling then resumes after settlement`() = runTest {
        val presentation = snapshot(split = false)
        val listState = LazyListState()
        val interactions = listState.interactionSource as MutableInteractionSource
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    WebtoonDisplayUnitList(
                        presentation = presentation,
                        currentPageId = pageId,
                        currentDisplayUnitId = presentation.displayUnits.first().id,
                        initialAnchor = null,
                        sidePadding = WebtoonSidePadding.NONE,
                        autoScroll = true,
                        autoScrollSpeed = mihon.desktop.ui.reader.WebtoonAutoScrollSpeed.Fastest,
                        listStateOverride = listState,
                        onViewportChanged = {},
                        onRetryPage = {},
                    ) { _, modifier ->
                        Box(modifier.height(1_200.dp))
                    }
                }
            }

            scene.render()
            advanceTimeBy(96)
            runCurrent()
            scene.render()
            assertTrue(listState.firstVisibleItemScrollOffset > 0)

            val drag = DragInteraction.Start()
            interactions.emit(drag)
            runCurrent()
            scene.render()
            val dragOffset = listState.firstVisibleItemScrollOffset
            advanceTimeBy(96)
            runCurrent()
            scene.render()
            assertEquals(dragOffset, listState.firstVisibleItemScrollOffset)

            interactions.emit(DragInteraction.Stop(drag))
            val fling = launch { listState.scroll { awaitCancellation() } }
            runCurrent()
            scene.render()
            val flingOffset = listState.firstVisibleItemScrollOffset
            advanceTimeBy(96)
            runCurrent()
            scene.render()
            assertEquals(flingOffset, listState.firstVisibleItemScrollOffset)

            fling.cancelAndJoin()
            runCurrent()
            scene.render()
            advanceTimeBy(96)
            runCurrent()
            scene.render()
            assertTrue(listState.firstVisibleItemScrollOffset > flingOffset)
        } finally {
            scene.close()
        }
    }

    private fun unit(state: ReaderPageLoadState, imageUrl: String? = null): DisplayUnit =
        WebtoonPresentation.present(
            ReaderPresentationRequest(
                chapter = ReaderChapterSession(
                    id = chapterId,
                    generation = 3,
                    loadState = ReaderChapterLoadState.Loaded,
                    pages = listOf(
                        ReaderPageSession(
                            id = pageId,
                            url = "/page/0",
                            imageUrl = imageUrl,
                            encodedPageRef = null,
                            loadState = state,
                        ),
                    ),
                ),
                direction = ReaderDirection.RTL,
            ),
        ).displayUnits.single()

    private fun snapshot(split: Boolean): ReaderPresentationSnapshot {
        val pages = listOf(
            ReaderPageSession(pageId, "/page/0", "ready-0", null, ReaderPageLoadState.Ready),
            ReaderPageSession(ReaderPageId(chapterId, 1), "/page/1", "ready-1", null, ReaderPageLoadState.Ready),
        )
        return WebtoonPresentation.present(
            ReaderPresentationRequest(
                chapter = ReaderChapterSession(chapterId, 3, ReaderChapterLoadState.Loaded, pages),
                direction = ReaderDirection.RTL,
                splitPageIds = if (split) setOf(pageId) else emptySet(),
            ),
        )
    }

    private fun unitNode(scene: ImageComposeScene): SemanticsNode =
        nodes(scene).single { it.config.contains(WebtoonDisplayUnitCompositionIdentityKey) }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private companion object {
        val chapterId = ReaderChapterId(72L)
        val pageId = ReaderPageId(chapterId, 0)
    }
}
