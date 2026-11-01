package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPresentationImageHolder
import mihon.desktop.reader.DesktopReaderRuntime
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.DesktopReaderSpreadSampler
import mihon.desktop.reader.NextChapterPrefetchMode
import mihon.desktop.reader.ReaderIoGate
import mihon.desktop.reader.ReaderIoGatePoint
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.SkiaDesktopReaderPageImageDecoder
import mihon.desktop.reader.gatePoint
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.test.http.ReaderTestModeController
import mihon.desktop.test.http.testHttpServer
import mihon.desktop.test.state.applicationState
import mihon.desktop.test.state.readerState
import mihon.desktop.ui.reader.presentation.resolveDualVisiblePages
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageRotation
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageFactLimits
import mihon.domain.reader.ReaderDualPageFactRole
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderEdgeAnalysisEvidence
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderOriginalPageSamples
import mihon.domain.reader.ReaderOriginalPageSampling
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.ReaderPairingCertifier
import mihon.domain.reader.ReaderPairingPrefetchPlan
import mihon.domain.reader.ReaderPairingSettings
import mihon.domain.reader.ReaderSpreadSamplesOwner
import mihon.domain.reader.SharedDualPageRules
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.content.ReaderPageContentLease
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunner
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import java.util.zip.ZipFile
import javax.imageio.ImageIO

@OptIn(
    ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.InternalCoroutinesApi::class,
)
class SharedSpreadDesktopResourceWiringTest {
    @TempDir lateinit var temp: File

