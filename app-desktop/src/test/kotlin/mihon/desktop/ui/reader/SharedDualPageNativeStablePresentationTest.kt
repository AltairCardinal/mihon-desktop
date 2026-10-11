package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

/** Pixels and reading callbacks come from the production Compose image/pager pipeline. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SharedDualPageNativeStablePresentationTest {
    @Test
    fun `later real image paints left then cumulative reading appends the earlier page`() = runTest {
        Fixture(this, slowCurrent = true).use { fixture ->
            fixture.partner.complete(Unit)
            fixture.pump(GREEN)
            val rectangle = fixture.colorRectangle(GREEN)
            val mount = fixture.mount()
            assertTrue(fixture.greenBounds()!!.second < 200)
            assertEquals(
                listOf(setOf(2)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
            fixture.current.complete(Unit)
            repeat(50) {
                fixture.frame()
                runCurrent()
                assertEquals(rectangle, fixture.colorRectangle(GREEN))
                assertSame(mount, fixture.mount())
            }
            assertEquals(
                listOf(setOf(2), setOf(1, 2)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `later image cannot guess the unknown earlier type and actual wide earlier image paints full`() = runTest {
        Fixture(this, facts = false, wideCurrent = true, slowCurrent = true).use { fixture ->
            fixture.partner.complete(Unit)
            repeat(40) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(null, fixture.greenBounds())
            assertTrue(fixture.reports.isEmpty())
            fixture.current.complete(Unit)
            fixture.pump()
            assertTrue(fixture.redBounds().first < 100)
            assertEquals(setOf(1), fixture.visibleSources())
        }
    }

    @Test
    fun `partner error and actual retry button retain the current pixels transform mount and frame`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.zoom()
            repeat(8) {
                fixture.frame()
                runCurrent()
            }
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            val frame = fixture.sharedFrame()
            fixture.errorPartner()
            repeat(8) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(rectangle, fixture.redRectangle())
            assertSame(mount, fixture.mount())
            fixture.retryPartnerButton()
            fixture.replacePage(2, ready = true)
            repeat(8) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(listOf(2), fixture.retries)
            assertEquals(rectangle, fixture.redRectangle())
            assertSame(mount, fixture.mount())
            assertEquals(frame, fixture.sharedFrame())
            fixture.partner.complete(Unit)
            repeat(40) {
                fixture.frame()
                runCurrent()
                assertEquals(rectangle, fixture.redRectangle())
                assertSame(mount, fixture.mount())
            }
            assertEquals(
                listOf(setOf(1), setOf(1, 2)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `dual ready source paints immediately when optional header is unavailable`() = runTest {
        Fixture(this, facts = false).use { fixture ->
            fixture.pump()
            assertTrue(fixture.redBounds().first >= 200)
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `actual wide source corrects remote portrait guess before its first pixel`() = runTest {
        Fixture(this, facts = false, remoteFact = true, wideCurrent = true).use { fixture ->
            fixture.pump()
            assertTrue(
                fixture.redBounds().first < 100,
                "Actual wide content must use the full viewport before it emits pixels",
            )
            assertEquals(setOf(1), fixture.visibleSources())
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `mounted offscreen neighbors remain decoded without being reported as displayed`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.partner.complete(Unit)
            repeat(60) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(
                setOf(1, 2),
                fixture.presented,
                "Only pixels actually exposed by the viewport establish presentation",
            )
        }
    }

    @Test
    fun `single ready image paints without waiting for an optional header fact`() = runTest {
        Fixture(this, dual = false, facts = false).use { fixture ->
            fixture.pump()
            assertTrue(fixture.redBounds().first < 200)
            assertEquals(setOf(1), fixture.presented)
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `explicit existing retry permits current content reload without manufacturing navigation`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.retry()
            fixture.replacePage(1)
            assertEquals(1L, fixture.attempt(1), "A user retry releases the retained old byte lease")
            repeat(30) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(1, fixture.page)
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `actual forward drag reaches the next group without reading its held old partner`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.dragForward()
            repeat(60) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(3, fixture.page)
            assertTrue(!fixture.partner.isCompleted)
            val reports = fixture.reports.map { it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex) }
            assertTrue(reports.any { it == setOf(1) })
            assertTrue(reports.any { it == setOf(3, 4) }, "The entered next group must really paint and report")
            assertTrue(reports.all { 2 !in it }, "The held old partner must never be reported read")
        }
    }

    @Test
    fun `first real image reports partial viewport then late portrait appends without moving pixels`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            val first = fixture.redBounds()
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            assertTrue(first.first >= 200, "The first source page paints in the RTL right physical slot")
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
            fixture.partner.complete(Unit)
            repeat(50) {
                fixture.frame()
                runCurrent()
                assertEquals(first, fixture.redBounds(), "Late content must not refit the image already shown")
                assertEquals(rectangle, fixture.redRectangle())
                assertSame(mount, fixture.mount())
            }
            assertEquals(
                listOf(setOf(1), setOf(1, 2)),
                fixture.reports.map { it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex) },
            )
        }
    }

    @Test
    fun `actual rapid tap navigation advances whole groups past a held source partner`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.tapForward()
            assertEquals(3, fixture.page, "A held partner does not consume a separate native turn")
            fixture.tapForward()
            assertEquals(5, fixture.page, "The next request advances beyond the whole loading group")
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `production wheel navigation advances whole groups without reading the held partner`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.wheelForward()
            assertEquals(3, fixture.page)
            fixture.wheelForward()
            assertEquals(5, fixture.page)
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `background replacement retains the displayed original content until its real frame leaves`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            fixture.replacePage(1)
            assertEquals(0L, fixture.attempt(1), "The current frame keeps its original content lease")
            repeat(30) {
                fixture.frame()
                runCurrent()
                assertEquals(rectangle, fixture.redRectangle())
                assertSame(mount, fixture.mount())
            }
            fixture.jump(5)
            repeat(80) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(1L, fixture.attempt(1), "Replacement becomes available after actual offscreen disposal")
        }
    }

    @Test
    fun `unrelated content replacement inherits the already drawn current frame and geometry`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            fixture.replacePage(5)
            assertEquals(1L, fixture.attempt(5))
            repeat(30) {
                fixture.frame()
                runCurrent()
                assertEquals(rectangle, fixture.redRectangle())
                assertSame(mount, fixture.mount())
            }
        }
    }

    @Test
    fun `late decoded wide partner never paints half a page or moves current source image`() = runTest {
        Fixture(this, widePartner = true).use { fixture ->
            fixture.pump()
            fixture.zoom()
            repeat(8) {
                fixture.frame()
                runCurrent()
            }
            val first = fixture.redBounds()
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            val frame = fixture.sharedFrame()
            val zoom = fixture.zoomState
            fixture.partner.complete(Unit)
            repeat(50) {
                fixture.frame()
                runCurrent()
                assertEquals(first, fixture.redBounds())
                assertEquals(rectangle, fixture.redRectangle())
                assertSame(mount, fixture.mount())
                assertEquals(frame, fixture.sharedFrame())
                assertEquals(zoom, fixture.zoomState)
                assertTrue(fixture.greenBounds() == null, "The wide partner belongs to the next full viewport")
            }
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `explicit crop keeps partially painted source combination and its pending partner`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.crop()
            repeat(8) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(setOf(1, 2), fixture.visibleSources())
            fixture.partner.complete(Unit)
            repeat(40) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(
                listOf(setOf(1), setOf(1, 2)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `actual viewport resize renews geometry without creating navigation or losing pending partner`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            val before = fixture.sharedFrame()
            fixture.resize()
            repeat(8) {
                fixture.frame()
                runCurrent()
            }
            assertTrue(before != fixture.sharedFrame(), "A real viewport change revokes the old geometry capability")
            assertEquals(1, fixture.page)
            assertEquals(setOf(1, 2), fixture.visibleSources())
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    private class Fixture(
        private val scope: TestScope,
        widePartner: Boolean = false,
        dual: Boolean = true,
        facts: Boolean = true,
        remoteFact: Boolean = facts,
        wideCurrent: Boolean = false,
        slowCurrent: Boolean = false,
    ) : AutoCloseable {
        private val root = Preferences.userRoot().node("/mihon/native-stable/${System.nanoTime()}")
        private val store = DesktopPreferenceStore(root.node("store"))
        private val appPreferences = DesktopAppPreferences(store, root.node("app"))
        val reports = mutableListOf<Set<ReaderPageId>>()
        val presented = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val partner = CompletableDeferred<Unit>()
        val current = CompletableDeferred<Unit>()
        val retries = mutableListOf<Int>()
        private val model = ReaderScreenModel(
            dualPageOverride = dual,
            prefs = ReaderPreferences(store, root.node("reader")).apply {
                readingMode = ReadingMode.RTL
                isDualPage = dual
                isAutoSpreadMatching = false
                autoSplitPages = false
            },
            initialSessionState = desktopReaderSessionState(pageCount = 6, initialPage = 1),
            onViewportSettled = { pages, _ -> reports += pages },
            onPageRetry = { retries += it.sourcePageIndex },
        )
        private val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        private val content = DesktopReaderPageContentOwner(scope, { byteArrayOf(1) }, reporter)
        private val pipeline = DesktopReaderPageImagePipeline(
            scope,
            content,
            reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                if (key.pageIndex == 2) partner.await()
                if (key.pageIndex == 1 && slowCurrent) current.await()
                val width = if ((key.pageIndex == 2 && widePartner) || (key.pageIndex == 1 && wideCurrent)) 72 else 32
                val bitmap = Bitmap().apply { allocN32Pixels(width, 48) }
                Canvas(bitmap).use {
                    it.clear(
                        when (key.pageIndex) {
                            1 -> RED
                            2 -> GREEN
                            else -> BLUE
                        },
                    )
                }
                val page = model.state.value.session.activeChapter.pages[key.pageIndex].id
                if (facts) {
                    assertTrue(
                        model.acceptDualFact(model.captureDualFact(page)!!, ReaderPageFact.dimensions(width, 48)),
                    )
                }
                DesktopReaderImageAsset(
                    bitmap.asComposeImageBitmap(),
                    width,
                    48,
                    width * 48L * 4,
                    false,
                    disposer = bitmap::close,
                )
            },
        )
        private val owner =
            DesktopReaderPresentationImageOwner(
                scope,
                pipeline,
                ReaderPageIoObserver(reporter) { page, _ ->
                    presented +=
                        page.sourcePageIndex
                },
            )
        private val scene = ImageComposeScene(400, 400, coroutineContext = scope.coroutineContext) {}
        private var viewportWidth by mutableStateOf(400.dp)
        private var time = 0L
        val page get() = model.state.value.currentPage
        val zoomState get() = model.state.value.zoomState
        fun zoom() = model.setZoomState(ZoomState(scale = 1.35f, offsetX = 7f, offsetY = -11f))
        fun crop() = model.setCropBordersPager(true)
        fun resize() {
            viewportWidth = 360.dp
        }
        fun retry() = model.retryPage(model.state.value.session.activeChapter.pages[1].id)
        fun attempt(index: Int) = model.state.value.session.activeChapter.pages[index].attemptGeneration
        fun jump(index: Int) = model.goToPage(index)
        fun replacePage(index: Int, ready: Boolean = false) {
            val state = model.state.value
            val chapter = state.session.activeChapter
            val pages = chapter.pages.map { page ->
                if (page.id.sourcePageIndex == index) {
                    page.copy(
                        attemptGeneration = page.attemptGeneration + 1,
                        loadState = if (ready) ReaderPageLoadState.Ready else page.loadState,
                    )
                } else {
                    page
                }
            }
            model.acceptSessionState(
                DesktopReaderSessionState(
                    state.context,
                    state.session.copy(activeChapter = chapter.copy(pages = pages)),
                ),
            )
        }
        fun errorPartner() {
            val state = model.state.value
            val chapter = state.session.activeChapter
            model.acceptSessionState(
                DesktopReaderSessionState(
                    state.context,
                    state.session.copy(
                        activeChapter = chapter.copy(
                            pages = chapter.pages.map {
                                if (it.id.sourcePageIndex ==
                                    2
                                ) {
                                    it.copy(loadState = ReaderPageLoadState.Error(AppError.Network()))
                                } else {
                                    it
                                }
                            },
                        ),
                    ),
                ),
            )
        }
        fun retryPartnerButton() {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val button = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.single {
                it.config.contains(SemanticsActions.OnClick) && !it.boundsInRoot.isEmpty &&
                    it.boundsInRoot.center.x < 200f
            }
            assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
            scope.runCurrent()
        }
        init {
            owner.beginGeneration(1)
            // A real remote header may know C before D's delayed content opens. No D guess is supplied.
            val page = model.state.value.session.activeChapter.pages[1].id
            if (remoteFact) {
                assertTrue(
                    model.acceptDualFact(model.captureDualFact(page)!!, ReaderPageFact.dimensions(32, 48)),
                )
            }
            val dependencies = mockk<DesktopUiDependencies> {
                every { appPreferences } returns
                    this@Fixture.appPreferences
            }
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    val state by model.state.collectAsState()
                    Box(Modifier.requiredSize(viewportWidth, 400.dp)) {
                        ReaderContent(state, model, scope, "", "", owner, null, {}, {})
                    }
                }
            }
        }
        fun pump(color: Int = RED) {
            val deadline = System.nanoTime() + 3_000_000_000L
            do {
                frame()
                scope.runCurrent()
            } while (bounds(color) == null && System.nanoTime() < deadline)
            repeat(8) {
                frame()
                scope.runCurrent()
            }
        }
        fun frame() {
            time += 16_000_000L
            scene.render(time).close()
            // The production content/transform owners dispatch genuine I/O outside TestScope.
            Thread.sleep(5)
        }
        private fun bounds(color: Int): Pair<Int, Int>? {
            time += 16_000_000L
            val bitmap = scene.render(time).toComposeImageBitmap().asSkiaBitmap()
            return try {
                val xs = (0 until 400).filter { bitmap.getColor(it, 200) == color }
                if (xs.isEmpty()) null else xs.first() to xs.last()
            } finally {
                bitmap.close()
            }
        }
        fun redBounds() = requireNotNull(bounds(RED)) { "The real first image must be drawn" }
        fun greenBounds() = bounds(GREEN)
        fun tapForward() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 200f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 200f), button = PointerButton.Primary)
            scope.runCurrent()
        }
        fun dragForward() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 200f), type = PointerType.Touch)
            scope.runCurrent()
            repeat(8) { index ->
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    Offset(200f + (index + 1) * 30f, 200f),
                    type = PointerType.Touch,
                )
                repeat(2) {
                    frame()
                    scope.runCurrent()
                }
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(440f, 200f), type = PointerType.Touch)
            scope.runCurrent()
        }
        fun wheelForward() {
            val event = java.awt.event.MouseWheelEvent(
                java.awt.Canvas(), java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 200, 200, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1,
            )
            assertTrue(handleReaderWheelEvent(event, model.state.value, model, {}, {}))
        }
        fun redRectangle(): List<Int> = colorRectangle(RED)
        fun colorRectangle(color: Int): List<Int> {
            time += 16_000_000L
            val bitmap = scene.render(time).toComposeImageBitmap().asSkiaBitmap()
            return try {
                var left = 400
                var top = 400
                var right = -1
                var bottom = -1
                for (y in 0 until 400) {
                    for (x in 0 until 400) {
                        if (bitmap.getColor(x, y) == color) {
                            left = minOf(left, x)
                            right = maxOf(right, x)
                            top = minOf(top, y)
                            bottom = maxOf(bottom, y)
                        }
                    }
                }
                assertTrue(right >= left && bottom >= top)
                listOf(left, top, right, bottom)
            } finally {
                bitmap.close()
            }
        }
        fun mount(): DualPageDisplayUnitCompositionIdentity {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val frame = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.single {
                it.config.contains(DualPageDisplayUnitCompositionIdentityKey) &&
                    it.boundsInRoot.contains(Offset(200f, 200f))
            }
            return frame.config[DualPageDisplayUnitCompositionIdentityKey]
        }
        private fun selectedNative(): ReaderPagerNativePresentation {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.single {
                it.config.contains(ReaderPagerNativePresentationKey)
            }.config[ReaderPagerNativePresentationKey]
        }
        fun sharedFrame() = requireNotNull(selectedNative().frameId)
        fun visibleSources(): Set<Int> {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val frame = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.single {
                it.config.contains(DualPageDisplayUnitIdKey) && it.boundsInRoot.contains(Offset(200f, 200f))
            }
            return frame.config[DualPageDisplayUnitIdKey].slots.mapNotNullTo(mutableSetOf()) {
                it.pageId?.sourcePageIndex
            }
        }
        override fun close() {
            scene.close()
            owner.close()
            pipeline.close()
            content.close()
            model.onDispose()
            root.removeNode()
        }
    }

    companion object {
        private const val RED = 0xffff0000.toInt()
        private const val GREEN = 0xff00ff00.toInt()
        private const val BLUE = 0xff0000ff.toInt()
    }
}
