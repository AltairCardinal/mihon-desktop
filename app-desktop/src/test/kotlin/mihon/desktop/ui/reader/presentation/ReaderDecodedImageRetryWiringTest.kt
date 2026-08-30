package mihon.desktop.ui.reader.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.NavigationMode
import mihon.desktop.ui.reader.WebtoonPresentationViewer
import mihon.desktop.ui.reader.ZoomablePagerViewer
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderDecodedImageRetryWiringTest {

    @Test
    fun `single page exposes Retry for a full decode failure and targets that page`() = runTest {
        val chapter = readerChapterSession(chapterId = 71L, generation = 9L, pageCount = 1)
        verifyRetry(chapter.pages.single().id) { owner, retries ->
            ZoomablePagerViewer(
                chapter = chapter,
                currentPage = 0,
                isRtl = false,
                isDualPage = false,
                zoomState = ZoomState(),
                navigationMode = NavigationMode.RightAndLeft,
                presentationImageOwner = owner,
                onPageChange = {},
                onZoomChange = {},
                onRetryPage = retries::add,
            )
        }
    }

    @Test
    fun `dual page exposes Retry for a full decode failure and targets that slot page`() = runTest {
        val chapter = readerChapterSession(chapterId = 72L, generation = 10L, pageCount = 1)
        verifyRetry(chapter.pages.single().id, sceneWidth = 1_600, sceneHeight = 900) { owner, retries ->
            ZoomablePagerViewer(
                chapter = chapter,
                currentPage = 0,
                isRtl = true,
                isDualPage = true,
                zoomState = ZoomState(),
                navigationMode = NavigationMode.RightAndLeft,
                presentationImageOwner = owner,
                onPageChange = {},
                onZoomChange = {},
                onRetryPage = retries::add,
            )
        }
    }

    @Test
    fun `webtoon exposes Retry for a full decode failure and targets that page`() = runTest {
        val chapter = readerChapterSession(chapterId = 73L, generation = 11L, pageCount = 1)
        verifyRetry(chapter.pages.single().id) { owner, retries ->
            WebtoonPresentationViewer(
                chapter = chapter,
                currentPage = 0,
                currentDisplayUnitId = null,
                initialAnchor = null,
                presentationImageOwner = owner,
                onViewportChanged = {},
                onRetryPage = retries::add,
            )
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyRetry(
        expectedPageId: ReaderPageId,
        sceneWidth: Int = 640,
        sceneHeight: Int = 480,
        content: @androidx.compose.runtime.Composable (
            DesktopReaderPresentationImageOwner,
            MutableList<ReaderPageId>,
        ) -> Unit,
    ) {
        val fixture = FailingFullDecodeFixture(this)
        val retries = mutableListOf<ReaderPageId>()
        val scene = ImageComposeScene(sceneWidth, sceneHeight, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    content(fixture.owner, retries)
                }
            }

            val retry = awaitSingleRetry(scene)
            assertTrue(requireNotNull(retry.config[SemanticsActions.OnClick].action).invoke())
            assertEquals(listOf(expectedPageId), retries)
        } finally {
            scene.close()
            fixture.close()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.awaitSingleRetry(
        scene: ImageComposeScene,
    ): SemanticsNode {
        repeat(50) {
            runCurrent()
            scene.render()
            nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }.singleOrNull()?.let { return it }
        }
        error("Full-page decode failure did not expose exactly one Retry action")
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private class FailingFullDecodeFixture(
        scope: CoroutineScope,
    ) : AutoCloseable {
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock { 0L },
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = { byteArrayOf(1) },
            ioReporter = reporter,
        )
        private val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = { _, _ -> null },
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter) { _, _ -> },
        )

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }
}