    @Test
    fun `reader state HTTP reports bounded real model and runtime attempts across retry and rebind`() = runBlocking {
        val previousTestMode = applicationState.testMode
        applicationState.testMode = true
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            fun readState() = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/test/reader/state")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).let { response ->
                assertEquals(200, response.statusCode())
                Json.parseToJsonElement(response.body()).jsonObject
            }
            assertEquals(JsonNull, readState().getValue("mountedReader"))
            ReaderTestModeController().use { control ->
                Fixture(
                    pageCount = 5,
                    initialPage = 1,
                    online = true,
                    matching = true,
                    readControl = control,
                ).use { f ->
                    f.observePerformance(2)
                    f.mountReader(fullViewport = true)
                    val rule = requireNotNull(f.controlledRead)
                    f.pump {
                        f.actualPaint.containsKey(1) &&
                            control.readControlSnapshot().single { it.id == rule }.status == "HOLDING"
                    }
                    assertTrue(f.actualPaint.containsKey(1))
                    val mounted = readState().getValue("mountedReader").jsonObject
                    assertTrue(mounted.containsKey("imageAttempts"), "Real HTTP must expose imageAttempts")
                    val initial = mounted.getValue("imageAttempts").jsonArray.map { it.jsonObject }
                    val current = f.model.state.value
                    val expected = (
                        current.currentDisplayUnitId?.slots.orEmpty().mapNotNull { it.pageId?.sourcePageIndex } +
                            current.currentPage
                        ).toSet()
                    val observedIndices = initial.map {
                        it.getValue("sourcePageIndex").jsonPrimitive.content.toInt()
                    }.toSet()
                    assertEquals(expected, observedIndices)
                    assertTrue(initial.size <= 3)
                    assertTrue(initial.size < current.session.activeChapter.pages.size)
                    assertEquals(initial.size, expected.size, "The logical source must be deduplicated")
                    initial.forEach { row ->
                        val index = row.getValue("sourcePageIndex").jsonPrimitive.content.toInt()
                        val modelPage = current.session.activeChapter.pages[index]
                        val runtimePage = f.runtime.session.state.value.snapshot.activeChapter.pages[index]
                        assertEquals(
                            modelPage.attemptGeneration,
                            row.getValue("modelAttemptGeneration").jsonPrimitive.content.toLong(),
                        )
                        assertEquals(
                            runtimePage.attemptGeneration,
                            row.getValue("runtimeAttemptGeneration").jsonPrimitive.content.toLong(),
                        )
                        assertEquals(
                            modelPage.loadState.javaClass.simpleName,
                            row.getValue("modelLoadState").jsonPrimitive.content,
                        )
                        assertEquals(
                            runtimePage.loadState.javaClass.simpleName,
                            row.getValue("runtimeLoadState").jsonPrimitive.content,
                        )
                    }
                    assertTrue(control.resolveRead(rule, fail = true))
                    f.pump { control.readControlSnapshot().single { it.id == rule }.status == "FAILED" }
                    val target = f.model.state.value.session.activeChapter.pages[2]
                    f.model.retryPage(target.id)
                    f.pump {
                        val attempt = f.model.state.value.session.activeChapter.pages[2].attemptGeneration
                        attempt > target.attemptGeneration && f.actualPaint.containsKey(2)
                    }
                    val retried = readState().getValue("mountedReader").jsonObject.getValue("imageAttempts")
                        .jsonArray.single {
                            it.jsonObject.getValue("sourcePageIndex").jsonPrimitive.content == "2"
                        }.jsonObject
                    assertTrue(
                        retried.getValue("modelAttemptGeneration").jsonPrimitive.content.toLong() >
                            target.attemptGeneration,
                    )
                    assertEquals(
                        f.runtime.session.state.value.snapshot.activeChapter.pages[2].attemptGeneration,
                        retried.getValue("runtimeAttemptGeneration").jsonPrimitive.content.toLong(),
                    )
                    // Keep the genuine old model mounted while the real runtime activates another chapter.
                    f.pauseModelCollection()
                    f.runtime.session.activate(f.runtime.session.state.value.context.copy(chapterId = 9999))
                    val rebound = readState().getValue("mountedReader").jsonObject.getValue("imageAttempts").jsonArray
                    rebound.forEach { row ->
                        assertEquals(JsonNull, row.jsonObject.getValue("runtimeAttemptGeneration"))
                        assertEquals(JsonNull, row.jsonObject.getValue("runtimeLoadState"))
                    }
                    f.pump { f.runtime.session.state.value.snapshot.activeChapter.pages.isNotEmpty() }
                    assertTrue(f.runtime.session.state.value.snapshot.activeChapter.pages.isNotEmpty())
                    val noRuntime = ReaderScreenModel(
                        prefs = f.prefs,
                        initialSessionState = f.runtime.session.state.value,
                    )
                    try {
                        readerState.mount(noRuntime)
                        val rows = readState().getValue("mountedReader").jsonObject.getValue("imageAttempts").jsonArray
                        assertTrue(rows.isNotEmpty())
                        rows.forEach { row ->
                            assertEquals(JsonNull, row.jsonObject.getValue("runtimeAttemptGeneration"))
                            assertEquals(JsonNull, row.jsonObject.getValue("runtimeLoadState"))
                        }
                    } finally {
                        readerState.unmount(noRuntime)
                        noRuntime.onDispose()
                    }
                }
            }
            assertEquals(JsonNull, readState().getValue("mountedReader"))
        } finally {
            server.stop(0, 0)
            readerState.reset()
            applicationState.testMode = previousTestMode
        }
    }

    @Test
    fun `real retry button rereads failed encoded partner and restores pixels without replacing drawn buddy`() =
        runBlocking {
            Fixture(pageCount = 3, initialPage = 1, online = true, failedEncodedPage = 2).use { fixture ->
                exerciseActualRetry(fixture, releaseFailure = { fixture.failEncoded.set(false) })
            }
        }

    @Test
    fun `matching retry button survives a frame and recovers actual encoded read failure`() = runBlocking {
        ReaderTestModeController().use { control ->
            Fixture(
                pageCount = 3,
                initialPage = 1,
                online = true,
                matching = true,
                readControl = control,
            ).use { fixture ->
                exerciseActualRetry(
                    fixture,
                    fullViewport = true,
                    makeError = {
                        val rule = requireNotNull(fixture.controlledRead)
                        fixture.pump {
                            control.readControlSnapshot().single { it.id == rule }.status == "HOLDING" &&
                                fixture.actualPaint.containsKey(1)
                        }
                        assertEquals("HOLDING", control.readControlSnapshot().single { it.id == rule }.status)
                        assertTrue(
                            fixture.actualPaint.containsKey(1),
                            "The actual buddy must draw while the read is held",
                        )
                        assertTrue(control.resolveRead(rule, fail = true))
                    },
                    releaseFailure = {},
                )
            }
        }
    }

    private suspend fun exerciseActualRetry(
        fixture: Fixture,
        fullViewport: Boolean = false,
        makeError: suspend () -> Unit = {},
        releaseFailure: () -> Unit,
    ) {
        fixture.observePerformance(2)
        fixture.mountReader(fullViewport)
        makeError()
        val retryLabel = MR.strings.action_retry.localized()
        fun retryButtons(): List<SemanticsNode> {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.filter {
                it.config.contains(SemanticsActions.OnClick) && !it.boundsInRoot.isEmpty &&
                    it.boundsInRoot.center.x in 0f..350f &&
                    flatten(it).any { child ->
                        child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text ->
                            text.text == retryLabel
                        }
                    }
            }
        }
        fun pixelBounds(index: Int): Pair<Int, Int>? {
            val color = (255 shl 24) or ((index * 35) shl 16) or (80 shl 8) or 120
            val pixels = fixture.scene.render(fixture.dispatcher.scheduler.currentTime * 1_000_000L)
                .toComposeImageBitmap().asSkiaBitmap()
            return try {
                val columns = (0 until 700).filter { pixels.getColor(it, 600) == color }
                columns.takeIf { it.isNotEmpty() }?.let { it.first() to it.last() }
            } finally {
                pixels.close()
            }
        }
        fixture.pump { fixture.actualPaint.containsKey(1) && retryButtons().isNotEmpty() }
        assertTrue(fixture.actualPaint.containsKey(1), "The real buddy must draw before retry")
        assertTrue(retryButtons().isNotEmpty(), "Malformed HTTP image must show the actual Retry button")
        val position = retryButtons().first().boundsInRoot.center
        val buddyBounds = requireNotNull(pixelBounds(1))
        val target = fixture.model.state.value.session.activeChapter.pages[2]
        val oldAttempt = target.attemptGeneration
        val frame = requireNotNull(fixture.owner()).targetFrameId
        val reads = fixture.ioEvents.count { it.type == ReaderIoEventType.OPEN_PAGE && it.pageId == target.id }
        val decodes = fixture.decodeInvocations[2]?.get() ?: 0
        val callbackCount = AtomicInteger()
        var callbackTarget: ReaderPageId? = null
        every { fixture.model.retryPage(any()) } answers {
            callbackCount.incrementAndGet()
            callbackTarget = firstArg()
            releaseFailure()
            callOriginal()
        }
        fixture.scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
        // A real native click spans a Compose frame; keep the real Button mounted across it.
        fixture.pump { true }
        fixture.scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        fixture.pump {
            fixture.model.state.value.session.activeChapter.pages[2].attemptGeneration > oldAttempt &&
                fixture.actualPaint.containsKey(2) && pixelBounds(2) != null && retryButtons().isEmpty()
        }
        assertEquals(1, callbackCount.get(), "Exactly one real button click must reach the model")
        assertEquals(target.id, callbackTarget, "Retry must address the actual failed source")
        val retried = fixture.model.state.value.session.activeChapter.pages[2]
        assertTrue(retried.attemptGeneration > oldAttempt, "The actual button must create a new attempt")
        assertTrue(
            fixture.ioEvents.count { it.type == ReaderIoEventType.OPEN_PAGE && it.pageId == target.id } > reads,
            "Retry must execute the real encoded content reader",
        )
        assertTrue((fixture.decodeInvocations[2]?.get() ?: 0) > decodes, "Retry must invoke the real decoder")
        assertTrue(fixture.actualPaint.containsKey(2), "Recovered partner must pass the production drawn gate")
        assertNotNull(pixelBounds(2), "Recovered target pixels must replace error feedback")
        assertTrue(retryButtons().isEmpty(), "Successful retry must remove the actual error button")
        assertEquals(buddyBounds, pixelBounds(1), "Retry must preserve the drawn buddy geometry")
        assertEquals(frame, requireNotNull(fixture.owner()).targetFrameId)
    }

    @Test
    fun `real runtime schedules an actual retained partner while its wide logical target stays unchanged`() =
        runBlocking {
            val wide = BufferedImage(80, 60, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 0 until height) for (x in 0 until width) setRGB(x, y, 0xffa05078.toInt())
            }
            val wideBytes = ByteArrayOutputStream().also { assertTrue(ImageIO.write(wide, "png", it)) }.toByteArray()
            val encoded = List(200) { if (it == 125) wideBytes else image(it) }
            Fixture(
                pageCount = 200,
                initialPage = 126,
                online = true,
                heldNetworkPage = 125,
                encodedPages = encoded,
            ).use { fixture ->
                fixture.observePerformance(126)
                fixture.mountReader()
                fixture.pump { fixture.actualPaint.containsKey(126) }
                assertTrue(fixture.actualPaint.containsKey(126))
                fixture.model.goToPage(125)
                fixture.pump { fixture.model.state.value.currentPage == 125 }
                fixture.networkRelease.set(true)
                fixture.pump {
                    fixture.owner()?.pageFact(fixture.owner()!!.identity.pageIds[125])?.state ==
                        ReaderPageFactState.SPREAD
                }
                val owner = fixture.owner()!!
                assertEquals(ReaderPageFactState.SPREAD, owner.pageFact(owner.identity.pageIds[125])?.state)
                assertEquals(listOf(126), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                val snapshot = fixture.model.state.value.dualPresentationSnapshot()
                val native = snapshot.displayUnits.single { it.id.frameId == owner.targetFrameId }
                println(
                    "NORMAL_WIDE_LAYOUT logical=${owner.location?.pageId?.sourcePageIndex} " +
                        "actual=${native.id.slots.mapNotNull { it.pageId?.sourcePageIndex }}",
                )
                assertDoesNotThrow {
                    fixture.model.beginPagerViewport(
                        snapshot.resolveDualVisiblePages(native.id),
                        false,
                        owner.location,
                        true,
                        Any(),
                    )
                }
                assertEquals(125, fixture.owner()!!.location!!.pageId.sourcePageIndex)
                assertEquals(125, fixture.model.state.value.currentPage)
                assertFalse(fixture.actualPaint.containsKey(125), "The wide target has not acquired a physical slot")
            }
        }

    @Test
    fun `online 200 page cold jump paints 126 while preceding analysis and partner remain suspended`() = runBlocking {
        Fixture(pageCount = 200, online = true, matching = true).use { fixture ->
            (5 until 200).filter { it != 125 }.forEach { fixture.pageGate[it] = CompletableDeferred() }
            fixture.observePerformance(125)
            fixture.mountReader()
            fixture.pump { fixture.actualPaint.containsKey(0) }
            assertTrue(fixture.actualPaint.containsKey(0), "Open through the normal online source first")
            val expected = (255 shl 24) or ((125 * 35) shl 16) or (80 shl 8) or 120
            fun targetPixelsVisible(): Boolean {
                val image = fixture.scene.render(fixture.dispatcher.scheduler.currentTime * 1_000_000L)
                val bitmap = image.toComposeImageBitmap().asSkiaBitmap()
                return try {
                    (1..9).any { y ->
                        (6..9).any { x -> bitmap.getColor(bitmap.width * x / 10, bitmap.height * y / 10) == expected }
                    }
                } finally {
                    bitmap.close()
                    image.close()
                }
            }
            val jumpStarted = System.nanoTime()
            var firstPixelAt: Long? = null
            fixture.model.goToPage(125)
            fixture.pump {
                (fixture.actualPaint.containsKey(125) && targetPixelsVisible()).also { visible ->
                    if (visible && firstPixelAt == null) firstPixelAt = System.nanoTime()
                }
            }
            assertEquals(125, fixture.model.state.value.currentPage)
            assertTrue(fixture.paths.contains(125), "The exact target uses the production HTTP content chain")
            assertTrue(fixture.decoderReady.containsKey(125), "The target decoder must finish without the prefix")
            val pairing = fixture.owner()!!.certifyPairing()
            println(
                "NONBLOCKING_COLD_JUMP targetIndex=125 decoded=${fixture.decoderReady[125]} " +
                    "paint=${fixture.actualPaint[125]} pairing=${pairing::class.simpleName} " +
                    "firstPixelAt=$firstPixelAt intentToPixelMs=${firstPixelAt?.let {
                        (it - jumpStarted) / 1_000_000
                    }} " +
                    "pendingGates=${fixture.pageGate.values.count { !it.isCompleted }}",
            )
            assertTrue(pairing is ReaderPairingCertification.NeedFacts)
            assertTrue(fixture.actualPaint.containsKey(125), "A ready target must draw before matching completes")
            assertEquals(listOf(125, 126), fixture.paintSlots[125])
            assertFalse(fixture.actualPaint.containsKey(126), "A waiting partner is not reported as read")
            assertTrue(fixture.pageGate.values.all { !it.isCompleted })
            if (firstPixelAt == null) {
                fixture.scene.render(fixture.dispatcher.scheduler.currentTime * 1_000_000L).use { image ->
                    image.encodeToData()?.use { data ->
                        val diagnostic = File(".gradle-coordinator/nb02-cold-pixels.png")
                        diagnostic.parentFile.mkdirs()
                        diagnostic.writeBytes(data.bytes)
                        println("NONBLOCKING_COLD_PIXELS ${diagnostic.absolutePath}")
                    }
                }
            }
            assertTrue(targetPixelsVisible(), "The real Compose image draws target pixels in its physical half-slot")
        }
    }

    @Test
    fun `background spread metadata preserves the real painted current native frame`() = runBlocking {
        val wide = BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().let { graphics ->
                try {
                    graphics.color = java.awt.Color.BLUE
                    graphics.fillRect(0, 0, width, height)
                } finally {
                    graphics.dispose()
                }
            }
        }
        val wideBytes = ByteArrayOutputStream().also { assertTrue(ImageIO.write(wide, "png", it)) }.toByteArray()
        val encoded = List(200) { if (it == 123) wideBytes else image(it) }
        Fixture(
            pageCount = 200,
            initialPage = 125,
            online = true,
            matching = true,
            encodedPages = encoded,
            heldNetworkPage = 123,
        ).use { fixture ->
            fixture.observePerformance(125)
            fixture.mountReader()
            fixture.pump(10_000) {
                fixture.actualPaint.keys.containsAll(listOf(125, 126)) && 123 in fixture.paths
            }
            assertTrue(
                fixture.actualPaint.keys.containsAll(listOf(125, 126)),
                "paint=${fixture.actualPaint.keys} paths=${fixture.paths}",
            )
            assertTrue(123 in fixture.paths, "The real neighbor HTTP request must enter its gate")
            val frame = fixture.model.state.value.dualPageTargetFrameId
            val selected = requireNotNull(fixture.model.state.value.currentDisplayUnitId)
            val visible = fixture.model.state.value.visiblePageIds
            fun bounds(): List<Int> {
                val image = fixture.scene.render(fixture.dispatcher.scheduler.currentTime * 1_000_000L)
                val bitmap = image.toComposeImageBitmap().asSkiaBitmap()
                return try {
                    val color = (255 shl 24) or ((125 * 35) shl 16) or (80 shl 8) or 120
                    val points = buildList {
                        for (y in 0 until bitmap.height) {
                            for (x in 0 until bitmap.width) {
                                if (bitmap.getColor(x, y) == color) add(x to y)
                            }
                        }
                    }
                    assertTrue(points.isNotEmpty(), "The actual current image must remain painted")
                    listOf(
                        points.minOf { it.first },
                        points.maxOf { it.first },
                        points.minOf { it.second },
                        points.maxOf { it.second },
                    )
                } finally {
                    bitmap.close()
                    image.close()
                }
            }
            val before = bounds()
            // Invoke the production metadata handler used by a decoded neighbor's spread callback.
            // HTTP and Skia above establish the actual current pixels; the neighbor callback's
            // complete asynchronous delivery requires separate native window validation.
            fixture.model.setSpreadPages(fixture.model.state.value.spreadPages + 123)
            repeat(120) { fixture.pump { true } }
            assertEquals(125, fixture.model.state.value.currentPage)
            assertEquals(frame, fixture.model.state.value.dualPageTargetFrameId)
            assertEquals(before, bounds(), "Background neighbor regrouping cannot remove current pixels")
            assertEquals(
                selected,
                fixture.model.state.value.currentDisplayUnitId,
                "A background wide image cannot clear the selected native presentation",
            )
            assertEquals(visible, fixture.model.state.value.visiblePageIds)
        }
    }

    private fun lifecycleLock(model: ReaderScreenModel): Any =
        ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
            .apply { isAccessible = true }.get(model)

    private fun pairingRegistration(model: ReaderScreenModel, pageId: ReaderPageId): Any? =
        synchronized(lifecycleLock(model)) {
            val registrations = ReaderScreenModel::class.java.getDeclaredField("pairingRegistrations")
                .apply { isAccessible = true }.get(model) as Map<*, *>
            registrations[pageId]
        }

    private fun pairingDemand(registration: Any): mihon.desktop.reader.DesktopReaderPairingDecodeDemand =
        registration.javaClass.getDeclaredField("demand").apply { isAccessible = true }.get(registration)
            as mihon.desktop.reader.DesktopReaderPairingDecodeDemand

    private fun pairingContent(model: ReaderScreenModel, pageId: ReaderPageId): ReaderPageContentOpenRequest? =
        pairingRegistration(model, pageId)?.let { registration ->
            registration.javaClass.getDeclaredField("content").apply { isAccessible = true }.get(registration)
                as ReaderPageContentOpenRequest?
        }

    private fun inFlightFullEntry(
        pipeline: mihon.desktop.reader.DesktopReaderPageImagePipeline,
        pageId: ReaderPageId,
    ): Any? {
        val lock = pipeline.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(pipeline)
        return synchronized(lock) {
            val entries = pipeline.javaClass.getDeclaredField("entries").apply { isAccessible = true }
                .get(pipeline) as Map<*, *>
            entries.entries.firstOrNull { (key, _) ->
                key as ReaderPageDecodeKey
                key.purpose == PageDecodePurpose.FULL_PAGE && key.contentKey.pageId == pageId
            }?.value
        }
    }

    private fun registrationJob(registration: Any): Job =
        registration.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(registration) as Job

    private fun mountNeighbor(model: ReaderScreenModel, page: ReaderPageId): ReaderPagerNativePresentation {
        val snapshot = model.state.value.dualPresentationSnapshot(false, false)
        val unit = snapshot.displayUnits.first { display -> display.slots.any { it.page?.id == page } }
        return ReaderPagerNativePresentation(
            unit,
            snapshot.resolveDualVisiblePages(unit.id),
            ReaderPagerPresentationBinding(Any(), model),
            {},
        ).also {
            it.begin(
                userNavigation = false,
                selected = false,
                logicalLocation = requireNotNull(ownerOf(model).location),
            )
        }
    }

    private fun ownerOf(model: ReaderScreenModel): SharedDualPageSession =
        ReaderScreenModel::class.java.getDeclaredField("dualSession").apply { isAccessible = true }.get(model)
            as SharedDualPageSession

    private fun budgetNewlyReadBytes(budget: mihon.domain.reader.ReaderDualPageFactBudget): Long {
        val window = budget.javaClass.getDeclaredField("window").apply { isAccessible = true }.get(budget)
        val lock = window.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(window)
        return synchronized(lock) {
            window.javaClass.getDeclaredField("newlyReadBytes").apply { isAccessible = true }.getLong(window)
        }
    }

    private fun startSpreadPublisher(
        model: ReaderScreenModel,
        owner: SharedDualPageSession,
        runtime: DesktopReaderRuntime,
        scope: CoroutineScope,
        parallelism: Semaphore = Semaphore(ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS),
    ) = model.startSpreadPublisher(owner, runtime, scope, parallelism)

    private fun spreadPublisher(model: ReaderScreenModel): Job? =
        synchronized(lifecycleLock(model)) {
            ReaderScreenModel::class.java.getDeclaredField("spreadPublisherJob").apply { isAccessible = true }
                .get(model) as Job?
        }

    private data class HeldSpreadPublisher(val job: Job, val pageId: ReaderPageId)

    private fun observeCancellationLock(model: ReaderScreenModel, job: Job): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { observed ->
            val lock = lifecycleLock(model)
            job.invokeOnCompletion(onCancelling = true, invokeImmediately = false) {
                observed.complete(Thread.holdsLock(lock))
            }
        }

    private suspend fun Fixture.startHeldSpreadPublisher(targetIndex: Int = 8): HeldSpreadPublisher {
        pump(timeoutMillis = 10_000) {
            model.state.value.session.activeChapter.pages.size >= 20
        }
        val pages = model.state.value.session.activeChapter.pages
        model.goToPage(targetIndex)
        val owner = requireNotNull(owner())
        val target = pages[targetIndex].id
        val need = owner.certifyPairing(target) as? ReaderPairingCertification.NeedFacts
            ?: error("The production certifier must expose facts at the held-publisher target")
        val candidate = ReaderPairingPrefetchPlan.classify(
            need,
            owner.identity.pageIds,
            owner::originalAnalysis,
            ReaderDualPageFactLimits.REQUIRED_LOOKAHEAD_K,
            emptySet(),
        ).speculative.firstOrNull() ?: error("The production classifier must expose a speculative candidate")

        speculativeGatePredicate.set { it == candidate.sourcePageIndex }
        scene.setContent {}
        pump { true }
        startSpreadPublisher(model, owner, runtime, scope)
        pump(timeoutMillis = 15_000) {
            speculativeEntered[candidate.sourcePageIndex]?.isCompleted == true
        }
        assertTrue(
            speculativeEntered[candidate.sourcePageIndex]?.isCompleted == true,
            "The real pairing publisher must be suspended at the selected decoder gate",
        )
        assertFalse(speculativeGate.isCompleted, "The physical decoder gate remains closed")
        val publisher = requireNotNull(spreadPublisher(model))
        assertTrue(publisher.isActive, "The observed Job must be the live production publisher")
        assertNotNull(pairingRegistration(model, candidate), "The real publisher has registered its work")
        assertNotNull(inFlightFullEntry(runtime.pageImagePipeline, candidate))
        return HeldSpreadPublisher(publisher, candidate)
    }

    private suspend fun Fixture.cleanupHeldPublishers(vararg jobs: Job?) {
        speculativeGate.complete(Unit)
        val current = spreadPublisher(model)
        val active = (jobs.asList() + current).filterNotNull().distinct()
        active.forEach { it.cancel() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (active.any { !it.isCompleted } && System.nanoTime() < deadline) delay(5)
        assertTrue(active.all { it.isCompleted }, "Held production publishers must complete during bounded cleanup")
    }

    @Test
    fun `mounted real neighbor demand promotes a queued same Entry when a slot becomes free`() = runBlocking {
        Fixture(pageCount = 20, matching = true, manualSpreadPublisher = true).use { fixture ->
            var native: ReaderPagerNativePresentation? = null
            var initialNeighbor: ReaderPagerNativePresentation? = null
            val consumeCompletions = CompletableDeferred<Unit>()
            val blockerIds = mutableListOf<Int>()
            val blockers = mutableListOf<Deferred<mihon.desktop.reader.DesktopReaderImageAssetLease?>>()
            try {
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 20 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                val owner = requireNotNull(fixture.owner())
                fixture.model.goToPage(8)
                val pages = fixture.model.state.value.session.activeChapter.pages
                val target = pages[8].id
                val need = owner.certifyPairing(target) as? ReaderPairingCertification.NeedFacts
                val candidate = requireNotNull(
                    need?.let {
                        ReaderPairingPrefetchPlan.classify(
                            it,
                            owner.identity.pageIds,
                            owner::originalAnalysis,
                            ReaderDualPageFactLimits.REQUIRED_LOOKAHEAD_K,
                            emptySet(),
                        ).speculative.firstOrNull()
                    },
                ) { "The real target classifier must provide a speculative queue candidate" }
                fixture.speculativeGatePredicate.set { it == candidate.sourcePageIndex }
                fixture.scene.setContent {}
                fixture.pump { true }
                fixture.runtime.pageImagePipeline.clear()
                blockerIds += pages.asSequence()
                    .map { it.id.sourcePageIndex }
                    .filter { it != candidate.sourcePageIndex && it != target.sourcePageIndex }
                    .take(3)
                    .toList()
                assertEquals(3, blockerIds.size, "Three distinct ready full decodes occupy the physical queue")
                blockerIds.forEach { fixture.pageGate[it] = CompletableDeferred() }
                blockers += blockerIds.map { index ->
                    val page = pages[index]
                    fixture.scope.async {
                        fixture.runtime.pageImagePipeline.acquireForPairing(
                            ReaderPageDecodeKey(
                                ReaderPageContentOpenRequest(
                                    page.id,
                                    fixture.model.state.value.session.generation,
                                    requireNotNull(page.encodedPageRef),
                                    page.attemptGeneration,
                                    page.encodedPageProvenance,
                                ),
                                PageDecodePurpose.FULL_PAGE,
                                2_048,
                                2_048,
                            ),
                            speculative = false,
                        )
                    }
                }
                fixture.pump(timeoutMillis = 8_000) {
                    blockerIds.all { fixture.pageEntered[it]?.isCompleted == true }
                }
                assertTrue(blockerIds.all { fixture.pageEntered[it]?.isCompleted == true })
                // Keep this real neighbor demand while the publisher registers its first round.
                // Holding completion consumption prevents a later round from replacing that registration.
                initialNeighbor = mountNeighbor(fixture.model, candidate)
                fixture.model.p5SpreadPublisherCompletionGate = { consumeCompletions.await() }
                startSpreadPublisher(fixture.model, owner, fixture.runtime, fixture.scope)
                fixture.pump(timeoutMillis = 10_000) {
                    pairingRegistration(fixture.model, candidate) != null &&
                        inFlightFullEntry(fixture.runtime.pageImagePipeline, candidate) != null
                }
                val registration = requireNotNull(pairingRegistration(fixture.model, candidate))
                val demand = pairingDemand(registration) as mihon.desktop.reader.DesktopReaderPairingDecodeDemand
                val entry = requireNotNull(inFlightFullEntry(fixture.runtime.pageImagePipeline, candidate))
                assertTrue(demand.speculative, "The candidate is still speculative while its Entry waits in queue")
                assertTrue(registrationJob(registration).isActive, "The captured queued producer is still alive")
                assertTrue(owner.factBudget.holds(candidate), "The live queued demand retains its actual budget slot")
                initialNeighbor!!.close()
                assertTrue(
                    fixture.speculativeEntered[candidate.sourcePageIndex]?.isCompleted != true,
                    "All decode slots are occupied, so the candidate has not entered the native decoder",
                )

                val snapshot = fixture.model.state.value.dualPresentationSnapshot(false, false)
                val unit = snapshot.displayUnits.first { display ->
                    display.slots.any { it.page?.id == candidate }
                }
                native = ReaderPagerNativePresentation(
                    unit,
                    snapshot.resolveDualVisiblePages(unit.id),
                    ReaderPagerPresentationBinding(Any(), fixture.model),
                    {},
                )
                val location = requireNotNull(owner.location)
                val budget = owner.factBudget
                native!!.begin(userNavigation = false, selected = false, logicalLocation = location)
                assertEquals(location, owner.location, "Mounting a neighbor leaves the logical location unchanged")
                fixture.pump(timeoutMillis = 8_000) { !demand.speculative }
                assertFalse(demand.speculative, "The true mounted viewport promotes queued work")
                assertSame(registration, pairingRegistration(fixture.model, candidate))
                assertSame(entry, inFlightFullEntry(fixture.runtime.pageImagePipeline, candidate))
                assertEquals(budget, owner.factBudget)

                blockerIds.forEach { fixture.pageGate.getValue(it).complete(Unit) }
                blockers.forEach { it.await()?.close() }
                fixture.pump(timeoutMillis = 8_000) {
                    fixture.speculativeEntered[candidate.sourcePageIndex]?.isCompleted == true
                }
                assertTrue(
                    fixture.speculativeEntered[candidate.sourcePageIndex]?.isCompleted == true,
                    "The same queued Entry reaches the decoder after the mounted-demand promotion",
                )
                assertSame(entry, inFlightFullEntry(fixture.runtime.pageImagePipeline, candidate))
                assertEquals(1, fixture.decodeInvocations[candidate.sourcePageIndex]?.get())
            } finally {
                consumeCompletions.complete(Unit)
                fixture.model.p5SpreadPublisherCompletionGate = null
                initialNeighbor?.close()
                fixture.speculativeGate.complete(Unit)
                blockerIds.forEach { fixture.pageGate[it]?.complete(Unit) }
                blockers.forEach { runCatching { it.await()?.close() } }
                native?.close()
            }
        }
    }

    @Test
    fun `mounted real neighbor demand promotes before its content key is registered`() = runBlocking {
        Fixture(
            pageCount = 20,
            initialPage = 8,
            online = true,
            matching = true,
            manualSpreadPublisher = true,
        )
            .use { fixture ->
                var native: ReaderPagerNativePresentation? = null
                var initialNeighbor: ReaderPagerNativePresentation? = null
                val consumeCompletions = CompletableDeferred<Unit>()
                val parallelism = Semaphore(ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS)
                repeat(ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS) { parallelism.acquire() }
                var permitsToRelease = ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS
                try {
                    fixture.mountReader()
                    fixture.pump(timeoutMillis = 15_000) {
                        val pages = fixture.model.state.value.session.activeChapter.pages
                        pages.size == 20 && pages.getOrNull(8)?.loadState is ReaderPageLoadState.Ready
                    }
                    // The direct native mount below owns this event; the ordinary scene must not
                    // introduce an unrelated viewport navigation while the materialization permit is held.
                    fixture.scene.setContent {}
                    fixture.pump { fixture.runtime.session.foregroundRequestsIdle }
                    val owner = requireNotNull(fixture.owner())
                    val pages = fixture.model.state.value.session.activeChapter.pages
                    val targetPage = pages[8].id
                    val targetCertification = owner.certifyPairing(targetPage) as? ReaderPairingCertification.NeedFacts
                    val startupPlan = requireNotNull(targetCertification).let {
                        ReaderPairingPrefetchPlan.classify(
                            it,
                            owner.identity.pageIds,
                            owner::originalAnalysis,
                            ReaderDualPageFactLimits.REQUIRED_LOOKAHEAD_K,
                            emptySet(),
                        )
                    }
                    val startupSpeculativeIds = startupPlan.speculative.filterTo(mutableSetOf()) { page ->
                        pages[page.sourcePageIndex].loadState !is ReaderPageLoadState.Ready
                    }
                    assertTrue(
                        startupSpeculativeIds.isNotEmpty(),
                        "The current target classifier must expose an unmaterialized speculative candidate",
                    )
                    val initialCandidate = startupSpeculativeIds.first()
                    initialNeighbor = mountNeighbor(fixture.model, initialCandidate)
                    fixture.model.p5SpreadPublisherCompletionGate = { consumeCompletions.await() }
                    // The producer registers its actual demand before waiting on this production semaphore.
                    // Occupying every permit creates the pre-key state without touching demand/map state or
                    // intercepting an HTTP request that may have started before registration.
                    startSpreadPublisher(fixture.model, owner, fixture.runtime, fixture.scope, parallelism)
                    fixture.pump(timeoutMillis = 10_000) {
                        startupSpeculativeIds.any { pageId ->
                            pairingRegistration(fixture.model, pageId)?.let { registration ->
                                (
                                    pairingDemand(registration) as
                                        mihon.desktop.reader.DesktopReaderPairingDecodeDemand
                                    ).speculative && registrationJob(registration).isActive &&
                                    pairingContent(fixture.model, pageId) == null
                            } == true
                        }
                    }
                    val candidate = requireNotNull(
                        startupSpeculativeIds.firstOrNull { pageId ->
                            pairingRegistration(fixture.model, pageId)?.let { registration ->
                                (
                                    pairingDemand(registration) as
                                        mihon.desktop.reader.DesktopReaderPairingDecodeDemand
                                    ).speculative && registrationJob(registration).isActive &&
                                    pairingContent(fixture.model, pageId) == null
                            } == true
                        },
                    ) { "The publisher must register an admitted startup-speculative page before acquiring a permit" }
                    assertTrue(candidate in startupSpeculativeIds)
                    val observedCertification = owner.certifyPairing(targetPage)
                    val observedPlan = (observedCertification as? ReaderPairingCertification.NeedFacts)?.let {
                        ReaderPairingPrefetchPlan.classify(
                            it,
                            owner.identity.pageIds,
                            owner::originalAnalysis,
                            ReaderDualPageFactLimits.REQUIRED_LOOKAHEAD_K,
                            emptySet(),
                        )
                    }
                    val registration = requireNotNull(pairingRegistration(fixture.model, candidate)) {
                        "No admitted candidate registration: candidate=$candidate " +
                            "certification=$observedCertification " +
                            "real=${observedPlan?.realMissing} speculative=${observedPlan?.speculative} " +
                            "startupReal=${startupPlan.realMissing} startupSpeculative=${startupPlan.speculative}"
                    }
                    val demand = pairingDemand(registration) as mihon.desktop.reader.DesktopReaderPairingDecodeDemand
                    assertTrue(demand.speculative, "The registered page remains the classifier's lookahead demand")
                    assertTrue(registrationJob(registration).isActive, "The pre-key producer is still alive")
                    assertTrue(owner.factBudget.holds(candidate), "The pre-key producer retains its actual budget slot")
                    initialNeighbor!!.close()
                    assertNull(pairingContent(fixture.model, candidate), "No decode content key exists before Ready")
                    assertNull(
                        inFlightFullEntry(fixture.runtime.pageImagePipeline, candidate),
                        "No full decode Entry exists while physical content materialization is held",
                    )
                    val location = requireNotNull(owner.location)
                    val budget = owner.factBudget
                    val budgetRemainingBefore = budget.remainingWaitMillis(System.nanoTime() / 1_000_000)
                    val budgetBytesBefore = budgetNewlyReadBytes(budget)

                    val snapshot = fixture.model.state.value.dualPresentationSnapshot(false, false)
                    val unit = snapshot.displayUnits.first { display ->
                        display.slots.any { it.page?.id == candidate }
                    }
                    val visible = snapshot.resolveDualVisiblePages(unit.id)
                    native = ReaderPagerNativePresentation(
                        unit,
                        visible,
                        ReaderPagerPresentationBinding(Any(), fixture.model),
                        {},
                    )
                    assertSame(owner, fixture.owner(), "The mounted demand must still belong to the live owner")
                    native!!.begin(
                        userNavigation = false,
                        selected = false,
                        logicalLocation = location,
                    )
                    assertEquals(location, owner.location, "A mounted neighbor is not a page navigation")
                    fixture.pump(timeoutMillis = 10_000) { !demand.speculative }
                    assertFalse(
                        demand.speculative,
                        "The actual mounted-neighbor event upgrades the existing demand before entry creation: " +
                            "native=${native!!.describe()} ownerCurrent=${owner === fixture.owner()} " +
                            "location=$location currentLocation=${fixture.owner()?.location} " +
                            "registrationCurrent=${registration === pairingRegistration(fixture.model, candidate)} " +
                            "visible=$visible",

                    )
                    assertSame(registration, pairingRegistration(fixture.model, candidate))
                    assertEquals(budget, owner.factBudget, "Mounted demand retains the original location budget")
                    assertTrue(
                        budget.remainingWaitMillis(System.nanoTime() / 1_000_000) <= budgetRemainingBefore,
                        "A role upgrade cannot extend the deadline",
                    )
                    assertTrue(
                        budgetNewlyReadBytes(budget) >= budgetBytesBefore,
                        "A role upgrade cannot return previously charged bytes",
                    )
                    assertNull(
                        pairingContent(fixture.model, candidate),
                        "The held permit precedes content-key creation",
                    )
                    assertNull(
                        inFlightFullEntry(fixture.runtime.pageImagePipeline, candidate),
                        "The held permit precedes Entry creation",
                    )

                    fixture.speculativeGatePredicate.set { it == candidate.sourcePageIndex }
                    repeat(permitsToRelease) {
                        parallelism.release()
                        permitsToRelease--
                    }
                    fixture.pump(timeoutMillis = 15_000) {
                        fixture.speculativeEntered[candidate.sourcePageIndex]?.isCompleted == true
                    }
                    assertNotNull(inFlightFullEntry(fixture.runtime.pageImagePipeline, candidate))
                    assertEquals(1, fixture.decodeInvocations[candidate.sourcePageIndex]?.get())
                    assertSame(registration, pairingRegistration(fixture.model, candidate))
                } finally {
                    consumeCompletions.complete(Unit)
                    fixture.model.p5SpreadPublisherCompletionGate = null
                    initialNeighbor?.close()
                    repeat(permitsToRelease) {
                        parallelism.release()
                        permitsToRelease--
                    }
                    fixture.speculativeGate.complete(Unit)
                    native?.close()
                }
            }
    }

    @Test
    fun `late old publisher cleanup preserves a newer same page registration`() = runBlocking {
        val oldScheduler = TestCoroutineScheduler()
        val newScheduler = TestCoroutineScheduler()
        val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(oldScheduler))
        val newScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(newScheduler))
        Fixture(pageCount = 20, matching = true, manualSpreadPublisher = true).use { fixture ->
            try {
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 20 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                fixture.scene.setContent {}
                fixture.pump { true }
                val owner = requireNotNull(fixture.owner())
                val pages = fixture.model.state.value.session.activeChapter.pages
                fixture.model.goToPage(8)
                val certification = owner.certifyPairing(pages[8].id) as? ReaderPairingCertification.NeedFacts
                val speculative = requireNotNull(
                    certification?.let {
                        ReaderPairingPrefetchPlan.classify(
                            it,
                            owner.identity.pageIds,
                            owner::originalAnalysis,
                            ReaderDualPageFactLimits.REQUIRED_LOOKAHEAD_K,
                            emptySet(),
                        ).realMissing.firstOrNull()
                    },
                ) { "The current production classifier must expose a required cleanup candidate" }
                fixture.speculativeGatePredicate.set { index -> index == speculative.sourcePageIndex }
                startSpreadPublisher(fixture.model, owner, fixture.runtime, oldScope)
                val oldPublisher = requireNotNull(spreadPublisher(fixture.model))
                val oldDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (fixture.speculativeEntered[speculative.sourcePageIndex]?.isCompleted != true &&
                    System.nanoTime() < oldDeadline
                ) {
                    oldScheduler.runCurrent()
                    delay(5)
                }
                assertTrue(
                    fixture.speculativeEntered[speculative.sourcePageIndex]?.isCompleted == true,
                    "The old task must reach its real native decoder before navigation",
                )
                val oldRegistration = requireNotNull(pairingRegistration(fixture.model, speculative))
                val oldDemand = pairingDemand(oldRegistration)
                assertFalse(
                    oldDemand.speculative,
                    "The captured required demand is independent of automatic speculation promotion",
                )

                // This real navigation invalidates the old location. Keep its scheduler parked so
                // its withContext return and finally cleanup cannot run before the replacement registers.
                fixture.model.goToPage(speculative.sourcePageIndex)
                startSpreadPublisher(fixture.model, owner, fixture.runtime, newScope)
                val newDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var newRegistration: Any? = null
                while (newRegistration == null && System.nanoTime() < newDeadline) {
                    newScheduler.runCurrent()
                    newRegistration = pairingRegistration(fixture.model, speculative)
                        ?.takeIf { pairingDemand(it) !== oldDemand }
                    delay(5)
                }
                assertNotNull(
                    newRegistration,
                    "The new location must register its same-page demand before old cleanup runs",
                )
                val capturedNewRegistration = requireNotNull(newRegistration)
                val capturedNewDemand = pairingDemand(capturedNewRegistration)
                assertFalse(oldPublisher.isCompleted, "The old finally must still be queued on its scheduler")

                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (!oldPublisher.isCompleted && System.nanoTime() < cleanupDeadline) {
                    oldScheduler.runCurrent()
                    delay(5)
                }
                assertTrue(
                    oldPublisher.isCompleted,
                    "The old publisher must complete its queued finally before checking the replacement",
                )
                assertSame(
                    capturedNewRegistration,
                    pairingRegistration(fixture.model, speculative),
                    "A late old finally must compare-remove only its own registration",
                )
                assertSame(
                    capturedNewDemand,
                    pairingDemand(requireNotNull(pairingRegistration(fixture.model, speculative))),
                )
            } finally {
                fixture.speculativeGate.complete(Unit)
                oldScope.cancel()
                newScope.cancel()
            }
        }
    }

    @Test
    fun `location change cancels active pairing publisher outside lifecycle lock`() = runBlocking {
        Fixture(pageCount = 20, matching = true, manualSpreadPublisher = true).use { fixture ->
            var heldPublisher: HeldSpreadPublisher? = null
            try {
                val held = fixture.startHeldSpreadPublisher()
                heldPublisher = held
                val cancellationLockHeld = observeCancellationLock(fixture.model, held.job)

                // Bind the real collector after the manual publisher is already active. Its immediate
                // current-state pass keeps the same location; the public navigation below delivers the
                // first location-change event that replaces this publisher.
                fixture.model.bindDualFactPublisher(fixture.scope)
                fixture.pump(timeoutMillis = 8_000) { spreadPublisher(fixture.model) === held.job }
                assertSame(held.job, spreadPublisher(fixture.model))
                fixture.model.goToPage(9)
                fixture.pump(timeoutMillis = 8_000) { cancellationLockHeld.isCompleted }

                assertTrue(cancellationLockHeld.isCompleted, "The real collector must cancel the old publisher")
                assertFalse(
                    cancellationLockHeld.await(),
                    "Location replacement must call Job.cancel after releasing the lifecycle lock",
                )
            } finally {
                fixture.cleanupHeldPublishers(heldPublisher?.job)
            }
        }
    }

    @Test
    fun `page retry cancels active pairing publisher outside lifecycle lock`() = runBlocking {
        Fixture(pageCount = 20, matching = true, manualSpreadPublisher = true).use { fixture ->
            var heldPublisher: HeldSpreadPublisher? = null
            try {
                val held = fixture.startHeldSpreadPublisher()
                heldPublisher = held
                val cancellationLockHeld = observeCancellationLock(fixture.model, held.job)

                fixture.model.retryPage(held.pageId)

                assertTrue(cancellationLockHeld.isCompleted, "retryPage must cancel the real publisher synchronously")
                assertFalse(
                    cancellationLockHeld.await(),
                    "Page retry must call Job.cancel after releasing the lifecycle lock",
                )
            } finally {
                fixture.cleanupHeldPublishers(heldPublisher?.job)
            }
        }
    }

    @Test
    fun `screen disposal cancels active pairing publisher outside lifecycle lock`() = runBlocking {
        Fixture(pageCount = 20, matching = true, manualSpreadPublisher = true).use { fixture ->
            var heldPublisher: HeldSpreadPublisher? = null
            try {
                val held = fixture.startHeldSpreadPublisher()
                heldPublisher = held
                val cancellationLockHeld = observeCancellationLock(fixture.model, held.job)

                // This fixture deliberately starts the real pairing publisher as a sibling in its scope,
                // with no fact-publisher collector, so the observed Job can only be cancelled by the
                // spreadPublisherJob handling in the production disposal path.
                fixture.model.onDispose()

                assertTrue(cancellationLockHeld.isCompleted, "Screen disposal must cancel the real publisher")
                assertFalse(
                    cancellationLockHeld.await(),
                    "Screen disposal must call Job.cancel after releasing the lifecycle lock",
                )
            } finally {
                fixture.cleanupHeldPublishers(heldPublisher?.job)
            }
        }
    }

    @Test
    fun `current demand replaces slider targets and null neighbor close revokes only its demand`() = runBlocking {
        Fixture(pageCount = 8).use { fixture ->
            fixture.pump {
                val pages = fixture.model.state.value.session.activeChapter.pages
                pages.size == 8 && pages.all { it.loadState is ReaderPageLoadState.Ready } &&
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
            }
            val model = fixture.model
            val pages = model.state.value.session.activeChapter.pages
            val oldLocation = model.state.value.dualPageLocation
            val snapshot = model.state.value.dualPresentationSnapshot(false, false)
            val neighbor = snapshot.displayUnits.first { unit -> unit.slots.any { it.page?.id == pages[6].id } }
            val visible = snapshot.resolveDualVisiblePages(neighbor.id)
            val binding = ReaderPagerPresentationBinding(Any(), model)
            val native = ReaderPagerNativePresentation(neighbor, visible, binding, {})
            native.begin(userNavigation = false, selected = false)
            assertEquals(null, native.frameId, "Unknown neighbor must have a demand before it has a drawing ticket")
            model.goToPage(4)
            val lifecycleLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
                .apply { isAccessible = true }.get(model)
            fun targets(): List<ReaderPageId> = synchronized(lifecycleLock) {
                val needs = ReaderScreenModel::class.java.getDeclaredMethod(
                    "certificationNeedsLocked",
                    SharedDualPageSession::class.java,
                )
                    .apply { isAccessible = true }
                needs.invoke(model, fixture.owner())
                (
                    ReaderScreenModel::class.java.getDeclaredField("certificationTargets").apply { isAccessible = true }
                        .get(model) as Set<*>
                    ).filterIsInstance<ReaderPageId>()
            }
            assertEquals(pages[4].id, targets().first(), "Current demand must precede mounted neighbor work")
            assertTrue(pages[0].id !in targets(), "Unmounted obsolete slider target must be withdrawn")
            assertTrue(visible.activePageId in targets(), "Actually mounted neighbor demand must survive navigation")
            val obsolete = snapshot.displayUnits.first { unit -> unit.slots.any { it.page?.id == pages[0].id } }
            val obsoleteVisible = snapshot.resolveDualVisiblePages(obsolete.id)
            assertTrue(
                requireNotNull(fixture.owner()).certifyPairing(pages[0].id) is ReaderPairingCertification.Certified,
            )
            native.update(obsolete, obsoleteVisible, binding)
            native.begin(false, false, oldLocation)
            assertEquals(
                null,
                native.frameId,
                "An old mount token cannot authorize a different unit at a stale location",
            )
            assertTrue(
                pages[0].id !in targets(),
                "Failed re-registration cannot install an obsolete replacement demand",
            )
            native.close()
            assertEquals(listOf(pages[4].id), targets(), "A null ticket cannot prevent neighbor demand cleanup")
            model.beginPagerViewport(visible, false, oldLocation, selected = false)
            assertEquals(listOf(pages[4].id), targets(), "A late old-location registration must be rejected")
            val navigationEventCount = fixture.ioEvents.size
            fixture.pump { fixture.owner()?.certifyPairing(pages[4].id) is ReaderPairingCertification.Certified }
            assertTrue(
                fixture.owner()?.certifyPairing(pages[4].id) is ReaderPairingCertification.Certified,
                "Current result=${fixture.owner()?.certifyPairing(
                    pages[4].id,
                )} pages=${model.state.value.session.activeChapter.pages.map {
                    it.id to it.loadState
                }} events=${fixture.ioEvents}",
            )
            assertTrue(
                fixture.ioEvents.drop(navigationEventCount).none {
                    it.pageId == pages[0].id &&
                        (it.type == ReaderIoEventType.OPEN_PAGE || it.type == ReaderIoEventType.HEADER_READ)
                },
                "The withdrawn old slider target cannot start another physical open",
            )
        }
    }

    @Test
    fun `exact normal full page cache certifies without new optional attempts or decoding`() = runBlocking {
        fun attempts(owner: SharedDualPageSession): Set<*> {
            val budget = owner.factBudget
            val lock = budget.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(budget)
            return synchronized(lock) {
                (
                    budget.javaClass.getDeclaredField("attempted").apply {
                        isAccessible = true
                    }.get(budget) as Set<*>
                    ).toSet()
            }
        }
        Fixture(pageCount = 4).use { fixture ->
            fixture.pump {
                fixture.model.state.value.session.activeChapter.pages.size == 4 &&
                    fixture.model.state.value.session.activeChapter.pages.all {
                        it.loadState is ReaderPageLoadState.Ready
                    }
            }
            val pages = fixture.model.state.value.session.activeChapter.pages
            for (page in pages) {
                val content = ReaderPageContentOpenRequest(
                    page.id,
                    fixture.model.state.value.session.generation,
                    requireNotNull(page.encodedPageRef),
                    page.attemptGeneration,
                    page.encodedPageProvenance,
                )
                requireNotNull(
                    fixture.runtime.pageImagePipeline.acquire(
                        ReaderPageDecodeKey(content, PageDecodePurpose.FULL_PAGE, 2048, 2048),
                    ),
                ).use { assertTrue(it.asset.originalSamples.isNotEmpty()) }
            }
            val decodes = fixture.decoderReady.toMap()
            val previousAttempts = attempts(requireNotNull(fixture.owner()))
            val opens = fixture.ioEvents.count {
                it.type == ReaderIoEventType.OPEN_PAGE ||
                    it.type == ReaderIoEventType.HEADER_READ
            }
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump { fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified }
            val owner = requireNotNull(fixture.owner())
            assertTrue(
                owner.certifyPairing() is ReaderPairingCertification.Certified,
                owner.certifyPairing().toString(),
            )
            assertEquals(
                opens,
                fixture.ioEvents.count {
                    it.type == ReaderIoEventType.OPEN_PAGE ||
                        it.type == ReaderIoEventType.HEADER_READ
                },
                "Cache-only fact admission cannot reopen encoded content or headers",
            )
            assertEquals(
                previousAttempts,
                attempts(owner),
                "Exact cache admission must not reserve new attempts or refund existing work",
            )
            assertEquals(decodes, fixture.decoderReady.toMap(), "Cache-only fact admission cannot call the decoder")
            assertEquals(0, fixture.regionReads.get())
            assertTrue(fixture.runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
        }
        Fixture(pageCount = 4, withoutCachedOriginalSamples = true).use { fixture ->
            fixture.pump {
                fixture.model.state.value.session.activeChapter.pages.size == 4 &&
                    fixture.model.state.value.session.activeChapter.pages.all {
                        it.loadState is ReaderPageLoadState.Ready
                    }
            }
            for (page in fixture.model.state.value.session.activeChapter.pages) {
                val content = ReaderPageContentOpenRequest(
                    page.id,
                    fixture.model.state.value.session.generation,
                    requireNotNull(page.encodedPageRef),
                    page.attemptGeneration,
                    page.encodedPageProvenance,
                )
                requireNotNull(
                    fixture.runtime.pageImagePipeline.acquire(
                        ReaderPageDecodeKey(content, PageDecodePurpose.FULL_PAGE, 2048, 2048),
                    ),
                ).use { assertTrue(it.asset.originalSamples.isEmpty()) }
            }
            val opens = fixture.ioEvents.count { it.type == ReaderIoEventType.OPEN_PAGE }
            val lock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
                .apply { isAccessible = true }.get(fixture.model)
            synchronized(lock) {
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.model.goToPage(2)
            }
            fixture.pump { fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified }
            val owner = requireNotNull(fixture.owner())
            assertTrue(
                owner.certifyPairing() is ReaderPairingCertification.Certified,
                owner.certifyPairing().toString(),
            )
            assertTrue(
                attempts(owner).isNotEmpty(),
                "A cached bitmap without original samples must reserve real new work",
            )
            assertTrue(
                fixture.regionReads.get() > 0,
                "Missing samples must use the production original-region fallback",
            )
            assertTrue(
                fixture.ioEvents.count { it.type == ReaderIoEventType.OPEN_PAGE } > opens,
                "Missing samples must perform the ordinary physical original open",
            )
        }
    }

    /**
     * The captured admission set of the publisher.
     *
     * The frozen P5 contracts observe the publisher through the bounded round
     * observer: every round reports which capabilities were admitted as real,
     * as speculation and as optional work, which completions the publisher
     * consumed and which capabilities were still outstanding.
     */
    private fun observePublisherRounds(
        model: ReaderScreenModel,
        onCompletionReported: ((ReaderPageId, Boolean) -> Unit)? = null,
    ): CopyOnWriteArrayList<ReaderSpreadPublisherRoundDiagnostic> {
        val rounds = CopyOnWriteArrayList<ReaderSpreadPublisherRoundDiagnostic>()
        model.p5SpreadPublisherRoundObserver = ReaderSpreadPublisherRoundObserver(
            captureSamples = { _, _ -> emptyMap() },
            onRound = { rounds += it },
            onCompletionReported = onCompletionReported,
        )
        return rounds
    }

    private fun ReaderSpreadPublisherRoundDiagnostic.admittedPages(): Set<ReaderPageId> =
        admittedRequiredIds + admittedSpeculativeIds + admittedOptionalIds

    private fun List<ReaderSpreadPublisherRoundDiagnostic>.summary(): String =
        joinToString("; ") { round ->
            "kind=${round.certificationKind} target=${round.targetPageId?.sourcePageIndex} " +
                "needs=${round.needPageIds.map { it.sourcePageIndex }} " +
                "real=${round.admittedRequiredIds.map { it.sourcePageIndex }} " +
                "spec=${round.admittedSpeculativeIds.map { it.sourcePageIndex }} " +
                "opt=${round.admittedOptionalIds.map { it.sourcePageIndex }} " +
                "failed=${round.failedPageIds.map { it.sourcePageIndex }} " +
                "consumed=${round.consumedCompletionIds.map { it.sourcePageIndex }}"
        }

    @Test
    fun `classifier selected pure full speculation leaves target certified and drawn before gate release`() =
        runBlocking {
            Fixture(pageCount = 40, matching = true).use { fixture ->
                val rounds = observePublisherRounds(fixture.model)
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 40 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                fixture.model.goToPage(30)
                fixture.pump(timeoutMillis = 10_000) {
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                }
                assertTrue(
                    rounds.any { it.admittedSpeculativeIds.isNotEmpty() },
                    "The actual admission set must contain a live speculative pairing demand: " +
                        rounds.summary(),
                )
                assertTrue(
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified,
                    "The target must be certified: ${fixture.owner()?.certifyPairing()}",
                )
                assertTrue(
                    fixture.model.state.value.dualPageUnits.any { it.certain },
                    "The target must be drawn while the speculative capability is outstanding",
                )
            }
        }

    @Test
    fun `classifier selected pure region speculation leaves target certified and drawn before gate release`() =
        runBlocking {
            Fixture(pageCount = 40, matching = true, withoutCachedOriginalSamples = true).use { fixture ->
                val rounds = observePublisherRounds(fixture.model)
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 40 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                fixture.model.goToPage(30)
                fixture.pump(timeoutMillis = 10_000) {
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                }
                assertTrue(
                    rounds.any { it.admittedSpeculativeIds.isNotEmpty() },
                    "The actual admission set must contain a live speculative pairing demand: " +
                        rounds.summary(),
                )
                assertTrue(
                    fixture.regionReads.get() > 0,
                    "The forced region fallback must be the path the speculation uses",
                )
                assertTrue(
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified,
                    "The target must be certified: ${fixture.owner()?.certifyPairing()}",
                )
                assertTrue(
                    fixture.model.state.value.dualPageUnits.any { it.certain },
                    "The target must be drawn while the speculative capability is outstanding",
                )
            }
        }

    @Test
    fun `accepted completion admits newly real missing work while unrelated speculation remains gated`() =
        runBlocking {
            Fixture(pageCount = 40, matching = true).use { fixture ->
                val reported = CopyOnWriteArrayList<ReaderPageId>()
                val heldSlots = CopyOnWriteArrayList<ReaderPageId>()
                val demandContext =
                    AtomicReference<Pair<SharedDualPageSession, mihon.domain.reader.ReaderDualPageLocationIntent>?>(
                        null,
                    )
                val allRounds = observePublisherRounds(fixture.model) { pageId, slotHeld ->
                    val context = demandContext.get()
                    if (context != null && fixture.owner() === context.first &&
                        context.first.location == context.second
                    ) {
                        reported += pageId
                        if (slotHeld) heldSlots += pageId
                    }
                }
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 40 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                fixture.model.goToPage(30)
                val targetOwner = requireNotNull(fixture.owner())
                val targetLocation = requireNotNull(targetOwner.location)
                val targetPageId = targetOwner.identity.pageIds[30]
                assertEquals(targetPageId, targetLocation.pageId)
                demandContext.set(targetOwner to targetLocation)
                fixture.pump(timeoutMillis = 10_000) {
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                }
                val rounds = allRounds.filter { it.targetPageId == targetPageId }
                assertTrue(
                    rounds.any { it.consumedCompletionIds.isNotEmpty() },
                    "The publisher must consume the completion of an accepted capability: " +
                        rounds.summary(),
                )
                assertTrue(
                    rounds.any { it.admittedSpeculativeIds.isNotEmpty() },
                    "The captured admission set contains requests classified speculative before admission: " +
                        rounds.summary(),
                )
                assertTrue(
                    rounds.any {
                        it.consumedCompletionIds.isNotEmpty() && it.admittedSpeculativeIds.isNotEmpty()
                    },
                    "The unrelated speculation must stay gated while a completion is accepted: " +
                        rounds.summary(),
                )
                assertTrue(
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified,
                    "The newly real missing work must be admitted and certified",
                )
                // R4: an enqueued, unconsumed completion still occupies its logical slot.
                // Consuming it must free capacity for newly discovered canonical real work;
                // an already Computed speculative page need not be decoded a second time.
                assertTrue(
                    reported.isNotEmpty() && heldSlots.isNotEmpty(),
                    "A reported but unconsumed completion must keep its logical slot until " +
                        "the publisher consumes it: " + reported.size,
                )
                val speculated = rounds.flatMap { it.admittedSpeculativeIds }.toSet()
                val firstTargetDemand = rounds.first().admittedRequiredIds
                assertTrue(
                    rounds.any { round ->
                        round.consumedCompletionIds.any { it in speculated } &&
                            round.admittedRequiredIds.any { it !in firstTargetDemand }
                    },
                    "Consuming speculative completion must permit newly discovered canonical real work: " +
                        rounds.summary(),
                )
                val consumedWhilePending = rounds.any { round ->
                    round.consumedCompletionIds.isNotEmpty() && round.needPageIds.any {
                        it !in round.admittedRequiredIds && it !in round.admittedSpeculativeIds
                    }
                }
                assertTrue(
                    consumedWhilePending,
                    "A real demand that is not admitted yet must exist while a completion is " +
                        "consumed: " + rounds.summary(),
                )
                assertSame(targetOwner, fixture.owner(), "Refill assertions must retain the captured target owner")
                assertEquals(
                    targetLocation,
                    targetOwner.location,
                    "Refill assertions must retain the captured logical intent",
                )
            }
        }

    /**
     * A capability that reported its completion but whose completion the publisher has not
     * consumed yet still occupies the logical capacity. The budget slot is only returned when
     * the publisher consumes the completion that released it, so the occupied capacity always
     * equals the admitted capabilities minus the consumed completions.
     */
    @Test
    fun `reported but unconsumed completions keep occupying the logical pairing capacity`() =
        runBlocking {
            Fixture(pageCount = 40, matching = true).use { fixture ->
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 40 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                fixture.model.goToPage(30)
                val released = CopyOnWriteArrayList<String>()
                val reported = java.util.concurrent.atomic.AtomicInteger()
                fixture.model.p5SpreadPublisherRoundObserver = ReaderSpreadPublisherRoundObserver(
                    captureSamples = { _, _ -> emptyMap() },
                    onRound = { _ -> },
                    onCompletionReported = { pageId, slotHeld ->
                        // In the window between the report and the publisher consuming it, the
                        // completion must still occupy its logical slot.
                        reported.incrementAndGet()
                        if (!slotHeld) {
                            released += "page ${pageId.sourcePageIndex} released its logical slot " +
                                "before the publisher consumed its completion"
                        }
                    },
                )
                fixture.pump(timeoutMillis = 20_000) {
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                }
                assertTrue(
                    reported.get() > 0,
                    "the publisher must report at least one completion, otherwise the boundary " +
                        "is not observed at all",
                )
                assertTrue(
                    released.isEmpty(),
                    "A reported but unconsumed completion must keep its logical budget slot: " +
                        released.joinToString("; "),
                )
            }
        }

    /**
     * A completion that is queued but not consumed yet keeps its logical capacity. The publisher
     * is held after it started the capabilities of a round and before it consumes their
     * completions, so the completion of a finished capability stays in the queue. The capacity it
     * occupies must survive until the publisher consumes it, the occupied capacity must stay
     * inside the cap, and the publisher must admit the next item after the consumption.
     */
    @Test
    fun `a queued but unconsumed completion keeps its capacity until the publisher consumes it`() =
        runBlocking {
            Fixture(pageCount = 40, matching = true).use { fixture ->
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 40 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                fixture.model.goToPage(30)
                val rounds = CopyOnWriteArrayList<ReaderSpreadPublisherRoundDiagnostic>()
                val reported = java.util.concurrent.atomic.AtomicInteger()
                val released = CopyOnWriteArrayList<String>()
                fixture.model.p5SpreadPublisherRoundObserver = ReaderSpreadPublisherRoundObserver(
                    captureSamples = { _, _ -> emptyMap() },
                    onRound = { rounds += it },
                    onCompletionReported = { pageId, slotHeld ->
                        reported.incrementAndGet()
                        if (!slotHeld) {
                            released += "page ${pageId.sourcePageIndex} released its logical slot " +
                                "before the publisher consumed its completion"
                        }
                    },
                )
                val held = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holding = java.util.concurrent.atomic.AtomicBoolean(false)
                // The publisher is held between the start of a round's capabilities and the
                // consumption of their completions, so the queue really holds a backlog.
                fixture.model.p5SpreadPublisherCompletionGate = { outstanding ->
                    if (outstanding.isNotEmpty() && holding.compareAndSet(false, true)) {
                        held.complete(Unit)
                        release.await()
                    }
                }
                fixture.pump(timeoutMillis = 20_000) { held.isCompleted }
                assertTrue(
                    held.isCompleted,
                    "the publisher must start a round with outstanding capabilities",
                )
                fixture.pump(timeoutMillis = 20_000) { reported.get() > 0 }
                assertTrue(
                    reported.get() > 0,
                    "the held round must report a completion that stays in the queue",
                )
                assertTrue(
                    released.isEmpty(),
                    "a queued but unconsumed completion must keep its logical capacity: " +
                        released.joinToString("; "),
                )
                val budget = requireNotNull(fixture.owner()).factBudget
                val activeField = budget.javaClass.getDeclaredField("active").apply { isAccessible = true }
                val occupied = (activeField.get(budget) as Set<*>).filterIsInstance<ReaderPageId>()
                assertTrue(
                    occupied.isNotEmpty(),
                    "the queued completion must keep occupying its logical capacity",
                )
                assertTrue(
                    occupied.size <= ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
                    "the occupied capacity must stay inside the cap: $occupied",
                )
                // The publisher consumes the queued completion and admits the next item.
                release.complete(Unit)
                fixture.pump(timeoutMillis = 20_000) {
                    rounds.any { it.consumedCompletionIds.isNotEmpty() }
                }
                assertTrue(
                    rounds.any { it.consumedCompletionIds.isNotEmpty() },
                    "the publisher must consume the queued completion: ${rounds.summary()}",
                )
                fixture.pump(timeoutMillis = 20_000) {
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                }
                assertTrue(
                    fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified,
                    "the publisher must admit the next item after the consumption: ${rounds.summary()}",
                )
            }
        }

    @Test
    fun `mounted neighbor event promotes an executing speculative decode in place`() = runBlocking {
        Fixture(pageCount = 40, matching = true).use { fixture ->
            val rounds = observePublisherRounds(fixture.model)
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.session.activeChapter.pages.size == 40 &&
                    fixture.model.state.value.session.activeChapter.pages.all {
                        it.loadState is ReaderPageLoadState.Ready
                    }
            }
            fixture.model.goToPage(30)
            fixture.pump(timeoutMillis = 10_000) {
                fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
            }
            val speculative = rounds.flatMap { it.admittedSpeculativeIds }.distinct()
            requireNotNull(speculative.firstOrNull()) {
                "Capture the live speculative demand before promotion: " + rounds.summary()
            }
            val page = speculative.first()
            val opensAfterAdmission = fixture.ioEvents.count {
                it.pageId == page && it.type == ReaderIoEventType.OPEN_PAGE
            }
            fixture.pump(timeoutMillis = 10_000) {
                fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
            }
            assertEquals(
                opensAfterAdmission,
                fixture.ioEvents.count {
                    it.pageId == page && it.type == ReaderIoEventType.OPEN_PAGE
                },
                "The promoted decode must not open the physical content again",
            )
        }
    }

    @Test
    fun `location replacement withdraws an obsolete queued pairing consumer before a new target uses the freed slot`() =
        runBlocking {
            Fixture(pageCount = 8, matching = true).use { fixture ->
                val rounds = observePublisherRounds(fixture.model)
                fixture.mountReader()
                fixture.pump {
                    fixture.model.state.value.session.activeChapter.pages.size == 8 &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                fixture.model.goToPage(4)
                val oldPageId = fixture.model.state.value.session.activeChapter.pages[4].id
                val newPageId = fixture.model.state.value.session.activeChapter.pages[6].id
                fixture.pump(timeoutMillis = 5_000) {
                    rounds.any { it.admittedPages().contains(oldPageId) }
                }
                val roundsBeforeReplacement = rounds.size
                val opensBefore = fixture.ioEvents.count {
                    it.pageId == oldPageId && it.type == ReaderIoEventType.OPEN_PAGE
                }
                fixture.model.goToPage(6)
                fixture.pump(timeoutMillis = 5_000) {
                    fixture.owner()?.location?.pageId == newPageId
                }
                assertTrue(
                    rounds.drop(roundsBeforeReplacement).none { it.admittedPages().contains(oldPageId) },
                    "The obsolete queued pairing consumer must be withdrawn before the new target uses the slot: " +
                        rounds.summary(),
                )
                assertEquals(
                    opensBefore,
                    fixture.ioEvents.count {
                        it.pageId == oldPageId && it.type == ReaderIoEventType.OPEN_PAGE
                    },
                    "The withdrawn queued pairing consumer must not start another physical open",
                )
            }
        }

    @Test
    fun `current location required pairing demand exceeds sixteen pages and paints without resource failure`() =
        runBlocking {
            Fixture(pageCount = 40, matching = true).use { fixture ->
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 40 }
                val target = 30
                fixture.model.goToPage(target)
                val sequence = requireNotNull(fixture.owner()?.location).sequence
                fixture.pump(timeoutMillis = 15_000) {
                    (
                        fixture.isDrawn(sequence, target) &&
                            fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                        ) ||
                        (
                            fixture.model.state.value.dualPairingFailures.keys.any {
                                it.sourcePageIndex == target
                            } && fixture.runtime.pageImagePipeline.foregroundWork.value == 0 &&
                                fixture.runtime.pageImagePipeline.optionalWork.value == 0
                            )
                }
                val pages = fixture.model.state.value.session.activeChapter.pages
                val targetPageId = pages[target].id
                assertFalse(
                    fixture.model.state.value.dualPairingFailures.containsKey(targetPageId),
                    "Required demand must not fail with RESOURCE when exceeding 16 pages: " +
                        fixture.model.state.value.dualPairingFailures[targetPageId],
                )
                assertTrue(
                    fixture.isDrawn(sequence, target),
                    "Target page $target must be painted even when requiring >16 prefix facts",
                )
                val cert = requireNotNull(fixture.owner()).certifyPairing(targetPageId)
                assertTrue(cert is ReaderPairingCertification.Certified)
            }
        }

    @Test
    fun `current location required pairing demand prefetch reduces certification rounds`() =
        runBlocking {
            Fixture(pageCount = 40, matching = true).use { fixture ->
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 40 }
                val target = 30
                fixture.model.goToPage(target)
                val sequence = requireNotNull(fixture.owner()?.location).sequence
                fixture.pump(timeoutMillis = 15_000) {
                    (
                        fixture.isDrawn(sequence, target) &&
                            fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                        ) ||
                        (
                            fixture.model.state.value.dualPairingFailures.keys.any {
                                it.sourcePageIndex == target
                            } && fixture.runtime.pageImagePipeline.foregroundWork.value == 0 &&
                                fixture.runtime.pageImagePipeline.optionalWork.value == 0
                            )
                }
                val pages = fixture.model.state.value.session.activeChapter.pages
                val targetPageId = pages[target].id
                assertFalse(
                    fixture.model.state.value.dualPairingFailures.containsKey(targetPageId),
                    "Required demand must not fail with RESOURCE",
                )
                assertTrue(
                    fixture.isDrawn(sequence, target),
                    "Target page $target must be painted",
                )
                val cert = requireNotNull(fixture.owner()).certifyPairing(targetPageId)
                assertTrue(cert is ReaderPairingCertification.Certified)
                // The schedule bound is no longer a threshold. A concurrency cap does not
                // guarantee k completions per round, so `ceil(D/k)+2` is not a proven bound
                // for all legal completion orders. The round counter measures scheduling,
                // not work. The concern behind the old bound -
                // repeated physical work - is protected below by the physical identity of the
                // target content, and the round count stays a reported diagnostic.
                val rounds = fixture.model.certificationRounds
                val owner = requireNotNull(fixture.owner())
                val opensOfTarget = {
                    fixture.ioEvents.count {
                        it.pageId == targetPageId && it.type == ReaderIoEventType.OPEN_PAGE
                    }
                }
                val opensBefore = opensOfTarget()
                val metrics = owner.certificationMetrics
                val engineRunsBefore = metrics.engineRuns
                val hitsBefore = metrics.memoHits
                repeat(3) { owner.certifyPairing(targetPageId) }
                assertEquals(
                    opensBefore,
                    opensOfTarget(),
                    "Repeated certification must not open the physical content again",
                )
                assertEquals(
                    engineRunsBefore,
                    metrics.engineRuns,
                    "Repeated certification with an unchanged input must not run the " +
                        "certification engine again",
                )
                assertTrue(
                    metrics.memoHits > hitsBefore,
                    "A repeated certification with an unchanged input must be served by the " +
                        "reuse path",
                )
                println(
                    "PREFETCH_ROUNDS rounds=$rounds engineRuns=${metrics.engineRuns} " +
                        "hits=${metrics.memoHits} misses=${metrics.memoMisses} " +
                        "scanSteps=${metrics.scanStepsExecuted} opens=$opensBefore",
                )
            }
        }

    @Test
    fun `sequential page turns near typical target certify and paint without regression`() =
        runBlocking {
            Fixture(pageCount = 45, matching = true).use { fixture ->
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 45 }
                fixture.model.goToPage(25)
                val initialSeq = requireNotNull(fixture.owner()?.location).sequence
                fixture.pump(timeoutMillis = 15_000) { fixture.isDrawn(initialSeq, 25) }
                for (page in 26..35) {
                    val turnStarted = System.nanoTime()
                    fixture.model.goToPage(page)
                    val seq = requireNotNull(fixture.owner()?.location).sequence
                    fixture.pump(timeoutMillis = 10_000) { fixture.isDrawn(seq, page) }
                    val elapsedMs = (System.nanoTime() - turnStarted) / 1_000_000
                    assertTrue(
                        fixture.isDrawn(seq, page),
                        "Page $page must be drawn within timeout",
                    )
                    val pages = fixture.model.state.value.session.activeChapter.pages
                    val cert = requireNotNull(fixture.owner()).certifyPairing(pages[page].id)
                    assertTrue(cert is ReaderPairingCertification.Certified)
                    println("PAGE_TURN_GUARDRAIL page=$page elapsedMs=$elapsedMs")
                }
            }
        }

    @Test
    @EnabledIfEnvironmentVariable(named = "MIHON_READER_REPLAY_DIRECTORY", matches = ".+")
    fun `downloaded original directory records every slider jump through isolated production reader`() =
        runBlocking {
            val directory = File(requireNotNull(System.getenv("MIHON_READER_REPLAY_DIRECTORY")))
            assertTrue(directory.isDirectory, "An explicit existing original directory is required")
            val pageCount = directory.listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
            assertTrue(pageCount > 0, "Original directory must contain pages")
            val observations = mutableListOf<String>()
            val undrawn = mutableListOf<Int>()
            Fixture(pageCount = pageCount, originalDirectory = directory, matching = true).use { fixture ->
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == pageCount }
                assertEquals(pageCount, fixture.model.state.value.session.activeChapter.pages.size)
                for (target in listOf(0, 34, 44, 34).filter { it < pageCount }) {
                    if (target == 34) fixture.captureReplayCostBaseline()
                    val started = System.nanoTime()
                    fixture.model.goToPage(target)
                    val sequence = requireNotNull(fixture.owner()?.location).sequence
                    fixture.pump(timeoutMillis = 20_000) {
                        (
                            fixture.isDrawn(sequence, target) &&
                                fixture.owner()?.certifyPairing() is ReaderPairingCertification.Certified
                            ) ||
                            (
                                fixture.model.state.value.dualPairingFailures.keys.any {
                                    it.sourcePageIndex == target
                                } && fixture.runtime.pageImagePipeline.foregroundWork.value == 0 &&
                                    fixture.runtime.pageImagePipeline.optionalWork.value == 0
                                )
                    }
                    val drawn = fixture.isDrawn(sequence, target)
                    if (!drawn) undrawn += target
                    val observation = "targetIndex=$target drawn=$drawn " +
                        "elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
                        fixture.replayDiagnostic(target)
                    observations += observation
                    println("READER_LOCAL_REPLAY $observation")
                }
                if (34 < pageCount) fixture.printReplayCosts(34)
            }
            assertTrue(undrawn.isEmpty(), "Undrawn target indices=$undrawn\n" + observations.joinToString("\n"))
        }

    @Test
    @EnabledIfEnvironmentVariable(named = "MIHON_READER_REPLAY_DIRECTORY", matches = ".+")
    fun `downloaded original directory measures replay pairing latency`() = runBlocking {
        val directory = File(requireNotNull(System.getenv("MIHON_READER_REPLAY_DIRECTORY")))
        assertTrue(directory.isDirectory, "An explicit existing original directory is required")
        val pageCount = directory.listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
        assertTrue(pageCount > 0, "Original directory must contain pages")

        val envTargets = System.getenv("MIHON_READER_REPLAY_TARGETS")
        val targets = if (!envTargets.isNullOrBlank()) {
            envTargets.split(",").map { it.trim().toInt() }.filter { it < pageCount }
        } else {
            (0 until pageCount step 10).toList()
        }
        val repeats = System.getenv("MIHON_READER_REPLAY_REPEATS")?.toIntOrNull() ?: 5

        for (target in targets) {
            for (repeatIndex in 0 until repeats) {
                Fixture(pageCount = pageCount, originalDirectory = directory, matching = true).use { fixture ->
                    fixture.observeReplayDraws()
                    fixture.mountReader()
                    fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == pageCount }

                    // Cold measurement
                    val coldRecord = fixture.measureOnce(target, repeatIndex, reopen = false)
                    println("READER_LATENCY " + coldRecord.toJson())

                    // Reopen measurement
                    fixture.replaceRuntime()
                    fixture.observeReplayDraws()
                    fixture.mountReader()
                    fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == pageCount }
                    val reopenRecord = fixture.measureOnce(target, repeatIndex, reopen = true)
                    println("READER_LATENCY " + reopenRecord.toJson())
                }
            }
        }
    }

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `replay measurement distinguishes runtime identity and never reports an undrawn target as drawn`() =
        runBlocking {
            Fixture(pageCount = 8, matching = true).use { fixture ->
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 8 }

                // Cold jump to target = 2: should certify and draw successfully
                val coldRecord = fixture.measureOnce(target = 2, repeat = 0, reopen = false)
                assertTrue(coldRecord.drawn, "Cold jump must be drawn")
                assertNotNull(coldRecord.intentToTargetDrawnMs, "Cold jump must record drawn time")
                assertNotNull(coldRecord.intentToCertifiedMs, "Cold jump must be certified")

                // Reopen: replace runtime, but hold decode of page 2 in the new runtime
                fixture.replaceRuntime()
                val gate = CompletableDeferred<Unit>()
                fixture.fullGate.set(gate)
                try {
                    fixture.observeReplayDraws()
                    fixture.mountReader()
                    fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 8 }

                    // Measure reopen with target = 2 blocked by gate
                    val reopenRecord = fixture.measureOnce(target = 2, repeat = 0, reopen = true, timeoutMillis = 1_000)

                    // BEFORE FIX: reopenRecord.drawn was TRUE because cold measurement left over (sequence to target).
                    // AFTER FIX: target 2 was never certified or drawn in the new runtime, so reopenRecord.drawn is FALSE.
                    assertFalse(
                        reopenRecord.drawn,
                        "Blocked target in new runtime must NOT be reported as drawn from prior runtime receipts: " +
                            "$reopenRecord",
                    )
                    assertNull(reopenRecord.intentToTargetDrawnMs, "Undrawn target must not have drawn ms")
                    assertNull(reopenRecord.intentToCertifiedMs, "Blocked target must not have certified ms")
                    assertEquals("TIMEOUT", reopenRecord.failureReason)
                } finally {
                    gate.complete(Unit)
                }
            }
        }

    @Test
    fun `replay measurement rejects late draw callback from old runtime epoch`() =
        runBlocking {
            Fixture(pageCount = 8, matching = true).use { fixture ->
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 8 }

                // Measure cold jump
                val coldRecord = fixture.measureOnce(target = 2, repeat = 0, reopen = false)
                assertTrue(coldRecord.drawn)

                // Advance to a new runtime
                fixture.replaceRuntime()
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 8 }

                // Manually inject a late callback receipt with the old epoch
                val oldEpoch = fixture.currentRuntimeEpoch - 1
                val newSequence = requireNotNull(fixture.owner()?.location).sequence
                fixture.replayDraws += ReplayDrawReceipt(
                    runtimeEpoch = oldEpoch,
                    locationSequence = newSequence,
                    sourcePageIndex = 4,
                    drawTimestampNs = System.nanoTime(),
                )

                // Blocking page 4 in new runtime
                val gate = CompletableDeferred<Unit>()
                fixture.fullGate.set(gate)
                try {
                    val record = fixture.measureOnce(target = 4, repeat = 0, reopen = true, timeoutMillis = 1_000)
                    assertFalse(record.drawn, "Late callback from old runtime epoch must be rejected")
                    assertNull(record.intentToTargetDrawnMs)
                } finally {
                    gate.complete(Unit)
                }
            }
        }

    @Test
    fun `normal cold jump and reopen measurements certify and draw with accurate timing across runtime replacement`() =
        runBlocking {
            Fixture(pageCount = 8, matching = true).use { fixture ->
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 8 }

                // Normal cold jump
                val coldRecord = fixture.measureOnce(target = 2, repeat = 0, reopen = false)
                assertTrue(coldRecord.drawn, "Cold jump must succeed")
                assertNotNull(coldRecord.intentToTargetDrawnMs, "Cold jump must record draw time")
                assertNotNull(coldRecord.intentToCertifiedMs, "Cold jump must record certified time")
                assertTrue(coldRecord.intentToTargetDrawnMs!! >= coldRecord.intentToCertifiedMs!!)
                assertNull(coldRecord.failureReason)

                // Normal reopen (unblocked)
                fixture.replaceRuntime()
                fixture.observeReplayDraws()
                fixture.mountReader()
                fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 8 }

                val reopenRecord = fixture.measureOnce(target = 2, repeat = 0, reopen = true)
                assertTrue(reopenRecord.drawn, "Reopen jump must succeed when unblocked")
                assertNotNull(reopenRecord.intentToTargetDrawnMs, "Reopen must record draw time")
                assertNotNull(reopenRecord.intentToCertifiedMs, "Reopen must record certified time")
                assertTrue(reopenRecord.intentToTargetDrawnMs!! >= reopenRecord.intentToCertifiedMs!!)
                assertTrue(reopenRecord.certificationRounds > 0, "Reopen must execute certification rounds")
                assertTrue(reopenRecord.decodes > 0, "Reopen must execute decodes in new runtime")
                assertNull(reopenRecord.failureReason)
            }
        }

    @Test
    fun `replay receipt selection strictly isolates epoch and sequence with exact timing`() =
        runBlocking {
            Fixture(pageCount = 8, matching = true).use { fixture ->
                val currentEpoch = fixture.currentRuntimeEpoch
                val oldEpoch = currentEpoch - 1
                val targetSeq = 42L
                val oldSeq = 41L
                val targetIndex = 3

                val oldEpochReceipt = ReplayDrawReceipt(
                    runtimeEpoch = oldEpoch,
                    locationSequence = targetSeq,
                    sourcePageIndex = targetIndex,
                    drawTimestampNs = 1_000_000_000L,
                )
                val currentEpochReceipt = ReplayDrawReceipt(
                    runtimeEpoch = currentEpoch,
                    locationSequence = targetSeq,
                    sourcePageIndex = targetIndex,
                    drawTimestampNs = 2_500_000_000L,
                )
                val staleLocationReceipt = ReplayDrawReceipt(
                    runtimeEpoch = currentEpoch,
                    locationSequence = oldSeq,
                    sourcePageIndex = targetIndex,
                    drawTimestampNs = 1_500_000_000L,
                )

                // 1. When only old epoch receipt is present: selection must return null directly
                fixture.replayDraws.clear()
                fixture.replayDraws += oldEpochReceipt
                assertNull(
                    fixture.selectMatchingReceipt(currentEpoch, targetSeq, targetIndex),
                    "Only old runtime epoch receipt present must yield null",
                )
                assertFalse(fixture.isDrawn(targetSeq, targetIndex))

                // 2. When both old epoch and current epoch receipts are present:
                // must select current epoch receipt, not the earlier old epoch receipt
                fixture.replayDraws.clear()
                fixture.replayDraws += oldEpochReceipt
                fixture.replayDraws += currentEpochReceipt
                val selected = fixture.selectMatchingReceipt(currentEpoch, targetSeq, targetIndex)
                assertNotNull(selected, "Must select current epoch receipt when both are present")
                assertEquals(currentEpoch, selected!!.runtimeEpoch, "Selected receipt must have current runtime epoch")
                assertEquals(
                    2_500_000_000L,
                    selected.drawTimestampNs,
                    "Must select current epoch timestamp, not the earlier old receipt",
                )
                assertTrue(fixture.isDrawn(targetSeq, targetIndex))

                // 3. Stale location within the same current runtime epoch must also be rejected
                fixture.replayDraws.clear()
                fixture.replayDraws += staleLocationReceipt
                assertNull(
                    fixture.selectMatchingReceipt(currentEpoch, targetSeq, targetIndex),
                    "Stale location sequence within current runtime must be rejected",
                )
                assertFalse(fixture.isDrawn(targetSeq, targetIndex))

                // 4. Deterministic timing calculation uses current receipt's drawTimestampNs
                val startedNs = 1_000_000_000L
                val calculatedMs = (selected.drawTimestampNs - startedNs) / 1_000_000
                assertEquals(
                    1500L,
                    calculatedMs,
                    "Timing calculation must strictly use selected receipt's drawTimestampNs",
                )
            }
        }

    @Test
    fun `old original decode can finish across slider navigation without blocking the new location`() =
        runBlocking {
            Fixture(pageCount = 8).use { fixture ->
                fixture.pump {
                    val pages = fixture.model.state.value.session.activeChapter.pages
                    pages.size == 8 && pages.all { it.loadState is ReaderPageLoadState.Ready } &&
                        fixture.owner()?.pageFact(pages.first().id) != null
                }
                val pages = fixture.model.state.value.session.activeChapter.pages
                assertEquals(8, pages.size)
                assertTrue(pages.all { it.loadState is ReaderPageLoadState.Ready })
                val release = CompletableDeferred<Unit>()
                fixture.fullGate.set(release)
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.model.goToPage(0)
                fixture.pump { fixture.fullEntered.isCompleted }
                assertTrue(
                    fixture.fullEntered.isCompleted,
                    "The old original batch must enter the real decoder: " +
                        fixture.owner()?.certifyPairing(pages[0].id),
                )
                val oldBudget = requireNotNull(fixture.owner()).factBudget

                // The real slider callback moves while an old decode is suspended. This does
                // not establish whether that batch has another admitted page still to start.
                fixture.model.goToPage(6)
                assertTrue(requireNotNull(fixture.owner()).factBudget !== oldBudget)
                release.complete(Unit)
                fixture.pump {
                    fixture.model.state.value.dualPairingFailures.containsKey(pages[6].id) ||
                        fixture.owner()?.certifyPairing(pages[6].id) is ReaderPairingCertification.Certified
                }

                assertTrue(
                    fixture.model.state.value.session.activeChapter.pages.all {
                        it.loadState is ReaderPageLoadState.Ready
                    },
                    "Local image materialization succeeds independently of pairing preparation",
                )
                assertFalse(
                    fixture.model.state.value.dualPairingFailures.containsKey(pages[6].id),
                    "An obsolete batch cannot make the current downloaded page show Retry: " +
                        fixture.model.state.value.dualPairingFailures + "; evidence=" +
                        pages.associate { it.id.sourcePageIndex to fixture.owner()?.originalAnalysis(it.id) },
                )
                fixture.mountReader()
                fixture.pump {
                    fixture.owner()?.displayedHistory?.any { pages[6].id in it.drawnPageIds } == true
                }
                assertTrue(
                    fixture.owner()?.displayedHistory?.any { pages[6].id in it.drawnPageIds } == true,
                    "The selected local original must reach the actual Compose draw path",
                )
            }
        }

    @Test
    fun `replacement runtime waits for cancelled original normal materialization until its actual exit`() =
        runBlocking {
            Fixture(
                online = true,
                holdOldBackground = true,
                // The assertions of this scenario observe the pairing region sampling,
                // which only runs when the decoder delivers no inline original samples.
                withoutCachedOriginalSamples = true,
            ).use { fixture ->
                fixture.mountReader()
                fixture.pump {
                    fixture.oldBackgroundEntered.isCompleted &&
                        fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true
                }
                assertTrue(fixture.oldBackgroundEntered.isCompleted)
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                fixture.replaceRuntime()
                fixture.mountReader()
                fixture.pump {
                    fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            it.loadState is ReaderPageLoadState.Ready
                        }
                }
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                assertTrue(!fixture.oldBackgroundRelease.isCompleted)
                val owner = requireNotNull(fixture.owner())
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.pump { fixture.regionReads.get() > 0 }
                assertEquals(
                    0,
                    fixture.regionReads.get(),
                    "Closed sessions cannot advertise cancelled real work as idle",
                )
                fixture.oldBackgroundRelease.complete(Unit)
                fixture.pump {
                    fixture.regionReads.get() > 0 && owner.spreadSampleDependencies().isEmpty() &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            owner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                        }
                }
                assertTrue(owner.spreadSampleDependencies().isEmpty())
                assertTrue(
                    fixture.model.state.value.session.activeChapter.pages.all {
                        owner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                    },
                )
                assertTrue(fixture.owner() === owner)
                assertTrue(fixture.regionReads.get() > 0)
            }
        }

    @Test
    fun `replacement runtime waits for original non cooperative optional Skia work and resumes on actual exit`() =
        runBlocking {
            Fixture(holdRegionNonCooperative = true, withoutCachedOriginalSamples = true).use { fixture ->
                fixture.mountReader()
                fixture.pump { fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true }
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                val release = CompletableDeferred<Unit>()
                fixture.regionGate.set(release)
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.pump { fixture.regionEntered.isCompleted }
                assertTrue(fixture.regionEntered.isCompleted)
                assertEquals(1, fixture.activeRegionReads.get())
                fixture.model.setAutoSpreadMatching(false, fixture.prefs)
                fixture.replaceRuntime()
                fixture.mountReader()
                fixture.pump {
                    fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            fixture.owner()?.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                        }
                }
                assertTrue(fixture.owner()!!.displayedHistory.any { it.drawnPageIds.isNotEmpty() })
                assertEquals(1, fixture.activeRegionReads.get(), "Closing the old runtime cannot end real native work")
                val newOwner = requireNotNull(fixture.owner())
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.pump { fixture.peakRegionReads.get() > 1 }
                assertEquals(1, fixture.peakRegionReads.get(), "New runtimes share the still occupied native resources")
                assertTrue(newOwner.spreadSampleDependencies().isNotEmpty())
                release.complete(Unit)
                fixture.pump(timeoutMillis = 15_000) {
                    newOwner.spreadSampleDependencies().isEmpty() &&
                        fixture.model.state.value.session.activeChapter.pages.all {
                            newOwner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                        }
                }
                assertTrue(newOwner.spreadSampleDependencies().isEmpty())
                assertTrue(fixture.owner() === newOwner)
                assertEquals(1, fixture.peakRegionReads.get())
            }
        }

    @Test
    fun `normal ready neighbor headers contribute before first displayed pixel`() = runBlocking {
        Fixture().use { fixture ->
            fixture.pump {
                fixture.model.state.value.session.activeChapter.pages.size == 5 &&
                    fixture.model.state.value.session.activeChapter.pages.all {
                        it.loadState is ReaderPageLoadState.Ready
                    } &&
                    fixture.owner()?.pageFact(fixture.model.state.value.session.activeChapter.pages[0].id) != null
            }
            val pages = fixture.model.state.value.session.activeChapter.pages
            assertEquals(5, pages.size)
            assertTrue(pages.all { it.loadState is ReaderPageLoadState.Ready })
            assertEquals(null, fixture.runtime.presentationImageOwner.firstPresentedGeneration.value)
            fixture.pump { pages.all { fixture.owner()?.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT } }
            assertTrue(
                pages.all { fixture.owner()?.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT },
                "Normal preloaded encoded headers cannot wait for the current image to be displayed",
            )
            assertTrue(fixture.owner()!!.displayedHistory.isEmpty())
            assertEquals(0, fixture.regionReads.get(), "Disabled matching does not decode optional regions")
        }
    }

    @Test
    fun `real foreground decode pauses optional regions and completion resumes existing content only`() = runBlocking {
        // The named behaviour is the pairing's region decoding, so the fixture has to run the
        // production missing-sample path: the ordinary full page decode already carries the
        // original samples and would leave the region path unobserved.
        Fixture(withoutCachedOriginalSamples = true).use { fixture ->
            val rounds = observePublisherRounds(fixture.model)
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val gate = CompletableDeferred<Unit>()
            fixture.fullGate.set(gate)
            val pending = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                )
            }
            fixture.pump { fixture.fullEntered.isCompleted }
            assertTrue(fixture.fullEntered.isCompleted, "The original native decoder is genuinely running")
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump { fixture.regionReads.get() > 0 }
            assertEquals(
                0,
                fixture.regionReads.get(),
                "Optional region decoding must not compete with real foreground work",
            )
            assertFalse(pending.isCompleted)
            gate.complete(Unit)
            fixture.pump { pending.isCompleted && fixture.regionReads.get() > 0 }
            pending.await()?.close()
            assertTrue(fixture.regionReads.get() > 0, "Original completion must re-evaluate optional work")
            assertTrue(
                fixture.model.state.value.session.activeChapter.pages.all {
                    it.loadState is ReaderPageLoadState.Ready
                },
            )
        }
    }

    @Test
    fun `reverse preparation uses normal HTTP window without extra earlier requests`() = runBlocking {
        Fixture(pageCount = 24, initialPage = 16, online = true).use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.owner()?.displayedHistory?.any { frame ->
                    frame.drawnPageIds.any { it.sourcePageIndex == 16 }
                } == true
            }
            assertEquals(16, fixture.model.state.value.currentPage)
            assertTrue(
                fixture.owner()!!.displayedHistory.any { it.drawnPageIds.any { page -> page.sourcePageIndex == 16 } },
            )
            assertTrue(fixture.paths.contains(16), "The target is fetched through the original production HTTP route")
            assertTrue(fixture.model.navigateDualPage(false))
            val target = fixture.model.state.value.currentPage
            assertTrue(target < 16)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump {
                fixture.owner()?.displayedHistory?.any { frame ->
                    frame.drawnPageIds.any { it.sourcePageIndex == target }
                } == true &&
                    (fixture.paths.any { it < target - 1 } || fixture.regionReads.get() > 0)
            }
            assertTrue(
                fixture.owner()!!.displayedHistory.any {
                    it.drawnPageIds.any { page -> page.sourcePageIndex == target }
                },
            )
            // The canonical certification of a backward pairing really does read pages
            // before target - 1: its scan starts two pages before the target and an
            // edge sample needs both of its endpoints. Those requests are the
            // certification's own demand. What must not happen is that the pairing's
            // own optional preparation or its speculation reaches the network below
            // target - 1, so the assertion is about the purpose of the request and not
            // about the raw request list, which also holds the ordinary preload and the
            // publisher's admitted guesses that never became a request.
            val roles = requireNotNull(fixture.owner()).factBudget.admissionRoles()
            fun roleOf(page: Int) = roles.entries.firstOrNull { it.key.sourcePageIndex == page }?.value
            val windowStart = target - ReaderDualPageFactLimits.DECISION_WINDOW_RADIUS
            // The canonical certification of a backward pairing really does read pages
            // before target - 1: its scan starts two pages before the target, an edge
            // sample needs both of its endpoints, and the scan moves one page further
            // left whenever the finite entries disagree. Those requests are the
            // certification's own demand, so the raw request list must not forbid
            // them. What must not happen is a lookahead that leaves the reader's own
            // neighbourhood: the final pairing is verified above.
            assertTrue(
                fixture.paths.filter { roleOf(it) == ReaderDualPageFactRole.SPECULATIVE }
                    .none { it < windowStart },
                "A pairing lookahead must not reach outside the reader's neighbourhood: " +
                    "target=$target windowStart=$windowStart paths=${fixture.paths} " +
                    "roles=${roles.entries.associate { it.key.sourcePageIndex to it.value }}",
            )
        }
    }

    @Test
    fun `cancelled non cooperative normal materialization stays busy until actual completion`() = runBlocking {
        Fixture(
            pageCount = 24,
            online = true,
            holdOldBackground = true,
            // The assertion of this scenario observes the pairing region sampling,
            // which only runs when the decoder delivers no inline original samples.
            withoutCachedOriginalSamples = true,
        ).use { fixture ->
            fixture.mountReader()
            fixture.pump { fixture.oldBackgroundEntered.isCompleted }
            assertTrue(fixture.oldBackgroundEntered.isCompleted)
            fixture.model.goToPage(16)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump {
                fixture.owner()?.displayedHistory?.any { frame ->
                    frame.drawnPageIds.any { it.sourcePageIndex == 16 }
                } == true && fixture.regionReads.get() > 0
            }
            assertTrue(
                fixture.owner()!!.displayedHistory.any { it.drawnPageIds.any { page -> page.sourcePageIndex == 16 } },
                "The current real image still paints while old physical I/O cannot be cancelled",
            )
            assertEquals(
                0,
                fixture.regionReads.get(),
                "Cancellation cannot advertise still-running original work as idle",
            )
            fixture.oldBackgroundRelease.complete(Unit)
            fixture.pump(timeoutMillis = 8_000) { fixture.regionReads.get() > 0 }
            assertTrue(fixture.regionReads.get() > 0, "Actual old completion resumes the original optional consumer")
        }
    }

    @Test
    fun `real foreground work pauses sampler distinctly from corrupt encoded content`() = runBlocking {
        Fixture().use { fixture ->
            fixture.pump { fixture.model.state.value.session.activeChapter.pages.size == 5 }
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            assertTrue(page.loadState is ReaderPageLoadState.Ready)
            val gate = CompletableDeferred<Unit>()
            fixture.fullGate.set(gate)
            val content = ReaderPageContentOpenRequest(
                page.id,
                fixture.model.state.value.session.generation,
                requireNotNull(page.encodedPageRef),
                page.attemptGeneration,
                page.encodedPageProvenance,
            )
            val pending = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(content, PageDecodePurpose.FULL_PAGE, 40, 60),
                )
            }
            fixture.pump { fixture.fullEntered.isCompleted }
            assertTrue(fixture.fullEntered.isCompleted)
            fixture.runtime.pageImagePipeline.openRegionSession(content).use { region ->
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        DesktopReaderSpreadSampler.sample(
                            fixture.runtime.pageImagePipeline,
                            region,
                            40,
                            60,
                            mihon.domain.reader.ReaderImageOrientation.NORMAL,
                            canContinue = { fixture.runtime.pageImagePipeline.foregroundWork.value == 0 },
                        )
                    }
                }
            }
            assertEquals(0, fixture.regionReads.get(), "Pause rejects work before optional allocation and decode")
            gate.complete(Unit)
            pending.await()?.close()
            assertEquals(0, fixture.runtime.pageImagePipeline.foregroundWork.value)
        }
    }

    @Test
    fun `foreground arriving during original edge decode pauses then resumes matching without retry`() = runBlocking {
        Fixture(withoutCachedOriginalSamples = true).use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val edgeRelease = CompletableDeferred<Unit>()
            fixture.regionGate.set(edgeRelease)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump { fixture.regionEntered.isCompleted }
            assertTrue(fixture.regionEntered.isCompleted, "An original Skia edge task genuinely started")
            val publisher = ReaderScreenModel::class.java.getDeclaredField("spreadPublisherJob").apply {
                isAccessible = true
            }.get(fixture.model) as kotlinx.coroutines.Job
            val fullRelease = CompletableDeferred<Unit>()
            fixture.fullGate.set(fullRelease)
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val full = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                )
            }
            fixture.pump { fixture.fullEntered.isCompleted }
            assertTrue(fixture.fullEntered.isCompleted)
            edgeRelease.complete(Unit)
            fixture.pump { publisher.isCompleted }
            assertTrue(publisher.isCompleted, "Already-running bounded work exits while foreground is busy")
            assertEquals(1, fixture.regionReads.get(), "Busy foreground prevents the second edge")
            fullRelease.complete(Unit)
            fixture.pump {
                fixture.regionReads.get() > 1 && fixture.owner()!!.spreadSampleDependencies().isEmpty()
            }
            full.await()?.close()
            assertTrue(fixture.model.state.value.autoSpreadMatching)
            assertTrue(fixture.owner()!!.spreadSampleDependencies().isEmpty(), "Original sample capabilities completed")
            assertTrue(fixture.regionReads.get() > 1, "Original region decoding resumed")
            assertTrue(
                fixture.model.state.value.dualPageUnits.any {
                    it.pageIds.map { page -> page.sourcePageIndex } == listOf(2, 3)
                },
                "Completed original samples produce the expected quality pair without user Retry",
            )
            assertEquals(0, fixture.model.state.value.currentPage)
        }
    }

    @Test
    fun `actual pinned native lease pauses matching at reduced soft budget and release resumes it`() = runBlocking {
        Fixture(withoutCachedOriginalSamples = true).use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val authority = fixture.runtime.pageImagePipeline.memoryAuthority
            authority.javaClass.getDeclaredField("maxBytes").apply { isAccessible = true }.setLong(authority, 128)
            assertEquals(128L, authority.snapshot().maxBytes, "Only the original soft budget is reduced")
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val lease = requireNotNull(
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                ),
            )
            val owner = requireNotNull(fixture.owner())
            try {
                assertEquals(40, lease.asset.bitmap.width)
                assertEquals(60, lease.asset.bitmap.height)
                assertTrue(authority.snapshot().pinnedBytes > 128)
                assertTrue(authority.snapshot().overBudgetBytes > 0)
                fixture.pump { fixture.runtime.session.foregroundRequestsIdle }
                assertTrue(fixture.runtime.session.foregroundRequestsIdle)
                assertEquals(0, fixture.runtime.pageImagePipeline.foregroundWork.value)
                fixture.model.setAutoSpreadMatching(true, fixture.prefs)
                assertTrue(fixture.owner() === owner)
                assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
                assertTrue(owner.spreadSampleDependencies().isNotEmpty())
                fixture.pump { fixture.regionReads.get() > 0 }
                assertEquals(
                    0,
                    fixture.regionReads.get(),
                    "Pinned original bytes defer optional decoding under pressure",
                )
            } finally {
                lease.close()
            }
            assertEquals(0L, authority.snapshot().overBudgetBytes)
            fixture.pump { fixture.regionReads.get() > 1 && fixture.owner()!!.spreadSampleDependencies().isEmpty() }
            assertTrue(fixture.owner() === owner)
            assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
            assertTrue(fixture.model.state.value.autoSpreadMatching)
            assertEquals(0, fixture.model.state.value.currentPage)
            assertTrue(fixture.regionReads.get() > 1, "Original ledger recovery automatically resumes work")
            assertTrue(fixture.owner()!!.spreadSampleDependencies().isEmpty())
        }
    }

    @Test
    fun `idle consumed while original encoded lease closes resumes paused matching at completion`() = runBlocking {
        Fixture(withoutCachedOriginalSamples = true).use { fixture ->
            fixture.mountReader()
            fixture.pump {
                fixture.model.state.value.dualPageUnits.size == 3 &&
                    fixture.model.state.value.dualPageUnits.all { it.certain } &&
                    fixture.runtime.presentationImageOwner.firstPresentedGeneration.value != null
            }
            assertTrue(fixture.model.state.value.dualPageUnits.all { it.certain })
            fixture.scene.setContent {}
            fixture.pump { true }
            fixture.runtime.pageImagePipeline.clear()
            val edgeRelease = CompletableDeferred<Unit>()
            fixture.regionGate.set(edgeRelease)
            fixture.model.setAutoSpreadMatching(true, fixture.prefs)
            fixture.pump { fixture.regionEntered.isCompleted }
            assertTrue(fixture.regionEntered.isCompleted)
            val owner = requireNotNull(fixture.owner())
            assertEquals(listOf(0), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
            assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
            val publisher = ReaderScreenModel::class.java.getDeclaredField("spreadPublisherJob").apply {
                isAccessible = true
            }.get(fixture.model) as Job
            val physicalLease = fixture.originalRegionLease(requireNotNull(fixture.firstRegionContent.get()))
            val releaseField = physicalLease.javaClass.getDeclaredField("release").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val originalRelease = releaseField.get(physicalLease) as () -> Unit
            val closeEntered = CompletableDeferred<Unit>()
            val closeRelease = CountDownLatch(1)
            val forwarded = AtomicInteger()
            releaseField.set(physicalLease, {
                try {
                    closeEntered.complete(Unit)
                    check(closeRelease.await(10, TimeUnit.SECONDS)) { "Only the original encoded close may be held" }
                } finally {
                    forwarded.incrementAndGet()
                    originalRelease()
                }
            })
            val fullRelease = CompletableDeferred<Unit>()
            fixture.fullGate.set(fullRelease)
            val page = fixture.model.state.value.session.activeChapter.pages[0]
            val full = fixture.scope.async {
                fixture.runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        ReaderPageContentOpenRequest(
                            page.id,
                            fixture.model.state.value.session.generation,
                            requireNotNull(page.encodedPageRef),
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        ),
                        PageDecodePurpose.FULL_PAGE,
                        40,
                        60,
                    ),
                )
            }
            val originalModel = fixture.model
            val modelLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock").apply {
                isAccessible = true
            }.get(originalModel)
            try {
                fixture.pump { fixture.fullEntered.isCompleted }
                assertTrue(fixture.fullEntered.isCompleted)
                edgeRelease.complete(Unit)
                fixture.pump { closeEntered.isCompleted }
                assertTrue(closeEntered.isCompleted, "Original region finally is closing its acquired encoded lease")
                assertFalse(publisher.isCompleted)
                assertEquals(1, fixture.regionReads.get())
                assertEquals(0, forwarded.get())
                val idleConsumed = CompletableDeferred<Unit>()
                synchronized(modelLock) {
                    mockkObject(originalModel, recordPrivateCalls = true)
                    every {
                        originalModel invoke "startSpreadSamplesLocked" withArguments listOf(
                            any<SharedDualPageSession>(),
                            any<mihon.desktop.reader.DesktopReaderRuntime>(),
                            any<CoroutineScope>(),
                            any<kotlinx.coroutines.sync.Semaphore>(),
                        )
                    } answers {
                        val transition = callOriginal()
                        if (closeEntered.isCompleted && closeRelease.count == 1L && !publisher.isCompleted &&
                            fixture.runtime.session.foregroundRequestsIdle &&
                            fixture.runtime.pageImagePipeline.foregroundWork.value == 0
                        ) {
                            idleConsumed.complete(Unit)
                        }
                        transition
                    }
                }
                assertTrue(fixture.model === originalModel)
                fullRelease.complete(Unit)
                fixture.pump {
                    full.isCompleted && fixture.runtime.pageImagePipeline.foregroundWork.value == 0 &&
                        idleConsumed.isCompleted
                }
                assertTrue(full.isCompleted)
                assertEquals(0, fixture.runtime.pageImagePipeline.foregroundWork.value)
                assertTrue(
                    idleConsumed.isCompleted,
                    "The original idle consumer returned while encoded cleanup was held",
                )
                assertFalse(publisher.isCompleted, "The prior publisher is still in original lease cleanup")
                assertEquals(1, fixture.regionReads.get())
                closeRelease.countDown()
                fixture.pump { publisher.isCompleted && owner.spreadSampleDependencies().isEmpty() }
                assertTrue(publisher.isCompleted)
                assertTrue(owner.spreadSampleDependencies().isEmpty(), "Actual completion resumes original sampling")
                assertTrue(fixture.regionReads.get() > 1)
                assertEquals(1, forwarded.get(), "The original coordinator release is forwarded exactly once")
                assertTrue(fixture.owner() === owner)
                assertTrue(owner.identity.pageIds.all { owner.pageFact(it)?.state == ReaderPageFactState.PORTRAIT })
                assertTrue(fixture.model.state.value.autoSpreadMatching)
                assertEquals(0, fixture.model.state.value.currentPage)
            } finally {
                synchronized(modelLock) { unmockkObject(fixture.model) }
                closeRelease.countDown()
                edgeRelease.complete(Unit)
                fullRelease.complete(Unit)
                full.await()?.close()
            }
            fixture.pump { fixture.runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty() }
            assertTrue(fixture.runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
        }
    }

    /** One opt-in development comparison; original B2 payloads, actual production Ready and draw calls. */
    @Test
    @EnabledIfEnvironmentVariable(named = "MIHON_READER_DEVELOPMENT_PERFORMANCE", matches = "true")
    fun `original payload four conditions six reading scenes have no matching dependency wait`() = runBlocking<Unit> {
        val archive = File(requireNotNull(System.getenv("MIHON_READER_ORIGINAL_ARCHIVES")), "udp-real-b2-original.cbz")
        val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439", hash)
        val encoded = ZipFile(archive).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }
                .map { entry -> zip.getInputStream(entry).use { it.readBytes() } }.toList()
        }
        assertEquals(22, encoded.size)
        val scenes = listOf(
            "cold-home",
            "warm-sequential",
            "slow-partner",
            "rapid-keys",
            "cold-jump-reverse",
            "300-pages",
        ).filter { System.getenv("MIHON_READER_PERFORMANCE_SCENE")?.let { selected -> it == selected } ?: true }
        repeat(3) { repeat ->
            for (condition in listOf("off", "on", "suspended", "foreground-pressure")) {
                val donor = if (condition == "suspended") {
                    Fixture(online = true, encodedPages = encoded, holdRegionNonCooperative = true).also {
                        it.regionGate.set(CompletableDeferred())
                        it.mountReader()
                        it.pump { it.runtime.pageImagePipeline.foregroundWork.value == 0 && it.owner() != null }
                        it.model.setAutoSpreadMatching(true, it.prefs)
                        it.pump { it.regionEntered.isCompleted }
                        assertTrue(it.regionEntered.isCompleted, "The original optional decode must really enter")
                    }
                } else {
                    null
                }
                var measuredPipeline: mihon.desktop.reader.DesktopReaderPageImagePipeline? = null
                try {
                    for (sceneName in scenes) {
                        val count = if (sceneName == "300-pages") 300 else 22
                        val initial = when (sceneName) {
                            "slow-partner" -> 3
                            "cold-jump-reverse" -> 19
                            "300-pages" -> 199
                            else -> 0
                        }
                        Fixture(
                            pageCount = count,
                            initialPage = initial,
                            online = true,
                            encodedPages = encoded,
                            heldNetworkPage = 4.takeIf { sceneName == "slow-partner" },
                        ).use { fixture ->
                            measuredPipeline = fixture.runtime.pageImagePipeline
                            fixture.observePerformance(initial)
                            if (condition == "foreground-pressure") {
                                val authority = fixture.runtime.pageImagePipeline.memoryAuthority
                                authority.javaClass.getDeclaredField("maxBytes").apply { isAccessible = true }
                                    .setLong(authority, 128)
                                assertEquals(128L, authority.snapshot().maxBytes)
                            }
                            fixture.model.setAutoSpreadMatching(condition != "off", fixture.prefs)
                            fixture.measurementStart = System.nanoTime()
                            fixture.mountReader()
                            fixture.performancePaint(initial)
                            when (sceneName) {
                                "warm-sequential" -> {
                                    fixture.markInput()
                                    fixture.input(true)
                                    fixture.performancePaint(fixture.model.state.value.currentPage)
                                }
                                "slow-partner" -> {
                                    assertTrue(!fixture.networkRelease.get())
                                    fixture.networkRelease.set(true)
                                    fixture.performancePaint(4)
                                }
                                "rapid-keys" -> {
                                    fixture.markInput()
                                    repeat(3) { fixture.input(true) }
                                    fixture.performancePaint(fixture.model.state.value.currentPage)
                                }
                                "cold-jump-reverse" -> {
                                    fixture.markInput()
                                    fixture.input(false)
                                    fixture.performancePaint(fixture.model.state.value.currentPage)
                                }
                            }
                            fixture.printPerformance(repeat, condition, sceneName, initial)
                            if (donor != null) {
                                assertTrue(!donor.regionGate.get()!!.isCompleted)
                                assertEquals(1, fixture.runtime.pageImagePipeline.optionalWork.value)
                            }
                        }
                    }
                } finally {
                    donor?.regionGate?.get()?.complete(Unit)
                    donor?.close()
                }
                val until = System.nanoTime() + 5_000_000_000L
                while (requireNotNull(measuredPipeline).optionalWork.value > 0 &&
                    System.nanoTime() < until
                ) {
                    delay(5)
                }
                assertEquals(0, requireNotNull(measuredPipeline).optionalWork.value)
            }
        }
    }

    @Test
    fun `publisher binding single failure recovers and persistent failure is bounded`() =
        runBlocking {
            Fixture(pageCount = 3).use { fixture ->
                fixture.mountReader()
                fixture.pump { fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true }
                val model = fixture.model
                val modelLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock").apply {
                    isAccessible = true
                }.get(model)
                val callCount = java.util.concurrent.atomic.AtomicInteger(0)
                val simulatedException = IllegalStateException("Simulated transient publisher failure")
                synchronized(modelLock) {
                    mockkObject(model, recordPrivateCalls = true)
                    every {
                        model invoke "startSpreadSamplesLocked" withArguments listOf(
                            any<SharedDualPageSession>(),
                            any<mihon.desktop.reader.DesktopReaderRuntime>(),
                            any<CoroutineScope>(),
                            any<kotlinx.coroutines.sync.Semaphore>(),
                        )
                    } answers {
                        val count = callCount.incrementAndGet()
                        if (count == 1) {
                            throw simulatedException
                        }
                        val transition = callOriginal()
                        transition
                    }
                }
                model.setAutoSpreadMatching(true, fixture.prefs)
                fixture.pump { callCount.get() >= 2 && model.publisherBindingFailure == null }
                assertTrue(callCount.get() >= 2, "Transient failure must trigger retry recovery")
                assertEquals(null, model.publisherBindingFailure, "Recovered publisher clears failure record")

                val persistentCount = java.util.concurrent.atomic.AtomicInteger(0)
                val persistentException = IllegalStateException("Simulated persistent publisher failure")
                synchronized(modelLock) {
                    every {
                        model invoke "startSpreadSamplesLocked" withArguments listOf(
                            any<SharedDualPageSession>(),
                            any<mihon.desktop.reader.DesktopReaderRuntime>(),
                            any<CoroutineScope>(),
                            any<kotlinx.coroutines.sync.Semaphore>(),
                        )
                    } answers {
                        persistentCount.incrementAndGet()
                        callOriginal()
                        throw persistentException
                    }
                }
                model.goToPage(1)
                fixture.pump { persistentCount.get() >= 2 }
                fixture.pump(timeoutMillis = 500) { persistentCount.get() > 2 }
                assertEquals(
                    2,
                    persistentCount.get(),
                    "Persistent failure must remain bounded without cancellation callback retry bypass (at most 1 retry)",
                )
                assertSame(persistentException, model.publisherBindingFailure)
            }
        }

    @Test
    fun `same page retry and configuration change recover from bounded publisher failures and remain bounded`() =
        runBlocking {
            // Retry force-refresh uses the source API. Supply the existing real HTTP fixture
            // instead of a relaxed source manager that only happened to serve local Ready refs.
            Fixture(pageCount = 3, online = true).use { fixture ->
                fixture.mountReader()
                fixture.pump { fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true }
                val model = fixture.model
                val recoveredUnit = fixture.completeContentTargetUnit(1)
                assertEquals(listOf(1, 2), recoveredUnit.pageIds.map { it.sourcePageIndex })
                val modelLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock").apply {
                    isAccessible = true
                }.get(model)

                val shouldFail = java.util.concurrent.atomic.AtomicBoolean(true)
                val attemptCount = java.util.concurrent.atomic.AtomicInteger(0)
                val persistentException = IllegalStateException("Simulated persistent publisher failure")

                try {
                    synchronized(modelLock) {
                        mockkObject(model, recordPrivateCalls = true)
                        every {
                            model invoke "startSpreadSamplesLocked" withArguments listOf(
                                any<SharedDualPageSession>(),
                                any<mihon.desktop.reader.DesktopReaderRuntime>(),
                                any<CoroutineScope>(),
                                any<kotlinx.coroutines.sync.Semaphore>(),
                            )
                        } answers {
                            attemptCount.incrementAndGet()
                            if (shouldFail.get()) {
                                callOriginal()
                                throw persistentException
                            } else {
                                callOriginal()
                            }
                        }
                    }

                    model.goToPage(1)
                    val targetPageId = model.state.value.session.activeChapter.pages[1].id
                    fixture.pump { attemptCount.get() >= 2 }
                    fixture.pump(timeoutMillis = 500) { attemptCount.get() > 2 }
                    assertEquals(
                        2,
                        attemptCount.get(),
                        "Persistent failure must converge to exactly 2 attempts on initial page",
                    )
                    assertSame(persistentException, model.publisherBindingFailure)

                    // PART 1: Still failing. External config change on same page must allow bounded retry (+2 attempts)
                    model.setAutoSpreadMatching(false, fixture.prefs)
                    model.setAutoSpreadMatching(true, fixture.prefs)
                    fixture.pump { attemptCount.get() >= 4 }
                    fixture.pump(timeoutMillis = 500) { attemptCount.get() > 4 }
                    assertEquals(
                        4,
                        attemptCount.get(),
                        "External config change with persistent failure must remain bounded (exactly 2 new attempts)",
                    )

                    // PART 2: Remove failure. Calling retryPage on same page without navigating must recover
                    shouldFail.set(false)
                    val requestsBeforeRetry = fixture.paths.count { it == targetPageId.sourcePageIndex }
                    model.retryPage(targetPageId)
                    fixture.pump(timeoutMillis = 5_000) {
                        attemptCount.get() >= 5 && model.publisherBindingFailure == null
                    }
                    assertTrue(
                        attemptCount.get() >= 5,
                        "retryPage on the same page must start a new attempt when failure is removed",
                    )
                    assertNull(
                        model.publisherBindingFailure,
                        "Successful retry on the same page must clear publisherBindingFailure",
                    )
                    val owner = requireNotNull(fixture.owner())
                    fixture.pump(timeoutMillis = 5_000) {
                        owner.spreadSampleDependencies().isEmpty() &&
                            model.state.value.session.activeChapter.pages.all {
                                owner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                            }
                    }
                    assertTrue(
                        owner.spreadSampleDependencies().isEmpty(),
                        "Recovered publisher on same page must complete spread sample dependencies",
                    )
                    assertTrue(
                        fixture.paths.count { it == targetPageId.sourcePageIndex } > requestsBeforeRetry,
                        "The retry must execute the fixture source's real image request",
                    )

                    // PART 3: Changing configuration after the failure is removed recovers on the same page
                    // and paints a new presentation frame.
                    shouldFail.set(true)
                    model.setAutoSpreadMatching(false, fixture.prefs)
                    model.setAutoSpreadMatching(true, fixture.prefs)
                    fixture.pump { attemptCount.get() >= 7 }
                    assertSame(persistentException, model.publisherBindingFailure)

                    // Capture boundary before recovery
                    val revisionBeforeRecovery = fixture.owner()?.layoutRevision ?: 0

                    // Remove failure and trigger config change on same page without calling retryPage
                    shouldFail.set(false)
                    model.setAutoSpreadMatching(false, fixture.prefs)
                    model.setAutoSpreadMatching(true, fixture.prefs)
                    fixture.pump(timeoutMillis = 5_000) {
                        attemptCount.get() >= 9 && model.publisherBindingFailure == null
                    }
                    assertNull(
                        model.publisherBindingFailure,
                        "Config change after fault removed must recover publisher on same page",
                    )
                    fixture.pump(timeoutMillis = 5_000) {
                        fixture.owner()?.spreadSampleDependencies()?.isEmpty() == true
                    }
                    assertTrue(
                        fixture.owner()?.spreadSampleDependencies()?.isEmpty() == true,
                        "Recovered publisher via config change must complete spread sample dependencies",
                    )
                    assertTrue(
                        (fixture.owner()?.layoutRevision ?: 0) > revisionBeforeRecovery,
                        "Layout revision must advance after config recovery",
                    )

                    // Verify actual presentation frame is drawn for targetPageId
                    fixture.pump(timeoutMillis = 5_000) {
                        fixture.owner()?.displayedHistory?.any { frame ->
                            frame.drawnPageIds.any { it == targetPageId }
                        } == true
                    }
                    // Verify actual presentation frame is drawn for the recovered unit
                    val page2Id = model.state.value.session.activeChapter.pages[2].id
                    fixture.pump(timeoutMillis = 5_000) {
                        fixture.owner()?.displayedHistory?.any { frame ->
                            frame.drawnPageIds.any { it == page2Id }
                        } == true
                    }
                    assertTrue(
                        fixture.owner()?.displayedHistory?.any { frame ->
                            frame.drawnPageIds.any { it == page2Id }
                        } == true,
                        "Recovered publisher must paint the full-content canonical unit=$recoveredUnit: " +
                            fixture.replayDiagnostic(1),
                    )
                } finally {
                    synchronized(modelLock) { unmockkObject(model) }
                }
            }
        }

    @Test
    fun `reopened reader model with new runtime waits for unclosed held pairing work and resumes on exit`() =
        runBlocking {
            val prepared = CopyOnWriteArrayList<mihon.domain.reader.scheduler.ReaderScheduledRequest>()
            mockkStatic("mihon.desktop.reader.ReaderIoGateKt")
            try {
                every { any<mihon.domain.reader.scheduler.ReaderScheduledRequest>().gatePoint(any()) } answers {
                    prepared += firstArg<mihon.domain.reader.scheduler.ReaderScheduledRequest>()
                    callOriginal()
                }
                Fixture(
                    pageCount = 10,
                    initialPage = 4,
                    online = true,
                    heldNetworkPage = 2,
                    holdNetworkOnce = true,
                    matching = true,
                    withoutCachedOriginalSamples = true,
                ).use { fixture ->
                    fixture.mountReader()
                    // Certification may need page two before the target-four frame can draw.
                    // Hold that real pairing request; the ordinary viewport window is 3..8.
                    fixture.pump { 2 in fixture.paths }
                    assertTrue(2 in fixture.paths, "Held network page 2 must be requested by the first model")

                    val oldRuntime = fixture.runtime
                    val oldModel = fixture.model
                    val oldOwner = requireNotNull(fixture.owner())
                    val heldRequests = prepared.filter { it.pageId.sourcePageIndex == 2 }
                    assertTrue(
                        heldRequests.isNotEmpty() && heldRequests.all { (it.factPermit?.ownerKey ?: 0L) > 0L },
                        "Held page two must be physically prepared with a pairing permit, not ordinary prefetch: " +
                            "$heldRequests",
                    )
                    val oldKeys = heldRequests.map { requireNotNull(it.factPermit).ownerKey }.distinct()
                    assertEquals(1, oldKeys.size, "The held input must have an unambiguous real publisher")
                    val oldOwnerKey = oldKeys.single()
                    assertEquals(
                        1,
                        ReaderPageMaterializeRunner.processBlockingWorkCounts.value[oldOwnerKey],
                        "Old pairing work must register with its publisher owner key",
                    )

                    val replacementStart = prepared.size
                    fixture.replaceRuntime()
                    assertEquals(
                        1,
                        ReaderPageMaterializeRunner.processBlockingWorkCounts.value[oldOwnerKey],
                        "Held pairing work must survive runtime replace",
                    )

                    fixture.mountReader()
                    val newModel = fixture.model
                    org.junit.jupiter.api.Assertions.assertNotSame(oldRuntime, fixture.runtime)
                    org.junit.jupiter.api.Assertions.assertNotSame(oldModel, newModel)
                    org.junit.jupiter.api.Assertions.assertNotSame(oldOwner, fixture.owner())

                    fixture.pump {
                        val pages = newModel.state.value.session.activeChapter.pages
                        pages.size == 10 && pages.filter { it.id.sourcePageIndex in 3..8 }
                            .all { it.loadState is ReaderPageLoadState.Ready }
                    }
                    val readyWindow = newModel.state.value.session.activeChapter.pages
                        .filter { it.id.sourcePageIndex in 3..8 }
                    assertEquals(6, readyWindow.size, "The new runtime must have the complete ordinary viewport window")
                    assertTrue(
                        readyWindow.all { it.loadState is ReaderPageLoadState.Ready },
                        "The new ordinary viewport must actually finish: ${readyWindow.map { it.id to it.loadState }}",
                    )

                    newModel.setAutoSpreadMatching(true, fixture.prefs)
                    fixture.pump { fixture.regionReads.get() > 0 }

                    // Required encoded materialization may run before sampling can pass the busy
                    // gate. Prove every additional owner from the real in-flight request's permit,
                    // rather than mistaking all positive publisher keys for ordinary reader work.
                    val newRequests = prepared.drop(replacementStart)
                    val blocking = ReaderPageMaterializeRunner.processBlockingWorkCounts.value.toMap()
                    val newRequestOwners = newRequests.mapNotNull { it.factPermit?.ownerKey }.toSet()
                    assertEquals(1, blocking[oldOwnerKey], "The old held page must still own physical work")
                    assertEquals(
                        0,
                        blocking[ReaderPageMaterializeRunner.ORDINARY_WORK_KEY] ?: 0,
                        "No ordinary work may mask the old pairing hold",
                    )
                    val additional = blocking.filterKeys { it != oldOwnerKey }
                    assertTrue(
                        additional.keys.all { it > 0L && it in newRequestOwners },
                        "Additional work must match a real permit prepared after runtime replacement: $blocking " +
                            "requests=${newRequests.map {
                                Triple(it.pageId.sourcePageIndex, it.kind, it.factPermit?.ownerKey)
                            }}",
                    )
                    assertTrue(
                        additional.values.sum() <= ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
                        "New required materialization must retain its concurrency bound: $blocking",
                    )
                    assertTrue(!fixture.networkRelease.get(), "The original physical input must remain held")
                    assertEquals(
                        0,
                        fixture.regionReads.get(),
                        "New model cannot mistakenly exclude unclosed old model's pairing work and must stay busy",
                    )

                    fixture.networkRelease.set(true)
                    val newOwner = requireNotNull(fixture.owner())
                    fixture.pump(timeoutMillis = 8_000) {
                        (ReaderPageMaterializeRunner.processBlockingWorkCounts.value[oldOwnerKey] ?: 0) == 0 &&
                            newOwner.spreadSampleDependencies().isEmpty() &&
                            newModel.state.value.session.activeChapter.pages
                                .filter { it.id.sourcePageIndex in 3..8 }
                                .all { newOwner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT }
                    }
                    assertTrue(
                        newOwner.spreadSampleDependencies().isEmpty(),
                        "New model must resume and complete dependencies after old work actually exits: " +
                            "blocking=${ReaderPageMaterializeRunner.processBlockingWorkCounts.value} " +
                            "capturedClosed=${newOwner.isClosed} sameOwner=${fixture.owner() === newOwner} " +
                            "dependencies=${newOwner.spreadSampleDependencies().map { it.sourcePageIndex }} " +
                            fixture.replayDiagnostic(4),
                    )
                    assertEquals(
                        0,
                        ReaderPageMaterializeRunner.blockingWorkExcept(0L),
                        "All blocking work must balance to 0 on exit",
                    )
                }
            } finally {
                unmockkStatic("mihon.desktop.reader.ReaderIoGateKt")
            }
        }

    @Test
    fun `content rebind on same position admits new owner and publishes facts while ordinary pages remain stable`() =
        runBlocking {
            Fixture(
                pageCount = 4,
                initialPage = 0,
                online = true,
                matching = true,
                withoutCachedOriginalSamples = true,
            ).use { fixture ->
                fixture.mountReader()
                val model = fixture.model
                fixture.pump { fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true }
                val initialOwner = requireNotNull(fixture.owner())
                val page2 = model.state.value.session.activeChapter.pages[2]
                val page2Id = page2.id

                // Wait for initial facts and Computed original analysis on all pages in demand range (including page 2)
                fixture.pump {
                    initialOwner.spreadSampleDependencies().isEmpty() &&
                        initialOwner.originalAnalysis(page2Id) is ReaderPageAnalysisEvidence.Computed &&
                        model.state.value.session.activeChapter.pages.all {
                            initialOwner.pageFact(it.id)?.state == ReaderPageFactState.PORTRAIT
                        }
                }
                val initialAnalysis = initialOwner.originalAnalysis(page2Id)
                assertTrue(
                    initialAnalysis is ReaderPageAnalysisEvidence.Computed,
                    "Initial owner must produce Computed original analysis via fact budget",
                )
                val initialContentVersion =
                    (initialAnalysis as ReaderPageAnalysisEvidence.Computed).content.contentVersion
                assertEquals(ReaderPageFactState.PORTRAIT, initialOwner.pageFact(page2Id)?.state)
                assertTrue(
                    initialOwner.factBudget.holds(page2Id) ||
                        page2Id in initialOwner.factBudget.requiredPages() ||
                        page2Id in initialOwner.factBudget.optionalPages(),
                    "Initial budget must have attempted page 2",
                )

                // Trigger real page content reload on page 2 (unexposed content in demand range)
                fixture.runtime.session.retryPage(page2Id)

                // Wait for the rebind and new owner without navigating (staying at page 0)
                fixture.pump { fixture.owner() !== initialOwner }
                val newOwner = requireNotNull(fixture.owner())
                org.junit.jupiter.api.Assertions.assertNotSame(
                    initialOwner,
                    newOwner,
                    "Content rebind must establish a new session owner",
                )
                assertTrue(
                    newOwner.inheritsPresentationFrom(initialOwner),
                    "New owner must inherit presentation lifecycle",
                )
                assertEquals(
                    0,
                    newOwner.location?.pageId?.sourcePageIndex,
                    "Location must stay at page 0 during rebind",
                )

                // Untouched pages 0, 1, 3 retain their facts
                listOf(0, 1, 3).forEach { index ->
                    val pid = model.state.value.session.activeChapter.pages[index].id
                    assertEquals(
                        ReaderPageFactState.PORTRAIT,
                        newOwner.pageFact(pid)?.state,
                        "Untouched page $index fact must remain stable",
                    )
                }

                // Wait for page 2 to reload and become Ready in session
                fixture.pump {
                    val p = model.state.value.session.activeChapter.pages[2]
                    p.attemptGeneration > 0 && p.loadState is ReaderPageLoadState.Ready
                }

                // BEFORE any navigation: independently prove that page 2 is re-admitted by the new owner
                // through the real fact budget, producing Computed analysis at the same position
                // while the visible pixel state stays stable.
                fixture.pump(timeoutMillis = 10_000) {
                    newOwner.spreadSampleDependencies().isEmpty() &&
                        newOwner.originalAnalysis(page2Id) is ReaderPageAnalysisEvidence.Computed &&
                        newOwner.pageFact(page2Id)?.state == ReaderPageFactState.PORTRAIT
                }
                val newAnalysis = newOwner.originalAnalysis(page2Id)
                assertTrue(
                    newAnalysis is ReaderPageAnalysisEvidence.Computed,
                    "New owner must re-admit and complete original analysis via fact budget before navigation",
                )
                val newContentVersion =
                    (newAnalysis as ReaderPageAnalysisEvidence.Computed).content.contentVersion
                assertNotEquals(
                    initialContentVersion,
                    newContentVersion,
                    "New original analysis must correspond to replacement content version",
                )
                assertEquals(ReaderPageFactState.PORTRAIT, newOwner.pageFact(page2Id)?.state)

                // Verify ordinary presentation of visible page 0 remains stable before navigation
                assertTrue(
                    newOwner.displayedHistory.isNotEmpty(),
                    "Presentation history must remain valid for ordinary viewer",
                )
                val lastFrame = newOwner.displayedHistory.last()
                assertTrue(
                    lastFrame.drawnPageIds.any { it.sourcePageIndex == 0 },
                    "Current visible page 0 must remain stably presented before navigation",
                )

                // Now verify subsequent normal navigation draws the re-admitted new content
                val historyCountBeforeNav = newOwner.displayedHistory.size
                fixture.model.goToPage(2)
                fixture.pump(timeoutMillis = 8_000) {
                    newOwner.displayedHistory.size > historyCountBeforeNav &&
                        newOwner.displayedHistory.any { it.drawnPageIds.contains(page2Id) }
                }
                assertTrue(
                    newOwner.displayedHistory.any { it.drawnPageIds.contains(page2Id) },
                    "Subsequent navigation must successfully present the re-admitted content",
                )
            }
        }

    @Test
    fun `explicit capacity hold blocks analysis admission and retry facts clears slot`() =
        runBlocking {
            Fixture(
                pageCount = 4,
                initialPage = 0,
                online = true,
                matching = true,
                withoutCachedOriginalSamples = true,
            ).use { fixture ->
                fixture.mountReader()
                val model = fixture.model
                fixture.pump { fixture.owner()?.displayedHistory?.any { it.drawnPageIds.isNotEmpty() } == true }
                val initialOwner = requireNotNull(fixture.owner())
                val page2 = model.state.value.session.activeChapter.pages[2]
                val page2Id = page2.id

                val modelLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
                    .apply { isAccessible = true }.get(model)
                val injectOldAttempt = java.util.concurrent.atomic.AtomicBoolean(false)
                synchronized(modelLock) {
                    mockkObject(model)
                    every {
                        model invoke "startSpreadSamplesLocked" withArguments listOf(
                            any<SharedDualPageSession>(),
                            any<mihon.desktop.reader.DesktopReaderRuntime>(),
                            any<CoroutineScope>(),
                            any<kotlinx.coroutines.sync.Semaphore>(),
                        )
                    } answers {
                        val owner = firstArg<SharedDualPageSession>()
                        if (injectOldAttempt.get()) {
                            val now = System.nanoTime() / 1_000_000
                            owner.factBudget.reserve(listOf(page2Id), now, required = true)
                        }
                        callOriginal()
                    }
                }

                try {
                    fixture.pump {
                        initialOwner.spreadSampleDependencies().isEmpty() &&
                            initialOwner.originalAnalysis(page2Id) is ReaderPageAnalysisEvidence.Computed
                    }
                    assertTrue(initialOwner.originalAnalysis(page2Id) is ReaderPageAnalysisEvidence.Computed)

                    // Enable fault injection: simulate bug where old attempt is retained across rebind
                    injectOldAttempt.set(true)

                    // Trigger real page content reload on page 2
                    fixture.runtime.session.retryPage(page2Id)

                    // Wait for page 2 to reload and become Ready with replacement content
                    fixture.pump {
                        val p = model.state.value.session.activeChapter.pages[2]
                        p.attemptGeneration > 0 && p.loadState is ReaderPageLoadState.Ready
                    }
                    val rebindOwner = requireNotNull(fixture.owner())
                    org.junit.jupiter.api.Assertions.assertNotSame(initialOwner, rebindOwner)

                    // Verify that because old attempt occupied the budget, new publisher was
                    // blocked from admitting and producing Computed analysis
                    kotlinx.coroutines.delay(400)
                    assertTrue(
                        rebindOwner.originalAnalysis(page2Id) !is ReaderPageAnalysisEvidence.Computed,
                        "Reusing old attempt must block new original analysis admission",
                    )

                    // Recovery: remove fault injection (simulate bug fixed) and retry
                    injectOldAttempt.set(false)
                    rebindOwner.retryFacts()
                    fixture.pump(timeoutMillis = 5_000) {
                        val current = fixture.owner()
                        current?.spreadSampleDependencies()?.isEmpty() == true &&
                            current.originalAnalysis(page2Id) is ReaderPageAnalysisEvidence.Computed
                    }
                    val recoveredOwner = requireNotNull(fixture.owner())
                    assertTrue(
                        recoveredOwner.originalAnalysis(page2Id) is ReaderPageAnalysisEvidence.Computed,
                        "Recovery without old attempt reuse successfully produces Computed original analysis",
                    )
                } finally {
                    synchronized(modelLock) { unmockkObject(model) }
                }
            }
        }

    private inner class Fixture(
        pageCount: Int = 5,
        initialPage: Int = 0,
        online: Boolean = false,
        holdOldBackground: Boolean = false,
        holdRegionNonCooperative: Boolean = false,
        private val encodedPages: List<ByteArray>? = null,
        private val heldNetworkPage: Int? = null,
        private val holdNetworkOnce: Boolean = false,
        originalDirectory: File? = null,
        matching: Boolean = false,
        withoutCachedOriginalSamples: Boolean = false,
        private val manualSpreadPublisher: Boolean = false,
        private val failedEncodedPage: Int? = null,
        private val readControl: ReaderTestModeController? = null,
    ) : AutoCloseable {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val uiScope = CoroutineScope(SupervisorJob() + dispatcher)
        val legacy = Preferences.userRoot().node("/mihon/spread-resource/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = matching
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val acceptedReady = ConcurrentHashMap<Int, Long>()
        val decoderReady = ConcurrentHashMap<Int, Long>()
        val ioEvents = CopyOnWriteArrayList<ReaderIoEvent>()
        val actualPaint = ConcurrentHashMap<Int, Long>()
        val paintFrames = ConcurrentHashMap<Int, mihon.domain.reader.ReaderDualPageFrameId>()
        val paintSlots = ConcurrentHashMap<Int, List<Int>>()
        val readyIdentities = ConcurrentHashMap<Int, String>()
        val readyFrames = ConcurrentHashMap<Int, Int>()
        val paintedFrames = ConcurrentHashMap<Int, Int>()
        val frameNumber = AtomicInteger()
        val networkBytes = AtomicLong()
        val networkRelease = AtomicBoolean(false)
        val frameCosts = mutableListOf<Long>()
        val frameIntervals = mutableListOf<Long>()
        var previousRender = 0L
        var observedOverBudget = 0L
        var measurementStart = 0L
        var inputStarted = 0L
        var firstInputPaint = 0L
        var observedResident = 0L
        var observedPinned = 0L
        var observedHeap = 0L
        private var performanceObserved = false
        private var replayObserved = false
        val runtimeEpochCounter = AtomicLong(1L)
        var currentRuntimeEpoch: Long = runtimeEpochCounter.get()
        val replayDraws = CopyOnWriteArrayList<ReplayDrawReceipt>()

        fun selectMatchingReceipt(epoch: Long, sequence: Long, target: Int): ReplayDrawReceipt? =
            replayDraws.firstOrNull {
                it.runtimeEpoch == epoch &&
                    it.locationSequence == sequence &&
                    it.sourcePageIndex == target
            }

        fun isDrawn(sequence: Long, target: Int): Boolean =
            selectMatchingReceipt(currentRuntimeEpoch, sequence, target) != null
        private val replayOriginals = ConcurrentHashMap<ReaderPageDecodeKey, ReplayOriginal>()
        private var replayCostBaseline: ReplayCostBaseline? = null
        private val diagnostic = System.getenv("MIHON_READER_PERFORMANCE_DIAGNOSTIC") == "true"
        private val performanceEvents = CopyOnWriteArrayList<String>()

        private fun diagnose(label: String) {
            if (diagnostic) performanceEvents += "${System.nanoTime()}/render${frameNumber.get()}:$label"
        }
        val failEncoded = AtomicBoolean(failedEncodedPage != null)
        private fun payload(index: Int) = if (index == failedEncodedPage && failEncoded.get()) {
            byteArrayOf(1, 2, 3, 4)
        } else {
            encodedPages?.let { it[index % it.size] } ?: image(index)
        }
        val decodeInvocations = ConcurrentHashMap<Int, AtomicInteger>()
        val pageGate = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val pageEntered = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val speculativeGatePredicate = AtomicReference<((Int) -> Boolean)?>(null)
        val speculativeGate = CompletableDeferred<Unit>()
        val speculativeEntered = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val regionReads = AtomicInteger()
        val activeRegionReads = AtomicInteger()
        val peakRegionReads = AtomicInteger()
        val firstRegionContent = AtomicReference<ReaderPageContentOpenRequest?>(null)
        val regionGate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val regionEntered = CompletableDeferred<Unit>()
        val firstRegionGate = AtomicBoolean(true)
        val fullGate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val fullEntered = CompletableDeferred<Unit>()
        val oldBackgroundEntered = CompletableDeferred<Unit>()
        val oldBackgroundRelease = CompletableDeferred<Unit>()
        val gateOldBackground = AtomicBoolean(holdOldBackground)
        val native = SkiaDesktopReaderPageImageDecoder()
        private val decodeFailures = CopyOnWriteArrayList<String>()
        private val recordedNative = DesktopReaderPageImageDecoder { bytes, key ->
            try {
                native.decode(bytes, key).also { decoded ->
                    if (decoded == null) decodeFailures += "$key returned null for ${bytes.size} encoded bytes"
                }
            } catch (error: Exception) {
                if (error !is CancellationException) decodeFailures += "$key: ${error.stackTraceToString()}"
                throw error
            }
        }
        val decoder = DesktopReaderPageImageDecoder { bytes, key ->
            diagnose("decoderEnter:${key.pageIndex}:${key.purpose}")
            decodeInvocations.computeIfAbsent(key.pageIndex) { AtomicInteger() }.incrementAndGet()
            pageGate[key.pageIndex]?.let { gate ->
                pageEntered.computeIfAbsent(key.pageIndex) { CompletableDeferred() }.complete(Unit)
                gate.await()
            }
            speculativeGatePredicate.get()?.takeIf { it(key.pageIndex) }?.let {
                speculativeEntered.computeIfAbsent(key.pageIndex) { CompletableDeferred() }.complete(Unit)
                speculativeGate.await()
            }
            if (key.purpose == PageDecodePurpose.REGION_TILE) {
                firstRegionContent.compareAndSet(null, key.contentKey)
                regionReads.incrementAndGet()
                val active = activeRegionReads.incrementAndGet()
                peakRegionReads.accumulateAndGet(active, ::maxOf)
                try {
                    regionGate.get()?.takeIf { firstRegionGate.compareAndSet(true, false) }?.let {
                        regionEntered.complete(Unit)
                        if (holdRegionNonCooperative) withContext(NonCancellable) { it.await() } else it.await()
                    }
                    return@DesktopReaderPageImageDecoder recordedNative.decode(bytes, key).also {
                        diagnose("decoderReturn:${key.pageIndex}:${key.purpose}")
                    }
                } finally {
                    activeRegionReads.decrementAndGet()
                }
            }
            if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                fullGate.get()?.let {
                    fullEntered.complete(Unit)
                    it.await()
                }
            }
            recordedNative.decode(bytes, key).let { decoded ->
                if (withoutCachedOriginalSamples && key.purpose == PageDecodePurpose.FULL_PAGE && decoded != null) {
                    val asset = decoded.asset
                    DesktopReaderImageAsset(
                        asset.bitmap,
                        asset.sourceWidth,
                        asset.sourceHeight,
                        asset.estimatedBytes,
                        asset.sampled,
                        sourceOrientation = asset.sourceOrientation,
                        disposer = decoded::close,
                    )
                } else {
                    decoded
                }
            }.also {
                if (originalDirectory != null && key.purpose == PageDecodePurpose.FULL_PAGE && it != null) {
                    val asset = it.asset
                    replayOriginals[key] = ReplayOriginal(
                        asset.sourceWidth,
                        asset.sourceHeight,
                        asset.sourceOrientation,
                        asset.originalSamples.toMap(),
                        ReaderOriginalPageSampling.ALGORITHM_VERSION,
                    )
                }
                decoderReady[key.pageIndex] = System.nanoTime()
                diagnose("decoderReturn:${key.pageIndex}:${key.purpose}")
            }
        }
        val original =
            originalDirectory ?: temp.resolve("pages-${System.nanoTime()}").apply { mkdirs() }.also { directory ->
                if (!online) repeat(pageCount) { index -> directory.resolve("$index.png").writeBytes(payload(index)) }
            }
        val paths = CopyOnWriteArrayList<Int>()
        val heldNetworkConsumed = AtomicBoolean(false)
        val server = if (online) {
            mockwebserver3.MockWebServer().apply {
                this.dispatcher = object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                        val index = request.url.encodedPath.substringAfterLast('/').toInt()
                        paths += index
                        diagnose("normalHttp:$index")
                        if (index == heldNetworkPage &&
                            (!holdNetworkOnce || heldNetworkConsumed.compareAndSet(false, true))
                        ) {
                            val until = System.nanoTime() + 30_000_000_000L
                            while (!networkRelease.get() && System.nanoTime() < until) Thread.sleep(2)
                        }
                        val body = payload(index)
                        networkBytes.addAndGet(body.size.toLong())
                        return mockwebserver3.MockResponse.Builder().addHeader(
                            "Content-Type",
                            if (encodedPages == null) "image/png" else "image/jpeg",
                        ).body(Buffer().write(body)).build()
                    }
                }
                start()
            }
        } else {
            null
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(temp.resolve("downloads")),
            if (server == null) {
                mockk<SourceManager>(relaxed = true)
            } else {
                FakeDesktopSourceManager(listOf(ReadyWindowSource(pageCount) { server.url("/page/$it").toString() }))
            },
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            temp.resolve("cache-${System.nanoTime()}"),
            pageImageDecoder = decoder,
            readerIoProbe = ReaderIoProbe { ioEvents += it },
            readerIoGate = readControl ?: ReaderIoGate { point ->
                if (point == ReaderIoGatePoint.NON_CURRENT_PAGE && gateOldBackground.compareAndSet(true, false)) {
                    withContext(NonCancellable) {
                        oldBackgroundEntered.complete(Unit)
                        oldBackgroundRelease.await()
                    }
                }
            },
        )
        private val context = DesktopReaderChapterContext(
            87, 42, if (online) "/chapter" else original.absolutePath, "M", "C", 1.0, 0, initialPage, false,
            localChapterPath = original.absolutePath.takeUnless { online },
        )
        var runtime = factory.createRuntime(context, scope)
        val controlledRead = readControl?.armRead(
            ownerId = runtime.pageContentOwner.controlOwnerId,
            chapterId = runtime.session.state.value.snapshot.activeChapter.id.value,
            pageIndex = 2,
            generation = runtime.session.state.value.snapshot.generation,
            maxBytes = null,
            timeoutMillis = 30_000,
        )
        private fun modelRuntime() =
            runtime.takeUnless { manualSpreadPublisher } ?: runtime.copy(presentationScope = null)
        var model = factory.createModel(modelRuntime(), false, 0, null)
        private var collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        val scene = ImageComposeScene(700, 1200, coroutineContext = uiScope.coroutineContext) {}

        @Suppress("UNCHECKED_CAST")
        fun captureReplayCostBaseline() {
            if (replayCostBaseline != null) return
            val lock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
                .apply { isAccessible = true }.get(model)
            synchronized(lock) {
                val owner = requireNotNull(owner())
                val current = model.state.value
                val samples = SharedDualPageSession::class.java.getDeclaredField("spreadSamples")
                    .apply { isAccessible = true }.get(owner) as ReaderSpreadSamplesOwner
                // Copy only existing scalar/sample authority. Calling rebind here would close
                // the real owner; acquiring an asset would touch LRU and alter the experiment.
                val maps = listOf("samples", "scores", "analyses", "sampleFailures").associateWith { name ->
                    (
                        ReaderSpreadSamplesOwner::class.java.getDeclaredField(name)
                            .apply { isAccessible = true }.get(samples) as Map<Any?, Any?>
                        ).toMap()
                }
                val originals = (
                    ReaderSpreadSamplesOwner::class.java.getDeclaredField("originalSamples")
                        .apply { isAccessible = true }.get(samples) as Set<ReaderPageId>
                    ).toSet()
                val headers = (
                    SharedDualPageSession::class.java.getDeclaredField("originalHeaders")
                        .apply { isAccessible = true }.get(owner) as Map<ReaderPageId, ReaderPageFact>
                    ).toMap()
                val requests = current.session.activeChapter.pages.mapNotNull { page ->
                    page.encodedPageRef?.let {
                        page.id to ReaderPageContentOpenRequest(
                            page.id,
                            current.session.generation,
                            it,
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        )
                    }
                }.toMap()
                val keys = runtime.pageImagePipeline.snapshot().cache.keys
                val cached = keys.mapNotNull { key ->
                    replayOriginals[key]?.takeIf { key.contentKey == requests[key.contentKey.pageId] }
                        ?.let { key.contentKey.pageId to it }
                }.toMap()
                replayCostBaseline = ReplayCostBaseline(
                    owner.identity,
                    ReaderPairingSettings(
                        if (current.readingMode == ReadingMode.RTL) ReaderDirection.RTL else ReaderDirection.LTR,
                        current.autoSpreadMatching,
                        owner.pairingState.committedForcedSinglePages.mapTo(mutableSetOf()) {
                            owner.identity.pageIds[it]
                        },
                        owner.pairingState.manualPairStarts,
                        owner.pairingState.openingMode,
                        owner.pairingState.revision ?: -1,
                    ),
                    headers,
                    maps,
                    originals,
                    requests,
                    cached,
                )
                println(
                    "READER_LOCAL_CACHE beforeTargetIndex=34 keys=${keys.map { it.pageIndex }} " +
                        "sampleRotations=${cached.mapKeys {
                            it.key.sourcePageIndex
                        }.mapValues { it.value.samples.keys }}",
                )
            }
        }

        fun printReplayCosts(target: Int) {
            val baseline = requireNotNull(replayCostBaseline)
            val observed = replayOriginals.entries.filter {
                it.key.contentKey == baseline.requests[it.key.contentKey.pageId]
            }.associate { it.key.contentKey.pageId to it.value }
            for (includeCache in listOf(false, true)) {
                println("READER_LOCAL_COST " + baseline.cost(target, includeCache, observed))
            }
            println("READER_LOCAL_COST " + baseline.cost(target, false, observed, nearFirst = true))
        }

        fun observeReplayDraws() {
            replayObserved = true
            val capturedEpoch = currentRuntimeEpoch
            mockkObject(model)
            every { model.drawDualViewportPage(any(), any()) } answers {
                val accepted = callOriginal()
                if (accepted) {
                    val drawTime = System.nanoTime()
                    val ticket = firstArg<mihon.domain.reader.ReaderDualPageViewportTicket>()
                    val page = secondArg<ReaderPageId>()
                    replayDraws += ReplayDrawReceipt(
                        runtimeEpoch = capturedEpoch,
                        locationSequence = ticket.locationSequence,
                        sourcePageIndex = page.sourcePageIndex,
                        drawTimestampNs = drawTime,
                    )
                }
                accepted
            }
        }

        /** Independent complete-content oracle, with fresh facts sampled from every fixture PNG. */
        fun completeContentTargetUnit(target: Int): mihon.domain.reader.ReaderDualPageUnit {
            val ids = model.state.value.session.activeChapter.pages.map { it.id }
            val identity = ReaderDualPageIdentity(
                1L,
                1L,
                ids.map {
                    mihon.domain.reader.ReaderDualPageContentIdentity(it, "complete-fixture-${it.sourcePageIndex}")
                },
            )
            val complete = ReaderSpreadSamplesOwner(identity)
            try {
                val facts = ids.associateWith { page ->
                    val image = ImageIO.read(payload(page.sourcePageIndex).inputStream())
                    val plan = requireNotNull(ReaderOriginalPageSampling.plan(image.width, image.height))
                    val samples = plan.samples(
                        plan.left.map { (x, y) -> image.getRGB(x, y) },
                        plan.right.map { (x, y) -> image.getRGB(x, y) },
                    )
                    val fact = ReaderPageFact.dimensions(image.width, image.height).copy(sideHint = samples.sideHint)
                    assertTrue(complete.acceptOriginal(complete.request(page), fact, samples.edges))
                    fact
                }
                return SharedDualPageRules.canonicalPlan(ids, facts, ReaderDirection.RTL, automatic = true) { a, b ->
                    (complete.edgeAnalysis(a, b, ReaderDirection.RTL) as? ReaderEdgeAnalysisEvidence.Computed)?.score
                }.single { ids[target] in it.pageIds }
            } finally {
                complete.close()
            }
        }

        fun replayDiagnostic(target: Int): String {
            val lifecycleLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
                .apply { isAccessible = true }.get(model)
            return synchronized(lifecycleLock) {
                val owner = requireNotNull(owner())
                val current = model.state.value
                val page = current.session.activeChapter.pages[target]
                val certification = when (val result = owner.certifyPairing(page.id)) {
                    is ReaderPairingCertification.Certified ->
                        "Certified(unit=${result.segment.unit.pageIds.map { it.sourcePageIndex }}," +
                            "first=${result.segment.firstIndex},steps=${result.segment.scanSteps})"
                    is ReaderPairingCertification.NeedFacts ->
                        "NeedFacts(first=${result.continuationFirstIndex},steps=${result.scanSteps}," +
                            "needs=${result.facts.map { "${it.pageId.sourcePageIndex}:${it.kind}" }})"
                    is ReaderPairingCertification.RetryableBlocked ->
                        "Blocked(first=${result.continuationFirstIndex},steps=${result.scanSteps}," +
                            "failures=${result.failures.mapKeys { it.key.sourcePageIndex }}," +
                            "needs=${result.facts.map { "${it.pageId.sourcePageIndex}:${it.kind}" }})"
                }
                val budget = owner.factBudget
                fun field(name: String): Any? = budget.javaClass.getDeclaredField(name)
                    .apply { isAccessible = true }.get(budget)
                val budgetState = synchronized(requireNotNull(field("lock"))) {
                    fun indices(name: String) = (field(name) as Set<*>).map { (it as ReaderPageId).sourcePageIndex }
                    "budgetToken=${System.identityHashCode(budget)} attempted=${indices("attempted")} " +
                        "active=${indices("active")} bytes=${field("newlyReadBytes")} closed=${field("closed")}" +
                        " remainingMs=${budget.remainingWaitMillis(System.nanoTime() / 1_000_000)}"
                }
                "sequence=${owner.location?.sequence} loadState=${page.loadState} " +
                    "allLoads=${current.session.activeChapter.pages.map { it.id.sourcePageIndex to it.loadState }} " +
                    "decodeFailures=$decodeFailures " +
                    "attempt=${page.attemptGeneration} decoded=${decoderReady.containsKey(target)} " +
                    "pairingFailures=${current.dualPairingFailures.mapKeys { it.key.sourcePageIndex }} " +
                    "certification=$certification $budgetState"
            }
        }

        fun observePerformance(target: Int) {
            performanceObserved = true
            val original = runtime.presentationImageOwner
            mockkObject(original)
            every { original.acceptReadyAsset(any(), any(), any()) } answers {
                val accepted = callOriginal()
                if (accepted) {
                    val holder = firstArg<DesktopReaderPresentationImageHolder>()
                    val source = holder.identity.pageId.sourcePageIndex
                    acceptedReady.putIfAbsent(source, System.nanoTime())
                    readyIdentities[source] = holder.identity.toString()
                    readyFrames.putIfAbsent(source, frameNumber.get())
                    diagnose("readyAccepted:$source")
                }
                accepted
            }
            mockkObject(model)
            if (diagnostic) {
                every { model.prepareDecodedDualViewportPage(any(), any(), any(), any()) } answers {
                    val ticket = firstArg<mihon.domain.reader.ReaderDualPageViewportTicket>()
                    val page = secondArg<mihon.domain.reader.session.ReaderPageSession>()
                    val revision = owner()?.layoutRevision
                    val accepted = callOriginal()
                    if (page.id.sourcePageIndex == target) {
                        diagnose(
                            "prepare:$target:$accepted:revision$revision->${owner()?.layoutRevision}:" +
                                "frame${ticket.frameId}:unit${owner()?.frame(ticket)?.unit}",
                        )
                    }
                    accepted
                }
            }
            every { model.drawDualViewportPage(any(), any()) } answers {
                val accepted = callOriginal()
                if (accepted) {
                    val index = secondArg<mihon.domain.reader.session.ReaderPageId>().sourcePageIndex
                    val now = System.nanoTime()
                    actualPaint.putIfAbsent(index, now)
                    diagnose("actualPaint:$index")
                    paintedFrames.putIfAbsent(index, frameNumber.get())
                    val ticket = firstArg<mihon.domain.reader.ReaderDualPageViewportTicket>()
                    paintFrames.putIfAbsent(index, ticket.frameId)
                    owner()?.frame(ticket)?.let { frame ->
                        paintSlots[index] = frame.unit.slots.map { it.pageId.sourcePageIndex }
                    }
                    if (inputStarted > 0 && now >= inputStarted && index == model.state.value.currentPage) {
                        if (firstInputPaint == 0L) firstInputPaint = now
                    }
                }
                accepted
            }
        }

        fun markInput() {
            inputStarted = System.nanoTime()
            firstInputPaint = 0
        }

        fun input(forward: Boolean) {
            val key = if (forward) {
                androidx.compose.ui.input.key.Key.DirectionLeft
            } else {
                androidx.compose.ui.input.key.Key.DirectionRight
            }
            val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods.single {
                it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
            }
            val event = factory.invoke(null, key.keyCode, type, 0, false, false, false, false, null)
            assertTrue(
                handleReaderKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(event),
                    model.state.value,
                    model,
                    mockk(),
                    null,
                    {},
                    {},
                ),
            )
            dispatcher.scheduler.runCurrent()
        }

        suspend fun performancePaint(source: Int) {
            val deadline = System.nanoTime() + 15_000_000_000L
            var pixels = false
            do {
                dispatcher.scheduler.runCurrent()
                val start = System.nanoTime()
                frameNumber.incrementAndGet()
                diagnose("renderStart:layout${owner()?.layoutRevision}")
                if (previousRender > 0L) frameIntervals += start - previousRender
                previousRender = start
                val bitmap = scene.render(start).toComposeImageBitmap().asSkiaBitmap()
                frameCosts += System.nanoTime() - start
                try {
                    val colors = (1..5).flatMap { y ->
                        (1..5).map { x ->
                            bitmap.getColor(bitmap.width * x / 6, bitmap.height * y / 6)
                        }
                    }
                    pixels = (
                        listOf(0, 8, 16).maxOf { shift ->
                            colors.maxOf { (it ushr shift) and 255 } - colors.minOf { (it ushr shift) and 255 }
                        } > 70
                        ) && actualPaint.containsKey(source)
                } finally {
                    bitmap.close()
                }
                val memory = runtime.pageImagePipeline.memoryAuthority.snapshot()
                observedResident = maxOf(observedResident, memory.residentBytes)
                observedPinned = maxOf(observedPinned, memory.pinnedBytes)
                observedOverBudget = maxOf(observedOverBudget, memory.overBudgetBytes)
                val java = Runtime.getRuntime()
                observedHeap = maxOf(observedHeap, java.totalMemory() - java.freeMemory())
                dispatcher.scheduler.advanceTimeBy(16)
                if (!pixels) delay(1)
            } while (!pixels && System.nanoTime() < deadline)
            assertTrue(pixels, "Original source $source must be actually drawn and nonuniform in this real scene")
            assertTrue(acceptedReady.containsKey(source), "The original holder must accept its drawable asset")
        }

        fun printPerformance(repeat: Int, condition: String, sceneName: String, target: Int) {
            val painted = requireNotNull(actualPaint[target])
            val ready = requireNotNull(acceptedReady[target])
            val frame = requireNotNull(paintFrames[target])
            val second = paintSlots[target].orEmpty().firstOrNull {
                it != target && paintFrames[it] == frame && actualPaint.containsKey(it)
            }
            if (condition == "foreground-pressure") assertTrue(observedOverBudget > 0)
            if (condition == "suspended") assertEquals(1, runtime.pageImagePipeline.optionalWork.value)
            val fields = linkedMapOf(
                "platform" to "desktop", "repeat" to repeat, "condition" to condition, "scene" to sceneName,
                "source" to target, "identity" to readyIdentities[target], "frame" to frame,
                "decoderNs" to decoderReady[target], "readyNs" to ready, "paintNs" to painted,
                "readyToPaintNs" to (painted - ready), "readyRender" to readyFrames[target],
                "paintRender" to paintedFrames[target], "secondSource" to second,
                "secondReadyNs" to second?.let { acceptedReady[it] },
                "secondPaintNs" to second?.let { actualPaint[it] },
                "inputNs" to inputStarted, "inputPaintNs" to firstInputPaint,
                "renderCostNs" to frameCosts.toList(), "renderIntervalNs" to frameIntervals.toList(),
                "requests" to paths.size, "requestIndices" to paths.toList(), "payloadBytes" to networkBytes.get(),
                "inputTarget" to model.state.value.currentPage, "diagnosticEvents" to performanceEvents.toList(),
                "residentPeak" to observedResident, "pinnedPeak" to observedPinned, "javaHeapPeak" to observedHeap,
                "optionalDecodePeak" to peakRegionReads.get(), "overBudgetObservedPeak" to observedOverBudget,
                "optionalWorkAtMeasurement" to runtime.pageImagePipeline.optionalWork.value,
                "deps" to owner()?.spreadSampleDependencies()?.size,
                "pageCount" to model.state.value.session.activeChapter.pages.size,
            )
            println("READER_PERFORMANCE " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
            assertTrue(painted >= ready)
            if (inputStarted > 0) assertTrue(firstInputPaint >= inputStarted)
        }

        fun replaceRuntime() {
            scene.setContent {}
            collector.cancel()
            if (replayObserved) {
                unmockkObject(model)
                replayObserved = false
            }
            model.onDispose()
            runtime.close()
            currentRuntimeEpoch = runtimeEpochCounter.incrementAndGet()
            runtime = factory.createRuntime(context, scope)
            model = factory.createModel(runtime, false, 0, null)
            collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        }

        fun owner(): SharedDualPageSession? = ReaderScreenModel::class.java.getDeclaredField("dualSession").apply {
            isAccessible = true
        }.get(model) as SharedDualPageSession?

        @Suppress("UNCHECKED_CAST")
        fun originalRegionLease(content: ReaderPageContentOpenRequest): ReaderPageContentLease<ByteArray> {
            val pipeline = runtime.pageImagePipeline
            val lock = pipeline.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(pipeline)
            val shared = synchronized(lock) {
                val sessions = pipeline.javaClass.getDeclaredField("contentSessions").apply {
                    isAccessible = true
                }.get(pipeline) as Map<*, *>
                val entry = requireNotNull(sessions[content])
                entry.javaClass.getDeclaredField("content").apply { isAccessible = true }.get(entry)
            }
            val completed = shared.javaClass.getDeclaredField("content").apply { isAccessible = true }.get(shared)
                as Deferred<ReaderPageContentLease<ByteArray>?>
            return requireNotNull(completed.getCompleted())
        }

        suspend fun pauseModelCollection() {
            collector.cancel()
            collector.join()
        }

        fun mountReader(fullViewport: Boolean = false) {
            val focusRequester = FocusRequester()
            val navigator = mockk<Navigator>(relaxed = true)
            val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
            val dependencies =
                mockk<DesktopUiDependencies> { every { this@mockk.appPreferences } returns appPreferences }
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalReaderNativeWindow provides null,
                ) {
                    MaterialTheme {
                        val state by model.state.collectAsState()
                        if (fullViewport) {
                            ReaderViewport(
                                state, model, navigator, focusRequester, uiScope,
                                "M", "C", runtime.presentationImageOwner,
                                readerNav = null, onPrevChapter = {}, onNextChapter = {},
                            )
                        } else {
                            ReaderContent(
                                state, model, uiScope, "M", "C", runtime.presentationImageOwner,
                                readerNav = null, onPrevChapter = {}, onNextChapter = {},
                            )
                        }
                    }
                }
            }
        }

        suspend fun pump(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            do {
                dispatcher.scheduler.runCurrent()
                scene.render(dispatcher.scheduler.currentTime * 1_000_000L).close()
                dispatcher.scheduler.advanceTimeBy(16)
                delay(5)
            } while (!condition() && System.nanoTime() < deadline)
        }

        suspend fun measureOnce(
            target: Int,
            repeat: Int,
            reopen: Boolean,
            timeoutMillis: Long = 20_000,
        ): LatencyRecord {
            val epoch = currentRuntimeEpoch
            var peakActive = 0
            var certifiedMs: Long? = null
            var certifiedFirstIndex: Int? = null
            var isCertified = false
            var matchingReceipt: ReplayDrawReceipt? = null
            val initialIoEvents = ioEvents.size

            val started = System.nanoTime()
            model.goToPage(target)
            val sequence = requireNotNull(owner()?.location).sequence

            pump(timeoutMillis = timeoutMillis) {
                val owner = owner()
                val budget = owner?.factBudget
                if (budget != null) {
                    val lock = budget.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(budget)
                    val activeCount = synchronized(lock) {
                        val activeSet = budget.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(budget) as Set<*>
                        activeSet.size
                    }
                    if (activeCount > peakActive) peakActive = activeCount
                }

                if (certifiedMs == null) {
                    val pages = model.state.value.session.activeChapter.pages
                    val pageId = pages.getOrNull(target)?.id
                    if (pageId != null && owner != null) {
                        val cert = owner.certifyPairing(pageId)
                        if (cert is ReaderPairingCertification.Certified && pageId in cert.segment.unit.pageIds) {
                            certifiedMs = (System.nanoTime() - started) / 1_000_000
                            certifiedFirstIndex = cert.segment.firstIndex
                            isCertified = true
                        }
                    }
                }

                if (matchingReceipt == null) {
                    matchingReceipt = selectMatchingReceipt(epoch, sequence, target)
                }

                (isCertified && matchingReceipt != null) || (
                    model.state.value.dualPairingFailures.keys.any { it.sourcePageIndex == target } &&
                        runtime.pageImagePipeline.foregroundWork.value == 0 &&
                        runtime.pageImagePipeline.optionalWork.value == 0
                    )
            }

            if (!isCertified) {
                val pages = model.state.value.session.activeChapter.pages
                val pageId = pages.getOrNull(target)?.id
                val owner = owner()
                if (pageId != null && owner != null) {
                    val cert = owner.certifyPairing(pageId)
                    if (cert is ReaderPairingCertification.Certified && pageId in cert.segment.unit.pageIds) {
                        if (certifiedMs == null) {
                            certifiedMs = (System.nanoTime() - started) / 1_000_000
                            certifiedFirstIndex = cert.segment.firstIndex
                        }
                        isCertified = true
                    }
                }
            }

            if (matchingReceipt == null) {
                matchingReceipt = selectMatchingReceipt(epoch, sequence, target)
            }

            val successfullyDrawn = isCertified && matchingReceipt != null
            val drawnMs = if (successfullyDrawn) {
                (matchingReceipt!!.drawTimestampNs - started) / 1_000_000
            } else {
                null
            }

            val failure = model.state.value.dualPairingFailures.entries.firstOrNull {
                it.key.sourcePageIndex == target
            }?.value?.name ?: if (!successfullyDrawn) "TIMEOUT" else null

            val owner = owner()
            val budget = owner?.factBudget
            val factsAttempted = if (budget != null) {
                val lock = budget.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(budget)
                synchronized(lock) {
                    val attemptedSet = budget.javaClass.getDeclaredField("attempted")
                        .apply { isAccessible = true }.get(budget) as Set<*>
                    attemptedSet.size
                }
            } else {
                0
            }

            val decodes = ioEvents.drop(initialIoEvents).count { it.type == ReaderIoEventType.DECODE }
            val opens = ioEvents.drop(initialIoEvents).count { it.type == ReaderIoEventType.OPEN_PAGE }
            val rounds = model.certificationRounds

            return LatencyRecord(
                target = target,
                repeat = repeat,
                drawn = successfullyDrawn,
                intentToTargetDrawnMs = drawnMs,
                intentToCertifiedMs = certifiedMs,
                certifiedFirstIndex = certifiedFirstIndex,
                factsAttempted = factsAttempted,
                certificationRounds = rounds,
                decodes = decodes,
                opens = opens,
                peakActiveFacts = peakActive,
                failureReason = failure,
                reopen = reopen,
            )
        }

        override fun close() {
            networkRelease.set(true)
            if (performanceObserved || replayObserved) {
                unmockkObject(model)
            }
            if (performanceObserved) {
                unmockkObject(runtime.presentationImageOwner)
            }
            fullGate.get()?.complete(Unit)
            regionGate.get()?.complete(Unit)
            speculativeGate.complete(Unit)
            pageGate.values.forEach { it.complete(Unit) }
            oldBackgroundRelease.complete(Unit)
            scene.close()
            collector.cancel()
            model.onDispose()
            runtime.close()
            scope.cancel()
            uiScope.cancel()
            server?.close()
            legacy.removeNode()
        }
    }

    private data class LatencyRecord(
        val target: Int,
        val repeat: Int,
        val drawn: Boolean,
        val intentToTargetDrawnMs: Long?,
        val intentToCertifiedMs: Long?,
        val certifiedFirstIndex: Int?,
        val factsAttempted: Int,
        val certificationRounds: Int,
        val decodes: Int,
        val opens: Int,
        val peakActiveFacts: Int,
        val failureReason: String?,
        val reopen: Boolean = false,
    ) {
        fun toJson(): String {
            fun quote(s: String?) = if (s == null) "null" else "\"$s\""
            return "{\"target\": $target, \"repeat\": $repeat, \"drawn\": $drawn, " +
                "\"intentToTargetDrawnMs\": $intentToTargetDrawnMs, " +
                "\"intentToCertifiedMs\": $intentToCertifiedMs, " +
                "\"certifiedFirstIndex\": $certifiedFirstIndex, \"factsAttempted\": $factsAttempted, " +
                "\"certificationRounds\": $certificationRounds, \"decodes\": $decodes, \"opens\": $opens, " +
                "\"peakActiveFacts\": $peakActiveFacts, \"failureReason\": ${quote(failureReason)}, " +
                "\"reopen\": $reopen}"
        }
    }

    data class ReplayDrawReceipt(
        val runtimeEpoch: Long,
        val locationSequence: Long,
        val sourcePageIndex: Int,
        val drawTimestampNs: Long,
    )

    private data class ReplayOriginal(
        val rawWidth: Int,
        val rawHeight: Int,
        val orientation: ReaderImageOrientation,
        val samples: Map<PageRotation, ReaderOriginalPageSamples>,
        val algorithmVersion: Int,
    )

    /** A scalar-only copy of the real state immediately before the first source-35 jump. */
    private data class ReplayCostBaseline(
        val identity: ReaderDualPageIdentity,
        val settings: ReaderPairingSettings,
        val headers: Map<ReaderPageId, ReaderPageFact>,
        val authorityMaps: Map<String, Map<Any?, Any?>>,
        val originalSamples: Set<ReaderPageId>,
        val requests: Map<ReaderPageId, ReaderPageContentOpenRequest>,
        val cached: Map<ReaderPageId, ReplayOriginal>,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun cost(
            target: Int,
            includeCache: Boolean,
            observed: Map<ReaderPageId, ReplayOriginal>,
            nearFirst: Boolean = false,
        ): String {
            val owner = ReaderSpreadSamplesOwner(identity)
            val knownHeaders = headers.toMutableMap()
            val initial = authorityMaps.getValue("analyses").filterValues {
                it is ReaderPageAnalysisEvidence.Computed
            }.keys.map { it as ReaderPageId }.toSet()
            val supplied = initial.toMutableSet()
            val cachedInputs = linkedSetOf<ReaderPageId>()
            val additional = linkedSetOf<ReaderPageId>()
            val trace = !includeCache && !nearFirst
            fun indices(pages: Collection<ReaderPageId>) = pages.map { it.sourcePageIndex }
            fun report(status: String) =
                "mode=${if (nearFirst) {
                    "nearest-demand-first"
                } else if (includeCache) {
                    "initial-cache"
                } else {
                    "existing-authority"
                }} targetIndex=$target " +
                    "initial=${indices(initial)} cachedInputs=${indices(cachedInputs)} " +
                    "additional=${indices(additional)} additionalCount=${additional.size} " +
                    "exceeds16=${additional.size > ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES} $status"
            fun publish(page: ReaderPageId, original: ReplayOriginal): Boolean {
                val content = identity.content.first { it.pageId == page }
                if (original.algorithmVersion != ReaderOriginalPageSampling.ALGORITHM_VERSION ||
                    original.orientation != content.sourceOrientation
                ) {
                    return false
                }
                val samples = original.samples[content.rotation] ?: return false
                val size = original.orientation.orientedSize(original.rawWidth, original.rawHeight)
                val fact = ReaderPageFact.dimensions(
                    size.width,
                    size.height,
                    content.rotation,
                    original.orientation,
                ).copy(sideHint = samples.sideHint)
                if (!owner.acceptOriginal(owner.request(page), fact, samples.edges)) return false
                knownHeaders[page] = fact.copy(sideHint = ReaderPageSideHint.UNKNOWN)
                return true
            }
            try {
                // Restore scalar authority exactly, rather than inventing initial edges or
                // copying the certification algorithm. The real owner is never mutated.
                authorityMaps.forEach { (name, values) ->
                    (
                        ReaderSpreadSamplesOwner::class.java.getDeclaredField(name)
                            .apply { isAccessible = true }.get(owner) as MutableMap<Any?, Any?>
                        ).putAll(values)
                }
                (
                    ReaderSpreadSamplesOwner::class.java.getDeclaredField("originalSamples")
                        .apply { isAccessible = true }.get(owner) as MutableSet<ReaderPageId>
                    ).addAll(originalSamples)
                if (includeCache) {
                    cached.forEach { (page, original) ->
                        if (publish(page, original)) {
                            cachedInputs += page
                            supplied += page
                        }
                    }
                }
                // Measurement only: consume already observed immutable decoder results beyond
                // sixteen when needed. No real budget, read, decode or navigation is performed.
                repeat(identity.pageIds.size * 4) { query ->
                    when (
                        val result = ReaderPairingCertifier.certify(
                            identity,
                            knownHeaders,
                            owner,
                            settings,
                            identity.pageIds[target],
                        )
                    ) {
                        is ReaderPairingCertification.Certified -> {
                            val facts = knownHeaders.mapValues { (page, header) ->
                                (owner.pageAnalysis(page) as? ReaderPageAnalysisEvidence.Computed)?.fact ?: header
                            }
                            val oracle = SharedDualPageRules.canonicalPlan(
                                identity.pageIds,
                                facts,
                                settings.direction,
                                settings.forcedSinglePages,
                                settings.manualPairStarts,
                                settings.openingMode,
                                settings.automatic,
                            ) { first, second ->
                                (
                                    owner.edgeAnalysis(
                                        first,
                                        second,
                                        settings.direction,
                                    ) as? ReaderEdgeAnalysisEvidence.Computed
                                    )?.score
                            }.single { identity.pageIds[target] in it.pageIds }
                            assertEquals(
                                oracle,
                                result.segment.unit,
                                "Demand ordering must retain the canonical target unit",
                            )
                            assertTrue(result.segment.isCurrent(identity, settings, owner, knownHeaders))
                            val changed = identity.copy(
                                content = identity.content.map { content ->
                                    if (content.pageId.sourcePageIndex == target) {
                                        content.copy(contentVersion = content.contentVersion + "-changed")
                                    } else {
                                        content
                                    }
                                },
                            )
                            assertFalse(result.segment.isCurrent(changed, settings, owner, knownHeaders))
                            if (trace) {
                                val ordered = additional.sortedBy { it.sourcePageIndex }
                                println(
                                    "READER_LOCAL_FACTS readerPagesAndHints=" +
                                        ordered.map { "${it.sourcePageIndex + 1}:${facts.getValue(it).sideHint}" } +
                                        " adjacentEdges=" + ordered.zipWithNext().map { (first, second) ->
                                            "${first.sourcePageIndex + 1}-${second.sourcePageIndex + 1}:" +
                                                (
                                                    owner.edgeAnalysis(
                                                        first,
                                                        second,
                                                        settings.direction,
                                                    ) as? ReaderEdgeAnalysisEvidence.Computed
                                                    )?.score
                                        } +
                                        " firstIndex=${result.segment.firstIndex} oracleIndices=${indices(
                                            oracle.pageIds,
                                        )} " +
                                        "certificateCurrent=true changedContentRejected=true",
                                )
                            }
                            return report(
                                "status=Certified unit=${indices(result.segment.unit.pageIds)} " +
                                    "first=${result.segment.firstIndex} dependencies=${indices(
                                        result.segment.dependentPageIds,
                                    )} " +
                                    "canonicalOracle=PASS certificateIdentity=PASS",
                            )
                        }
                        is ReaderPairingCertification.RetryableBlocked -> return report(
                            "status=Blocked failures=${result.failures.mapKeys { it.key.sourcePageIndex }}",
                        )
                        is ReaderPairingCertification.NeedFacts -> {
                            val demanded = result.facts.flatMap { listOfNotNull(it.pageId, it.secondPageId) }
                                .distinct().filter { it !in supplied }
                            val missing = if (nearFirst) {
                                // Uses only already requested page indices, never unseen hints or edge scores.
                                demanded.sortedWith(
                                    compareBy<ReaderPageId> { kotlin.math.abs(it.sourcePageIndex - target) }
                                        .thenByDescending { it.sourcePageIndex },
                                )
                            } else {
                                demanded
                            }
                                .take(ReaderDualPageFactLimits.MAX_CONCURRENT_REQUESTS)
                            if (trace) {
                                println(
                                    "READER_LOCAL_NEED query=$query firstIndex=${result.continuationFirstIndex} " +
                                        "steps=${result.scanSteps} takeIndices=${indices(missing)} " +
                                        "needs=${result.facts.map {
                                            "${it.pageId.sourcePageIndex}:${it.kind}:${it.secondPageId?.sourcePageIndex}"
                                        }}",
                                )
                            }
                            if (missing.isEmpty()) {
                                return report(
                                    "status=InsufficientEvidence reason=no-new-observed-input",
                                )
                            }
                            for (page in missing) {
                                val original = observed[page]
                                    ?: return report("status=InsufficientEvidence missingIndex=${page.sourcePageIndex}")
                                if (!publish(page, original)) {
                                    return report(
                                        "status=InsufficientEvidence invalidIdentityIndex=${page.sourcePageIndex}",
                                    )
                                }
                                supplied += page
                                additional += page
                            }
                        }
                    }
                }
                return report("status=InsufficientEvidence reason=query-bound")
            } finally {
                owner.close()
            }
        }
    }

    private class ReadyWindowSource(private val count: Int, private val imageUrl: (Int) -> String) : CatalogueSource {
        override val id = 42L
        override val name = "normal-window"
        override val lang = "en"
        override val supportsLatest = false

        @Suppress("unused")
        fun getClient() = OkHttpClient()

        @Suppress("unused")
        suspend fun getImageUrl(page: Page): String = imageUrl(page.index)

        override suspend fun getPageList(chapter: SChapter) = List(count) {
            Page(it, url = "/page/$it", imageUrl = imageUrl(it))
        }
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
    }

    private fun image(index: Int): ByteArray {
        val image = BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 60) {
            for (x in 0 until 40) {
                val channel = if ((x == 2 && index == 2) || (x == 37 && index == 3)) 80 + y * 2 else index * 35
                image.setRGB(x, y, (255 shl 24) or (channel shl 16) or (80 shl 8) or 120)
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
