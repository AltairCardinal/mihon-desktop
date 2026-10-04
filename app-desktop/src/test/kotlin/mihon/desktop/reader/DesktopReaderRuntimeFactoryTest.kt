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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.browse.localReaderScreen
import mihon.desktop.ui.reader.DualPageSlotIdKey
import mihon.desktop.ui.reader.ReaderLifecycleEffect
import mihon.desktop.ui.reader.ReaderModeState
import mihon.desktop.ui.reader.ReaderPagerNativePresentationKey
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.desktop.ui.reader.ReaderViewport
import mihon.desktop.ui.reader.dualPresentationSnapshot
import mihon.desktop.ui.reader.presentation.resolveDualVisiblePages
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.partial.DisabledPartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialCommittedPage
import mihon.domain.reader.partial.PartialDownloadSnapshot
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialPageTable
import mihon.domain.reader.partial.PartialPageTableEntry
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class DesktopReaderRuntimeFactoryTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `two original encoded header leases survive another page confirming its orientation`() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        val directory = tempDir.resolve("two-header-leases").apply { mkdirs() }
        for ((index, orientation) in listOf(6, 8).withIndex()) {
            val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.toByte() }
            directory.resolve("$index.jpg").writeBytes(
                raw.copyOfRange(0, 2) + byteArrayOf(
                    -1,
                    -31,
                    0,
                    (
                        exif.size +
                            2
                        ).toByte(),
                ) + exif + raw.copyOfRange(2, raw.size),
            )
        }
        val legacy = Preferences.userRoot().node("/mihon/two-original-header-leases/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = false
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(tempDir.resolve("two-header-downloads")),
            mockk<SourceManager>(relaxed = true),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            tempDir.resolve("two-header-cache"),
        )
        val originalRuntime = factory.createRuntime(localContext(99, directory), scope)
        val gates = mapOf(
            6 to kotlinx.coroutines.CompletableDeferred<Unit>(),
            8 to kotlinx.coroutines.CompletableDeferred<Unit>(),
        )
        val entered = CopyOnWriteArrayList<Int>()
        val cancelled = CopyOnWriteArrayList<Int>()
        val boundedOwner = DesktopReaderPageContentOwner(
            scope,
            originalRuntime.encodedPageStore::read,
            mihon.domain.reader.observability.ReaderIoReporter(clock = ReaderMonotonicClock { System.nanoTime() }),
            encodedPrefixReader = { ref, limit ->
                val bytes = checkNotNull(originalRuntime.encodedPageStore.readPrefix(ref, limit))
                val orientation = mihon.domain.reader.readImageOrientation(bytes).exifValue
                entered += orientation
                try {
                    gates.getValue(orientation).await()
                    bytes
                } catch (error: kotlinx.coroutines.CancellationException) {
                    cancelled += orientation
                    throw error
                }
            },
        )
        val runtime = originalRuntime.copy(pageContentOwner = boundedOwner)
        val model = factory.createModel(runtime, false, 0, null)
        val collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        val scene = mountReaderScene(runtime, model, scope, legacy)
        suspend fun pumpUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < end) {
                dispatcher.scheduler.runCurrent()
                scene.render(dispatcher.scheduler.currentTime * 1_000_000).close()
                dispatcher.scheduler.advanceTimeBy(16)
                delay(10)
            }
        }
        try {
            pumpUntil { entered.contains(6) }
            assertEquals(
                listOf(6),
                entered.toList(),
                "The original bounded header worker serializes optional reads",
            )
            val location = model.state.value.dualPageLocation
            gates.getValue(6).complete(Unit)
            pumpUntil {
                model.state.value.dualPageIdentity?.content?.firstOrNull()?.sourceOrientation ==
                    ReaderImageOrientation.CLOCKWISE
            }
            assertEquals(
                ReaderImageOrientation.CLOCKWISE,
                model.state.value.dualPageIdentity?.content?.firstOrNull()?.sourceOrientation,
            )
            assertEquals(
                ReaderImageOrientation.NORMAL,
                model.state.value.dualPageIdentity?.content?.lastOrNull()?.sourceOrientation,
            )
            assertTrue(
                cancelled.isEmpty(),
                "Another page's metadata confirmation cannot cancel the original second header lease",
            )
            pumpUntil { entered.contains(8) }
            assertEquals(1, entered.count { it == 8 })
            gates.getValue(8).complete(Unit)
            pumpUntil {
                model.state.value.dualPageUnits.all { it.certain } &&
                    model.state.value.dualPageIdentity?.content?.lastOrNull()?.sourceOrientation ==
                    ReaderImageOrientation.COUNTER_CLOCKWISE
            }
            assertEquals(
                listOf(
                    ReaderImageOrientation.CLOCKWISE,
                    ReaderImageOrientation.COUNTER_CLOCKWISE,
                ),
                model.state.value.dualPageIdentity?.content?.map { it.sourceOrientation },
            )
            assertEquals(listOf(6, 8), entered.sorted())
            assertEquals(location, model.state.value.dualPageLocation)
            assertTrue(cancelled.isEmpty())
            pumpUntil { boundedOwner.snapshot().activeLeaseCounts.isEmpty() }
            assertTrue(
                boundedOwner.snapshot().activeLeaseCounts.isEmpty(),
                "Both original header leases must close after their original results are consumed",
            )
        } finally {
            gates.values.forEach { it.complete(Unit) }
            scene.close()
            collector.cancel()
            model.onDispose()
            runtime.close()
            originalRuntime.pageContentOwner.close()
            scope.cancel()
            legacy.removeNode()
        }
    }

    @Test
    fun `interleaved actual EXIF headers preserve original HTTP consumers in Compose`() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        fun encoded(orientation: Int): ByteArray {
            val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.toByte() }
            return raw.copyOfRange(0, 2) +
                byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) +
                exif + raw.copyOfRange(2, raw.size)
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val paths = CopyOnWriteArrayList<String>()
        val server = mockwebserver3.MockWebServer().apply {
            this.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    paths += request.url.encodedPath
                    val index = request.url.encodedPath.substringAfterLast('/').toInt()
                    if (index == 1) {
                        entered.countDown()
                        check(gate.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return mockwebserver3.MockResponse.Builder().body(
                        okio.Buffer().write(
                            encoded(
                                if (index ==
                                    0
                                ) {
                                    6
                                } else {
                                    8
                                },
                            ),
                        ),
                    ).build()
                }
            }
            start()
        }
        val source = mockk<CatalogueSource>(relaxed = true) { every { id } returns 42L }
        coEvery { source.getPageList(any()) } returns List(2) {
            Page(
                it,
                imageUrl =
                server.url("/image/$it").toString(),
            )
        }
        val legacy = Preferences.userRoot().node("/mihon/exif-header-interleave/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = false
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(tempDir.resolve("exif-downloads")),
            sourceManager(listOf(source)),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            tempDir.resolve("exif-encoded"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                98, 42, "/chapter/98", "Manga", "EXIF", 98.0, 0,
                0, false,
            ),
            scope,
        )
        val model = factory.createModel(runtime, false, 0, null)
        val collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        val scene = mountReaderScene(runtime, model, scope, legacy, animated = false)
        suspend fun pumpUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < end) {
                dispatcher.scheduler.runCurrent()
                scene.render(dispatcher.scheduler.currentTime * 1_000_000).close()
                dispatcher.scheduler.advanceTimeBy(16)
                delay(10)
            }
        }
        try {
            pumpUntil {
                entered.count == 0L &&
                    model.state.value.dualPageIdentity?.content?.firstOrNull()?.sourceOrientation ==
                    ReaderImageOrientation.CLOCKWISE
            }
            assertEquals(
                0L,
                entered.count,
                "The original normal page request must remain in flight through the other page's direction confirmation",
            )
            assertEquals(
                ReaderImageOrientation.CLOCKWISE,
                model.state.value.dualPageIdentity?.content?.firstOrNull()?.sourceOrientation,
            )
            model.goToPage(1)
            val identity = requireNotNull(model.state.value.dualPageIdentity)
            val location = model.state.value.dualPageLocation
            gate.countDown()
            pumpUntil {
                model.state.value.dualPageIdentity?.content?.lastOrNull()?.sourceOrientation ==
                    ReaderImageOrientation.COUNTER_CLOCKWISE &&
                    model.state.value.dualPageUnits.all { it.certain }
            }
            assertEquals(
                listOf(
                    ReaderImageOrientation.CLOCKWISE,
                    ReaderImageOrientation.COUNTER_CLOCKWISE,
                ),
                model.state.value.dualPageIdentity?.content?.map { it.sourceOrientation },
            )
            assertEquals(identity.pageListRevision, model.state.value.dualPageIdentity?.pageListRevision)
            assertEquals(location, model.state.value.dualPageLocation)
            assertEquals(1, paths.count { it == "/image/0" })
            assertEquals(1, paths.count { it == "/image/1" })
            pumpUntil {
                sceneNodes(scene).any {
                    it.config.contains(DualPageSlotIdKey) &&
                        it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == 1 && it.boundsInRoot.width == 350f
                }
            }
            assertEquals(
                mihon.domain.reader.ReaderPortraitSingleSlot.RIGHT,
                model.state.value.dualPageUnits.last().slots.single().position,
            )
            assertTrue(
                sceneNodes(scene).any {
                    it.config.contains(DualPageSlotIdKey) &&
                        it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == 1 && it.boundsInRoot.width == 350f &&
                        it.boundsInRoot.left == 350f
                },
                "Actual EXIF viewport: page=${model.state.value.currentPage}, " +
                    "unit=${model.state.value.currentDisplayUnitId}, " +
                    "slots=" + sceneNodes(scene).filter { it.config.contains(DualPageSlotIdKey) }
                        .map { it.config[DualPageSlotIdKey] to it.boundsInRoot },
            )
        } finally {
            gate.countDown()
            scene.close()
            collector.cancel()
            model.onDispose()
            runtime.close()
            scope.cancel()
            server.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `cold tail runtime reads ordinary images without fetching an unknown prefix`() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val dimensions =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/shared-dual-page-real-b1.csv"))
                .bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }
                        .map { row -> row.split(',').map(String::toInt).let { it[1] to it[2] } }.toList()
                }
        val images = dimensions.distinct().associateWith { size ->
            val image = BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_RGB)
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray().also { image.flush() }
        }
        val paths = CopyOnWriteArrayList<String>()
        val enteredPrefix = java.util.concurrent.CountDownLatch(1)
        val releasePrefix = java.util.concurrent.CountDownLatch(1)
        val server = mockwebserver3.MockWebServer().apply {
            this.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    paths += request.url.encodedPath
                    val index = request.url.encodedPath.substringAfterLast('/').toInt()
                    if (index == 14) {
                        enteredPrefix.countDown()
                        check(releasePrefix.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return mockwebserver3.MockResponse.Builder().body(
                        okio.Buffer().write(images.getValue(dimensions[index])),
                    ).build()
                }
            }
            start()
        }
        val source = mockk<CatalogueSource>(relaxed = true) { every { id } returns 42L }
        coEvery { source.getPageList(any()) } returns List(17) {
            Page(
                it,
                imageUrl =
                server.url("/image/$it").toString(),
            )
        }
        val legacy = Preferences.userRoot().node("/mihon/cold-header-supplement/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = false
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(tempDir.resolve("cold-tail-downloads")),
            sourceManager(listOf(source)),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            tempDir.resolve("cold-tail-encoded"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                96, 42, "/chapter/96", "Manga", "Tail", 96.0, 0,
                16, false,
            ),
            scope,
        )
        val model = factory.createModel(runtime, false, 0, null)
        val collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        val scene = mountReaderScene(runtime, model, scope, legacy)
        suspend fun pumpUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < end) {
                dispatcher.scheduler.runCurrent()
                scene.render(dispatcher.scheduler.currentTime * 1_000_000L).close()
                dispatcher.scheduler.advanceTimeBy(16)
                delay(10)
            }
        }
        try {
            pumpUntil { model.state.value.session.activeChapter.pages.size == 17 }
            val tail = model.state.value.session.activeChapter.pages.last().id
            // A cold tail consumes ordinary loading; optional facts must not fetch a backward prefix.
            pumpUntil {
                sceneNodes(scene).any { node ->
                    node.config.contains(ReaderPagerNativePresentationKey) &&
                        node.config[ReaderPagerNativePresentationKey].describe().contains("reported=true")
                }
            }
            assertEquals(1L, enteredPrefix.count, "Optional metadata must not materialize source fifteen")
            assertTrue(
                sceneNodes(scene).any {
                    it.config.contains(ReaderPagerNativePresentationKey) &&
                        it.config[ReaderPagerNativePresentationKey].describe().contains("reported=true")
                },
                "The original tail pixels must actually have been drawn and settled",
            )
            val location = model.state.value.dualPageLocation
            releasePrefix.countDown()
            assertTrue(
                !paths.contains("/image/9") && !paths.contains("/image/14"),
                "Optional metadata cannot add distant or backward HTTP work; paths=$paths",
            )
            pumpUntil {
                model.state.value.dualPageUnits.singleOrNull {
                    it.pageIds.singleOrNull()?.sourcePageIndex == 16
                }?.slots?.singleOrNull()?.position == mihon.domain.reader.ReaderPortraitSingleSlot.RIGHT
            }
            val units = model.state.value.dualPageUnits
            assertEquals(
                mihon.domain.reader.ReaderPortraitSingleSlot.RIGHT,
                units.single { it.pageIds.singleOrNull()?.sourcePageIndex == 16 }.slots.single().position,
            )
            assertEquals(16, model.state.value.dualPageLocation?.pageId?.sourcePageIndex)
            assertEquals(location, model.state.value.dualPageLocation)
            assertEquals(
                0,
                paths.count { it == "/image/14" },
                "The backward unknown page is not an optional network request",
            )
            assertEquals(1, paths.count { it == "/image/16" }, "Metadata must not restart normal visible image work")
            pumpUntil {
                sceneNodes(scene).any {
                    it.config.contains(DualPageSlotIdKey) &&
                        it.config[DualPageSlotIdKey].pageId == tail && it.boundsInRoot.width == 350f &&
                        it.boundsInRoot.left == 350f
                }
            }
            assertTrue(
                sceneNodes(scene).any {
                    it.config.contains(DualPageSlotIdKey) &&
                        it.config[DualPageSlotIdKey].pageId == tail && it.boundsInRoot.width == 350f &&
                        it.boundsInRoot.left == 350f
                },
            )
        } finally {
            releasePrefix.countDown()
            scene.close()
            collector.cancel()
            model.onDispose()
            runtime.close()
            scope.cancel()
            server.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `selected cold native viewport schedules pixels before a rendered settlement`() = runTest {
        val source = mockk<CatalogueSource>(relaxed = true) { every { id } returns 42L }
        val server = mockwebserver3.MockWebServer().apply { start() }
        server.enqueue(mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(pngBytes())).build())
        coEvery { source.getPageList(any()) } returns listOf(Page(0, imageUrl = server.url("/visible.png").toString()))
        val legacy = Preferences.userRoot().node("/mihon/cold-presentation-start/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = false
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(tempDir.resolve("cold-start-downloads")),
            sourceManager(listOf(source)),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            tempDir.resolve("cold-start-encoded"),
        )
        val context = DesktopReaderChapterContext(95, 42, "/chapter/95", "Manga", "Cold chapter", 95.0, 0, 0, false)
        val runtime = factory.createRuntime(context, this)
        val model = factory.createModel(runtime, false, 0, null)
        try {
            advanceUntilIdle()
            model.acceptSessionState(runtime.session.state.value)
            val presentation = model.state.value.dualPresentationSnapshot(false, false)
            val unit = presentation.displayUnits.single { it.slots.any { slot -> slot.page != null } }
            val visible = presentation.resolveDualVisiblePages(unit.id)
            assertNotNull(model.beginPagerViewport(visible, false))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    runtime.session.state.first {
                        it.snapshot.activeChapter.pages.single().loadState is ReaderPageLoadState.Ready
                    }
                }
            }
            assertEquals(
                1,
                server.requestCount,
                "Starting actual presentation must schedule normal image work before pixels exist",
            )
            assertInstanceOf(
                ReaderPageLoadState.Ready::class.java,
                runtime.session.state.value.snapshot.activeChapter.pages.single().loadState,
            )
        } finally {
            model.onDispose()
            runtime.close()
            server.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `production header leases bound actual encoded reads independently of full image`() = runTest {
        val file = tempDir.resolve("header-bounded.png").apply { writeBytes(pngBytes() + ByteArray(1536 * 1024)) }
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val factory = DesktopReaderRuntimeFactory(
            ReaderPreferences(),
            DesktopDownloadProvider(tempDir.resolve("header-downloads")),
            mockk<SourceManager>(relaxed = true),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            tempDir.resolve("header-encoded"),
            readerIoProbe = ReaderIoProbe(events::add),
        )
        val runtime = factory.createRuntime(localContext(92, file.parentFile), this)
        val request = ReaderPageContentOpenRequest(
            ReaderPageId(ReaderChapterId(92), 0),
            1,
            mihon.domain.reader.session.EncodedPageRef(file.toURI().toString()),
        )
        try {
            val full = requireNotNull(runtime.pageContentOwner.acquire(request))
            val header = requireNotNull(runtime.pageContentOwner.acquireHeader(request))
            try {
                assertTrue(
                    header.content.size <= mihon.domain.reader.ReaderDualPageFactLimits.MAX_HEADER_BYTES,
                    "Header open must read at most 512KiB from the actual encoded file",
                )
                assertEquals(file.length(), full.content.size.toLong())
                assertEquals(
                    1,
                    events.count {
                        it.type == ReaderIoEventType.OPEN_PAGE
                    },
                    "Full physical opens remain separate from bounded prefixes",
                )
                assertEquals(
                    1,
                    events.count {
                        it.type.name == "HEADER_READ"
                    },
                    "The actual prefix read must remain observable",
                )
                val headerEvent = events.single { it.type == ReaderIoEventType.HEADER_READ }
                assertEquals(request.pageId, headerEvent.pageId)
                assertEquals(request.generation, headerEvent.generation)
                assertEquals(
                    mihon.domain.reader.ReaderDualPageFactLimits.MAX_HEADER_BYTES.toInt(),
                    headerEvent.byteLimit,
                )
                assertEquals(2, runtime.pageContentOwner.snapshot().activeLeaseCounts.size)
                header.close()
                assertEquals(1, runtime.pageContentOwner.snapshot().activeLeaseCounts.size)
            } finally {
                header.close()
                full.close()
            }
            assertTrue(runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `real B2 directory materialization publishes shared slots before Compose holders`() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val dimensions =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/shared-dual-page-real-b2.csv"))
                .bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }
                        .map { row -> row.split(',').map(String::toInt).let { it[1] to it[2] } }.toList()
                }
        val directory = tempDir.resolve("real-b2-dimensions").apply { mkdirs() }
        dimensions.forEachIndexed { index, size ->
            val image = BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_ARGB)
            ImageIO.write(image, "png", directory.resolve("%02d.png".format(index + 1)))
            image.flush()
        }
        val legacy = Preferences.userRoot().node("/mihon/shared-b2-content/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = false
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(tempDir.resolve("downloads-b2-facts")),
            mockk<SourceManager>(relaxed = true),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            tempDir.resolve("encoded-b2-facts"),
        )
        val runtime = factory.createRuntime(localContext(91, directory), scope)
        val model = factory.createModel(runtime, false, 0, null)
        val collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        val scene = mountReaderScene(runtime, model, scope, legacy, animated = false)
        suspend fun pumpUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < end) {
                dispatcher.scheduler.runCurrent()
                scene.render(dispatcher.scheduler.currentTime * 1_000_000L).close()
                dispatcher.scheduler.advanceTimeBy(16)
                delay(10)
            }
        }
        try {
            pumpUntil {
                model.state.value.session.activeChapter.pages.size == 22 &&
                    model.state.value.dualPageUnits.any {
                        it.pageIds.singleOrNull()?.sourcePageIndex == 1 &&
                            it.slots.single().position == mihon.domain.reader.ReaderPortraitSingleSlot.LEFT
                    }
            }
            assertEquals(22, model.state.value.session.activeChapter.pages.size)
            assertEquals(
                mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                model.state.value.dualPageIdentity?.pageIds?.get(19)?.let { id ->
                    val owner = ReaderScreenModel::class.java.getDeclaredField("dualSession")
                        .apply { isAccessible = true }.get(model) as mihon.domain.reader.SharedDualPageSession
                    owner.pageFact(id)?.state ?: mihon.domain.reader.ReaderPageFactState.UNKNOWN
                },
                "Normal nearby loading does not analyze every distant page",
            )
            for ((index, expectedWidth) in listOf(1 to 350f, 19 to 700f)) {
                if (index == 19) {
                    model.setDualPageMode(false)
                    for (neighbor in listOf(18, 20)) {
                        model.goToPage(neighbor)
                        pumpUntil {
                            model.state.value.visiblePageIds.any { it.sourcePageIndex == neighbor }
                        }
                        assertTrue(model.state.value.visiblePageIds.any { it.sourcePageIndex == neighbor })
                    }
                    model.goToPage(index)
                    pumpUntil { model.state.value.visiblePageIds.any { it.sourcePageIndex == index } }
                    model.setDualPageMode(true)
                } else {
                    model.goToPage(index)
                }
                pumpUntil {
                    sceneNodes(scene).any {
                        it.config.contains(DualPageSlotIdKey) &&
                            it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == index &&
                            it.boundsInRoot.width == expectedWidth && it.boundsInRoot.left == 0f
                    }
                }
                val slots = sceneNodes(scene).filter { it.config.contains(DualPageSlotIdKey) }
                assertTrue(
                    slots.any { it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == index },
                    "Actual B2 viewport: target=$index, page=${model.state.value.currentPage}, " +
                        "unit=${model.state.value.currentDisplayUnitId}, " +
                        "slots=" + slots.map { it.config[DualPageSlotIdKey] to it.boundsInRoot },
                )
                val slot = slots.single { it.config[DualPageSlotIdKey].pageId?.sourcePageIndex == index }
                assertEquals(expectedWidth, slot.boundsInRoot.width, "Actual B2 page ${index + 1} Compose slot")
                assertEquals(0f, slot.boundsInRoot.left)
                assertEquals(index, model.state.value.dualPageLocation?.pageId?.sourcePageIndex)
            }
        } finally {
            scene.close()
            collector.cancel()
            model.onDispose()
            runtime.close()
            scope.cancel()
            legacy.removeNode()
        }
    }

    @Test
    fun `runtime applies filename preference to initial and live partial identities`() = runTest {
        val context = DesktopReaderChapterContext(
            chapterId = 59L,
            sourceId = 42L,
            chapterUrl = "/chapter/日本語",
            mangaTitle = "漫画",
            chapterTitle = "第一話",
            chapterNumber = 1.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
            sourceDisplayName = "Source",
            disallowNonAsciiFilenames = false,
        )
        val expectedIdentity = DownloadChapterIdentity(
            sourceDisplayName = context.sourceDisplayName,
            mangaTitle = context.mangaTitle,
            chapterName = context.chapterTitle,
            scanlator = null,
            chapterUrl = context.chapterUrl,
            disallowNonAsciiFilenames = true,
        )
        val firstLocalPage = tempDir.resolve("live-partial/001.png").apply {
            parentFile.mkdirs()
            writeBytes(pngBytes())
        }
        val liveLocalPage = tempDir.resolve("live-partial/002.png").apply { writeBytes(pngBytes()) }
        val initialSnapshot = PartialDownloadSnapshot(
            chapterId = context.chapterId,
            identity = expectedIdentity,
            attemptGeneration = 3L,
            queueStatus = DownloadQueueStatus.DOWNLOADING,
            pageTable = PartialPageTable.complete(
                listOf(
                    PartialPageTableEntry(0, 0, "/page/0", "https://fixture.invalid/0.png"),
                    PartialPageTableEntry(1, 1, "/page/1", "https://fixture.invalid/1.png"),
                ),
            ),
            committedPages = listOf(PartialCommittedPage(0, 0, firstLocalPage.absolutePath, 1L)),
        )
        val currentSnapshot = AtomicReference(initialSnapshot)
        val observedIdentities = CopyOnWriteArrayList<DownloadChapterIdentity>()
        val lookup = PartialDownloadSnapshotLookup { _, identity ->
            observedIdentities += identity
            currentSnapshot.get().takeIf { it.identity == identity }
        }
        val source = mockk<CatalogueSource>(relaxed = true) {
            every { id } returns context.sourceId
        }
        val sourceManager = sourceManager(listOf(source))
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-live-identity")),
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-live-identity"),
            disallowNonAsciiFilenames = { true },
            partialDownloadSnapshotLookup = lookup,
        )
        val runtime = factory.createRuntime(context, this)
        try {
            advanceUntilIdle()
            val targetPage = runtime.session.state.value.snapshot.activeChapter.pages[1]
            assertEquals(null, targetPage.partialPageCandidate)

            currentSnapshot.set(
                initialSnapshot.copy(
                    committedPages = initialSnapshot.committedPages +
                        PartialCommittedPage(1, 1, liveLocalPage.absolutePath, 2L),
                ),
            )
            runtime.session.settleViewport(setOf(targetPage.id), targetPage.id)
            advanceUntilIdle()

            val materialized = runtime.session.state.value.snapshot.activeChapter.pages[1]
            assertInstanceOf(
                mihon.domain.reader.session.ReaderEncodedPageProvenance.Partial::class.java,
                materialized.encodedPageProvenance,
            )
            assertTrue(runtime.session.state.value.context.disallowNonAsciiFilenames)
            assertTrue(observedIdentities.size >= 2)
            assertTrue(observedIdentities.all { it.disallowNonAsciiFilenames })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `next chapter prefetch preserves filename identity in the partial page table`() = runTest {
        val legacy = Preferences.userRoot().node("/mihon/runtime-partial-prefetch-identity/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            nextChapterPrefetchMode = NextChapterPrefetchMode.FIRST_VIEWPORT
        }
        val currentContext = DesktopReaderChapterContext(
            chapterId = 59L,
            sourceId = 42L,
            chapterUrl = "/chapter/current",
            mangaTitle = "漫画",
            chapterTitle = "現在話",
            chapterNumber = 1.0,
            chapterIndex = 1,
            initialPage = 0,
            wasRead = false,
            sourceDisplayName = "Source",
        )
        val nextContext = currentContext.copy(
            chapterId = 60L,
            chapterUrl = "/chapter/next",
            chapterTitle = "次話",
            chapterNumber = 2.0,
            chapterIndex = 0,
            disallowNonAsciiFilenames = false,
        )
        val currentPage = tempDir.resolve("partial-prefetch/current.png").apply {
            parentFile.mkdirs()
            writeBytes(pngBytes())
        }
        val nextPage = tempDir.resolve("partial-prefetch/next.png").apply { writeBytes(pngBytes()) }
        fun identity(context: DesktopReaderChapterContext) = DownloadChapterIdentity(
            sourceDisplayName = context.sourceDisplayName,
            mangaTitle = context.mangaTitle,
            chapterName = context.chapterTitle,
            scanlator = context.scanlator,
            chapterUrl = context.chapterUrl,
            disallowNonAsciiFilenames = true,
        )
        fun snapshot(
            context: DesktopReaderChapterContext,
            page: File,
        ) = PartialDownloadSnapshot(
            chapterId = context.chapterId,
            identity = identity(context),
            attemptGeneration = context.chapterId,
            queueStatus = DownloadQueueStatus.DOWNLOADING,
            pageTable = PartialPageTable.complete(
                listOf(
                    PartialPageTableEntry(0, 0, "${context.chapterUrl}/0", "https://fixture.invalid/0.png"),
                    PartialPageTableEntry(1, 1, "${context.chapterUrl}/1", "https://fixture.invalid/1.png"),
                ),
            ),
            committedPages = listOf(PartialCommittedPage(0, 0, page.absolutePath, 1L)),
        )
        val snapshots = mapOf(
            currentContext.chapterId to snapshot(currentContext, currentPage),
            nextContext.chapterId to snapshot(nextContext, nextPage),
        )
        val observedNextIdentities = CopyOnWriteArrayList<DownloadChapterIdentity>()
        val lookup = PartialDownloadSnapshotLookup { chapterId, requestedIdentity ->
            if (chapterId == nextContext.chapterId) observedNextIdentities += requestedIdentity
            snapshots[chapterId]?.takeIf { it.identity == requestedIdentity }
        }
        val source = mockk<CatalogueSource>(relaxed = true) {
            every { id } returns currentContext.sourceId
            coEvery { getPageList(any()) } returns listOf(Page(0, url = "/unexpected-online", imageUrl = null))
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs = prefs,
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-partial-prefetch-identity")),
            sourceManager = sourceManager(listOf(source)),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-partial-prefetch-identity"),
            disallowNonAsciiFilenames = { true },
            partialDownloadSnapshotLookup = lookup,
        )
        val runtime = factory.createRuntime(currentContext, this)
        var model: ReaderScreenModel? = null
        try {
            advanceUntilIdle()
            model = factory.createModel(runtime, isWebtoon = false, mangaViewerFlags = 0L, dualPageOverride = null)

            model.updateNextChapterPrefetch(nextContext, firstViewportPageCount = 1)
            val currentReaderPage = runtime.session.state.value.snapshot.activeChapter.pages.first()
            runtime.session.settleViewport(setOf(currentReaderPage.id), currentReaderPage.id)
            advanceUntilIdle()
            model.activateChapter(nextContext)
            advanceUntilIdle()

            val activated = runtime.session.state.value
            assertEquals(nextContext.chapterId, activated.context.chapterId)
            assertTrue(activated.context.disallowNonAsciiFilenames)
            assertNotNull(activated.snapshot.activeChapter.pages.first().partialPageCandidate)
            assertTrue(observedNextIdentities.isNotEmpty())
            assertTrue(observedNextIdentities.all { it.disallowNonAsciiFilenames })
            coVerify(exactly = 0) { source.getPageList(any()) }
        } finally {
            model?.onDispose() ?: runtime.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `runtime factory reaches partial page lists through explicit lookup injection`() = runTest {
        val context = DesktopReaderChapterContext(
            chapterId = 60L,
            sourceId = 42L,
            chapterUrl = "/chapter/60",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 60",
            chapterNumber = 60.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
            sourceDisplayName = "Source",
        )
        val source = mockk<CatalogueSource>(relaxed = true) {
            every { id } returns 42L
            coEvery { getPageList(any()) } returns listOf(
                Page(4, url = "/online/first", imageUrl = "https://img/online-first.jpg"),
                Page(41, url = "/online/last", imageUrl = null),
            )
        }
        val sourceManager = sourceManager(listOf(source))
        val snapshot = PartialDownloadSnapshot(
            chapterId = context.chapterId,
            identity = DownloadChapterIdentity(
                sourceDisplayName = context.sourceDisplayName,
                mangaTitle = context.mangaTitle,
                chapterName = context.chapterTitle,
                scanlator = null,
                chapterUrl = context.chapterUrl,
                disallowNonAsciiFilenames = false,
            ),
            attemptGeneration = 2L,
            queueStatus = DownloadQueueStatus.DOWNLOADING,
            pageTable = PartialPageTable.complete(
                listOf(
                    PartialPageTableEntry(0, 4, "/page/first", "https://img/first.jpg"),
                    PartialPageTableEntry(1, 19, "/page/middle", null),
                    PartialPageTableEntry(2, 41, "/page/last", "https://img/last.jpg"),
                ),
            ),
            committedPages = listOf(PartialCommittedPage(0, 4, "opaque://partial/001.jpg", 5L)),
        )
        val lookup = PartialDownloadSnapshotLookup { _, _ -> snapshot }
        val explicitFactory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-explicit-partial")),
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-explicit-partial"),
            partialDownloadSnapshotLookup = lookup,
        )
        assertSame(lookup, explicitFactory.configuredPartialDownloadSnapshotLookup)

        val explicitRuntime = explicitFactory.createRuntime(context, this)
        try {
            advanceUntilIdle()

            val pages = explicitRuntime.session.state.value.snapshot.activeChapter.pages
            assertEquals(listOf(4, 19, 41), pages.map { it.id.sourcePageIndex })
            assertEquals(5L, pages[0].partialPageCandidate?.committedRevision)
            assertTrue(pages.drop(1).all { it.partialPageCandidate == null })
            coVerify(exactly = 0) { source.getPageList(any()) }
        } finally {
            explicitRuntime.close()
        }

        val defaultFactory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-disabled-partial")),
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-disabled-partial"),
        )
        assertSame(DisabledPartialDownloadSnapshotLookup, defaultFactory.configuredPartialDownloadSnapshotLookup)

        val defaultRuntime = defaultFactory.createRuntime(context, this)
        try {
            advanceUntilIdle()

            val pages = defaultRuntime.session.state.value.snapshot.activeChapter.pages
            assertEquals(listOf(0, 1), pages.map { it.id.sourcePageIndex })
            assertEquals(listOf("/online/first", "/online/last"), pages.map { it.url })
            assertTrue(pages.all { it.partialPageCandidate == null })
            assertTrue(pages.all { it.encodedPageProvenance == null })
            coVerify(exactly = 1) { source.getPageList(any()) }
        } finally {
            defaultRuntime.close()
        }
    }

    @Test
    fun `production runtime follows persisted next chapter prefetch changes`() = runTest {
        val legacy = Preferences.userRoot().node("/mihon/runtime-prefetch/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs = prefs,
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-prefetch-mode")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-prefetch-mode"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 61L,
                sourceId = 42L,
                chapterUrl = "/chapter/61",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 61",
                chapterNumber = 61.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )
        try {
            assertEquals(NextChapterPrefetchMode.OFF, runtime.session.currentNextChapterPrefetchMode)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.FIRST_VIEWPORT
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.FIRST_VIEWPORT, runtime.session.currentNextChapterPrefetchMode)
        } finally {
            runtime.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `production runtime preference changes drive the full viewport request sets`() = runTest {
        val legacy = Preferences.userRoot().node("/mihon/runtime-prefetch-requests/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val currentDirectory = tempDir.resolve("runtime-prefetch-current").also(File::mkdirs).apply {
            resolve("001.png").writeBytes(byteArrayOf(1, 2, 3))
        }
        val nextArchive = archive("runtime-prefetch-next.cbz", pageCount = 4)
        val laterArchive = archive("runtime-prefetch-later.cbz", pageCount = 3)
        val factory = DesktopReaderRuntimeFactory(
            prefs = prefs,
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-prefetch-requests")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-prefetch-requests"),
        )
        val runtime = factory.createRuntime(localContext(71L, currentDirectory), this)
        try {
            advanceUntilIdle()
            val opening = runtime.session.state.value.snapshot
            runtime.session.onFirstPagePresented(opening.activeChapter.pages.single().id, opening.generation)
            advanceUntilIdle()
            runtime.session.updateNextChapter(localContext(72L, nextArchive), firstViewportPageCount = 2)
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.OFF, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(0, runtime.encodedPageStore.diagnostics().refs.size)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.FIRST_VIEWPORT
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.FIRST_VIEWPORT, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(2, runtime.encodedPageStore.diagnostics().refs.size)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.FULL_NEXT_CHAPTER, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(4, runtime.encodedPageStore.diagnostics().refs.size)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
            advanceUntilIdle()
            runtime.session.updateNextChapter(localContext(73L, laterArchive), firstViewportPageCount = 2)
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.OFF, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(4, runtime.encodedPageStore.diagnostics().refs.size)
        } finally {
            runtime.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `local reader session identity never becomes a durable chapter progress id`() = runTest {
        val localChapter = tempDir.resolve("local/Manga/Chapter 1").also { directory ->
            directory.mkdirs()
            directory.resolve("001.png").writeBytes(byteArrayOf(1, 2, 3))
        }
        val screen = localReaderScreen(localChapter, mangaName = "Manga", chapterTitle = "Chapter 1")
        val progressTracker = mockk<ReaderProgressTracker>(relaxed = true)
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-local-progress")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = progressTracker,
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-local-progress"),
        )
        val runtime = factory.createRuntime(screen.initialContext(), this)
        try {
            advanceUntilIdle()
            val pageId = runtime.session.state.value.snapshot.activeChapter.pages.single().id

            runtime.session.settleViewport(setOf(pageId), pageId)
            advanceUntilIdle()

            coVerify(exactly = 0) {
                progressTracker.track(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        } finally {
            runtime.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `leaving composition does not close the screen-owned reader runtime`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-lifecycle")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-lifecycle"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 31L,
            sourceId = 42L,
            chapterUrl = "/chapter/31",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 31",
            chapterNumber = 31.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )
        val runtime = factory.createRuntime(context, this)
        val model = factory.createModel(
            runtime = runtime,
            isWebtoon = false,
            mangaViewerFlags = 0L,
            dualPageOverride = null,
        )
        val scene = ImageComposeScene(320, 240, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent { ReaderLifecycleEffect(model) }
            scene.render()
            assertEquals(true, ReaderModeState.isInReaderMode)

            scene.close()

            assertEquals(false, ReaderModeState.isInReaderMode)
            assertDoesNotThrow {
                runtime.session.activate(
                    context.copy(chapterId = 32L, chapterUrl = "/chapter/32", chapterTitle = "Chapter 32"),
                )
            }
        } finally {
            runCatching(scene::close)
            model.onDispose()
        }
    }

    @Test
    fun `production screen model owns its runtime until Voyager disposal`() {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-owner")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-owner"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 41L,
            sourceId = 42L,
            chapterUrl = "/chapter/41",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 41",
            chapterNumber = 41.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )

        lateinit var runtime: DesktopReaderRuntime
        var productionClosedCallbacks = 0
        val model = factory.createScreenModel(
            initialContext = context,
            isWebtoon = false,
            mangaViewerFlags = 0L,
            dualPageOverride = null,
            onProductionClosed = {
                assertThrows(IllegalStateException::class.java) { runtime.session.activate(context) }
                productionClosedCallbacks += 1
            },
        )
        runtime = requireNotNull(model.runtime)
        assertDoesNotThrow {
            runtime.session.activate(
                context.copy(chapterId = 42L, chapterUrl = "/chapter/42", chapterTitle = "Chapter 42"),
            )
        }

        model.onDispose()

        assertThrows(IllegalStateException::class.java) { runtime.session.activate(context) }
        assertEquals(1, productionClosedCallbacks)
    }

    @Test
    fun `Voyager disposal waits for the outgoing reader composition before closing production runtime`() {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-outgoing-owner")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-outgoing-owner"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 51L,
            sourceId = 42L,
            chapterUrl = "/chapter/51",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 51",
            chapterNumber = 51.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )
        var productionClosedCallbacks = 0
        val model = factory.createScreenModel(
            initialContext = context,
            isWebtoon = false,
            mangaViewerFlags = 0L,
            dualPageOverride = null,
            onProductionClosed = { productionClosedCallbacks += 1 },
        )
        val runtime = requireNotNull(model.runtime)
        val compositionLease = model.retainProductionRuntimeForComposition()

        model.onDispose()

        assertFalse(runtime.presentationImageOwner.isClosed())
        assertDoesNotThrow { runtime.session.activate(context.copy(chapterId = 52L)) }
        assertEquals(0, productionClosedCallbacks)

        compositionLease.close()

        assertTrue(runtime.presentationImageOwner.isClosed())
        assertThrows(IllegalStateException::class.java) { runtime.session.activate(context) }
        assertEquals(1, productionClosedCallbacks)

        compositionLease.close()
        model.onDispose()
        assertEquals(1, productionClosedCallbacks)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `reader lifecycle effect releases the production runtime after outgoing composition leaves`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-outgoing-effect")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-outgoing-effect"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 61L,
            sourceId = 42L,
            chapterUrl = "/chapter/61",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 61",
            chapterNumber = 61.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )
        var productionClosedCallbacks = 0
        val model = factory.createScreenModel(
            initialContext = context,
            isWebtoon = false,
            mangaViewerFlags = 0L,
            dualPageOverride = null,
            onProductionClosed = { productionClosedCallbacks += 1 },
        )
        val runtime = requireNotNull(model.runtime)
        val scene = ImageComposeScene(320, 240, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent { ReaderLifecycleEffect(model) }
            scene.render()

            model.onDispose()

            assertFalse(runtime.presentationImageOwner.isClosed())
            assertEquals(0, productionClosedCallbacks)

            scene.close()

            assertTrue(runtime.presentationImageOwner.isClosed())
            assertEquals(1, productionClosedCallbacks)
        } finally {
            runCatching(scene::close)
            model.onDispose()
        }
    }

    @Test
    fun `production close failure cancels its scope without reporting production closed`() {
        val closeFailure = IllegalStateException("runtime-close-failed")
        val runtime = mockk<DesktopReaderRuntime>()
        val ownedRuntimeJob = SupervisorJob()
        var productionClosedCallbacks = 0
        every { runtime.close() } throws closeFailure
        val model = ReaderScreenModel(
            runtime = runtime,
            ownedRuntimeScope = CoroutineScope(ownedRuntimeJob),
            onProductionClosed = { productionClosedCallbacks += 1 },
        )

        val thrown = assertThrows(IllegalStateException::class.java) { model.onDispose() }

        assertSame(closeFailure, thrown)
        assertFalse(ownedRuntimeJob.isActive)
        assertEquals(0, productionClosedCallbacks)
        verify(exactly = 1) { runtime.close() }
    }

    @Test
    fun `model without a production runtime still cancels its owned scope on disposal`() {
        val ownedRuntimeJob = SupervisorJob()
        var productionClosedCallbacks = 0
        val model = ReaderScreenModel(
            ownedRuntimeScope = CoroutineScope(ownedRuntimeJob),
            onProductionClosed = { productionClosedCallbacks += 1 },
        )

        model.onDispose()

        assertFalse(ownedRuntimeJob.isActive)
        assertEquals(0, productionClosedCallbacks)
    }

    @Test
    fun `production factory creates one shared core and exposes its canonical state to the model`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 17L,
            sourceId = 42L,
            chapterUrl = "/chapter/17",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 17",
            chapterNumber = 17.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )

        val runtime = factory.createRuntime(context, this)
        var model: ReaderScreenModel? = null
        try {
            val sessionState = runtime.session.state.value
            model = factory.createModel(
                runtime = runtime,
                isWebtoon = false,
                mangaViewerFlags = 0L,
                dualPageOverride = null,
            )

            assertNotNull(runtime.session.core)
            assertSame(runtime.session.core.snapshot, sessionState.snapshot)
            assertEquals(
                sessionState,
                mihon.desktop.reader.DesktopReaderSessionState(
                    model.state.value.context,
                    model.state.value.session,
                ),
            )
            assertInstanceOf(
                ReaderChapterLoadState.LoadingPageList::class.java,
                model.state.value.session.activeChapter.loadState,
            )

            val target = context.copy(
                chapterId = 18L,
                chapterUrl = "/chapter/18",
                chapterTitle = "Chapter 18",
                chapterNumber = 18.0,
            )
            val core = runtime.session.core
            model.activateChapter(target)

            assertSame(core, runtime.session.core)
            assertEquals(target, model.state.value.context)
            assertEquals(ReaderChapterId(18L), model.state.value.session.activeChapter.id)
            assertInstanceOf(
                ReaderChapterLoadState.LoadingPageList::class.java,
                model.state.value.session.activeChapter.loadState,
            )
        } finally {
            model?.onDispose() ?: runtime.close()
        }
    }

    @Test
    fun `production runtime image pipeline reads its own encoded page store`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-preloader-wiring")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-preloader-wiring"),
        )
        val localDirectory = tempDir.resolve("preloader-wiring-local").also { directory ->
            directory.mkdirs()
            directory.resolve("001.png").writeBytes(pngBytes())
        }
        val context = localContext(81L, localDirectory)
        val runtime = factory.createRuntime(context, this)
        try {
            advanceUntilIdle()
            val snapshot = runtime.session.state.value.snapshot
            val page = snapshot.activeChapter.pages.single()
            val ref = requireNotNull(page.encodedPageRef)

            val lease = requireNotNull(
                runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(page.id, snapshot.generation, ref),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            lease.close()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `production visible consumers share one image pipeline open and decode`() = runTest {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val now = AtomicLong()
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-content-owner")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-content-owner"),
            readerIoProbe = ReaderIoProbe(events::add),
            readerMonotonicClock = ReaderMonotonicClock(now::incrementAndGet),
        )
        val bytes = pngBytes()
        val localDirectory = tempDir.resolve("content-owner-local").also { directory ->
            directory.mkdirs()
            directory.resolve("001.png").writeBytes(bytes)
        }
        val runtime = factory.createRuntime(localContext(83L, localDirectory), this)
        try {
            advanceUntilIdle()
            val snapshot = runtime.session.state.value.snapshot
            val page = snapshot.activeChapter.pages.single()
            val ref = requireNotNull(page.encodedPageRef)
            val firstVisibleLease = requireNotNull(
                runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(page.id, snapshot.generation, ref),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            val secondVisibleLease = requireNotNull(
                runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(page.id, snapshot.generation, ref),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            try {
                assertSame(firstVisibleLease.asset.bitmap, secondVisibleLease.asset.bitmap)
                assertEquals(
                    1,
                    events.count { event ->
                        event.type == ReaderIoEventType.OPEN_PAGE &&
                            event.pageId == page.id &&
                            event.generation == snapshot.generation
                    },
                )
                assertEquals(
                    1,
                    events.count { event ->
                        event.type == ReaderIoEventType.DECODE &&
                            event.pageId == page.id &&
                            event.generation == snapshot.generation
                    },
                )
            } finally {
                secondVisibleLease.close()
                firstVisibleLease.close()
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `production runtime binds the independent 512 MiB encoded cache policy`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-encoded-budget")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-budget"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 82L,
                sourceId = 42L,
                chapterUrl = "/chapter/82",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 82",
                chapterNumber = 82.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )

        try {
            assertEquals(512L * 1024L * 1024L, runtime.encodedPageStore.diagnostics().maxBytes)
            assertEquals(
                DesktopReaderEncodedPageStore.DEFAULT_MAX_BYTES,
                runtime.encodedPageStore.diagnostics().maxBytes,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `production runtime binds and closes the default unified image memory policy`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-decoded-budget")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-decoded-budget"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 84L,
                sourceId = 42L,
                chapterUrl = "/chapter/84",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 84",
                chapterNumber = 84.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )

        try {
            val openSnapshot = runtime.pageImagePipeline.snapshot()
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_CACHE_ENTRIES, openSnapshot.cache.maxEntries)
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_TOTAL_IMAGE_BYTES, openSnapshot.cache.maxBytes)
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_TILE_CACHE_ENTRIES, openSnapshot.tileCache.maxEntries)
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_TOTAL_IMAGE_BYTES, openSnapshot.tileCache.maxBytes)
            assertEquals(
                DesktopReaderPageImagePipeline.DEFAULT_TOTAL_IMAGE_BYTES,
                openSnapshot.memory.maxBytes,
            )
        } finally {
            runtime.close()
        }

        val closedSnapshot = runtime.pageImagePipeline.snapshot()
        assertTrue(closedSnapshot.closed)
        assertTrue(closedSnapshot.cache.keys.isEmpty())
        assertEquals(0, closedSnapshot.cache.entryCount)
    }

    @Test
    fun `production factory coordinates encoded cache across concurrent reader runtimes`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-shared-cache")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-shared-cache"),
        )
        val first = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 51L,
                sourceId = 42L,
                chapterUrl = "/chapter/51",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 51",
                chapterNumber = 51.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )
        val second = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 52L,
                sourceId = 42L,
                chapterUrl = "/chapter/52",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 52",
                chapterNumber = 52.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )
        try {
            assertEquals(true, first.encodedPageStore.sharesCoordinatorWith(second.encodedPageStore))
        } finally {
            first.close()
            second.close()
        }
    }

    private fun archive(name: String, pageCount: Int): File = tempDir.resolve(name).also { archive ->
        ZipOutputStream(archive.outputStream()).use { output ->
            repeat(pageCount) { index ->
                output.putNextEntry(ZipEntry("${(index + 1).toString().padStart(3, '0')}.png"))
                output.write(byteArrayOf(1, 2, 3, index.toByte()))
                output.closeEntry()
            }
        }
    }

    private fun mountReaderScene(
        runtime: DesktopReaderRuntime,
        model: ReaderScreenModel,
        scope: CoroutineScope,
        legacy: Preferences,
        animated: Boolean = true,
    ): ImageComposeScene {
        val appPrefs = DesktopAppPreferences(InMemoryPreferenceStore(), legacy.node("app")).apply {
            pageTurnAnimation.set(animated)
        }
        val dependencies = mockk<DesktopUiDependencies> { every { appPreferences } returns appPrefs }
        model.toggleUI()
        return ImageComposeScene(700, 400, coroutineContext = scope.coroutineContext) {}.apply {
            setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme {
                        val current by model.state.collectAsState()
                        val focus = remember { FocusRequester() }
                        Box(Modifier.requiredSize(700.dp, 400.dp)) {
                            ReaderViewport(
                                current, model, mockk(relaxed = true), focus, scope, "", "",
                                runtime.presentationImageOwner, null, {}, {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sceneNodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.BLUE
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { output -> ImageIO.write(image, "png", output) }.toByteArray()
    }

    private fun sourceManager(sources: List<CatalogueSource>) = object : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(sources)
        override fun get(sourceKey: Long): eu.kanade.tachiyomi.source.Source? = null
        override fun getOrStub(sourceKey: Long): eu.kanade.tachiyomi.source.Source = StubSource(sourceKey, "", "")
        override fun getOnlineSources(): List<eu.kanade.tachiyomi.source.online.HttpSource> = emptyList()
        override fun getCatalogueSources(): List<CatalogueSource> = sources
        override fun getStubSources(): List<StubSource> = emptyList()
    }

    private fun localContext(chapterId: Long, path: File) = DesktopReaderChapterContext(
        chapterId = chapterId,
        sourceId = 42L,
        chapterUrl = path.absolutePath,
        mangaTitle = "Manga",
        chapterTitle = "Chapter $chapterId",
        chapterNumber = chapterId.toDouble(),
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
        localChapterPath = path.absolutePath,
    )
}
