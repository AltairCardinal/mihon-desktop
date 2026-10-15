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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.reader.DualPageDisplayUnitCompositionIdentityKey
import mihon.desktop.ui.reader.ReaderPagerNativePresentationKey
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.desktop.ui.reader.ReaderViewport
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.content.ReaderPageContentOpenCoordinator
import mihon.domain.reader.content.ReaderPageContentOpenPort
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences

/** The encoded HTTP image is painted by Skia before its original bounded metadata read returns. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SharedDualPageLateExifProductionTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `actual EXIF pixels can paint before the original late header confirms sample orientation`() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val uiScope = CoroutineScope(SupervisorJob() + dispatcher)
        val encoded = originalExif(6)
        val server = mockwebserver3.MockWebServer().apply {
            this.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest) =
                    mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(encoded)).build()
            }
            start()
        }
        val source = mockk<CatalogueSource>(relaxed = true) { every { id } returns 42L }
        coEvery { source.getPageList(any()) } returns List(3) {
            Page(it, imageUrl = server.url("/image/$it").toString())
        }
        val manager = object : SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources = flowOf(listOf(source))
            override fun get(sourceKey: Long): eu.kanade.tachiyomi.source.Source? = null
            override fun getOrStub(sourceKey: Long): eu.kanade.tachiyomi.source.Source = StubSource(sourceKey, "", "")
            override fun getOnlineSources(): List<eu.kanade.tachiyomi.source.online.HttpSource> = emptyList()
            override fun getCatalogueSources(): List<CatalogueSource> = listOf(source)
            override fun getStubSources(): List<StubSource> = emptyList()
        }
        val legacy = Preferences.userRoot().node("/mihon/late-original-exif/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = false
            autoSplitPages = false
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(directory.resolve("downloads")),
            manager,
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            directory.resolve("encoded"),
            readerIoProbe = ReaderIoProbe(events::add),
        )
        val originalRuntime = factory.createRuntime(
            DesktopReaderChapterContext(98, 42, "/chapter/98", "Manga", "EXIF", 98.0, 0, 1, false),
            scope,
        )
        val coordinator = DesktopReaderPageContentOwner::class.java.getDeclaredField("coordinator")
            .apply { isAccessible = true }
        val authority = coordinator.get(originalRuntime.pageContentOwner)
        val port = ReaderPageContentOpenCoordinator::class.java.getDeclaredField("port")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val originalPort = port.get(authority) as ReaderPageContentOpenPort<ByteArray>
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        port.set(
            authority,
            ReaderPageContentOpenPort<ByteArray> { request ->
                if (request.maxBytes != null && request.pageId.sourcePageIndex == 1) {
                    entered.complete(Unit)
                    release.await()
                    originalPort.open(request).also { returned.complete(Unit) }
                } else {
                    originalPort.open(request)
                }
            },
        )
        val runtime = originalRuntime
        assertSame(authority, coordinator.get(runtime.pageContentOwner))
        val model = factory.createModel(runtime, false, 0, true)
        val collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        val scene = mount(runtime, model, uiScope, legacy)
        var paintedColors = 0
        var artworkRectangle = emptyList<Int>()
        suspend fun pumpUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < end) {
                dispatcher.scheduler.runCurrent()
                scene.render(dispatcher.scheduler.currentTime * 1_000_000).use { image ->
                    val bitmap = image.toComposeImageBitmap().asSkiaBitmap()
                    try {
                        val points = (350 until 700 step 4).flatMap { x ->
                            (0 until 400 step 4).mapNotNull { y ->
                                val color = bitmap.getColor(x, y)
                                val channels = listOf(0, 8, 16).map { (color shr it) and 255 }
                                if (channels.max() - channels.min() > 100) x to y else null
                            }
                        }
                        paintedColors = points.size
                        artworkRectangle = if (points.isEmpty()) {
                            emptyList()
                        } else {
                            listOf(
                                points.minOf { it.first },
                                points.minOf { it.second },
                                points.maxOf { it.first },
                                points.maxOf { it.second },
                            )
                        }
                    } finally {
                        bitmap.close()
                    }
                }
                dispatcher.scheduler.advanceTimeBy(16)
                delay(10)
            }
        }
        try {
            pumpUntil {
                entered.isCompleted && paintedColors > 100 && events.any {
                    it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED && it.pageId?.sourcePageIndex == 1
                }
            }
            assertTrue(entered.isCompleted, "The original header capability must precede the real first paint")
            assertTrue(paintedColors > 100, "Actual oriented artwork must reach the native right-slot pixels")
            assertTrue(
                events.any {
                    it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED && it.pageId?.sourcePageIndex == 1
                },
            )
            assertTrue(!returned.isCompleted, "First pixels must precede the original encoded header read")
            assertEquals(
                ReaderImageOrientation.CLOCKWISE,
                model.state.value.dualPageIdentity?.content?.get(1)?.sourceOrientation,
            )
            val first = nativeIdentity(scene)
            val rectangle = artworkRectangle
            release.complete(Unit)
            pumpUntil {
                returned.isCompleted && model.state.value.dualPageIdentity?.content?.get(1)?.sourceOrientation ==
                    ReaderImageOrientation.CLOCKWISE
            }
            assertTrue(returned.isCompleted, "The delayed header must return through the original content authority")
            assertTrue(events.any { it.type == ReaderIoEventType.HEADER_READ && it.pageId?.sourcePageIndex == 1 })
            assertEquals(
                ReaderImageOrientation.CLOCKWISE,
                model.state.value.dualPageIdentity?.content?.get(1)?.sourceOrientation,
                "Actual first-pixel classification must preserve the original EXIF capability for later samples",
            )
            assertEquals(first.first, nativeIdentity(scene).first)
            assertSame(first.second, nativeIdentity(scene).second)
            assertEquals(rectangle, artworkRectangle)
            val page = model.state.value.session.activeChapter.pages[1]
            val content = requireNotNull(model.state.value.dualPageIdentity).content[1]
            val request = ReaderPageContentOpenRequest(
                page.id,
                model.state.value.session.generation,
                requireNotNull(page.encodedPageRef),
                page.attemptGeneration,
                page.encodedPageProvenance,
            )
            runtime.pageImagePipeline.openRegionSession(request).use { region ->
                val oriented = requireNotNull(region.withContent(SkiaImageDecoder::peekSize))
                val raw = content.sourceOrientation.orientedSize(oriented.first, oriented.second)
                val edges = requireNotNull(
                    DesktopReaderSpreadSampler.sample(
                        runtime.pageImagePipeline,
                        region,
                        raw.width,
                        raw.height,
                        content.sourceOrientation,
                    ),
                )
                val actual = listOf(edges.left.first(), edges.left.last(), edges.right.first(), edges.right.last())
                val expected = listOf(0xff0000ff, 0xffffff00, 0xffff0000, 0xff00ff00)
                actual.zip(expected).forEach { (pixel, color) ->
                    for (shift in listOf(0, 8, 16)) {
                        val distance = kotlin.math.abs((pixel ushr shift and 255) - (color ushr shift and 255).toInt())
                        assertTrue(distance < 30)
                    }
                }
            }
        } finally {
            release.complete(Unit)
            scene.close()
            collector.cancel()
            model.onDispose()
            originalRuntime.close()
            scope.cancel()
            uiScope.cancel()
            server.close()
            assertTrue(originalRuntime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
            assertSame(authority, coordinator.get(runtime.pageContentOwner))
            port.set(authority, originalPort)
            legacy.removeNode()
        }
    }

    private fun nativeIdentity(scene: ImageComposeScene): Pair<Any, Any> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.filter {
            it.boundsInRoot.contains(Offset(525f, 200f))
        }
        val frame = nodes.first { it.config.contains(ReaderPagerNativePresentationKey) }
            .config[ReaderPagerNativePresentationKey].frameId
        val mount = nodes.first { it.config.contains(DualPageDisplayUnitCompositionIdentityKey) }
            .config[DualPageDisplayUnitCompositionIdentityKey]
        return requireNotNull(frame) to mount
    }

    private fun originalExif(orientation: Int): ByteArray {
        val raw = requireNotNull(javaClass.classLoader.getResourceAsStream("reader/exif-corners-raw.jpg"))
            .use { it.readBytes() }
        val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.toByte() }
        return raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) +
            exif + raw.copyOfRange(2, raw.size)
    }

    private fun mount(
        runtime: DesktopReaderRuntime,
        model: ReaderScreenModel,
        scope: CoroutineScope,
        preferences: Preferences,
    ): ImageComposeScene {
        val appPrefs = DesktopAppPreferences(InMemoryPreferenceStore(), preferences.node("app"))
            .apply { pageTurnAnimation.set(false) }
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
}
