package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.test.http.testHttpServer
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DualPageCurrentPageWiringTest {
    @Test
    fun `Test Mode reads the mounted owner and HTTP actions drive the actual model`() = runTest {
        val testReader = mihon.desktop.test.state.readerState
        testReader.open(1, 0, 20, false, "shadow", "shadow", true, true)
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) {
            testHttpServer()
        }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val client = java.net.http.HttpClient.newHttpClient()
            fun request(path: String, body: String? = null): kotlinx.serialization.json.JsonObject {
                val builder = java.net.http.HttpRequest.newBuilder(
                    java.net.URI("http://127.0.0.1:$port/test/reader/$path"),
                )
                if (body ==
                    null
                ) {
                    builder.GET()
                } else {
                    builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                }
                return kotlinx.serialization.json.Json.parseToJsonElement(
                    client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body(),
                ) as kotlinx.serialization.json.JsonObject
            }
            Fixture(this, ReadingMode.LTR, initialPage = 1).use { fixture ->
                fixture.confirmPortraitFacts()
                fixture.dual(true)
                fixture.frames()
                fun read() = request("state")
                assertEquals(
                    "1",
                    read().getValue("currentPage").toString(),
                    "Must query the mounted model, not open() shadow",
                )
                assertEquals("9", read().getValue("totalPages").toString())
                assertEquals("[[0],[1,2],[3,4],[5,6],[7,8]]", read().getValue("sharedUnits").toString())
                val mounted = read().getValue("mountedReader") as kotlinx.serialization.json.JsonObject
                assertEquals("\"LTR\"", mounted.getValue("readingMode").toString())
                assertEquals("true", mounted.getValue("dualPageMode").toString())
                assertEquals("false", mounted.getValue("autoSpreadMatching").toString())
                assertEquals("[1,2]", mounted.getValue("currentDisplayPageIndices").toString())
                assertEquals(
                    setOf("1", "2"),
                    (mounted.getValue("visiblePageIndices") as kotlinx.serialization.json.JsonArray).map {
                        it.toString()
                    }.toSet(),
                )
                val native = read().getValue("nativeInput") as kotlinx.serialization.json.JsonObject
                assertEquals("true", native.getValue("geometryAvailable").toString())
                assertEquals(
                    "false",
                    native.getValue("windowPresent").toString(),
                    "Offscreen Compose is not an AWT native window",
                )
                assertEquals("false", native.getValue("available").toString())
                assertEquals("true", request("next_page", "{}").getValue("success").toString())
                fixture.frames()
                assertEquals(3, fixture.state.currentPage, "Next uses the actual shared unit, not ordinal + 1")
                assertEquals("true", request("go_to_page", "{\"page\":5}").getValue("success").toString())
                fixture.frames()
                assertEquals(5, fixture.state.currentPage)
                fixture.goTo(7)
                fixture.frames()
                assertEquals(
                    "7",
                    read().getValue("currentPage").toString(),
                    "Native/external model actions remain observable",
                )
                assertEquals(
                    "false",
                    request("next_chapter", "{}").getValue("success").toString(),
                    "Shadow hasNext cannot invent an actual adjacent chapter",
                )
                fixture.webtoon()
                fixture.frames()
                assertTrue(
                    read().getValue("mountedReader").toString() != "null",
                    "The same reader viewport remains mounted",
                )
                val unmountedPager = read().getValue("nativeInput") as kotlinx.serialization.json.JsonObject
                assertEquals(
                    "false",
                    unmountedPager.getValue("geometryAvailable").toString(),
                    "Unmounted pager must release its old geometry",
                )
                assertEquals("null", unmountedPager.getValue("viewportPixels").toString())
            }
            assertEquals(
                "null",
                request("state").getValue("mountedReader").toString(),
                "Unmount must release the actual model",
            )
            assertEquals("false", request("next_page", "{}").getValue("success").toString())
        } finally {
            server.stop(0, 0)
            testReader.reset()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "RTL,tap,false", "LTR,tap,false", "RTL,key,false", "LTR,key,false", "RTL,drag,false", "LTR,drag,false",
        "RTL,tap,true", "RTL,key,true", "RTL,drag,true",
    )
    fun `native input target survives facts or matching during the unsettled animation`(
        mode: ReadingMode,
        input: String,
        matching: Boolean,
    ) = runTest {
        Fixture(this, mode, initialPage = 1).use { fixture ->
            fixture.confirmPortraitFacts()
            fixture.dual(true)
            fixture.frames()
            when (input) {
                "tap" -> fixture.nextTap()
                "key" -> fixture.nextKey()
                "drag" -> fixture.nextDrag()
                else -> error(input)
            }
            fixture.frames(2)
            assertTrue(
                fixture.viewportDiagnostic().contains("idle=false"),
                "The metadata must arrive during the actual native animation, not after settlement",
            )
            if (matching) fixture.confirmMatching() else fixture.wideFact(1)
            fixture.frames(120)
            assertEquals(3, fixture.state.currentPage, fixture.viewportDiagnostic())
            assertTrue(fixture.reports.all { it == 1 || it == 3 }, "Reflow cannot manufacture another logical turn")
            assertEquals(3, fixture.reports.last())
        }
    }

    @Test
    fun `late metadata after an actual turn retains its logical target and consumed reading cause`() = runTest {
        Fixture(this, ReadingMode.RTL, initialPage = 1).use { fixture ->
            fixture.confirmPortraitFacts()
            fixture.frames()
            fixture.dual(true)
            fixture.frames()
            fixture.nextTap()
            fixture.frames(90)
            assertEquals(3, fixture.state.currentPage)
            val sequence = fixture.locationSequence()
            val reports = fixture.reports.toList()
            fixture.wideFact(1)
            assertTrue(
                fixture.state.dualPageUnits.any { it.pageIds.map { page -> page.sourcePageIndex } == listOf(2, 3) },
                "The changed prefix must reflow the actual target into its new shared pair",
            )
            fixture.frames(120)
            assertEquals(3, fixture.state.currentPage, fixture.viewportDiagnostic())
            assertEquals(sequence, fixture.locationSequence())
            assertEquals(reports, fixture.reports)
            fixture.dual(false)
            fixture.frames()
            assertEquals(3, fixture.state.currentPage)
            assertEquals(sequence, fixture.locationSequence())
            assertEquals(reports, fixture.reports)
        }
    }

    @ParameterizedTest
    @EnumSource(value = ReadingMode::class, names = ["RTL", "LTR"])
    fun `mounted rapid taps and latest external goto retain their logical destinations`(mode: ReadingMode) = runTest {
        Fixture(this, mode, initialPage = 1).use { fixture ->
            fixture.confirmPortraitFacts()
            fixture.dual(true)
            fixture.frames()
            fixture.nextTap()
            fixture.frames(2)
            fixture.nextTap()
            fixture.wideFact(1)
            fixture.frames(120)
            assertEquals(5, fixture.state.currentPage, fixture.viewportDiagnostic())
            fixture.goTo(7)
            fixture.frames(2)
            fixture.goTo(3)
            fixture.wideFact(5)
            fixture.frames(120)
            assertEquals(3, fixture.state.currentPage, fixture.viewportDiagnostic())
            assertEquals(3, fixture.reports.last())
        }
    }

    @Test
    fun `mounted Compose consumes shared confirmed pairs and retains explicit second logical page`() = runTest {
        Fixture(this, ReadingMode.RTL, initialPage = 2).use { fixture ->
            fixture.confirmPortraitFacts()
            fixture.frames()
            fixture.dual(true)
            fixture.frames()
            assertTrue(
                fixture.state.dualPageUnits.any {
                    it.pageIds.map { page -> page.sourcePageIndex } == listOf(1, 2)
                },
            )
            assertEquals(
                listOf(2, 1),
                fixture.slotIndices(),
                "Native Compose must consume the shared pair, not rebuild UNKNOWN singles",
            )
            assertEquals(2, fixture.state.currentPage)
            fixture.dual(false)
            fixture.frames()
            assertEquals(2, fixture.state.currentPage)
        }
    }

    @Test
    fun `mounted single defers actual model reading until its decoder has produced pixels`() = runTest {
        Fixture(this, ReadingMode.RTL, initialPage = 1, holdDecode = true).use { fixture ->
            fixture.frames(12)
            assertEquals(emptyList<Int>(), fixture.reports, "Ready encoded data is not a rendered single viewport")
            fixture.releaseDecode()
            fixture.frames()
            assertEquals(listOf(1), fixture.reports)
            fixture.frames()
            assertEquals(listOf(1), fixture.reports)
        }
    }

    @ParameterizedTest
    @EnumSource(value = ReadingMode::class, names = ["AUTO", "RTL", "LTR"])
    fun `explicit initial second logical page survives dual and single mode changes`(
        mode: ReadingMode,
    ) = runTest {
        Fixture(this, mode, initialPage = 2).use { fixture ->
            fixture.confirmPortraitFacts()
            fixture.frames()
            assertEquals(2, fixture.state.currentPage)
            val reports = fixture.reports.toList()
            fixture.dual(true)
            fixture.frames()
            assertEquals(2, fixture.state.currentPage)
            assertEquals(if (mode == ReadingMode.LTR) listOf(1, 2) else listOf(2, 1), fixture.slotIndices())
            assertEquals(reports, fixture.reports)
            fixture.dual(false)
            fixture.frames()
            assertEquals(2, fixture.state.currentPage)
            assertEquals(reports, fixture.reports)
            assertEquals(0, fixture.chapterRequests)
        }
    }

    @ParameterizedTest
    @CsvSource("AUTO,true", "RTL,true", "LTR,true", "RTL,false", "LTR,false")
    fun `mounted adjust spread button alternates paired units without runaway paging`(
        mode: ReadingMode,
        animated: Boolean,
    ) = runTest {
        Fixture(this, mode, initialPage = 1, animated = animated).use { fixture ->
            fixture.confirmPortraitFacts()
            fixture.frames()
            fixture.dual(true)
            fixture.frames()
            val reports = fixture.reports.toList()
            repeat(6) { index ->
                fixture.adjust()
                fixture.frames(90)
                val firstPage = if (index % 2 == 0) 2 else 1
                assertEquals(firstPage, fixture.state.currentPage)
                assertEquals(
                    setOf(firstPage, firstPage + 1),
                    fixture.state.visiblePageIds.map {
                        it.sourcePageIndex
                    }.toSet(),
                )
                assertEquals(reports, fixture.reports)
                assertEquals(0, fixture.chapterRequests)
            }
            fixture.adjust()
            fixture.adjust()
            fixture.frames(120)
            assertEquals(1, fixture.state.currentPage)
            assertEquals(setOf(1, 2), fixture.state.visiblePageIds.map { it.sourcePageIndex }.toSet())
            assertEquals(reports, fixture.reports)
            repeat(4) {
                fixture.adjust()
                fixture.frames(2)
                fixture.adjust()
                fixture.frames(90)
                assertEquals(1, fixture.state.currentPage)
                assertEquals(setOf(1, 2), fixture.state.visiblePageIds.map { it.sourcePageIndex }.toSet())
                assertEquals(reports, fixture.reports)
            }
            fixture.nextTap()
            fixture.frames(90)
            assertEquals(3, fixture.state.currentPage)
            assertEquals(reports + 3, fixture.reports, fixture.viewportDiagnostic())
            assertEquals(0, fixture.chapterRequests)
        }
    }

    @ParameterizedTest
    @EnumSource(value = ReadingMode::class, names = ["AUTO", "RTL", "LTR"])
    fun `portrait cover stays uniquely current at its directional edge and adjust has no effect`(
        mode: ReadingMode,
    ) = runTest {
        Fixture(this, mode, initialPage = 0).use { fixture ->
            fixture.confirmPortraitFacts()
            fixture.frames()
            fixture.dual(true)
            fixture.frames()
            val expectedSlots = if (mode == ReadingMode.LTR) listOf(null, 0) else listOf(0, null)
            assertEquals(expectedSlots, fixture.slotIndices())
            assertEquals(0, fixture.state.currentPage)
            fixture.adjust()
            fixture.frames()
            assertEquals(expectedSlots, fixture.slotIndices())
            fixture.dual(false)
            fixture.frames()
            assertEquals(0, fixture.state.currentPage)
            assertEquals(0, fixture.chapterRequests)
        }
    }

    private class Fixture(
        private val scope: TestScope,
        private val mode: ReadingMode,
        initialPage: Int,
        animated: Boolean = true,
        holdDecode: Boolean = false,
    ) : AutoCloseable {
        private val root = Preferences.userRoot().node("/mihon/dual-current-test/${System.nanoTime()}")
        private val prefs = ReaderPreferences(DesktopPreferenceStore(root.node("reader")), root.node("legacy")).apply {
            readingMode = mode
        }
        val reports = mutableListOf<Int>()
        var chapterRequests = 0
        private val model = ReaderScreenModel(
            prefs = prefs,
            initialSessionState = desktopReaderSessionState(pageCount = 9, initialPage = initialPage),
            onViewportSettled = { _, active -> reports += active.sourcePageIndex },
        )
        val state get() = model.state.value
        private val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        private val content = DesktopReaderPageContentOwner(scope, { byteArrayOf(1) }, reporter)
        private val decodeReady = CompletableDeferred<Unit>().apply { if (!holdDecode) complete(Unit) }
        private val pipeline = DesktopReaderPageImagePipeline(
            scope,
            content,
            reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeReady.await()
                val bitmap = Bitmap().apply { allocN32Pixels(10, 14) }
                DesktopReaderImageAsset(bitmap.asComposeImageBitmap(), 10, 14, 560L, false, disposer = bitmap::close)
            },
        )
        private val owner = DesktopReaderPresentationImageOwner(scope, pipeline, ReaderPageIoObserver(reporter))
        private val scene = ImageComposeScene(700, 400, coroutineContext = scope.coroutineContext) {}
        private val focus = FocusRequester()
        private var width by mutableStateOf(400.dp)

        init {
            owner.beginGeneration(1L)
            model.toggleUI()
            val appPrefs = DesktopAppPreferences(DesktopPreferenceStore(root.node("app")), root.node("legacy-app"))
            appPrefs.pageTurnAnimation.set(animated)
            val dependencies = mockk<DesktopUiDependencies> { every { appPreferences } returns appPrefs }
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme {
                        val state by model.state.collectAsState()
                        Box(Modifier.requiredSize(width, 400.dp)) {
                            ReaderViewport(
                                state, model, mockk(relaxed = true), focus, scope, "", "", owner, null,
                                { chapterRequests++ }, { chapterRequests++ },
                            )
                        }
                    }
                }
            }
        }

        fun frames(count: Int = 35) = repeat(count) {
            scene.render(scope.testScheduler.currentTime * 1_000_000).close()
            scope.runCurrent()
            scope.advanceTimeBy(16)
        }

        fun dual(enabled: Boolean) {
            if (mode == ReadingMode.AUTO) {
                width = if (enabled) 700.dp else 400.dp
            } else {
                model.setDualPageMode(enabled)
            }
        }

        fun confirmPortraitFacts() {
            state.session.activeChapter.pages.forEach { page ->
                assertTrue(
                    model.acceptDualFact(
                        requireNotNull(model.captureDualFact(page.id)),
                        ReaderPageFact.dimensions(10, 14),
                    ),
                )
            }
        }

        fun releaseDecode() {
            decodeReady.complete(Unit)
        }

        fun wideFact(index: Int) {
            val page = state.session.activeChapter.pages[index]
            assertTrue(
                model.acceptDualFact(requireNotNull(model.captureDualFact(page.id)), ReaderPageFact.dimensions(20, 10)),
            )
        }

        fun confirmMatching() {
            model.setAutoSpreadMatching(true, prefs)
            val shared = ReaderScreenModel::class.java.getDeclaredField("dualSession").apply { isAccessible = true }
                .get(model) as mihon.domain.reader.SharedDualPageSession
            val artwork = List(50) { (255 shl 24) or ((80 + it * 3) shl 16) or (80 shl 8) or 120 }
            while (true) {
                val id = shared.spreadSampleDependencies().firstOrNull() ?: break
                val request = requireNotNull(shared.requestSpreadSample(id))
                val plain = List(50) { (255 shl 24) or (id.sourcePageIndex * 30 shl 16) or 80 }
                assertTrue(
                    shared.acceptSpreadSample(
                        request,
                        mihon.domain.reader.ReaderSpreadEdgeSamples(
                            if (id.sourcePageIndex == 2) artwork else plain,
                            if (id.sourcePageIndex == 3) artwork else plain,
                        ),
                    ),
                )
            }
            model.setAutoSpreadMatching(true, prefs)
            assertTrue(
                state.dualPageUnits.any { it.pageIds.map { page -> page.sourcePageIndex } == listOf(2, 3) },
                "The delivered samples must cause an actual shared matching reflow",
            )
        }

        fun locationSequence(): Long {
            val owner = ReaderScreenModel::class.java.getDeclaredField("dualSession").apply { isAccessible = true }
                .get(model) as mihon.domain.reader.SharedDualPageSession
            return requireNotNull(owner.location).sequence
        }

        fun viewportDiagnostic(): String {
            val owner = ReaderScreenModel::class.java.getDeclaredField("dualSession").apply { isAccessible = true }
                .get(model) as mihon.domain.reader.SharedDualPageSession
            fun field(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
            val native = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .filter { it.config.contains(ReaderPagerNativePresentationKey) }
                .map { it.config[ReaderPagerNativePresentationKey].describe() }
            return "location=${owner.location}, layout=${owner.layoutRevision}, issued=${field("issuedViewport")}, " +
                "lastRead=${field("lastReadLocationSequence")}, state=${state.currentDisplayUnitId}, native=$native"
        }

        fun slotIndices(): List<Int?> = requireNotNull(state.currentDisplayUnitId).slots.map {
            it.pageId?.sourcePageIndex
        }

        fun nextTap() {
            val x = if (mode == ReadingMode.LTR) width.value - 50f else 50f
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 200f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, 200f), button = PointerButton.Primary)
        }

        fun goTo(index: Int) = model.goToPage(index)

        fun webtoon() = model.setReadingMode(ReadingMode.WEBTOON)

        fun nextKey() {
            focus.requestFocus()
            val key = if (mode == ReadingMode.LTR) {
                androidx.compose.ui.input.key.Key.DirectionRight
            } else {
                androidx.compose.ui.input.key.Key.DirectionLeft
            }
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") &&
                    !it.name.endsWith("\$default")
            }
            val native = factory.invoke(null, key.keyCode, type, 0, false, false, false, false, null)
            assertTrue(scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native)))
        }

        fun nextDrag() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 200f), type = PointerType.Touch)
            repeat(8) { step ->
                val distance = (step + 1) * 30f * if (mode == ReadingMode.LTR) -1 else 1
                scene.sendPointerEvent(PointerEventType.Move, Offset(200f + distance, 200f), type = PointerType.Touch)
                frames(2)
            }
            scene.sendPointerEvent(
                PointerEventType.Release,
                Offset(if (mode == ReadingMode.LTR) -40f else 440f, 200f),
                type = PointerType.Touch,
            )
        }

        fun adjust() {
            val label = MR.strings.desktop_ui_adjust_spread.localized()
            val button = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
                it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child ->
                    child.config.contains(SemanticsProperties.ContentDescription) &&
                        label in child.config[SemanticsProperties.ContentDescription]
                }
            }
            assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
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
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    }
}
