package mihon.desktop.ui.reader.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import mihon.desktop.reader.ReaderStartupObservation
import mihon.desktop.reader.ReaderStartupTrace
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.LocalReaderPageImageContent
import mihon.desktop.ui.reader.NavigationMode
import mihon.desktop.ui.reader.ReaderDisplayUnitCompositionIdentityKey
import mihon.desktop.ui.reader.ReaderDisplayUnitIdKey
import mihon.desktop.ui.reader.ReaderPageImageContentRegistry
import mihon.desktop.ui.reader.ReaderPresentationHolderKey
import mihon.desktop.ui.reader.ReaderStartupSingleViewportKey
import mihon.desktop.ui.reader.ZoomablePagerViewer
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageLoadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class)
class StartupSingleViewportTest {
    @Test
    fun `vertical paging never mounts the horizontal startup gesture viewport`() = runTest {
        val fixture = PresentationImageOwnerFixture(this)
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                ZoomablePagerViewer(
                    chapter = readerChapterSession(41, 11, 3) { ReaderPageLoadState.Queued },
                    currentPage = 0, isRtl = false, isDualPage = false, isVertical = true,
                    zoomState = ZoomState(), presentationImageOwner = fixture.owner,
                    onPageChange = {}, onZoomChange = {}, startupSingleViewport = true,
                )
            }
            scene.render().close()
            assertFalse(nodes(scene).any { it.config.contains(ReaderStartupSingleViewportKey) })
        } finally {
            scene.close()
            fixture.close()
        }
    }

    @Test
    fun `real fifth-page startup viewport accepts ready and hands the same image holder to the pager`() = runTest {
        var chapter by mutableStateOf(readerChapterSession(41, 11, 187) { ReaderPageLoadState.Queued })
        var startup by mutableStateOf(true)
        val target = chapter.pages[4].id
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
        val observation = ReaderStartupObservation()
        ReaderStartupTrace.install(observation)
        var encodedReads = 0
        val fixture = PresentationImageOwnerFixture(this) {
            encodedReads++
            png
        }
        fixture.owner.beginGeneration(11)
        val registry = ReaderPageImageContentRegistry()
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        var sceneClosed = false
        try {
            scene.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalReaderPageImageContent provides registry) {
                        ZoomablePagerViewer(
                            chapter = chapter, currentPage = 4, isRtl = true, isDualPage = false,
                            zoomState = ZoomState(), navigationMode = NavigationMode.RightAndLeft,
                            mangaTitle = "Fixture", chapterTitle = "Chapter",
                            presentationImageOwner = fixture.owner, onPageChange = {}, onZoomChange = {},
                            startupSingleViewport = startup, allowAdjacentViewport = false,
                        )
                    }
                }
            }
            scene.render().close()
            val initial = nodes(scene).singleOrNull { it.config.contains(ReaderStartupSingleViewportKey) }
            assertTrue(
                initial != null,
                "Startup must mount the actual target viewport without initializing HorizontalPager",
            )
            val container = nodes(scene).single { it.config.contains(ReaderDisplayUnitIdKey) }
            assertEquals(target, container.config[ReaderDisplayUnitIdKey].slots.single().pageId)
            val identity = container.config[ReaderDisplayUnitCompositionIdentityKey]
            repeat(3) {
                scene.render().close()
                yield()
            }
            val pendingNode = nodes(scene).singleOrNull { it.config.contains(ReaderPresentationHolderKey) }
            assertTrue(pendingNode != null, "Queued viewport must already mount the real display holder, not a proxy")
            val pendingHolder = requireNotNull(pendingNode).config[ReaderPresentationHolderKey]
            assertEquals(0, encodedReads, "Mounting a Queued holder cannot read or decode target content")
            assertTrue(pendingHolder.state.value is mihon.desktop.reader.DesktopReaderPresentationImageState.Idle)
            chapter = chapter.copy(
                pages = chapter.pages.map { page ->
                    if (page.id ==
                        target
                    ) {
                        page.copy(loadState = ReaderPageLoadState.Ready, encodedPageRef = EncodedPageRef("target"))
                    } else {
                        page
                    }
                },
            )
            repeat(40) {
                scene.render().close()
                yield()
                Thread.sleep(3)
            }
            assertEquals(11L, fixture.owner.firstPresentedGeneration.value, "A real decoded image must be drawn")
            assertSame(
                pendingHolder,
                nodes(scene).single {
                    it.config.contains(ReaderPresentationHolderKey)
                }.config[ReaderPresentationHolderKey],
            )
            assertSame(
                identity,
                nodes(scene).single {
                    it.config.contains(ReaderDisplayUnitIdKey)
                }.config[ReaderDisplayUnitCompositionIdentityKey],
            )
            assertEquals(
                1,
                observation.snapshot().events.count {
                    it.stage == "HOLDER_CREATE_BEGIN" && it.pageIndex == 4
                },
            )
            startup = false
            repeat(8) {
                scene.render().close()
                yield()
            }
            assertEquals(
                1,
                observation.snapshot().events.count {
                    it.stage == "HOLDER_CREATE_BEGIN" && it.pageIndex == 4
                },
                "The full pager must consume the mounted holder instead of recreating it",
            )
            assertEquals(11L, fixture.owner.firstPresentedGeneration.value)
            assertSame(
                pendingHolder,
                nodes(scene).single {
                    it.config.contains(ReaderPresentationHolderKey)
                }.config[ReaderPresentationHolderKey],
            )
            assertTrue(encodedReads > 0)
            scene.close()
            sceneClosed = true
            assertTrue(
                pendingHolder.state.value is mihon.desktop.reader.DesktopReaderPresentationImageState.Closed,
                "Unmount cancels and closes the actual holder",
            )
            val readsBeforeCancel = encodedReads
            fixture.owner.beginGeneration(12)
            val canceledChapter = readerChapterSession(41, 12, 187) { ReaderPageLoadState.Queued }
            val canceledScene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
            var canceledSceneClosed = false
            try {
                val canceledRegistry = ReaderPageImageContentRegistry()
                canceledScene.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalReaderPageImageContent provides canceledRegistry) {
                            ZoomablePagerViewer(
                                chapter = canceledChapter, currentPage = 4, isRtl = true, isDualPage = false,
                                zoomState = ZoomState(), presentationImageOwner = fixture.owner,
                                onPageChange = {}, onZoomChange = {}, startupSingleViewport = true,
                            )
                        }
                    }
                }
                repeat(3) {
                    canceledScene.render().close()
                    yield()
                }
                val canceled = nodes(canceledScene).single {
                    it.config.contains(ReaderPresentationHolderKey)
                }.config[ReaderPresentationHolderKey]
                assertTrue(canceled.state.value is mihon.desktop.reader.DesktopReaderPresentationImageState.Idle)
                canceledScene.close()
                canceledSceneClosed = true
                assertTrue(canceled.state.value is mihon.desktop.reader.DesktopReaderPresentationImageState.Closed)
                assertEquals(
                    readsBeforeCancel,
                    encodedReads,
                    "Canceling the mounted unbound holder must not start image work",
                )
            } finally {
                if (!canceledSceneClosed) canceledScene.close()
            }
        } finally {
            if (!sceneClosed) scene.close()
            fixture.close()
            ReaderStartupTrace.clear(observation)
        }
    }

    @Test
    fun `active touch drag survives queued ready first draw and releases into RTL next page`() = runTest {
        var chapter by mutableStateOf(readerChapterSession(41, 11, 187) { ReaderPageLoadState.Queued })
        var page by mutableStateOf(4)
        var startup by mutableStateOf(true)
        val png = ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out)
        }.toByteArray()
        val fixture = PresentationImageOwnerFixture(this) { png }
        fixture.owner.beginGeneration(11)
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        val registry = ReaderPageImageContentRegistry()
        try {
            scene.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalReaderPageImageContent provides registry) {
                        ZoomablePagerViewer(
                            chapter = chapter, currentPage = page, isRtl = true, isDualPage = false,
                            zoomState = ZoomState(), presentationImageOwner = fixture.owner,
                            onPageChange = { page = it }, onZoomChange = {},
                            onSingleVisiblePagesChanged = {}, startupSingleViewport = startup,
                            allowAdjacentViewport = false,
                        )
                    }
                }
            }
            repeat(3) {
                scene.render(System.nanoTime()).close()
                yield()
            }
            val holder = nodes(scene).single {
                it.config.contains(ReaderPresentationHolderKey)
            }.config[ReaderPresentationHolderKey]
            scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 240f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Move, Offset(400f, 240f), type = PointerType.Touch)
            repeat(3) {
                scene.render(System.nanoTime()).close()
                yield()
            }
            chapter = chapter.copy(
                pages = chapter.pages.map {
                    if (it.id.sourcePageIndex ==
                        4
                    ) {
                        it.copy(loadState = ReaderPageLoadState.Ready, encodedPageRef = EncodedPageRef("ready"))
                    } else {
                        it
                    }
                },
            )
            repeat(40) {
                scene.render(System.nanoTime()).close()
                yield()
                Thread.sleep(2)
            }
            assertEquals(11L, fixture.owner.firstPresentedGeneration.value)
            startup = false
            repeat(3) {
                scene.render(System.nanoTime()).close()
                yield()
            }
            assertTrue(
                nodes(scene).any { it.config.contains(ReaderStartupSingleViewportKey) },
                "First draw cannot unmount the owner of an active input gesture",
            )
            assertSame(
                holder,
                nodes(scene).single {
                    it.config.contains(ReaderPresentationHolderKey)
                }.config[ReaderPresentationHolderKey],
            )
            scene.sendPointerEvent(PointerEventType.Release, Offset(400f, 240f), type = PointerType.Touch)
            repeat(10) {
                scene.render(System.nanoTime()).close()
                yield()
            }
            assertEquals(5, page, "The real RTL swipe must commit exactly one next-page action")
            assertFalse(nodes(scene).any { it.config.contains(ReaderStartupSingleViewportKey) })
        } finally {
            scene.close()
            fixture.close()
        }
    }

    @Test
    fun `unbound holder rejects late ready after generation and retry attempt change`() = runTest {
        var reads = 0
        val fixture = PresentationImageOwnerFixture(this) {
            reads++
            null
        }
        try {
            val page = readerChapterSession(41, 11, 187).pages[4]
            val identity = mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity(page.id, 11, 0)
            fixture.owner.beginGeneration(11)
            val holder = fixture.owner.createHolder(identity, null)
            holder.acquire()
            fixture.owner.beginGeneration(12)
            assertFalse(holder.bindContent(mihon.desktop.ui.reader.readerPresentationDecodeKey(page, 11)))
            assertTrue(holder.state.value is mihon.desktop.reader.DesktopReaderPresentationImageState.Closed)
            val current = fixture.owner.createHolder(identity.copy(generation = 12), null)
            fixture.owner.beginPageAttempt(page.id, 12, 1)
            assertFalse(current.bindContent(mihon.desktop.ui.reader.readerPresentationDecodeKey(page, 12)))
            current.acquire()
            yield()
            assertEquals(0, reads, "Unbound stale holders cannot start content I/O")
        } finally {
            fixture.close()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap {
        flatten(it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
