package mihon.desktop.ui.reader

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.platform.DesktopClipboardPort
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageHolder
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.SkiaImageDecoder
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.ReaderPairingSettings
import mihon.domain.reader.SharedDualPageRules
import mihon.domain.reader.SharedDualPageSession
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Pixels and reading callbacks come from the production Compose image/pager pipeline. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SharedDualPageNativeStablePresentationTest {
    @Test
    fun `zoom pan limits blank space and immediately follows reverse movement`() = runTest {
        for (dual in listOf(false, true)) {
            for (type in listOf(PointerType.Mouse, PointerType.Touch)) {
                Fixture(this, dual = dual, originalHeadersBeforeImages = true).use { fixture ->
                    fixture.partner.complete(Unit)
                    fixture.pump()
                    assertTrue(fixture.hasRedPixels(), "The comic must draw before zoom and pan")
                    if (dual) assertTrue(fixture.greenBounds() != null, "The complete spread must draw before pan")
                    fixture.setZoom(ZoomState(scale = 2f))
                    fixture.settle()
                    fixture.dragTo(Offset(10_000f, 10_000f), type, release = false)
                    val limited = fixture.zoomState
                    assertTrue(limited.offsetX < 1_000f && limited.offsetY < 1_000f, "$dual $type: $limited")
                    fixture.settle()
                    val bounds = fixture.comicRectangle()
                    assertTrue(bounds[0] in 132..134 && bounds[1] in 132..134, "$dual $type: $bounds")
                    fixture.movePointer(Offset(9_970f, 9_970f), type)
                    assertEquals(limited.offsetX - 30f, fixture.zoomState.offsetX, 0.01f)
                    assertEquals(limited.offsetY - 30f, fixture.zoomState.offsetY, 0.01f)
                    fixture.releasePointer(Offset(9_970f, 9_970f), type)
                    fixture.dragTo(Offset(-10_000f, -10_000f), type)
                    val opposite = fixture.zoomState
                    assertTrue(opposite.offsetX > -1_000f && opposite.offsetY > -1_000f)
                    fixture.settle()
                    val oppositeBounds = fixture.comicRectangle()
                    assertTrue(oppositeBounds[2] in 265..267 && oppositeBounds[3] in 265..267)
                    assertEquals(1, fixture.page, "A bounded drag must not turn the page")
                }
            }
        }
    }

    @Test
    fun `zoom pan preserves legal spread focus while a partner is loading`() = runTest {
        for (offset in listOf(-200f, 200f)) {
            Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
                fixture.pump()
                val zoom = ZoomState(scale = 2f, offsetX = offset)
                fixture.setZoom(zoom)
                fixture.settle()
                assertEquals(zoom, fixture.zoomState)
                fixture.partner.complete(Unit)
                fixture.settle(60)
                assertEquals(zoom, fixture.zoomState)
            }
        }
        Fixture(
            this,
            originalHeadersBeforeImages = true,
            imageWidth = 400,
            imageHeight = 600,
            partnerImageWidth = 80,
        ).use { fixture ->
            fixture.pump()
            val zoom = ZoomState(scale = 2f, offsetX = 100f)
            fixture.setZoom(zoom)
            fixture.settle()
            assertEquals(zoom, fixture.zoomState)
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            val frame = fixture.sharedFrame()
            fixture.partner.complete(Unit)
            fixture.pump(GREEN)
            fixture.settle(60)
            assertEquals(zoom, fixture.zoomState, "A narrow late portrait cannot move the already drawn partner")
            assertEquals(rectangle, fixture.redRectangle(), "Actual red pixel geometry must remain stable")
            assertSame(mount, fixture.mount())
            assertEquals(frame, fixture.sharedFrame())
            fixture.dragTo(Offset(10_000f, 200f), PointerType.Mouse, release = false)
            assertEquals(100f, fixture.zoomState.offsetX, 0.01f, "New geometry cannot allow more outward blank")
            fixture.movePointer(Offset(9_970f, 200f), PointerType.Mouse)
            assertEquals(70f, fixture.zoomState.offsetX, 0.01f, "Reverse movement must immediately follow the pointer")
            fixture.releasePointer(Offset(9_970f, 200f), PointerType.Mouse)
            fixture.settle()
            fixture.dragTo(Offset(10_000f, 200f), PointerType.Mouse)
            assertEquals(70f, fixture.zoomState.offsetX, 0.01f, "Returning must tighten the old focus allowance")
        }
    }

    @Test
    fun `zoom pan preserves small image margins and reclamps after scale and viewport changes`() = runTest {
        Fixture(this, dual = false, imageWidth = 2, originalHeadersBeforeImages = true).use { fixture ->
            fixture.pump()
            fixture.setZoom(ZoomState(scale = 2f))
            fixture.settle()
            val before = fixture.redRectangle()
            fixture.dragTo(Offset(10_000f, 200f), PointerType.Mouse)
            assertEquals(0f, fixture.zoomState.offsetX)
            assertEquals(before, fixture.redRectangle())
        }
        for (dual in listOf(false, true)) {
            Fixture(this, dual = dual, originalHeadersBeforeImages = true).use { fixture ->
                fixture.partner.complete(Unit)
                fixture.pump()
                fixture.setZoom(ZoomState(scale = 4f))
                fixture.settle()
                fixture.dragTo(Offset(10_000f, 10_000f), PointerType.Mouse)
                val old = fixture.zoomState
                fixture.setZoom(old.copy(scale = 2f))
                fixture.settle()
                assertTrue(fixture.zoomState.offsetX < old.offsetX)
                assertTrue(fixture.zoomState.offsetY < old.offsetY)
                fixture.resize(300)
                fixture.settle()
                val resized = fixture.comicRectangle()
                assertTrue(resized[0] <= 101, "$dual: $resized")
                fixture.doubleTap()
                assertEquals(ZoomState(), fixture.zoomState)
            }
        }
    }

    @Test
    fun `cancelling an actual overlapping page drag preserves its original anchor frame and reading`() = runTest {
        Fixture(
            this,
            initialPage = 125,
            pageCount = 200,
            partnerIndex = 126,
            facts = false,
            remoteFact = false,
            deferredOriginalHeaders = true,
            wideOriginalPage = 1,
        ).use { fixture ->
            fixture.partner.complete(Unit)
            fixture.pump()
            fixture.pump(GREEN)
            fixture.publishOriginalHeaders()
            fixture.settle()
            val frame = fixture.sharedFrame()
            val rectangle = fixture.redRectangle()
            fixture.previewForwardDrag()
            val previewAnchor = fixture.page
            val previewFrames = fixture.liveDrawnFrames(126)
            fixture.cancelPointerInput()
            fixture.settle(60)
            assertEquals(125, previewAnchor, "Preview pixels cannot commit the logical source anchor")
            assertTrue(previewFrames.size >= 2, "The real gesture must expose both independent boundary frames")
            assertEquals(125, fixture.page)
            assertEquals(frame, fixture.sharedFrame())
            assertEquals(rectangle, fixture.redRectangle())
            assertEquals(1, fixture.liveDrawnFrames(126).size, "The cancelled neighbour releases its display lease")
            assertTrue(fixture.reports.flatten().all { it.sourcePageIndex in setOf(125, 126) })
        }
    }

    @Test
    fun `rapid native reverse inputs select the latest corrected frame rather than an overlapping old mount`() =
        runTest {
            Fixture(
                this,
                initialPage = 125,
                pageCount = 200,
                partnerIndex = 126,
                facts = false,
                remoteFact = false,
                deferredOriginalHeaders = true,
                wideOriginalPage = 1,
            ).use { fixture ->
                fixture.partner.complete(Unit)
                fixture.pump()
                fixture.pump(GREEN)
                fixture.publishOriginalHeaders()
                fixture.settle()
                val old = fixture.sharedFrame()
                fixture.tapForward()
                assertEquals(127, fixture.page)
                fixture.tapBackward()
                assertEquals(126, fixture.page, "An unpainted incoming partner is explicitly activated once")
                fixture.tapBackward()
                assertEquals(125, fixture.page)
                fixture.settle(60)
                assertEquals(setOf(124, 125), fixture.visibleSources())
                assertTrue(
                    fixture.redBounds().second < 200,
                    "The latest corrected frame gives source 126 its trailing slot",
                )
                assertTrue(old != fixture.sharedFrame())
                assertEquals(1, fixture.liveDrawnFrames(125).size)
            }
        }

    @Test
    fun `a drag whose unpainted destination is replaced restores its original logical frame`() = runTest {
        Fixture(
            this, facts = false, remoteFact = false, initialPage = 125, pageCount = 200,
            partnerIndex = 126, deferredOriginalHeaders = true, wideOriginalPage = 1,
            heldDecodePages = setOf(127, 128),
        ).use { fixture ->
            fixture.pump()
            val frame = fixture.sharedFrame()
            val rectangle = fixture.redRectangle()
            fixture.previewForwardDrag()
            val oldIncoming = fixture.framesContaining(128)
            assertTrue(oldIncoming.isNotEmpty(), "The real pager prepared its not-yet-drawn destination")
            fixture.publishOriginalHeaders()
            fixture.finishForwardDrag()
            fixture.settle(60)
            assertTrue(
                fixture.framesContaining(128).intersect(oldIncoming).isEmpty(),
                "New original evidence replaced the unlocked candidate",
            )
            assertEquals(125, fixture.page, "An invalidated frozen destination restores the logical target")
            assertEquals(frame, fixture.sharedFrame())
            assertEquals(rectangle, fixture.redRectangle())
            assertTrue(fixture.reports.none { pages -> pages.any { it.sourcePageIndex >= 127 } })
        }
    }

    @Test
    fun `late wide requested page cannot replace its already drawn partner until explicit navigation`() = runTest {
        for (zoom in listOf(ZoomState(), ZoomState(scale = 2f, offsetX = -100f))) {
            Fixture(this, slowCurrent = true, wideCurrent = true, facts = false, remoteFact = false).use { fixture ->
                fixture.partner.complete(Unit)
                fixture.pump(GREEN)
                fixture.setZoom(zoom)
                fixture.settle()
                assertEquals(zoom, fixture.zoomState, "The pending right slot keeps this legal spread focus")
                val green = requireNotNull(fixture.greenBounds())
                val rectangle = fixture.comicRectangle()
                val mount = fixture.mount()
                val frame = fixture.sharedFrame()
                fixture.current.complete(Unit)
                fixture.settle(40)
                assertEquals(zoom, fixture.zoomState, "Withdrawing an unpainted wide slot cannot change the camera")
                assertEquals(rectangle, fixture.comicRectangle(), "Background geometry cannot move actual green pixels")
                assertEquals(
                    green,
                    fixture.greenBounds(),
                    "The decoded wide target cannot remove its locked portrait partner",
                )
                assertSame(mount, fixture.mount())
                assertEquals(frame, fixture.sharedFrame())
                assertTrue(!fixture.hasRedPixels(), "A wide original cannot first paint inside its withdrawn half-slot")
                fixture.jump(1)
                fixture.pump(expectedBounds = 0 to 399)
                assertEquals(
                    0 to 399,
                    fixture.redBounds(),
                    "An explicit source jump may select the now-known full wide page",
                )
            }
        }
    }

    @Test
    fun `late original phase corrects on a native turn without moving pixels or losing an unpainted boundary`() =
        runTest {
            for (paintPartner in listOf(false, true)) {
                Fixture(
                    this,
                    initialPage = 125,
                    pageCount = 200,
                    partnerIndex = 126,
                    facts = false,
                    remoteFact = false,
                    deferredOriginalHeaders = true,
                    wideOriginalPage = 1,
                ).use { fixture ->
                    fixture.pump()
                    assertTrue(fixture.hasRedPixels(), "The target may draw before the original prefix is available")
                    if (paintPartner) {
                        fixture.partner.complete(Unit)
                        fixture.pump(GREEN)
                    }
                    val rectangle = fixture.redRectangle()
                    val mounted = fixture.mount()
                    val frame = fixture.sharedFrame()
                    fixture.publishOriginalHeaders()
                    fixture.settle()
                    assertEquals(
                        rectangle,
                        fixture.redRectangle(),
                        "Background correction must preserve committed pixels",
                    )
                    assertSame(mounted, fixture.mount())
                    assertEquals(frame, fixture.sharedFrame())
                    fixture.tapForward()
                    fixture.settle(10)
                    if (paintPartner) {
                        assertEquals(
                            2,
                            fixture.liveDrawnFrames(126).size,
                            "The repeated boundary holds two independent live frame leases during animation",
                        )
                    }
                    fixture.settle(60)
                    assertEquals(if (paintPartner) 127 else 126, fixture.page)
                    assertEquals(
                        setOf(126, 127),
                        fixture.visibleSources(),
                        "The incoming shared boundary must not select the old frame",
                    )
                    assertTrue(!fixture.hasRedPixels(), "The settled incoming frame must leave source 126 behind")
                    fixture.partner.complete(Unit)
                    fixture.pump(GREEN)
                    assertTrue(
                        fixture.greenBounds()!!.first >= 200,
                        "Repeated source 127 is now the leading physical page",
                    )
                }
            }
        }

    @Test
    fun `zoomed dual reader clicks turn whole groups in both directions without dragging`() = runTest {
        for (mode in listOf(ReadingMode.RTL, ReadingMode.LTR)) {
            for (initialPage in listOf(0, 1)) {
                Fixture(this, mode = mode, initialPage = initialPage).use { fixture ->
                    fixture.partner.complete(Unit)
                    fixture.pump()
                    fixture.setZoom(ZoomState(scale = 2f, offsetX = -100f))
                    fixture.settle()
                    if (mode == ReadingMode.RTL) fixture.tapForward() else fixture.tapBackward()
                    fixture.settle(60)
                    assertEquals(
                        if (initialPage == 0) 1 else 3,
                        fixture.page,
                        "Zoomed next click: $mode $initialPage",
                    )
                    fixture.setZoom(ZoomState(scale = 2f, offsetX = 100f))
                    fixture.settle()
                    if (mode == ReadingMode.RTL) fixture.tapBackward() else fixture.tapForward()
                    fixture.settle(60)
                    assertEquals(
                        if (initialPage == 0) 0 else 2,
                        fixture.page,
                        "Zoomed previous click: $mode $initialPage",
                    )
                }
            }
        }
    }

    @Test
    fun `ordinary zoomed reader keeps click navigation blocked`() = runTest {
        Fixture(this, dual = false).use { fixture ->
            fixture.partner.complete(Unit)
            fixture.pump()
            fixture.setZoom(ZoomState(scale = 2f))
            fixture.settle()
            fixture.tapForward()
            fixture.settle(60)
            assertEquals(1, fixture.page)
            assertEquals(ZoomState(scale = 2f), fixture.zoomState)
        }
    }

    @Test
    fun `hardening repeated zoom pan reset and rapid turns reuse bounded images and release on exit`() = runTest {
        Fixture(this, measureFrames = true).use { fixture ->
            fixture.partner.complete(Unit)
            fixture.pump()
            fixture.jump(0)
            fixture.settle(60)
            fixture.tapForward()
            fixture.settle(60)
            repeat(2) {
                fixture.tapForward()
                fixture.settle(60)
            }
            repeat(2) {
                fixture.tapBackward()
                fixture.settle(60)
            }
            // Reverse navigation names the last page of the preceding group: 5 -> 4 -> 2.
            assertEquals(2, fixture.page)
            assertEquals(setOf(1, 2), fixture.visibleSources())
            val warm = fixture.resourceSnapshot()
            val opens = fixture.sourceOpens.get()
            val decodes = fixture.decodedImages.get()
            val holders = fixture.imageHolderCount()
            assertTrue(opens > 0 && decodes > 0)
            assertEquals(0, warm.inFlightCount)
            fixture.beginWarmMeasurements()
            repeat(4) { cycle ->
                val images = fixture.imageOwners(1)
                fixture.pinchAndContinue()
                assertEquals(ZoomState(scale = 2f, offsetX = 130f), fixture.zoomState)
                val type = if (cycle % 2 == 0) PointerType.Mouse else PointerType.Touch
                assertEquals(listOf(100f, 70f, 40f, 10f), fixture.mousePan(type))
                assertSame(images.first, fixture.imageOwners(1).first)
                assertSame(images.second, fixture.imageOwners(1).second)
                fixture.doubleTap()
                assertEquals(ZoomState(), fixture.zoomState)
                fixture.tapForward()
                fixture.tapForward()
                fixture.settle(60)
                assertEquals(4, fixture.page, "Rapid input activates the unpainted incoming partner once")
                fixture.tapBackward()
                assertEquals(2, fixture.page)
                assertTrue(fixture.currentDrawnSources().isEmpty(), "The new native frame has not exposed pixels yet")
                fixture.tapBackward()
                assertEquals(1, fixture.page, "A second rapid reverse input activates the undrawn earlier partner once")
                assertTrue(
                    fixture.currentDrawnSources().isEmpty(),
                    "Activating an undrawn source cannot manufacture reading",
                )
                fixture.settle(60)
                assertEquals(1, fixture.page)
                assertEquals(setOf(1, 2), fixture.visibleSources())
                val after = fixture.resourceSnapshot()
                assertEquals(opens, fixture.sourceOpens.get(), "Warm native turns must not reopen source content")
                assertEquals(decodes, fixture.decodedImages.get(), "Zoom and turns reuse decoded image resources")
                assertEquals(warm.cache.entryCount, after.cache.entryCount)
                assertEquals(warm.memory.residentBytes, after.memory.residentBytes)
                assertEquals(warm.memory.pinnedBytes, after.memory.pinnedBytes)
                assertEquals(0L, after.memory.inFlightBytes)
                assertEquals(0, after.inFlightCount)
                assertEquals(holders, fixture.imageHolderCount(), "Returning to the same viewport cannot grow holders")
            }
            fixture.printMeasurements()
            fixture.close()
            runCurrent()
            val closed = fixture.resourceSnapshot()
            assertTrue(closed.closed)
            assertEquals(0, closed.inFlightCount)
            assertEquals(0, closed.cache.entryCount)
            assertEquals(0L, closed.memory.residentBytes)
            assertEquals(0L, closed.memory.pinnedBytes)
            assertEquals(0L, closed.memory.inFlightBytes)
            assertEquals(0, fixture.contentLeaseCount())
            assertEquals(0, fixture.imageHolderCount())
            assertEquals(fixture.decodedImages.get(), fixture.releasedImages.get())
        }
    }

    @Test
    fun `hardening native pointer cancellation is neither a tap nor the first half of a double tap`() = runTest {
        for (dual in listOf(false, true)) {
            for (type in listOf(PointerType.Mouse, PointerType.Touch)) {
                Fixture(this, dual = dual).use { fixture ->
                    fixture.partner.complete(Unit)
                    fixture.pump()
                    fixture.pointer(PointerEventType.Press, 20f, type)
                    fixture.cancelPointerInput()
                    assertEquals(1, fixture.page, "A cancelled press cannot navigate: $dual $type")
                    fixture.setZoom(ZoomState(scale = 2f))
                    fixture.settle()
                    fixture.pointer(PointerEventType.Press, 320f, type)
                    fixture.cancelPointerInput()
                    assertEquals(ZoomState(scale = 2f), fixture.zoomState)
                    assertEquals(listOf(-30f, -60f, -90f, -120f), fixture.mousePan(type), "$dual $type")
                    assertEquals(1, fixture.page)
                }
            }
        }
    }

    @Test
    fun `hardening ordinary and dual readers resume Mouse and Touch pan after gesture disposal`() = runTest {
        for (dual in listOf(false, true)) {
            for (type in listOf(PointerType.Mouse, PointerType.Touch)) {
                Fixture(this, dual = dual).use { fixture ->
                    fixture.partner.complete(Unit)
                    fixture.pump()
                    fixture.setZoom(ZoomState(scale = 2f))
                    fixture.settle()
                    fixture.pointer(PointerEventType.Press, 320f, type)
                    fixture.pointer(PointerEventType.Move, 290f, type)
                    assertEquals(ZoomState(scale = 2f, offsetX = -30f), fixture.zoomState, "$dual $type")
                    fixture.mountReader(false)
                    fixture.pointer(PointerEventType.Move, 260f, type)
                    fixture.pointer(PointerEventType.Release, 260f, type)
                    assertEquals(ZoomState(scale = 2f, offsetX = -30f), fixture.zoomState)
                    fixture.mountReader(true)
                    assertEquals(listOf(-60f, -90f, -120f, -150f), fixture.mousePan(type), "$dual $type")
                    assertEquals(1, fixture.page, "A cancelled zoomed drag must not turn the page")
                    fixture.doubleTap()
                    assertEquals(ZoomState(), fixture.zoomState)
                    fixture.tapForward()
                    fixture.settle(60)
                    assertEquals(if (dual) 3 else 2, fixture.page)
                }
            }
        }
    }

    @Test
    fun `hardening zoomed resize preserves images and input in ordinary and dual readers`() = runTest {
        for (dual in listOf(false, true)) {
            Fixture(this, dual = dual, originalHeadersBeforeImages = true).use { fixture ->
                fixture.partner.complete(Unit)
                fixture.pump()
                fixture.setZoom(ZoomState(scale = 2f, offsetX = -100f))
                fixture.settle()
                val before = fixture.imageOwners(1)
                val bounds = fixture.redRectangle()
                fixture.resize(360)
                fixture.settle()
                assertEquals(ZoomState(scale = 2f, offsetX = -100f), fixture.zoomState)
                assertTrue(fixture.hasRedPixels())
                assertSame(before.first, fixture.imageOwners(1).first)
                assertSame(before.second, fixture.imageOwners(1).second)
                fixture.resize(400)
                fixture.settle()
                assertEquals(bounds, fixture.redRectangle())
                val offsets = fixture.mousePan()
                assertEquals(listOf(-130f, -160f, -190f), offsets.take(3))
                assertEquals(if (dual) -220f else -201.33333f, offsets.last(), 0.01f)
                assertEquals(1, fixture.page)
                fixture.doubleTap()
                assertEquals(ZoomState(), fixture.zoomState)
            }
        }
    }

    @Test
    fun `dual zoom right click on the crossed spine copies the visible page`() = runTest {
        for (initialPage in listOf(0, 1)) {
            Fixture(this, initialPage = initialPage).use { fixture ->
                fixture.partner.complete(Unit)
                fixture.pump()
                val left = fixture.redBounds().second < 200
                fixture.setZoom(ZoomState(scale = 2f, offsetX = if (left) 200f else -200f))
                fixture.settle()
                assertEquals(RED, fixture.copyAt(Offset(if (left) 350f else 50f, 200f)))
                assertEquals(initialPage, fixture.page)
            }
        }
    }

    @Test
    fun `dual zoom simultaneous pinch preserves its focal point and continues after one finger lifts`() = runTest {
        Fixture(this).use { fixture ->
            fixture.partner.complete(Unit)
            fixture.pump()
            fixture.pinchAndContinue()
            assertEquals(ZoomState(scale = 2f, offsetX = 130f), fixture.zoomState)
            assertEquals(1, fixture.page)
            fixture.doubleTap()
            assertEquals(ZoomState(), fixture.zoomState)
            assertEquals(1, fixture.page)
        }
    }

    @Test
    fun `dual zoom corrects a stale offscreen portrait before reporting visible pixels`() =
        runTest {
            Fixture(
                this,
                canvasWidth = 1_400,
                canvasHeight = 1_000,
                imageWidth = 1_600,
                imageHeight = 3_200,
                originalHeadersBeforeImages = true,
            ).use { fixture ->
                fixture.setZoom(ZoomState(scale = 2f, offsetX = -2_000f))
                fixture.settle(60)
                fixture.imageOwners(1)
                assertTrue(fixture.hasRedPixels())
                assertTrue(1 in fixture.presented, "The corrected portrait must present real image pixels")
            }
        }

    @Test
    fun `dual zoom shared Android Desktop vectors render through the production reader`() = runTest {
        val vectors = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "domain/src/commonTest/resources/reader/dual-page-zoom.csv") }
            .first { it.isFile }
            .readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(',') }
        vectors.forEach { vector ->
            val width = vector[2].toInt()
            val height = vector[3].toInt()
            Fixture(
                this,
                canvasWidth = width,
                canvasHeight = height,
                imageWidth = vector[4].toInt(),
                imageHeight = vector[5].toInt(),
                mode = if (vector[1] == "LEFT") ReadingMode.LTR else ReadingMode.RTL,
            ).use { fixture ->
                fixture.pump()
                fixture.setZoom(ZoomState(vector[6].toFloat(), vector[7].toFloat(), vector[8].toFloat()))
                fixture.settle()
                assertEquals(
                    listOf(
                        vector[9].toInt().coerceIn(0, width),
                        vector[10].toInt().coerceIn(0, height),
                        vector[11].toInt().coerceIn(0, width) - 1,
                        vector[12].toInt().coerceIn(0, height) - 1,
                    ),
                    fixture.redRectangle(),
                    vector[0],
                )
            }
        }
    }

    @Test
    fun `dual zoom focuses either physical page across the complete viewport and resets`() = runTest {
        for (initialPage in listOf(0, 1)) {
            Fixture(this, initialPage = initialPage).use { fixture ->
                fixture.partner.complete(Unit)
                fixture.pump()
                val original = fixture.redRectangle()
                val mount = fixture.mount()
                val frame = fixture.sharedFrame()
                val images = fixture.imageOwners(initialPage)
                val focusPan = if (fixture.redBounds().second < 200) 200f else -200f
                fixture.setZoom(ZoomState(scale = 2f, offsetX = focusPan))
                fixture.settle()
                assertEquals(0 to 399, fixture.redBounds(), "The same whole-viewport transform must focus either slot")
                assertEquals(null, fixture.greenBounds())
                assertSame(mount, fixture.mount())
                assertEquals(frame, fixture.sharedFrame())
                val zoomedImages = fixture.imageOwners(initialPage)
                assertSame(images.first, zoomedImages.first)
                assertSame(images.second, zoomedImages.second)
                fixture.setZoom(ZoomState())
                fixture.settle()
                assertEquals(original, fixture.redRectangle())
            }
        }
    }

    @Test
    fun `dual zoom continuously consumes mouse pan and restores native page turns after reset`() = runTest {
        Fixture(this).use { fixture ->
            fixture.partner.complete(Unit)
            fixture.pump()
            fixture.setZoom(ZoomState(scale = 2f))
            fixture.settle()
            val page = fixture.page
            assertEquals(listOf(-30f, -60f, -90f, -120f), fixture.mousePan())
            assertEquals(page, fixture.page, "A zoomed drag belongs to the display unit, not the pager")
            fixture.doubleTap()
            assertEquals(ZoomState(), fixture.zoomState)
            fixture.dragForward()
            fixture.settle(60)
            assertEquals(3, fixture.page, "The existing pager drag is restored at 1x")
        }
    }

    @Test
    fun `dual zoom late partner preserves focused pixels leases and does not report hidden content`() = runTest {
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
            fixture.pump()
            fixture.assertCertifiedWithPendingPartner()
            fixture.setZoom(ZoomState(scale = 2f, offsetX = -200f))
            fixture.settle()
            assertEquals(0 to 399, fixture.redBounds())
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            val frame = fixture.sharedFrame()
            val images = fixture.imageOwners(1)
            fixture.partner.complete(Unit)
            fixture.settle(40)
            assertEquals(rectangle, fixture.redRectangle())
            assertEquals(null, fixture.greenBounds())
            assertSame(mount, fixture.mount())
            assertEquals(frame, fixture.sharedFrame())
            assertSame(images.first, fixture.imageOwners(1).first)
            assertSame(images.second, fixture.imageOwners(1).second)
            assertEquals(setOf(1), fixture.presented, "A decoded partner outside the transformed viewport is not read")
        }
    }

    @Test
    fun `later real image paints left then cumulative reading appends the earlier page`() = runTest {
        Fixture(this, slowCurrent = true, originalHeadersBeforeImages = true).use { fixture ->
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
    fun `later image holds default pixels while unknown earlier wide waits for explicit full presentation`() = runTest {
        Fixture(
            this,
            facts = false,
            wideCurrent = true,
            slowCurrent = true,
            originalHeadersBeforeImages = true,
            deferredOriginalHeaderPage = 1,
        ).use { fixture ->
            fixture.partner.complete(Unit)
            repeat(40) {
                fixture.frame()
                runCurrent()
            }
            fixture.assertOriginalHeaderPending(1)
            assertTrue(!fixture.hasRedPixels())
            val partnerBounds = requireNotNull(fixture.greenBounds())
            val frame = fixture.sharedFrame()
            fixture.current.complete(Unit)
            fixture.settle(40)
            assertEquals(partnerBounds, fixture.greenBounds())
            assertEquals(frame, fixture.sharedFrame())
            assertTrue(!fixture.hasRedPixels(), "The late wide source cannot draw in a portrait slot")
            fixture.jump(1)
            fixture.pump(expectedBounds = 0 to 399)
            assertTrue(fixture.redBounds().first < 100)
            assertEquals(0 to 399, fixture.redBounds(), "The certified wide original must occupy the full viewport")
            assertEquals(setOf(1), fixture.visibleSources())
            assertEquals(setOf(1, 2), fixture.presented, "Both originals really painted during separate presentations")
            assertEquals(setOf(1, 2), fixture.reports.flatten().mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex))
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
            fixture.assertDecodedGeometryWithoutAnalysis(1, ReaderPageFact.dimensions(72, 48))
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
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
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
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
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
    fun `actual forward drag activates a held partner once then leaves without reading it`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.dragForward()
            repeat(60) {
                fixture.frame()
                runCurrent()
            }
            assertEquals(2, fixture.page)
            fixture.dragForward()
            fixture.settle(60)
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
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
            fixture.assertOriginalHeadersKnownBeforeImages(1, 2)
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
    fun `actual rapid taps activate a held source once then advance the logical target`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.tapForward()
            assertEquals(2, fixture.page, "The first input explicitly activates the held partner")
            fixture.tapForward()
            assertEquals(3, fixture.page, "The next input may leave the still-loading page")
            assertEquals(
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
        }
    }

    @Test
    fun `production wheel input parks on a held partner once without reading it`() = runTest {
        Fixture(this).use { fixture ->
            fixture.pump()
            fixture.wheelForward()
            assertEquals(2, fixture.page)
            fixture.wheelForward()
            assertEquals(3, fixture.page)
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
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
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
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
            fixture.pump()
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            val frame = fixture.sharedFrame()
            fixture.replacePage(5)
            assertEquals(1L, fixture.attempt(5))
            repeat(30) {
                fixture.frame()
                runCurrent()
                assertEquals(rectangle, fixture.redRectangle())
                assertSame(mount, fixture.mount())
                assertEquals(frame, fixture.sharedFrame())
            }
        }
    }

    @Test
    fun `original native close after unrelated rebind releases deferred visible content before recapture`() = runTest {
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
            fixture.pump()
            val closeOriginal = fixture.currentNativeCloseAction()
            val frame = fixture.sharedFrame()
            fixture.replacePage(5)
            assertEquals(1L, fixture.attempt(5), "An unrelated content change must rebind immediately")
            assertEquals(frame, fixture.sharedFrame(), "The original native mount still owns its old ticket")
            fixture.replacePage(1)
            assertEquals(0L, fixture.attempt(1), "The displayed old content remains protected until native close")
            // No Compose frame or capture is allowed between rebind and this actual presenter close.
            closeOriginal()
            assertEquals(1L, fixture.attempt(1), "Native close must reach the successor and accept deferred content")
            closeOriginal()
            assertEquals(1L, fixture.attempt(1), "Duplicate native cleanup must not restart or revoke content")
        }
    }

    @Test
    fun `late decoded wide partner never paints half a page or moves current source image`() = runTest {
        Fixture(this, widePartner = true, originalHeadersBeforeImages = true).use { fixture ->
            fixture.assertOriginalHeadersKnownBeforeImages(1, 2)
            fixture.pump()
            assertTrue(
                fixture.redBounds().second < 200,
                "The unzoomed opening portrait must occupy the RTL left slot: ${fixture.slotDiagnostic()}",
            )
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
    fun `unknown wide partner allows stable default pixels without certification or half painting`() = runTest {
        Fixture(
            this,
            widePartner = true,
            originalHeadersBeforeImages = true,
            deferredOriginalHeaderPage = 2,
        ).use { fixture ->
            repeat(40) {
                fixture.frame()
                runCurrent()
            }
            fixture.assertUnknownPartnerHasNoCertificate()
            assertTrue(fixture.hasRedPixels(), "Only the target geometry is required for default exposure")
            val rectangle = fixture.redRectangle()
            val mount = fixture.mount()
            val frame = fixture.sharedFrame()
            assertEquals(null, fixture.greenBounds())
            fixture.partner.complete(Unit)
            fixture.pump()
            fixture.assertPortraitNotMarkedSpread(1)
            println("CP1_UNKNOWN_WIDE_FIRST_DRAW " + fixture.slotDiagnostic())
            assertTrue(
                fixture.redBounds().first >= 200,
                "The locked default portrait keeps its original RTL right slot: ${fixture.slotDiagnostic()}",
            )
            assertEquals(rectangle, fixture.redRectangle())
            assertSame(mount, fixture.mount())
            assertEquals(frame, fixture.sharedFrame())
            assertEquals(null, fixture.greenBounds(), "The wide partner must not paint in a half slot")
            assertEquals(setOf(1), fixture.presented)
            assertEquals(setOf(1), fixture.reports.flatten().mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex))
            fixture.tapForward()
            fixture.settle(60)
            fixture.pump(GREEN)
            assertEquals(2, fixture.page)
            assertEquals(0 to 399, fixture.greenBounds())
        }
    }

    @Test
    fun `explicit crop keeps partial pixels without reviving consumed reading until source navigation`() = runTest {
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
                listOf(setOf(1)),
                fixture.reports.map {
                    it.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
                },
            )
            assertTrue(fixture.greenBounds() != null, "The new crop still draws the real partner")
            fixture.jump(2)
            fixture.settle(60)
            assertTrue(fixture.reports.any { report -> report.any { it.sourcePageIndex == 2 } })
        }
    }

    @Test
    fun `mounted reader F11 and Escape draw the resized window while retaining current images`() = runTest {
        var placement = WindowPlacement.Floating
        val window = mockk<ComposeWindow>(relaxed = true) {
            every { isDisplayable } returns true
            every { this@mockk.placement } answers { placement }
            every { this@mockk.placement = any() } answers { placement = firstArg() }
        }
        Fixture(this, nativeWindow = window, originalHeadersBeforeImages = true).use { fixture ->
            fixture.partner.complete(Unit)
            fixture.pump()
            val page = fixture.page
            val decoded = fixture.decodedImages.get()

            fixture.windowKey(Key.F11)
            assertEquals(WindowPlacement.Fullscreen, placement)
            verify(exactly = 1) { window.renderImmediately() }
            assertTrue(fixture.hasRedPixels())

            fixture.resize(360)
            fixture.settle()
            fixture.windowKey(Key.Escape)
            assertEquals(WindowPlacement.Floating, placement)
            verify(exactly = 2) { window.renderImmediately() }
            assertTrue(fixture.hasRedPixels())
            assertEquals(page, fixture.page)
            assertEquals(decoded, fixture.decodedImages.get())
        }
    }

    @Test
    fun `fullscreen viewport resize keeps the current image drawn without decoding it again`() = runTest {
        for (dual in listOf(false, true)) {
            Fixture(this, dual = dual, originalHeadersBeforeImages = true).use { fixture ->
                fixture.partner.complete(Unit)
                fixture.pump()
                val decoded = fixture.decodedImages.get()
                val page = fixture.page

                for (width in listOf(380, 360, 400, 370, 400)) {
                    fixture.resize(width)
                    repeat(4) { frame ->
                        fixture.frame()
                        runCurrent()
                        assertTrue(
                            fixture.hasRedPixels(),
                            "Current image disappeared: dual=$dual width=$width frame=$frame",
                        )
                        assertEquals(page, fixture.page, "Resizing must not navigate")
                    }
                }
                assertEquals(decoded, fixture.decodedImages.get(), "Resizing must retain the decoded images")
            }
        }
    }

    @Test
    fun `automatic fullscreen layout keeps the current comic drawn through single and dual remounts`() = runTest {
        Fixture(this, mode = ReadingMode.AUTO, canvasHeight = 240, originalHeadersBeforeImages = true).use { fixture ->
            fixture.resize(240)
            fixture.partner.complete(Unit)
            fixture.pump()
            assertTrue(!fixture.dualPageMode)
            val image = fixture.imageOwners(1)
            for (width in listOf(400, 240, 400, 240, 400, 240)) {
                fixture.resize(width)
                repeat(40) { frame ->
                    advanceTimeBy(16)
                    assertTrue(
                        fixture.hasRedPixels(),
                        "The current comic disappeared during automatic layout: " +
                            "width=$width frame=$frame dual=${fixture.dualPageMode}",
                    )
                    runCurrent()
                    fixture.frame()
                    assertEquals(1, fixture.page, "A layout change must preserve the reading page")
                }
                assertEquals(width == 400, fixture.dualPageMode)
                assertSame(image.first, fixture.imageOwners(1).first, "Layout changes must retain the mounted image")
                assertSame(image.second, fixture.imageOwners(1).second, "Layout changes must retain decoded pixels")
            }
            fixture.tapForward()
            // A logical page change precedes the animated viewport. Use the existing real
            // pixel pump, rather than treating eight rendered frames as a settled page turn.
            fixture.pump(GREEN)
            assertEquals(2, fixture.page, "A real page turn must still work after automatic layout changes")
            assertTrue(
                fixture.greenBounds() != null,
                "The next page must actually draw after automatic layout changes: " + fixture.slotDiagnostic(2),
            )
            fixture.mountReader(false)
            assertEquals(0, fixture.imageHolderCount(), "Leaving the reader must release all mounted slots")
        }
    }

    @Test
    fun `actual viewport resize renews geometry without creating navigation or losing pending partner`() = runTest {
        Fixture(this, originalHeadersBeforeImages = true).use { fixture ->
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
        private val initialPage: Int = 1,
        pageCount: Int = 6,
        private val partnerIndex: Int = 2,
        private val canvasWidth: Int = 400,
        private val canvasHeight: Int = 400,
        imageWidth: Int = 32,
        imageHeight: Int = 48,
        mode: ReadingMode = ReadingMode.RTL,
        private val measureFrames: Boolean = false,
        originalHeadersBeforeImages: Boolean = false,
        private val deferredOriginalHeaderPage: Int? = null,
        deferredOriginalHeaders: Boolean = false,
        wideOriginalPage: Int? = null,
        private val nativeWindow: ComposeWindow? = null,
        heldDecodePages: Set<Int> = emptySet(),
        partnerImageWidth: Int? = null,
    ) : AutoCloseable {
        private val root = Preferences.userRoot().node("/mihon/native-stable/${System.nanoTime()}")
        private val store = DesktopPreferenceStore(root.node("store"))
        private val appPreferences = DesktopAppPreferences(store, root.node("app"))
        val reports = mutableListOf<Set<ReaderPageId>>()
        val presented = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val partner = CompletableDeferred<Unit>()
        val current = CompletableDeferred<Unit>()
        private val heldDecodes = heldDecodePages.associateWith { CompletableDeferred<Unit>() }
        val retries = mutableListOf<Int>()
        val sourceOpens = AtomicInteger()
        val decodedImages = AtomicInteger()
        val releasedImages = AtomicInteger()
        private val copiedColors = CopyOnWriteArrayList<Int>()
        private val menus = IdentityHashMap<ContextMenuState, List<ContextMenuItem>>()
        private val menuRepresentation = object : ContextMenuRepresentation {
            @Composable
            override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
                menus[state] = items()
            }
        }
        private val model = ReaderScreenModel(
            dualPageOverride = dual.takeUnless { mode == ReadingMode.AUTO },
            prefs = ReaderPreferences(store, root.node("reader")).apply {
                readingMode = mode
                isDualPage = dual
                isAutoSpreadMatching = false
                autoSplitPages = false
            },
            initialSessionState = desktopReaderSessionState(pageCount = pageCount, initialPage = initialPage),
            onViewportSettled = { pages, _ -> reports += pages },
            onPageRetry = { retries += it.sourcePageIndex },
        )
        private val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })

        // Opt-in scenarios supply real independent metadata, optionally withholding one header
        // until its image gate opens. The default fixture retains its existing header timing.
        private val originalImages = if (originalHeadersBeforeImages || deferredOriginalHeaders) {
            model.state.value.session.activeChapter.pages.associate { page ->
                val index = page.id.sourcePageIndex
                val width = if ((index == 2 && widePartner) || (index == 1 && wideCurrent) ||
                    index == wideOriginalPage
                ) {
                    72
                } else if (index == partnerIndex) {
                    partnerImageWidth ?: imageWidth
                } else {
                    imageWidth
                }
                val image = BufferedImage(width, imageHeight, BufferedImage.TYPE_INT_ARGB)
                val color = when (index) {
                    initialPage -> RED
                    partnerIndex -> GREEN
                    else -> BLUE
                }
                for (y in 0 until imageHeight) for (x in 0 until width) image.setRGB(x, y, color)
                val encoded = ByteArrayOutputStream().also {
                    assertTrue(ImageIO.write(image, "png", it))
                }.toByteArray()
                requireNotNull(page.encodedPageRef) to encoded
            }
        } else {
            emptyMap()
        }
        private val content = DesktopReaderPageContentOwner(
            scope,
            { ref ->
                sourceOpens.incrementAndGet()
                originalImages[ref] ?: byteArrayOf(1)
            },
            reporter,
        )
        private val pipeline = DesktopReaderPageImagePipeline(
            scope,
            content,
            reporter,
            decoder = DesktopReaderPageImageDecoder { encoded, key ->
                heldDecodes[key.pageIndex]?.await()
                if (key.pageIndex == partnerIndex) partner.await()
                if (key.pageIndex == 1 && slowCurrent) current.await()
                val width = if ((key.pageIndex == 2 && widePartner) ||
                    (key.pageIndex == 1 && wideCurrent) || key.pageIndex == wideOriginalPage
                ) {
                    72
                } else if (key.pageIndex == partnerIndex) {
                    partnerImageWidth ?: imageWidth
                } else {
                    imageWidth
                }
                val bitmapWidth = if (width > 512) 32 else width
                val bitmapHeight = (imageHeight.toDouble() * bitmapWidth / width).roundToInt()
                val bitmap = Bitmap().apply { allocN32Pixels(bitmapWidth, bitmapHeight) }
                decodedImages.incrementAndGet()
                Canvas(bitmap).use {
                    it.clear(
                        when (key.pageIndex) {
                            initialPage -> RED
                            partnerIndex -> GREEN
                            else -> BLUE
                        },
                    )
                }
                val page = model.state.value.session.activeChapter.pages[key.pageIndex].id
                if (key.pageIndex == deferredOriginalHeaderPage) {
                    check(originalHeadersBeforeImages)
                    val (originalWidth, originalHeight) = requireNotNull(SkiaImageDecoder.peekSize(encoded))
                    assertTrue(
                        model.acceptDualFact(
                            model.captureDualFact(page)!!,
                            ReaderPageFact.dimensions(originalWidth, originalHeight),
                        ),
                    )
                } else if (facts) {
                    assertTrue(
                        model.acceptDualFact(
                            model.captureDualFact(page)!!,
                            ReaderPageFact.dimensions(width, imageHeight),
                        ),
                    )
                }
                DesktopReaderImageAsset(
                    bitmap.asComposeImageBitmap(),
                    width,
                    imageHeight,
                    bitmapWidth * bitmapHeight.toLong() * 4,
                    bitmapWidth != width,
                    disposer = {
                        releasedImages.incrementAndGet()
                        bitmap.close()
                    },
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
        private val scene = ImageComposeScene(canvasWidth, canvasHeight, coroutineContext = scope.coroutineContext) {}
        private val focusRequester = FocusRequester()
        private var viewportWidth by mutableStateOf(canvasWidth.dp)
        private var readerMounted by mutableStateOf(true)
        private var closed = false
        private class FrameMeasurements {
            val renderNanos = mutableListOf<Long>()
            var peakResidentBytes = 0L
            var peakPinnedBytes = 0L
            var peakInFlight = 0
            var peakHolders = 0
        }
        private val measurements = linkedMapOf("cold" to FrameMeasurements(), "warm" to FrameMeasurements())
        private var measurementPhase = "cold"
        private var time = 0L
        val page get() = model.state.value.currentPage
        val dualPageMode get() = model.state.value.dualPageMode
        val zoomState get() = model.state.value.zoomState
        fun zoom() = model.setZoomState(ZoomState(scale = 1.35f, offsetX = 7f, offsetY = -11f))
        fun setZoom(value: ZoomState) = model.setZoomState(value)
        fun doubleTap() {
            repeat(2) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 200f), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(200f, 200f), button = PointerButton.Primary)
                settle(2)
            }
        }
        fun copyAt(position: Offset): Int {
            scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Secondary)
            settle(3)
            val items = menus.entries.single { it.key.status is ContextMenuState.Status.Open }.value
            items[1].onClick()
            repeat(40) {
                if (copiedColors.isNotEmpty()) return copiedColors.last()
                settle(1)
            }
            error("The real context menu copy action did not retain the displayed image")
        }
        fun pinchAndContinue() {
            fun pointer(id: Long, x: Float, pressed: Boolean = true) = ComposeScenePointer(
                id = PointerId(id),
                position = Offset(x, 200f),
                pressed = pressed,
                type = PointerType.Touch,
            )
            scene.sendPointerEvent(PointerEventType.Press, listOf(pointer(1, 50f), pointer(2, 150f)))
            settle(2)
            scene.sendPointerEvent(PointerEventType.Move, listOf(pointer(1, 0f), pointer(2, 200f)))
            settle(2)
            scene.sendPointerEvent(PointerEventType.Release, listOf(pointer(1, 0f, false), pointer(2, 200f)))
            settle(2)
            scene.sendPointerEvent(PointerEventType.Move, listOf(pointer(2, 230f)))
            settle(2)
            scene.sendPointerEvent(PointerEventType.Release, listOf(pointer(2, 230f, false)))
            settle(4)
        }
        fun settle(frames: Int = 8) = repeat(frames) {
            frame()
            scope.runCurrent()
        }
        fun pointer(event: PointerEventType, x: Float, type: PointerType) {
            scene.sendPointerEvent(
                event,
                Offset(x, 200f),
                type = type,
                button = if (type == PointerType.Mouse &&
                    event != PointerEventType.Move
                ) {
                    PointerButton.Primary
                } else {
                    null
                },
            )
            settle(2)
        }

        fun dragTo(position: Offset, type: PointerType, release: Boolean = true) {
            scene.sendPointerEvent(
                PointerEventType.Press,
                Offset(200f, 200f),
                type = type,
                button = if (type == PointerType.Mouse) PointerButton.Primary else null,
            )
            settle(2)
            movePointer(position, type)
            if (release) releasePointer(position, type)
        }

        fun movePointer(position: Offset, type: PointerType) {
            scene.sendPointerEvent(PointerEventType.Move, position, type = type)
            settle(2)
        }

        fun releasePointer(position: Offset, type: PointerType) {
            scene.sendPointerEvent(
                PointerEventType.Release,
                position,
                type = type,
                button = if (type == PointerType.Mouse) PointerButton.Primary else null,
            )
            settle(2)
        }

        @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
        fun cancelPointerInput() {
            // ImageComposeScene does not expose the native scene's cancellation entrypoint.
            val field = scene.javaClass.getDeclaredField("scene").apply { isAccessible = true }
            (field.get(scene) as ComposeScene).cancelPointerInput()
            settle(2)
        }
        fun mountReader(mounted: Boolean) {
            readerMounted = mounted
            settle()
        }
        fun mousePan(type: PointerType = PointerType.Mouse): List<Float> {
            scene.sendPointerEvent(
                PointerEventType.Press,
                Offset(320f, 200f),
                type = type,
                button = if (type == PointerType.Mouse) PointerButton.Primary else null,
            )
            scope.runCurrent()
            val offsets = buildList {
                repeat(4) { index ->
                    scene.sendPointerEvent(
                        PointerEventType.Move,
                        Offset(320f - (index + 1) * 30f, 200f),
                        type = type,
                    )
                    settle(2)
                    add(zoomState.offsetX)
                }
            }
            scene.sendPointerEvent(
                PointerEventType.Release,
                Offset(200f, 200f),
                type = type,
                button = if (type == PointerType.Mouse) PointerButton.Primary else null,
            )
            settle(20)
            return offsets
        }
        fun imageOwners(page: Int): Pair<DesktopReaderPresentationImageHolder, DesktopReaderImageAsset> {
            val lockField = owner.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            val holdersField = owner.javaClass.getDeclaredField("holders").apply { isAccessible = true }
            return synchronized(lockField.get(owner)) {
                @Suppress("UNCHECKED_CAST")
                val holders = holdersField.get(owner) as Set<DesktopReaderPresentationImageHolder>
                val holder = holders.single { it.identity.pageId.sourcePageIndex == page }
                holder to (holder.state.value as DesktopReaderPresentationImageState.Ready).asset
            }
        }
        fun resourceSnapshot() = pipeline.snapshot()
        fun contentLeaseCount() = content.snapshot().activeLeaseCounts.values.sum()
        fun imageHolderCount(): Int {
            val lockField = owner.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            val holdersField = owner.javaClass.getDeclaredField("holders").apply { isAccessible = true }
            return synchronized(lockField.get(owner)) { (holdersField.get(owner) as Set<*>).size }
        }
        fun crop() = model.setCropBordersPager(true)
        fun resize(width: Int = 360) {
            viewportWidth = width.dp
        }
        fun windowKey(key: Key) {
            focusRequester.requestFocus()
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") &&
                    !it.name.endsWith("\$default")
            }
            val native = factory.invoke(null, key.keyCode, type, 0, false, false, false, false, null)
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native))
            settle()
            SwingUtilities.invokeAndWait {}
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
            val position = button.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
            scope.runCurrent()
        }
        fun publishOriginalHeaders() {
            model.state.value.session.activeChapter.pages.forEach { original ->
                if (original.id.sourcePageIndex == deferredOriginalHeaderPage) return@forEach
                val encoded = originalImages.getValue(requireNotNull(original.encodedPageRef))
                val (width, height) = requireNotNull(SkiaImageDecoder.peekSize(encoded))
                assertTrue(
                    model.acceptDualFact(
                        model.captureDualFact(original.id)!!,
                        ReaderPageFact.dimensions(width, height),
                    ),
                )
            }
        }
        init {
            owner.beginGeneration(1)
            val page = model.state.value.session.activeChapter.pages[initialPage].id
            if (originalHeadersBeforeImages) {
                publishOriginalHeaders()
            } else if (remoteFact) {
                // A remote header may know C before D's delayed content opens. No D guess is supplied.
                assertTrue(
                    model.acceptDualFact(
                        model.captureDualFact(page)!!,
                        ReaderPageFact.dimensions(imageWidth, imageHeight),
                    ),
                )
            }
            val dependencies = mockk<DesktopUiDependencies> {
                every { appPreferences } returns
                    this@Fixture.appPreferences
                every { shareService } returns DesktopShareService(
                    clipboardPort = object : DesktopClipboardPort {
                        override fun copyText(text: String) = Unit
                        override fun copyImage(image: BufferedImage) {
                            copiedColors += image.getRGB(0, 0)
                        }
                    },
                    isHeadless = { false },
                )
                every { notificationService } returns DesktopNotificationService()
            }
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalContextMenuRepresentation provides menuRepresentation,
                    LocalReaderNativeWindow provides nativeWindow,
                ) {
                    val state by model.state.collectAsState()
                    Box(Modifier.requiredSize(viewportWidth, canvasHeight.dp)) {
                        if (readerMounted) {
                            if (nativeWindow == null) {
                                ReaderContent(state, model, scope, "", "", owner, null, {}, {})
                            } else {
                                ReaderViewport(
                                    state, model, mockk<Navigator>(relaxed = true), focusRequester,
                                    scope, "", "", owner, null, {}, {},
                                )
                            }
                        }
                    }
                }
            }
        }
        fun pump(color: Int = RED, expectedBounds: Pair<Int, Int>? = null) {
            val deadline = System.nanoTime() + 3_000_000_000L
            do {
                frame()
                scope.runCurrent()
            } while (
                (bounds(color) == null || (expectedBounds != null && bounds(color) != expectedBounds)) &&
                System.nanoTime() < deadline
            )
            repeat(8) {
                frame()
                scope.runCurrent()
            }
        }
        fun frame() {
            time += 16_000_000L
            val before = if (measureFrames) System.nanoTime() else 0L
            scene.render(time).close()
            if (measureFrames) {
                val sample = measurements.getValue(measurementPhase)
                sample.renderNanos += System.nanoTime() - before
                val resources = pipeline.snapshot()
                sample.peakResidentBytes = maxOf(sample.peakResidentBytes, resources.memory.residentBytes)
                sample.peakPinnedBytes = maxOf(sample.peakPinnedBytes, resources.memory.pinnedBytes)
                sample.peakInFlight = maxOf(sample.peakInFlight, resources.inFlightCount)
                sample.peakHolders = maxOf(sample.peakHolders, imageHolderCount())
            }
            // The production content/transform owners dispatch genuine I/O outside TestScope.
            Thread.sleep(5)
        }
        fun beginWarmMeasurements() {
            measurementPhase = "warm"
        }
        fun printMeasurements() {
            for ((phase, sample) in measurements) {
                val sorted = sample.renderNanos.sorted()
                assertTrue(sorted.isNotEmpty())
                val p95 = sorted[((sorted.size - 1) * 0.95).roundToInt()] / 1_000_000.0
                val max = sorted.last() / 1_000_000.0
                println(
                    String.format(
                        Locale.ROOT,
                        "OFFSCREEN_RENDER phase=%s frames=%d p95Ms=%.3f maxMs=%.3f " +
                            "sampledPeakResidentBytes=%d sampledPeakPinnedBytes=%d " +
                            "sampledPeakInFlight=%d sampledPeakHolders=%d sourceOpens=%d decodedImages=%d",
                        phase, sorted.size, p95, max, sample.peakResidentBytes, sample.peakPinnedBytes,
                        sample.peakInFlight, sample.peakHolders, sourceOpens.get(), decodedImages.get(),
                    ),
                )
            }
        }
        private fun bounds(color: Int): Pair<Int, Int>? {
            time += 16_000_000L
            val bitmap = scene.render(time).toComposeImageBitmap().asSkiaBitmap()
            return try {
                val xs = (0 until canvasWidth).filter { bitmap.getColor(it, canvasHeight / 2) == color }
                if (xs.isEmpty()) null else xs.first() to xs.last()
            } finally {
                bitmap.close()
            }
        }
        fun redBounds() = requireNotNull(bounds(RED)) { "The real first image must be drawn" }
        fun hasRedPixels() = bounds(RED) != null
        fun greenBounds() = bounds(GREEN)
        fun tapForward() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 200f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 200f), button = PointerButton.Primary)
            scope.runCurrent()
        }
        fun tapBackward() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(380f, 200f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(380f, 200f), button = PointerButton.Primary)
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
        fun finishForwardDrag() {
            scene.sendPointerEvent(PointerEventType.Release, Offset(440f, 200f), type = PointerType.Touch)
            scope.runCurrent()
        }
        fun framesContaining(index: Int) = sharedSession().presentationFrames.filter { frame ->
            frame.unit.pageIds.any { it.sourcePageIndex == index }
        }.mapNotNull { it.frameId }.toSet()
        fun previewForwardDrag() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 200f), type = PointerType.Touch)
            scope.runCurrent()
            repeat(6) { index ->
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    Offset(200f + (index + 1) * 40f, 200f),
                    type = PointerType.Touch,
                )
                settle(2)
            }
        }
        fun wheelForward() {
            val event = java.awt.event.MouseWheelEvent(
                java.awt.Canvas(), java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 200, 200, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1,
            )
            assertTrue(handleReaderWheelEvent(event, model.state.value, model, {}, {}))
        }
        fun redRectangle(): List<Int> = colorRectangle(RED)
        fun comicRectangle(): List<Int> = colorRectangle(RED, GREEN)
        fun colorRectangle(color: Int, additionalColor: Int? = null): List<Int> {
            time += 16_000_000L
            val bitmap = scene.render(time).toComposeImageBitmap().asSkiaBitmap()
            return try {
                var left = canvasWidth
                var top = canvasHeight
                var right = -1
                var bottom = -1
                for (y in 0 until canvasHeight) {
                    for (x in 0 until canvasWidth) {
                        if (bitmap.getColor(x, y) == color || bitmap.getColor(x, y) == additionalColor) {
                            left = minOf(left, x)
                            right = maxOf(right, x)
                            top = minOf(top, y)
                            bottom = maxOf(bottom, y)
                        }
                    }
                }
                assertTrue(
                    right >= left && bottom >= top,
                    "No comic pixels: zoom=$zoomState page=$page ${slotDiagnostic()}",
                )
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
        fun currentDrawnSources(): Set<Int> {
            val session = sharedSession()
            return session.displayedHistory.firstOrNull { it.id == session.targetFrameId }
                ?.drawnPageIds?.mapTo(mutableSetOf()) { it.sourcePageIndex }.orEmpty()
        }
        fun sharedFrame() = requireNotNull(selectedNative().frameId)
        fun liveDrawnFrames(index: Int): Set<mihon.domain.reader.ReaderDualPageFrameId> {
            val session = sharedSession()
            val page = session.identity.pageIds[index]
            val live = session.presentationFrames.mapNotNullTo(mutableSetOf()) { it.frameId }
            return session.displayedHistory.filter {
                it.id in live && page in it.drawnPageIds
            }.mapTo(mutableSetOf()) { it.id }
        }
        fun currentNativeCloseAction(): () -> Unit = selectedNative()::close
        private fun sharedSession() = model.javaClass.getDeclaredField("dualSession").run {
            isAccessible = true
            get(model) as SharedDualPageSession
        }
        fun assertPortraitNotMarkedSpread(index: Int) {
            assertTrue(
                index !in model.state.value.spreadPages,
                "A portrait must not inherit a prior holder's wide marker",
            )
        }
        fun slotDiagnostic(targetPage: Int = initialPage): String {
            val session = sharedSession()
            val pages = session.identity.pageIds
            val facts = pages.mapNotNull { page -> session.pageFact(page)?.let { page to it } }.toMap()
            val settings = session.javaClass.getDeclaredMethod("currentPairingSettings").run {
                isAccessible = true
                invoke(session) as ReaderPairingSettings
            }
            val oracle = SharedDualPageRules.canonicalPlan(
                pages,
                facts,
                settings.direction,
                settings.forcedSinglePages,
                settings.manualPairStarts,
                settings.openingMode,
                settings.automatic,
            )
            val certification = session.certifyPairing()
            val certified = (certification as? ReaderPairingCertification.Certified)?.segment?.unit
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            val displayUnits = nodes.filter {
                it.config.contains(DualPageDisplayUnitIdKey) && it.boundsInRoot.contains(Offset(200f, 200f))
            }.map { it.config[DualPageDisplayUnitIdKey] }
            val physicalSlots = nodes.filter {
                it.config.contains(DualPageSlotIdKey) &&
                    it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == targetPage
            }.map { "${it.config[DualPagePhysicalSlotKey]}@${it.boundsInRoot}" }
            val factSnapshot = facts.map { (page, fact) -> page.sourcePageIndex to fact }
            val oracleSnapshot = oracle.filter { pages[targetPage] in it.pageIds }
            return "bounds=${bounds(RED)} settings=$settings " +
                "target=$targetPage page=${model.state.value.currentPage} cache=${pipeline.snapshot().cache.keys} " +
                "spreadPages=${model.state.value.spreadPages} " +
                "displayUnits=$displayUnits physicalSlots=$physicalSlots " +
                "facts=$factSnapshot " +
                "oracle=$oracleSnapshot " +
                "certified=$certified canonical=${session.canonicalTargetUnit} presentation=${session.targetUnit} " +
                "history=${session.displayedHistory}"
        }
        fun assertDecodedGeometryWithoutAnalysis(index: Int, expected: ReaderPageFact) {
            val session = sharedSession()
            val page = session.identity.pageIds[index]
            assertEquals(expected, session.pageFact(page))
            assertEquals(ReaderPageAnalysisEvidence.NotAnalyzed, session.originalAnalysis(page))
        }
        fun assertOriginalHeadersKnownBeforeImages(vararg pageIndexes: Int) {
            val session = sharedSession()
            pageIndexes.forEach { index ->
                val page = session.identity.pageIds[index]
                assertTrue(session.pageFact(page) != null, "The independent original header must precede pixels")
                assertEquals(ReaderPageAnalysisEvidence.NotAnalyzed, session.originalAnalysis(page))
            }
            assertTrue(presented.isEmpty(), "Metadata setup must not expose any image")
            assertTrue(!partner.isCompleted, "Metadata setup must not release the partner bitmap")
        }
        fun assertUnknownPartnerHasNoCertificate() {
            val session = sharedSession()
            val partnerId = session.identity.pageIds[2]
            assertEquals(null, session.pageFact(partnerId))
            assertTrue(session.certifyPairing() is ReaderPairingCertification.NeedFacts)
            assertEquals(null, session.captureCertifiedViewport(session.location!!.pageId))
            assertTrue(session.displayedHistory.none { partnerId in it.lockedPageIds })
            assertEquals(setOf(1), presented)
            assertTrue(!partner.isCompleted)
        }
        fun assertOriginalHeaderPending(pageIndex: Int) {
            val session = sharedSession()
            val page = session.identity.pageIds[pageIndex]
            assertEquals(null, session.pageFact(page), "The withheld original header must remain unknown")
            assertTrue(session.certifyPairing(page) is ReaderPairingCertification.NeedFacts)
            assertTrue(!current.isCompleted, "The current image and its original header remain gated together")
        }
        fun assertCertifiedWithPendingPartner() {
            val session = sharedSession()
            assertTrue(
                session.certifyPairing() is ReaderPairingCertification.Certified,
                "Original PNG headers must certify the spread before its partner's display decode finishes",
            )
            assertTrue(!partner.isCompleted, "The partner bitmap must remain behind the original decode gate")
            assertEquals(setOf(initialPage), presented, "Only the first page has actually exposed pixels")
        }
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
            if (closed) return
            closed = true
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
