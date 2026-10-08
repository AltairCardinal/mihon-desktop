package mihon.desktop.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.ReaderIoGate
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.test.http.ReaderIoTestModeBridge
import mihon.desktop.test.http.ReaderTestModeController
import mihon.desktop.test.http.testHttpServer
import mihon.desktop.test.state.applicationState
import mihon.desktop.test.state.readerState
import mihon.desktop.ui.reader.DualPageDisplayUnitCompositionIdentityKey
import mihon.desktop.ui.reader.DualPagePhysicalSlot
import mihon.desktop.ui.reader.DualPagePhysicalSlotKey
import mihon.desktop.ui.reader.DualPageSlotIdKey
import mihon.desktop.ui.reader.ReaderPagerNativePresentationKey
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.desktop.ui.reader.ReaderViewport
import mihon.domain.reader.ReaderDualPageFrameId
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.content.ReaderPageContentOpenCoordinator
import mihon.domain.reader.content.ReaderPageContentOpenPort
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.session.ReaderPageSession
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Opt-in captured original archives, through the normal local-CBZ runtime and actual Skia/Compose paints. */
@EnabledIfEnvironmentVariable(named = "MIHON_READER_ORIGINAL_ARCHIVES", matches = ".+")
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SharedDualPageOriginalImagesProductionTest {
    @TempDir lateinit var directory: File

    @Test
    fun `explicit read control holds the real original foreground partner until released`() = runBlocking {
        val previousMode = applicationState.testMode
        val previousController = ReaderIoTestModeBridge.controller
        val controller = ReaderTestModeController()
        applicationState.testMode = true
        ReaderIoTestModeBridge.install(controller)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            Fixture("b1", 14, dual = true, controlledReads = true).use { fixture ->
                readerState.mount(fixture.model)
                try {
                    val response = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/test/reader/io_control/arm"))
                            .header("Content-Type", "application/json")
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    """{
                                      "chapterId":98,"pageIndex":15,"generation":1,
                                      "range":"full","timeoutMillis":10000
                                    }""",
                                ),
                            ).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(200, response.statusCode(), "Explicit foreground partner control must be available")
                    val id = Json.parseToJsonElement(response.body()).jsonObject.getValue("id").jsonPrimitive.content
                    fixture.pixels(14, ReaderPortraitSingleSlot.RIGHT)
                    fixture.awaitDrawObservation {
                        val state = client.send(
                            HttpRequest.newBuilder(
                                URI("http://127.0.0.1:$port/test/reader/io_control/state"),
                            ).GET().build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                        Json.parseToJsonElement(state.body()).jsonObject.getValue("rules").jsonArray.any {
                            it.jsonObject.getValue("id").jsonPrimitive.content == id &&
                                it.jsonObject.getValue("status").jsonPrimitive.content == "HOLDING"
                        }
                    }
                    fixture.stableWhilePartnerReadHeld(14)
                    val released = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/test/reader/io_control/$id/release"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(200, released.statusCode())
                    fixture.pixels(15, ReaderPortraitSingleSlot.LEFT)
                } finally {
                    readerState.unmount(fixture.model)
                }
            }
        } finally {
            server.stop(0, 1000)
            ReaderIoTestModeBridge.clear(controller)
            previousController?.let(ReaderIoTestModeBridge::install)
            applicationState.testMode = previousMode
            controller.close()
        }
    }

    @Test
    fun `explicit foreground read timeout exposes original retry and restores original partner pixels`() = runBlocking {
        val previousMode = applicationState.testMode
        val previousController = ReaderIoTestModeBridge.controller
        val controller = ReaderTestModeController()
        applicationState.testMode = true
        ReaderIoTestModeBridge.install(controller)
        try {
            Fixture("b1", 14, dual = true, controlledReads = true).use { fixture ->
                val id = controller.armRead(fixture.contentOwnerId(), 98, 15, 1, null, 5000)
                fixture.awaitDrawObservation {
                    controller.readControlSnapshot().any { it.id == id && it.status == "HOLDING" }
                }
                fixture.pixels(14, ReaderPortraitSingleSlot.RIGHT)
                fixture.clickOriginalRetry()
                assertEquals("TIMED_OUT", controller.readControlSnapshot().single { it.id == id }.status)
                assertTrue(controller.readControlSnapshot().single { it.id == id }.enteredNanos != null)
                fixture.pixels(15, ReaderPortraitSingleSlot.LEFT)
            }
        } finally {
            ReaderIoTestModeBridge.clear(controller)
            previousController?.let(ReaderIoTestModeBridge::install)
            applicationState.testMode = previousMode
            controller.close()
        }
    }

    @Test
    fun `explicit observation samples real applied geometry while original image commands remain cached`() =
        runBlocking {
            val previousMode = applicationState.testMode
            val previousController = ReaderIoTestModeBridge.controller
            val controller = ReaderTestModeController()
            applicationState.testMode = true
            ReaderIoTestModeBridge.install(controller)
            try {
                Fixture("b1", 14, dual = true).use { fixture ->
                    readerState.mount(fixture.model)
                    try {
                        fixture.pixels(14, ReaderPortraitSingleSlot.RIGHT)
                        assertTrue(ReaderIoTestModeBridge.startDrawObservation(128))
                        fixture.awaitDrawObservation {
                            readerState.slotDrawSnapshot().records.any { it.sourcePageIndex == 14 }
                        }
                        repeat(16) { fixture.observationFrame() }
                        val records = readerState.slotDrawSnapshot().records.filter { it.sourcePageIndex == 14 }
                        val commands = records.filter { it.level == "base-image-draw-command" }
                        val geometry = records.filter { it.level == "compose-frame-geometry" }
                        assertTrue(commands.isNotEmpty(), "A genuine original command must precede geometry replay")
                        assertTrue(
                            geometry.size >= 12,
                            "Cached original images need explicit clock-triggered applied-layout samples; levels=" +
                                records.map { it.level },
                        )
                        assertTrue(commands.size < geometry.size, "Geometry must not manufacture draw commands")
                        val lastCommand = commands.last()
                        val commandMount = lastCommand.mountId
                        val commandAsset = lastCommand.assetIdentity
                        assertTrue(
                            geometry.all { sample ->
                                sample.mountId == commandMount && sample.assetIdentity == commandAsset
                            },
                        )
                        val coordinatePhase = "last-applied-layout-at-compose-clock-callback"
                        val actualCommandTimes = commands.map { it.monotonicNanos }
                        assertTrue(
                            geometry.all { sample ->
                                val layoutTime = requireNotNull(sample.lastLayoutCallbackMonotonicNanos)
                                val commandObserved = sample.lastActualCommand in actualCommandTimes
                                val afterAppliedLayout = sample.monotonicNanos >= layoutTime
                                sample.coordinatePhase == coordinatePhase && sample.composeFrameNanos != null &&
                                    commandObserved && afterAppliedLayout
                            },
                        )
                        val initialWidth = geometry.last().rootQuad[1].x - geometry.last().rootQuad[0].x
                        fixture.model.setZoomState(ZoomState(scale = 1.35f, offsetX = 7f, offsetY = -11f))
                        // The tick precedes its layout work; allow that callback's applied-layout sample to lag once.
                        repeat(2) { fixture.observationFrame() }
                        val moved = readerState.slotDrawSnapshot().records.last {
                            it.sourcePageIndex == 14 && it.level == "compose-frame-geometry"
                        }
                        assertTrue(abs((moved.rootQuad[1].x - moved.rootQuad[0].x) / initialWidth - 1.35f) < .02f)
                        assertTrue(
                            moved.imageLayoutQuad.zip(fixture.currentImageLayoutQuad(14)).all { (sample, applied) ->
                                abs(sample.x - applied.x) < 1f && abs(sample.y - applied.y) < 1f
                            },
                            "Geometry is the actual applied Image layout, not an old quad labelled as the next draw",
                        )
                        fixture.assertObservedArtworkQuad(14, moved.rootQuad)
                    } finally {
                        readerState.unmount(fixture.model)
                    }
                }
            } finally {
                ReaderIoTestModeBridge.clear(controller)
                previousController?.let(ReaderIoTestModeBridge::install)
                applicationState.testMode = previousMode
                controller.close()
            }
        }

    @Test
    fun `test mode records real original slot draw commands through the mounted reader`() = runBlocking {
        val previousMode = applicationState.testMode
        val previousController = ReaderIoTestModeBridge.controller
        val controller = ReaderTestModeController()
        applicationState.testMode = true
        ReaderIoTestModeBridge.install(controller)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            Fixture("b1", 14, dual = true).use { fixture ->
                readerState.mount(fixture.model)
                try {
                    fixture.pixels(14, ReaderPortraitSingleSlot.RIGHT)
                    val start = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/test/reader/draw_observation/start"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(200, start.statusCode(), "The existing reader must permit bounded draw observation")
                    fixture.pixels(14, ReaderPortraitSingleSlot.RIGHT)
                    fixture.awaitDrawObservation {
                        readerState.slotDrawSnapshot().records.any { it.sourcePageIndex == 14 }
                    }
                    val snapshot = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/test/reader/state")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    val observation = Json.parseToJsonElement(snapshot.body()).jsonObject
                        .getValue("slotDrawObservation").jsonObject
                    val records = observation.getValue("records").jsonArray.map { it.jsonObject }
                    assertTrue(records.isNotEmpty(), "Actual original Image draws must reach the bound reader")
                    val actual = records.last {
                        it.getValue("sourcePageIndex").jsonPrimitive.content == "14" &&
                            it.getValue("level").jsonPrimitive.content == "base-image-draw-command"
                    }
                    assertEquals("base-image-draw-command", actual.getValue("level").jsonPrimitive.content)
                    assertEquals(4, actual.getValue("rootQuad").jsonArray.size)
                    assertTrue(actual.getValue("frameId") != JsonNull)
                    assertTrue(actual.getValue("mountId").jsonPrimitive.content.toLong() > 0)
                    assertEquals(fixture.observedFrame(14).toString(), actual.getValue("frameId").jsonPrimitive.content)
                    val points = actual.getValue("rootQuad").jsonArray.map { it.jsonObject }
                    val xs = points.map { it.getValue("x").jsonPrimitive.content.toFloat() }
                    val ys = points.map { it.getValue("y").jsonPrimitive.content.toFloat() }
                    val actualBounds = listOf(xs.min(), ys.min(), xs.max(), ys.max())
                    val paintedBounds = fixture.observedArtworkBounds(14)
                    assertTrue(
                        actualBounds.zip(paintedBounds).all { (reported, pixel) -> abs(reported - pixel) <= 2f },
                        "Reported actual command quad $actualBounds must match real original pixels $paintedBounds",
                    )
                    fixture.model.setZoomState(ZoomState(scale = 1.35f, offsetX = 7f, offsetY = -11f))
                    val zoomStart = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/test/reader/draw_observation/start"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(200, zoomStart.statusCode())
                    fixture.awaitDrawObservation {
                        readerState.slotDrawSnapshot().records.any { it.sourcePageIndex == 14 }
                    }
                    val zoomed = readerState.slotDrawSnapshot().records.last { it.sourcePageIndex == 14 }
                    val width = zoomed.rootQuad.maxOf { it.x } - zoomed.rootQuad.minOf { it.x }
                    assertTrue(
                        abs(width / (xs.max() - xs.min()) - 1.35f) < .02f,
                        "The measured Image-to-root transform must apply real zoom exactly once",
                    )
                    fixture.assertObservedArtworkQuad(14, zoomed.rootQuad)
                    fun postObservation(path: String) = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/test/reader/draw_observation/$path"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    for (invalid in listOf("abc", "0", "513", "999999999999999999")) {
                        assertEquals(400, postObservation("start?capacity=$invalid").statusCode())
                    }
                    val stoppedCapture = readerState.slotDrawSnapshot().captureId
                    assertEquals(200, postObservation("stop").statusCode())
                    assertEquals(false, readerState.slotDrawSnapshot().active)
                    assertEquals(false, readerState.recordSlotDraw(fixture.model, stoppedCapture, zoomed))
                    assertEquals(200, postObservation("start?capacity=2").statusCode())
                    val boundedCapture = readerState.slotDrawSnapshot().captureId
                    assertEquals(false, readerState.recordSlotDraw(fixture.model, stoppedCapture, zoomed))
                    // Exercise the recorder protocol with an actual validated command, not a draw receipt.
                    repeat(3) { assertTrue(readerState.recordSlotDraw(fixture.model, boundedCapture, zoomed)) }
                    val bounded = readerState.slotDrawSnapshot()
                    assertEquals(2, bounded.records.size)
                    assertEquals(1L, bounded.dropped)
                    assertEquals(listOf(2L, 3L), bounded.records.map { it.sequence })
                    Fixture("b1", 14, dual = true).use { successor ->
                        readerState.mount(successor.model)
                        try {
                            assertEquals(false, readerState.slotDrawSnapshot().active)
                            assertTrue(readerState.slotDrawSnapshot().records.isEmpty())
                            assertEquals(false, readerState.recordSlotDraw(fixture.model, boundedCapture, zoomed))
                            assertEquals(200, postObservation("start?capacity=2").statusCode())
                            val successorCapture = readerState.slotDrawSnapshot().captureId
                            readerState.unmount(fixture.model)
                            assertTrue(readerState.slotDrawSnapshot().active, "Old unmount must preserve the successor")
                            assertEquals(false, readerState.recordSlotDraw(fixture.model, successorCapture, zoomed))
                            applicationState.testMode = false
                            try {
                                assertEquals(409, postObservation("start").statusCode())
                                assertEquals(
                                    false,
                                    readerState.recordSlotDraw(successor.model, successorCapture, zoomed),
                                )
                                assertEquals(false, readerState.slotDrawSnapshot().active)
                            } finally {
                                applicationState.testMode = true
                            }
                            assertEquals(200, postObservation("stop").statusCode())
                        } finally {
                            readerState.unmount(successor.model)
                        }
                    }
                } finally {
                    readerState.unmount(fixture.model)
                }
            }
        } finally {
            server.stop(0, 1000)
            ReaderIoTestModeBridge.clear(controller)
            previousController?.let(ReaderIoTestModeBridge::install)
            applicationState.testMode = previousMode
            controller.close()
        }
    }

    @Test
    fun `real home cold jumps preserve forward original B1 tail pairing`() = runBlocking {
        for (target in listOf(14, 16)) {
            Fixture("b1", 0, dual = true, animated = true, matching = true).use { fixture ->
                fixture.pixels(0, ReaderPortraitSingleSlot.LEFT)
                fixture.assertNotPreviouslyDrawn(target)
                fixture.model.goToPage(target)
                fixture.pixels(target, ReaderPortraitSingleSlot.RIGHT)
                fixture.assertTargetSources(if (target == 14) listOf(14, 15) else listOf(16))
                if (target == 14) fixture.pixels(15, ReaderPortraitSingleSlot.LEFT)
            }
        }
    }

    @Test
    fun `original B1 manual tail cached pointer reverse and return keep artwork in every actual frame`() = runBlocking {
        Fixture("b1", 0, dual = true, animated = true, matching = true).use { fixture ->
            fixture.pixels(0, ReaderPortraitSingleSlot.LEFT)
            fixture.next()
            fixture.pixels(1, ReaderPortraitSingleSlot.RIGHT)
            fixture.pixels(2, ReaderPortraitSingleSlot.LEFT)
            fixture.model.adjustSpread()
            assertEquals(setOf(1), fixture.model.state.value.forcedSinglePages)
            // The real slider first lands on source 16 before the explicit source-17 endpoint.
            fixture.model.goToPage(15)
            fixture.pixels(15, ReaderPortraitSingleSlot.RIGHT)
            fixture.pixels(16, ReaderPortraitSingleSlot.LEFT)
            fixture.model.goToPage(16)
            fixture.pixels(16, ReaderPortraitSingleSlot.LEFT)
            fixture.cachedTailTurns()
        }
    }

    @Test
    fun `original B1 cold seventeen reverses to unknown sixteen and returns with stable late pixels`() = runBlocking {
        Fixture("b1", 16, dual = true, held = setOf(14, 15), heldDecode = setOf(14)).use { fixture ->
            fixture.pixels(16, ReaderPortraitSingleSlot.RIGHT)
            fixture.previous()
            fixture.pixels(15, ReaderPortraitSingleSlot.RIGHT)
            fixture.stableAfterHeaders(15, ReaderPortraitSingleSlot.RIGHT)
            fixture.next()
            fixture.pixels(16, ReaderPortraitSingleSlot.RIGHT)
        }
    }

    @Test
    fun `original B1 ready fifteen and sixteen retain ordinary dual and reachable seventeen`() = runBlocking {
        Fixture("b1", 14, dual = false).use { fixture ->
            fixture.knownNeighbors(14, mapOf(15 to ReaderPageFactState.PORTRAIT))
            fixture.model.setDualPageMode(true)
            fixture.pixels(14, ReaderPortraitSingleSlot.RIGHT)
            fixture.pixels(15, ReaderPortraitSingleSlot.LEFT)
            fixture.next()
            fixture.pixels(16, ReaderPortraitSingleSlot.RIGHT)
        }
    }

    @Test
    fun `original B2 cold second stays right and actual third wide remains reachable`() = runBlocking {
        Fixture("b2", 1, dual = true, held = setOf(0, 2)).use { fixture ->
            fixture.pixels(1, ReaderPortraitSingleSlot.RIGHT)
            fixture.stableAfterHeaders(1, ReaderPortraitSingleSlot.RIGHT)
            fixture.next()
            fixture.pixels(2, ReaderPortraitSingleSlot.FULL)
        }
    }

    @Test
    fun `original B2 cold twenty stays right and twenty one wide and last artwork remain reachable`() = runBlocking {
        Fixture("b2", 19, dual = true, held = setOf(18, 20)).use { fixture ->
            fixture.pixels(19, ReaderPortraitSingleSlot.RIGHT)
            fixture.stableAfterHeaders(19, ReaderPortraitSingleSlot.RIGHT)
            fixture.next()
            fixture.pixels(20, ReaderPortraitSingleSlot.FULL)
            fixture.next()
            fixture.pixels(21, ReaderPortraitSingleSlot.RIGHT)
        }
    }

    @Test
    fun `original B2 known opening paints second in left before its first dual pixels`() = runBlocking {
        Fixture("b2", 1, dual = false).use { fixture ->
            fixture.knownNeighbors(1, mapOf(0 to ReaderPageFactState.PORTRAIT, 2 to ReaderPageFactState.SPREAD))
            fixture.model.setDualPageMode(true)
            fixture.pixels(1, ReaderPortraitSingleSlot.LEFT)
            fixture.stableAfterHeaders(1, ReaderPortraitSingleSlot.LEFT)
        }
    }

    @Test
    fun `original B2 known wide neighbors paint twenty full before its first dual pixels`() = runBlocking {
        Fixture("b2", 19, dual = false).use { fixture ->
            fixture.knownNeighbors(19, mapOf(18 to ReaderPageFactState.SPREAD, 20 to ReaderPageFactState.SPREAD))
            fixture.model.setDualPageMode(true)
            fixture.pixels(19, ReaderPortraitSingleSlot.FULL)
            fixture.stableAfterHeaders(19, ReaderPortraitSingleSlot.FULL)
        }
    }

    private inner class Fixture(
        volume: String,
        initial: Int,
        dual: Boolean,
        private val held: Set<Int> = emptySet(),
        private val heldDecode: Set<Int> = held.filter { it > initial }.toSet(),
        animated: Boolean = false,
        matching: Boolean = false,
        controlledReads: Boolean = false,
    ) : AutoCloseable {
        private val archive = File(
            requireNotNull(System.getenv("MIHON_READER_ORIGINAL_ARCHIVES")),
            "udp-real-$volume-original.cbz",
        )
        private val expectedHash = if (volume == "b1") {
            "fec7ffef2dc6773c0c917b5e9961fff745cca5eb9eaccc82deee2dffe26b522b"
        } else {
            "760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439"
        }
        private val zip = ZipFile(archive)
        private val entries = zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.toList()
        private val dispatcher = StandardTestDispatcher()
        private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val uiScope = CoroutineScope(SupervisorJob() + dispatcher)
        private val legacy = Preferences.userRoot().node("/mihon/original-native/${System.nanoTime()}")
        private val preferences = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = dual
            isAutoSpreadMatching = matching
            autoSplitPages = false
            cropBordersPager = false
            backgroundTheme = ReaderBackgroundTheme.BLACK
            scaleType = ScaleType.FIT_SCREEN
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        private val enteredHeaders = CopyOnWriteArrayList<Int>()
        private val returnedHeaders = CopyOnWriteArrayList<Int>()
        private val enteredDecodes = CopyOnWriteArrayList<Int>()
        private val returnedDecodes = CopyOnWriteArrayList<Int>()
        private val decoded = CompletableDeferred<Unit>()
        private val decoder = SkiaDesktopReaderPageImageDecoder()
        private val factory = DesktopReaderRuntimeFactory(
            preferences,
            DesktopDownloadProvider(directory.resolve("downloads-$volume-$initial")),
            FakeDesktopSourceManager(emptyList()),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            directory.resolve("encoded-$volume-$initial"),
            readerIoProbe = ReaderIoProbe(events::add),
            readerIoGate = if (controlledReads) ReaderIoTestModeBridge else ReaderIoGate.None,
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (key.pageIndex in heldDecode) {
                    enteredDecodes += key.pageIndex
                    decoded.await()
                }
                decoder.decode(encoded, key).also {
                    if (key.pageIndex in heldDecode) returnedDecodes += key.pageIndex
                }
            },
        )
        private val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                98, 42, "/original", "Original", volume, 98.0, 0, initial, false,
                localChapterPath = archive.absolutePath,
            ),
            runtimeScope,
        )
        private val coordinatorField = DesktopReaderPageContentOwner::class.java.getDeclaredField("coordinator")
            .apply { isAccessible = true }
        private val coordinator = coordinatorField.get(runtime.pageContentOwner)
        private val portField = ReaderPageContentOpenCoordinator::class.java.getDeclaredField("port")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        private val port = portField.get(coordinator) as ReaderPageContentOpenPort<ByteArray>
        private val headers = CompletableDeferred<Unit>()
        val model: ReaderScreenModel
        private val collector: kotlinx.coroutines.Job
        private val scene: ImageComposeScene
        private var time = 0L
        private val observations = mutableMapOf<Int, Observation>()
        private val firstPrepared = mutableMapOf<Int, FirstPrepared>()
        private var knownFacts = emptyMap<Int, ReaderPageFactState>()

        init {
            assertEquals(
                expectedHash,
                archive.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                },
            )
            assertEquals(if (volume == "b1") 17 else 22, entries.size)
            portField.set(
                coordinator,
                ReaderPageContentOpenPort<ByteArray> { request ->
                    if (request.maxBytes != null && request.pageId.sourcePageIndex in held) {
                        enteredHeaders += request.pageId.sourcePageIndex
                        headers.await()
                    }
                    port.open(request).also { bytes ->
                        if (request.maxBytes != null && request.pageId.sourcePageIndex in held) {
                            returnedHeaders += request.pageId.sourcePageIndex
                            println(
                                "original-late-header source=${request.pageId.sourcePageIndex + 1} " +
                                    "prefixSize=${bytes?.size} orientedSize=${bytes?.let(SkiaImageDecoder::peekSize)}",
                            )
                        }
                    }
                },
            )
            model = factory.createModel(runtime, false, 0, dual)
            // Instrument this exact object; no shallow spy, replacement model, or second owner.
            mockkObject(model)
            every { model.prepareDecodedDualViewportPage(any(), any(), any(), any()) } answers {
                val accepted = callOriginal()
                if (accepted && model.state.value.dualPageMode) {
                    val ticket = firstArg<ReaderDualPageViewportTicket>()
                    val page = secondArg<ReaderPageSession>()
                    val frame = requireNotNull(owner().frame(ticket))
                    val position = frame.unit.slots.single { it.pageId == page.id }.position
                    val facts = owner().identity.pageIds.associate { id ->
                        id.sourcePageIndex to (owner().pageFact(id)?.state ?: ReaderPageFactState.UNKNOWN)
                    }
                    firstPrepared.putIfAbsent(page.id.sourcePageIndex, FirstPrepared(frame.id, position, facts))
                }
                accepted
            }
            collector = runtimeScope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
            val app = DesktopAppPreferences(InMemoryPreferenceStore(), legacy.node("app"))
                .apply { pageTurnAnimation.set(animated) }
            val dependencies = mockk<DesktopUiDependencies> { every { appPreferences } returns app }
            scene = ImageComposeScene(700, 400, coroutineContext = uiScope.coroutineContext) {}.apply {
                setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        MaterialTheme {
                            val state by model.state.collectAsState()
                            val focus = remember { FocusRequester() }
                            Box(Modifier.requiredSize(700.dp, 400.dp)) {
                                ReaderViewport(
                                    state, model, mockk(relaxed = true), focus, uiScope, "", "",
                                    runtime.presentationImageOwner, null, {}, {},
                                )
                            }
                        }
                    }
                }
            }
        }

        private fun owner(): SharedDualPageSession = requireNotNull(
            ReaderScreenModel::class.java.getDeclaredField("dualSession").apply { isAccessible = true }.get(model)
                as SharedDualPageSession?,
        )

        fun assertNotPreviouslyDrawn(source: Int) {
            assertTrue(
                owner().displayedHistory.none { frame ->
                    frame.drawnPageIds.any { it.sourcePageIndex == source }
                },
                "The cold jump target must not already have a real image history",
            )
        }

        fun assertTargetSources(expected: List<Int>) {
            assertEquals(expected, owner().targetUnit!!.slots.map { it.pageId.sourcePageIndex })
        }

        fun observedArtworkBounds(source: Int) = observations.getValue(source).rectangle
        fun observedFrame(source: Int) = observations.getValue(source).frame
        fun contentOwnerId() = runtime.pageContentOwner.controlOwnerId

        suspend fun clickOriginalRetry() {
            var retry: SemanticsNode? = null
            await("The actual failed image must expose its original Retry button") {
                retry = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.firstOrNull { node ->
                    node.config.contains(SemanticsActions.OnClick) &&
                        node.config.contains(SemanticsProperties.Role) &&
                        node.config[SemanticsProperties.Role] ==
                        Role.Button &&
                        Rect(0f, 0f, 700f, 400f).contains(node.boundsInRoot.center) &&
                        flatten(node).any { child ->
                            child.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                                .any { it.text == tachiyomi.i18n.MR.strings.action_retry.localized() }
                        }
                }
                retry != null
            }
            val attempt = model.state.value.session.activeChapter.pages[15].attemptGeneration
            assertEquals(14, model.state.value.currentPage, "The actual failed partner is beside the current page")
            val button = requireNotNull(retry).boundsInRoot
            scene.sendPointerEvent(PointerEventType.Press, button.center, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, button.center, button = PointerButton.Primary)
            await("The pointer must execute the original Retry and advance the actual page attempt") {
                model.state.value.session.activeChapter.pages[15].attemptGeneration > attempt
            }
            assertEquals(
                14,
                model.state.value.currentPage,
                "Retry must not also dispatch the parent page turn; actual button=$button",
            )
        }

        suspend fun awaitDrawObservation(done: () -> Boolean) = await("A real image command must be observed", done)
        fun observationFrame() = render()
        fun stableWhilePartnerReadHeld(source: Int) {
            val first = observations.getValue(source)
            repeat(12) {
                val actual = requireNotNull(observe(source, ReaderPortraitSingleSlot.RIGHT))
                assertEquals(first.frame, actual.frame)
                assertSame(first.mount, actual.mount)
                assertEquals(first.rectangle, actual.rectangle)
            }
        }
        fun currentImageLayoutQuad(source: Int): List<mihon.desktop.test.state.ReaderDrawPoint> {
            val slot = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
                it.config.contains(DualPageSlotIdKey) &&
                    it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == source && !it.boundsInRoot.isEmpty
            }
            val image = flatten(slot).first {
                it.config.contains(SemanticsProperties.ContentDescription)
            }.layoutInfo.coordinates
            assertTrue(image.isAttached)
            return listOf(
                Offset.Zero,
                Offset(image.size.width.toFloat(), 0f),
                Offset(image.size.width.toFloat(), image.size.height.toFloat()),
                Offset(0f, image.size.height.toFloat()),
            ).map { point ->
                image.localToRoot(point).let { mihon.desktop.test.state.ReaderDrawPoint(it.x, it.y) }
            }
        }

        fun assertObservedArtworkQuad(source: Int, quad: List<mihon.desktop.test.state.ReaderDrawPoint>) {
            dispatcher.scheduler.runCurrent()
            time += 16_000_000L
            val actual = scene.render(time).toComposeImageBitmap().asSkiaBitmap()
            val reference = zip.getInputStream(entries[source]).use {
                SkiaImageDecoder.decode(it.readBytes()).asSkiaBitmap()
            }
            try {
                val width = quad[1].x - quad[0].x
                val height = quad[3].y - quad[0].y
                val slot = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
                    it.config.contains(DualPageSlotIdKey) &&
                        it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == source && !it.boundsInRoot.isEmpty
                }.boundsInRoot
                val clip = slot.intersect(Rect(0f, 0f, actual.width.toFloat(), actual.height.toFloat()))
                val sourceRoi = Rect(
                    ((clip.left - quad[0].x) / width).coerceIn(0f, 1f) * reference.width,
                    ((clip.top - quad[0].y) / height).coerceIn(0f, 1f) * reference.height,
                    ((clip.right - quad[0].x) / width).coerceIn(0f, 1f) * reference.width,
                    ((clip.bottom - quad[0].y) / height).coerceIn(0f, 1f) * reference.height,
                )
                val colors = samplePoints(
                    reference,
                    ceil(2 / (width / reference.width)).toInt(),
                    sourceRoi,
                ).map { (x, y) ->
                    val u = (x + .5f) / reference.width
                    val v = (y + .5f) / reference.height
                    val px = (
                        quad[0].x + (quad[1].x - quad[0].x) * u +
                            (quad[3].x - quad[0].x) * v
                        ).roundToInt()
                    val py = (
                        quad[0].y + (quad[1].y - quad[0].y) * u +
                            (quad[3].y - quad[0].y) * v
                        ).roundToInt()
                    assertTrue(
                        px in 0 until actual.width && py in 0 until actual.height,
                        "Actual source ROI $sourceRoi maps outside viewport at $px/$py; quad=$quad clip=$clip",
                    )
                    reference.getColor(x, y) to actual.getColor(px, py)
                }
                val matched = colors.count { colorDistance(it.first, it.second) < 35 }
                val paintedSpan = span(colors.map { it.second })
                println(
                    "observed-artwork source=$source matched=$matched/25 span=$paintedSpan quad=$quad roi=$sourceRoi",
                )
                assertTrue(
                    matched >= 20,
                    "Actual command must match original ROI: $matched/25; quad=$quad roi=$sourceRoi",
                )
                assertTrue(paintedSpan > 70 && span(colors.map { it.first }) > 70)
            } finally {
                reference.close()
                actual.close()
                dispatcher.scheduler.advanceTimeBy(16)
            }
        }

        suspend fun knownNeighbors(target: Int, expected: Map<Int, ReaderPageFactState>) {
            assertTrue(!model.state.value.dualPageMode)
            for ((index, type) in expected) {
                model.goToPage(index)
                await("A normal Single visit must decode original source $index") {
                    model.state.value.currentPage == index &&
                        runCatching { owner().pageFact(owner().identity.pageIds[index])?.state == type }
                            .getOrDefault(false) &&
                        owner().displayedHistory.any { it.drawnPageIds.any { page -> page.sourcePageIndex == index } }
                }
            }
            model.goToPage(target)
            await("Ordinary encoded headers must precede this explicit mode change") {
                model.state.value.currentPage == target && expected.all { (index, type) ->
                    runCatching { owner().pageFact(owner().identity.pageIds[index])?.state == type }.getOrDefault(false)
                } && events.any { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED }
            }
            assertTrue(!model.state.value.dualPageMode)
            assertTrue(firstPrepared.isEmpty(), "The normal Single warm-up does not prepare a Dual frame")
            knownFacts = expected
            println("original-known before-dual target=$target facts=$expected content=$expectedHash")
        }

        suspend fun next() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 200f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 200f), button = PointerButton.Primary)
            dispatcher.scheduler.runCurrent()
            delay(5)
        }

        suspend fun previous() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(680f, 200f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(680f, 200f), button = PointerButton.Primary)
            dispatcher.scheduler.runCurrent()
            delay(5)
        }

        private fun pointerTurn(forward: Boolean) {
            val point = Offset(if (forward) 20f else 680f, 200f)
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
        }

        suspend fun cachedTailTurns() {
            val references = (14..16).associateWith { source ->
                zip.getInputStream(entries[source]).use { SkiaImageDecoder.decode(it.readBytes()).asSkiaBitmap() }
            }
            try {
                for (forward in listOf(false, true)) {
                    pointerTurn(forward)
                    assertEquals(if (forward) 15 else 14, model.state.value.currentPage)
                    repeat(40) { index ->
                        // Inspect existing Ready assets without pinning them across the render.
                        val leases = runtime.presentationImageOwner.retainCachedFullPageAssets()
                        val ready = try {
                            leases.filterValues { it.asset.sourceWidth > 0 && it.asset.sourceHeight > 0 }.keys.toSet()
                        } finally {
                            leases.values.forEach { it.close() }
                        }
                        assertTrue(setOf(15, 16).all { it in ready }, "The original tail pair must already be Ready")
                        time += 16_000_000L
                        // No runCurrent or preparation drain before this first post-pointer render.
                        val actual = scene.render(time).toComposeImageBitmap().asSkiaBitmap()
                        try {
                            val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                            val frames = nodes.filter { it.config.contains(ReaderPagerNativePresentationKey) }
                                .map { node ->
                                    val native = node.config[ReaderPagerNativePresentationKey]
                                    "${native.frameId}:${native.describe()}"
                                }
                            val slots = nodes.filter {
                                it.config.contains(DualPageSlotIdKey) &&
                                    it.config[DualPageSlotIdKey].pageId?.sourcePageIndex in references
                            }
                            var artwork = false
                            val details = slots.map { slot ->
                                val source = requireNotNull(slot.config[DualPageSlotIdKey].pageId).sourcePageIndex
                                val reference = references.getValue(source)
                                val coordinates = slot.layoutInfo.coordinates
                                val position = coordinates.positionInRoot()
                                val rectangle = Rect(
                                    position.x,
                                    position.y,
                                    position.x + coordinates.size.width,
                                    position.y + coordinates.size.height,
                                )
                                val scale = min(rectangle.width / reference.width, rectangle.height / reference.height)
                                val width = reference.width * scale
                                val height = reference.height * scale
                                val left = when (slot.config[DualPagePhysicalSlotKey]) {
                                    DualPagePhysicalSlot.LEFT -> rectangle.right - width
                                    DualPagePhysicalSlot.RIGHT -> rectangle.left
                                    DualPagePhysicalSlot.FULL -> rectangle.center.x - width / 2
                                }
                                val top = rectangle.center.y - height / 2
                                val points = samplePoints(reference, ceil(2 / scale).toInt())
                                val samples = points.mapNotNull { (x, y) ->
                                    val px = (left + (x + .5f) * scale).roundToInt()
                                    val py = (top + (y + .5f) * scale).roundToInt()
                                    if (px !in 0 until actual.width || py !in 0 until actual.height) {
                                        null
                                    } else {
                                        Triple(
                                            Offset(px.toFloat(), py.toFloat()),
                                            reference.getColor(x, y),
                                            actual.getColor(px, py),
                                        )
                                    }
                                }
                                val matched = samples.count { colorDistance(it.second, it.third) < 35 }
                                val painted = samples.map { it.third }
                                val content = Rect(left, top, left + width, top + height)
                                val complete = content.left >= 0 && content.right <= 700 &&
                                    content.top >= 0 && content.bottom <= 400 && samples.size == 25
                                if (complete && source in ready) {
                                    assertTrue(
                                        matched >= 20 && span(painted) > 70,
                                        "Original source=$source frame=$index forward=$forward matched=$matched " +
                                            "rect=$rectangle frames=$frames history=${owner().displayedHistory}",
                                    )
                                }
                                val bright = samples.filter {
                                    colorDistance(it.second, it.third) < 35 &&
                                        listOf(0, 8, 16).any { shift -> (it.third ushr shift and 255) > 70 }
                                }
                                val spanX = if (bright.isEmpty()) {
                                    0f
                                } else {
                                    bright.maxOf { it.first.x } - bright.minOf { it.first.x }
                                }
                                val spanY = if (bright.isEmpty()) {
                                    0f
                                } else {
                                    bright.maxOf { it.first.y } - bright.minOf { it.first.y }
                                }
                                val dispersed = bright.size >= 4 && spanX > 32 && spanY > 32
                                artwork = artwork || dispersed
                                "$source:slot=$rectangle,content=$content,matched=$matched/${samples.size}," +
                                    "bright=${bright.size},spread=$spanX/$spanY,dispersed=$dispersed"
                            }
                            println(
                                "original-cached-turn forward=$forward render=$index ready=$ready " +
                                    "logical=${model.state.value.currentPage} " +
                                    "frames=$frames slots=$details artwork=$artwork",
                            )
                            assertTrue(artwork, "Cached original turn exposed a black frame: $details frames=$frames")
                        } finally {
                            actual.close()
                        }
                        dispatcher.scheduler.advanceTimeBy(16)
                        dispatcher.scheduler.runCurrent()
                        delay(5)
                    }
                    if (!forward) pixels(14, ReaderPortraitSingleSlot.RIGHT)
                }
            } finally {
                references.values.forEach { it.close() }
            }
        }

        suspend fun pixels(source: Int, position: ReaderPortraitSingleSlot) {
            await("Original page ${source + 1} must paint at $position") {
                observe(source, position)?.also { observations[source] = it } != null
            }
            val first = requireNotNull(observations[source])
            val prepared = requireNotNull(firstPrepared[source])
            assertEquals(position, prepared.position, "The first successful native prepare chooses this actual slot")
            assertEquals(first.frame, prepared.frame, "First preparation and actual pixels retain the same context")
            knownFacts.forEach { (index, type) -> assertEquals(type, prepared.facts[index]) }
            if (!headers.isCompleted) {
                held.filter { it != source }.forEach { assertEquals(ReaderPageFactState.UNKNOWN, prepared.facts[it]) }
            }
            println(
                "original-first source=${source + 1} position=$position frame=${first.frame} " +
                    "rectangle=${first.rectangle} matched=${first.matched}/25 span=${first.span} " +
                    "firstPrepare=$prepared content=$expectedHash",
            )
        }

        suspend fun stableAfterHeaders(source: Int, position: ReaderPortraitSingleSlot) {
            val first = requireNotNull(observations[source])
            if (held.isNotEmpty()) {
                await("A real held header or decoder must have entered") {
                    enteredHeaders.isNotEmpty() || enteredDecodes.isNotEmpty()
                }
            }
            val relevant = (enteredHeaders + enteredDecodes).toSet()
            assertTrue(held.isEmpty() || relevant.isNotEmpty())
            headers.complete(Unit)
            decoded.complete(Unit)
            await("Held normal headers must finish") {
                enteredHeaders.all { it in returnedHeaders } && enteredDecodes.all { it in returnedDecodes } &&
                    relevant.all { index ->
                        runCatching {
                            owner().pageFact(owner().identity.pageIds[index])?.state in
                                setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD)
                        }.getOrDefault(false)
                    }
            }
            repeat(12) {
                val observed = observe(source, position)
                val actual = requireNotNull(observed) {
                    "Original after-header source=$source expected=$position " +
                        "showUI=${model.state.value.showUI} " +
                        "target=${owner().targetUnit} history=${owner().displayedHistory} " +
                        "native=${scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.filter {
                            it.config.contains(ReaderPagerNativePresentationKey)
                        }.map { it.boundsInRoot to it.config[ReaderPagerNativePresentationKey].describe() }}"
                }
                assertEquals(first.frame, actual.frame)
                assertSame(first.mount, actual.mount)
                assertEquals(first.rectangle, actual.rectangle)
                assertEquals(source, model.state.value.currentPage)
            }
        }

        private suspend fun await(message: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!done() && System.nanoTime() < deadline) {
                render()
                delay(5)
            }
            assertTrue(
                done(),
                "$message; state=${model.state.value.currentPage}, " +
                    "units=${model.state.value.dualPageUnits}",
            )
        }

        private fun render() {
            dispatcher.scheduler.runCurrent()
            time += 16_000_000L
            scene.render(time).close()
            dispatcher.scheduler.advanceTimeBy(16)
        }

        private fun observe(source: Int, position: ReaderPortraitSingleSlot): Observation? {
            // Compose's render flushes layout. Read this frame's semantics after rendering,
            // rather than combining the preceding layout with pixels from the next one.
            dispatcher.scheduler.runCurrent()
            time += 16_000_000L
            val actual = scene.render(time).toComposeImageBitmap().asSkiaBitmap()
            return try {
                if (!model.state.value.dualPageMode) return null
                val unit = owner().targetUnit ?: return null
                if (unit.slots.none {
                        it.pageId.sourcePageIndex == source && it.position == position
                    }
                ) {
                    return null
                }
                val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                    .filter { it.boundsInRoot.contains(Offset(350f, 200f)) }
                val native = nodes.firstOrNull { it.config.contains(ReaderPagerNativePresentationKey) }
                    ?.config?.get(ReaderPagerNativePresentationKey) ?: return null
                val frame = native.frameId ?: return null
                if (owner().displayedHistory.none {
                        it.id == frame && it.drawnPageIds.any { page ->
                            page.sourcePageIndex == source
                        }
                    }
                ) {
                    return null
                }
                val mount = nodes.firstOrNull { it.config.contains(DualPageDisplayUnitCompositionIdentityKey) }
                    ?.config?.get(DualPageDisplayUnitCompositionIdentityKey) ?: return null
                val slot = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.firstOrNull {
                    it.config.contains(DualPageSlotIdKey) &&
                        it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == source &&
                        it.boundsInRoot.left < 700f && it.boundsInRoot.right > 0f && !it.boundsInRoot.isEmpty
                } ?: return null
                val encoded = zip.getInputStream(entries[source]).use { it.readBytes() }
                val reference = SkiaImageDecoder.decode(encoded).asSkiaBitmap()
                try {
                    val width = slot.boundsInRoot.width
                    val height = slot.boundsInRoot.height
                    val scale = min(width / reference.width, height / reference.height)
                    val imageWidth = reference.width * scale
                    val imageHeight = reference.height * scale
                    val left = when (position) {
                        ReaderPortraitSingleSlot.LEFT -> slot.boundsInRoot.right - imageWidth
                        ReaderPortraitSingleSlot.RIGHT -> slot.boundsInRoot.left
                        ReaderPortraitSingleSlot.FULL -> slot.boundsInRoot.center.x - imageWidth / 2
                    }
                    val top = slot.boundsInRoot.center.y - imageHeight / 2
                    val points = samplePoints(reference, ceil(2 / scale).toInt())
                    val colors = points.map { (x, y) -> reference.getColor(x, y) }
                    val painted = points.map { (x, y) ->
                        val px = (left + (x + .5f) * scale).roundToInt().coerceIn(0, actual.width - 1)
                        val py = (top + (y + .5f) * scale).roundToInt().coerceIn(0, actual.height - 1)
                        actual.getColor(px, py)
                    }
                    val matched = colors.zip(painted).count { (expected, pixel) -> colorDistance(expected, pixel) < 35 }
                    val span = span(painted)
                    if (matched < 20 || span <= 70 || span(colors) <= 70) {
                        println(
                            "original-observe-miss source=$source matched=$matched span=$span " +
                                "slot=${slot.boundsInRoot} frame=$frame",
                        )
                        return null
                    }
                    val paintedBounds = pixelBounds(actual, slot.boundsInRoot)
                    Observation(frame, mount, paintedBounds, matched, span)
                } finally {
                    reference.close()
                }
            } finally {
                actual.close()
                dispatcher.scheduler.advanceTimeBy(16)
            }
        }

        override fun close() {
            headers.complete(Unit)
            decoded.complete(Unit)
            scene.close()
            unmockkObject(model)
            collector.cancel()
            model.onDispose()
            runtime.close()
            runtimeScope.cancel()
            uiScope.cancel()
            portField.set(coordinator, port)
            assertTrue(runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
            legacy.removeNode()
            zip.close()
        }
    }

    private data class Observation(
        val frame: ReaderDualPageFrameId,
        val mount: Any,
        val rectangle: List<Int>,
        val matched: Int,
        val span: Int,
    )

    private data class FirstPrepared(
        val frame: ReaderDualPageFrameId,
        val position: ReaderPortraitSingleSlot,
        val facts: Map<Int, ReaderPageFactState>,
    )

    /** Measured artwork pixels, independent of the fit oracle used to locate reference samples. */
    private fun pixelBounds(bitmap: Bitmap, slot: androidx.compose.ui.geometry.Rect): List<Int> {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in slot.top.toInt().coerceAtLeast(0) until slot.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in slot.left.toInt().coerceAtLeast(0) until slot.right.toInt().coerceAtMost(bitmap.width)) {
                val color = bitmap.getColor(x, y)
                if ((color and 255) > 15 || (color ushr 8 and 255) > 15 || (color ushr 16 and 255) > 15) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue(right >= left && bottom >= top, "The actual original artwork must have non-background pixels")
        return listOf(left, top, right, bottom)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    /** Spatially fixed 5x5 cells choose low-gradient source points, independently of observed screen pixels. */
    private fun samplePoints(
        bitmap: Bitmap,
        radius: Int,
        region: Rect = Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
    ): List<Pair<Int, Int>> = (1..5).flatMap { row ->
        (1..5).map { column ->
            val cx = (region.left + region.width * column / 6).toInt()
            val cy = (region.top + region.height * row / 6).toInt()
            val minX = maxOf(radius, region.left.toInt() + radius)
            val maxX = minOf(bitmap.width - radius - 1, region.right.toInt() - radius - 1)
            val minY = maxOf(radius, region.top.toInt() + radius)
            val maxY = minOf(bitmap.height - radius - 1, region.bottom.toInt() - radius - 1)
            val candidates = (-24..24 step 4).flatMap { dx ->
                (-24..24 step 4).map { dy ->
                    (cx + dx).coerceIn(minX, maxX) to (cy + dy).coerceIn(minY, maxY)
                }
            }
            candidates.minBy { (x, y) ->
                val color = bitmap.getColor(x, y)
                listOf(-radius, 0, radius).sumOf { dx ->
                    listOf(-radius, 0, radius).sumOf { dy -> colorDistance(color, bitmap.getColor(x + dx, y + dy)) }
                }
            }
        }
    }

    private fun colorDistance(first: Int, second: Int) = listOf(0, 8, 16).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }

    private fun span(colors: List<Int>) = listOf(0, 8, 16).maxOf { shift ->
        colors.maxOf { it ushr shift and 255 } - colors.minOf { it ushr shift and 255 }
    }
}
