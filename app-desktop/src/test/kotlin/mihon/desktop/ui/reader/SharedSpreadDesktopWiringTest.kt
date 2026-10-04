package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.NextChapterPrefetchMode
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.SkiaDesktopReaderPageImageDecoder
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedSpreadDesktopWiringTest {
    @TempDir lateinit var temp: File

    @Test
    fun `actual setting event samples original encoded leases and changes shared visible layout`() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val legacy = Preferences.userRoot().node("/mihon/spread-wiring/${System.nanoTime()}")
        val enabledLegacy = legacy.node("previously-enabled").apply { putBoolean("autoSpreadMatching", true) }
        assertTrue(ReaderPreferences(InMemoryPreferenceStore(), enabledLegacy).isAutoSpreadMatching)
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        assertFalse(prefs.isAutoSpreadMatching)
        val server = mockwebserver3.MockWebServer().apply {
            this.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    val index = request.url.encodedPath.substringAfterLast('/').toInt()
                    return mockwebserver3.MockResponse.Builder().addHeader("Content-Type", "image/png")
                        .body(Buffer().write(image(index))).build()
                }
            }
            start()
        }
        val rejectRegions = java.util.concurrent.atomic.AtomicBoolean(false)
        val failedReads = java.util.concurrent.atomic.AtomicInteger()
        val gate = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<Unit>?>(null)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val activeRegions = java.util.concurrent.atomic.AtomicInteger()
        val peakRegions = java.util.concurrent.atomic.AtomicInteger()
        val native = SkiaDesktopReaderPageImageDecoder()
        val decoder = DesktopReaderPageImageDecoder { bytes, key ->
            val optional = key.purpose == PageDecodePurpose.REGION_TILE
            if (optional) {
                val active = activeRegions.incrementAndGet()
                peakRegions.updateAndGet { maxOf(it, active) }
            }
            try {
                if (key.purpose == PageDecodePurpose.REGION_TILE && rejectRegions.get()) {
                    failedReads.incrementAndGet()
                    null
                } else {
                    val paused = gate.get()
                    if (key.purpose == PageDecodePurpose.REGION_TILE && paused != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            entered.complete(Unit)
                            paused.await()
                            native.decode(bytes, key)
                        }
                    } else {
                        native.decode(bytes, key)
                    }
                }
            } finally {
                if (optional) activeRegions.decrementAndGet()
            }
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(temp.resolve("downloads")),
            FakeDesktopSourceManager(listOf(OriginalPageSource { server.url("/page/$it").toString() })),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            temp.resolve("cache"),
            pageImageDecoder = decoder,
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                87, 42, "/chapter", "M", "C", 1.0, 0, 0, false,
            ),
            scope,
        )
        val model = factory.createModel(runtime, false, 0, null)
        val collector = scope.launch { runtime.session.state.collect { model.acceptSessionState(it) } }
        runtime.session.start()
        val scene = ImageComposeScene(700, 1200, coroutineContext = scope.coroutineContext) {}
        fun mountSettings() = scene.setContent {
            MaterialTheme {
                Column {
                    val state by model.state.collectAsState()
                    GeneralTab(
                        currentMode = state.readingMode, isDualPage = state.dualPageMode,
                        autoSplitPages = state.autoSplitPages, isAutoSpreadMatching = state.autoSpreadMatching,
                        backgroundTheme = state.backgroundTheme, navigationMode = state.navigationMode,
                        zoomState = state.zoomState, onModeChange = {}, onDualPageChange = {},
                        onAutoSplitPagesChange = {},
                        onAutoSpreadMatchingChange = { model.setAutoSpreadMatching(it, prefs) },
                        onBackgroundThemeChange = {}, onNavigationModeChange = {}, onZoomChange = {},
                    )
                }
            }
        }
        suspend fun pump(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                dispatcher.scheduler.runCurrent()
                scene.render().close()
                delay(5)
            } while (!condition() && System.nanoTime() < deadline)
        }
        fun clickMatching() {
            val node = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first { node ->
                node.config.contains(SemanticsActions.OnClick) && flatten(node).any { child ->
                    child.config.contains(SemanticsProperties.Text) &&
                        child.config[SemanticsProperties.Text].any { it.text == "Auto Spread Matching" }
                }
            }
            requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        }
        fun owner(): SharedDualPageSession = ReaderScreenModel::class.java.getDeclaredField("dualSession").apply {
            isAccessible = true
        }.get(model) as SharedDualPageSession
        fun groups() = model.state.value.dualPageUnits.map { unit -> unit.pageIds.map { it.sourcePageIndex } }
        try {
            val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
            val dependencies =
                mockk<DesktopUiDependencies> { every { this@mockk.appPreferences } returns appPreferences }
            fun mountReader() = scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme {
                        val state by model.state.collectAsState()
                        ReaderContent(
                            state, model, scope, "M", "C", runtime.presentationImageOwner,
                            readerNav = null, onPrevChapter = {}, onNextChapter = {},
                        )
                    }
                }
            }
            mountReader()
            pump {
                model.state.value.dualPageUnits.all { it.certain } && model.state.value.dualPageUnits.size == 3 &&
                    runtime.presentationImageOwner.firstPresentedGeneration.value ==
                    model.state.value.session.generation
            }
            assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), groups())
            assertEquals(
                model.state.value.session.generation,
                runtime.presentationImageOwner.firstPresentedGeneration.value,
            )
            mountSettings()
            pump { true }
            // Remove the initial real presentation and its decoded cache; the matching pass must
            // still consume only original encoded region leases, without a displayed full bitmap.
            runtime.pageImagePipeline.clear()
            clickMatching()
            pump { listOf(2, 3) in groups() && owner().spreadSampleDependencies().isEmpty() }
            assertTrue(prefs.isAutoSpreadMatching, "Real setting event must persist through existing preference")
            assertTrue(listOf(2, 3) in groups(), "Original region samples must reach the shared owner")
            assertTrue(owner().spreadSampleDependencies().isEmpty())
            assertEquals(
                0,
                runtime.pageImagePipeline.snapshot().cache.entryCount,
                "Matching does not need a displayed full-page bitmap",
            )
            clickMatching()
            pump {
                groups() == listOf(listOf(0), listOf(1, 2), listOf(3, 4)) &&
                    runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty() &&
                    runtime.pageImagePipeline.snapshot().inFlightCount == 0
            }
            assertFalse(prefs.isAutoSpreadMatching)
            assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), groups())
            assertTrue(runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
            assertEquals(0, runtime.pageImagePipeline.snapshot().inFlightCount)
            assertTrue(model.state.value.session.activeChapter.pages.all { it.loadState is ReaderPageLoadState.Ready })
            val page = model.state.value.session.activeChapter.pages[2].id
            rejectRegions.set(true)
            model.retryPage(page)
            pump {
                val retried = model.state.value.session.activeChapter.pages[2]
                retried.attemptGeneration > 0 && retried.loadState is ReaderPageLoadState.Ready &&
                    runtime.session.foregroundRequestsIdle
            }
            assertTrue(model.state.value.session.activeChapter.pages[2].attemptGeneration > 0)
            clickMatching()
            pump { failedReads.get() > 0 && runtime.pageImagePipeline.snapshot().inFlightCount == 0 }
            assertTrue(model.state.value.session.activeChapter.pages[2].loadState is ReaderPageLoadState.Ready)
            assertEquals(ReaderPageFactState.PORTRAIT, owner().pageFact(page)?.state)
            assertTrue(page in owner().spreadSampleDependencies())
            val registeredAttempts = DesktopReaderPageImagePipeline::class.java.getDeclaredField("minimumPageAttempts")
                .apply { isAccessible = true }.get(runtime.pageImagePipeline)
            assertEquals(
                1,
                failedReads.get(),
                "Original Retry state=${model.state.value.session.activeChapter.pages[2]} " +
                    "content=${owner().identity.content[2]} deps=${owner().spreadSampleDependencies()} " +
                    "registeredAttempts=$registeredAttempts pipeline=${runtime.pageImagePipeline.snapshot()}",
            )
            for (index in listOf(1, 3, 2)) {
                model.goToPage(index)
                pump { model.state.value.currentPage == index }
            }
            assertEquals(1, failedReads.get(), "Unrelated reader events cannot loop on a failed original edge")
            model.retryPage(page)
            pump { failedReads.get() == 2 && runtime.pageImagePipeline.snapshot().inFlightCount == 0 }
            assertEquals(2, failedReads.get(), "Existing explicit retry admits one new attempt")
            rejectRegions.set(false)
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            gate.set(release)
            model.retryPage(page)
            pump { entered.isCompleted }
            assertTrue(entered.isCompleted)
            val pending = ReaderScreenModel::class.java.getDeclaredField("spreadPublisherJob").apply {
                isAccessible = true
            }.get(model) as kotlinx.coroutines.Job
            dispatcher.scheduler.advanceTimeBy(15_001)
            dispatcher.scheduler.runCurrent()
            assertFalse(pending.isCancelled, "Optional matching does not install a chapter-wide waiting deadline")
            assertEquals(1, activeRegions.get(), "The original region is genuinely still executing")
            clickMatching()
            pump { pending.isCancelled }
            assertTrue(pending.isCancelled, "The actual setting switch revokes the old caller")
            assertEquals(1, activeRegions.get(), "Caller cancellation does not end non-cooperative native work")
            model.goToPage(3)
            // The existing explicit Retry invalidates only this future page's confirmed sample;
            // reopening matching must genuinely demand different original bytes while old work runs.
            model.retryPage(model.state.value.session.activeChapter.pages[3].id)
            mountReader()
            pump { owner().displayedHistory.any { frame -> frame.drawnPageIds.any { it.sourcePageIndex == 3 } } }
            assertTrue(
                owner().displayedHistory.any { frame -> frame.drawnPageIds.any { it.sourcePageIndex == 3 } },
                "The actual current native image paints while the old optional decoder is hung",
            )
            assertEquals(3, model.state.value.currentPage)
            scene.render().use { actual ->
                actual.toComposeImageBitmap().asSkiaBitmap().use { pixels ->
                    val source3 = (0 until pixels.width step 20).count { x ->
                        pixels.getColor(x, pixels.height / 2) == 0xff695078.toInt()
                    }
                    assertTrue(source3 >= 5, "The original target PNG's actual native pixels are visible")
                }
            }
            mountSettings()
            pump { true }
            clickMatching()
            pump { peakRegions.get() > 1 }
            assertTrue(prefs.isAutoSpreadMatching)
            assertTrue(owner().spreadSampleDependencies().isNotEmpty())
            assertEquals(1, peakRegions.get(), "At most one original optional decoder may execute across toggle/retry")
            release.complete(Unit)
            pump { owner().spreadSampleDependencies().isEmpty() && activeRegions.get() == 0 }
            assertTrue(prefs.isAutoSpreadMatching)
            assertTrue(owner().spreadSampleDependencies().isEmpty(), "Physical completion wakes matching without Retry")
            assertEquals(1, peakRegions.get(), "Automatic recovery never overlaps two optional native decoders")
            assertFalse(listOf(2, 3) in groups(), "The revoked old sample cannot publish its former group")
            clickMatching()
            pump { !model.state.value.autoSpreadMatching }
            pump {
                pending.isCompleted && runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty() &&
                    runtime.pageImagePipeline.snapshot().inFlightCount == 0
            }
            assertTrue(pending.isCompleted)
            assertTrue(runtime.pageContentOwner.snapshot().activeLeaseCounts.isEmpty())
            assertEquals(0, runtime.pageImagePipeline.snapshot().inFlightCount)
            assertFalse(
                listOf(2, 3) in groups(),
                "A region finishing after the actual setting switch cannot publish matching",
            )
            assertEquals(
                3,
                model.state.value.dualPageLocation?.pageId?.sourcePageIndex,
                "A cancelled late sample cannot manufacture navigation",
            )
            model.setDualPageMode(false, prefs)
        } finally {
            gate.get()?.complete(Unit)
            scene.close()
            collector.cancel()
            model.onDispose()
            runtime.close()
            scope.cancel()
            server.close()
            legacy.removeNode()
            Locale.setDefault(previousLocale)
        }
    }

    private class OriginalPageSource(private val imageUrl: (Int) -> String) : CatalogueSource {
        override val id = 42L
        override val name = "original-matching-retry"
        override val lang = "en"
        override val supportsLatest = false

        @Suppress("unused")
        fun getClient() = OkHttpClient()

        @Suppress("unused")
        suspend fun getImageUrl(page: Page): String = imageUrl(page.index)
        override suspend fun getPageList(chapter: SChapter) = List(5) {
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

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
