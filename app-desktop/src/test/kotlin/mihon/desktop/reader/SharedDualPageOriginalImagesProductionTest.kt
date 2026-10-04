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
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
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
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.ui.reader.DualPageDisplayUnitCompositionIdentityKey
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
            isAutoSpreadMatching = false
            autoSplitPages = false
            cropBordersPager = false
            backgroundTheme = ReaderBackgroundTheme.BLACK
            scaleType = ScaleType.FIT_SCREEN
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
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
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (key.pageIndex in heldDecode) decoded.await()
                decoder.decode(encoded, key)
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
                    if (request.maxBytes != null && request.pageId.sourcePageIndex in held) headers.await()
                    port.open(request).also { bytes ->
                        if (request.maxBytes != null && request.pageId.sourcePageIndex in held) {
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
                .apply { pageTurnAnimation.set(false) }
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

        suspend fun knownNeighbors(target: Int, expected: Map<Int, ReaderPageFactState>) {
            await("Ordinary encoded headers must precede this explicit mode change") {
                model.state.value.currentPage == target && expected.all { (index, type) ->
                    runCatching { owner().pageFact(owner().identity.pageIds[index])?.state == type }.getOrDefault(false)
                } && events.any { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED }
            }
            assertTrue(owner().displayedHistory.none { it.drawnPageIds.any { page -> page.sourcePageIndex != target } })
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
            headers.complete(Unit)
            decoded.complete(Unit)
            await("Held normal headers must finish") {
                held.all { index ->
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
                            "original-observe-miss source=$source matched=$matched span=$span slot=${slot.boundsInRoot} frame=$frame",
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
    private fun samplePoints(bitmap: Bitmap, radius: Int): List<Pair<Int, Int>> = (1..5).flatMap { row ->
        (1..5).map { column ->
            val cx = bitmap.width * column / 6
            val cy = bitmap.height * row / 6
            val candidates = (-24..24 step 4).flatMap { dx ->
                (-24..24 step 4).map { dy ->
                    (cx + dx).coerceIn(radius, bitmap.width - radius - 1) to
                        (cy + dy).coerceIn(radius, bitmap.height - radius - 1)
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
