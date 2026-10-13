package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderDualPageFactBudget
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.OkHttpClient
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.prefs.Preferences
import javax.imageio.ImageIO

/** Diagnostic only: the counted component port below is not a production permit wiring change. */
class DesktopReaderReadyFactReadBudgetResearchTest {
    @TempDir lateinit var directory: File

    @Test
    fun `real local Ready admission does not consume the optional byte permit`() = runBlocking {
        val pages = directory.resolve("pages").apply { mkdirs() }
        png(pages.resolve("001.png"))
        val legacy = Preferences.userRoot().node("/mihon/ready-budget-research/${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs,
            DesktopDownloadProvider(directory.resolve("downloads")),
            mockk<SourceManager>(relaxed = true),
            NetworkHelper(OkHttpClient()),
            mockk<ReaderProgressTracker>(relaxed = true),
            null,
            directory.resolve("cache"),
        )
        val context = DesktopReaderChapterContext(
            87, 42, pages.absolutePath, "Fixture", "Chapter", 1.0, 0, 0, false,
            localChapterPath = pages.absolutePath,
        )
        val runtime = factory.createRuntime(context, scope)
        val budget = ReaderDualPageFactBudget()
        try {
            val ready = withTimeout(10_000) {
                runtime.session.state.first {
                    it.snapshot.activeChapter.pages.singleOrNull()?.loadState is ReaderPageLoadState.Ready
                }
            }.snapshot.activeChapter.pages.single()
            assertTrue(runtime.pageImagePipeline.snapshot().cache.keys.isEmpty())
            assertEquals(listOf(ready.id), budget.reserve(listOf(ready.id), 0))
            val permit = ReaderDualPageFactReadPermit(budget, ready.id) { 0 }
            assertTrue(runtime.session.loadFactPage(ready.id, permit))
            assertEquals(0L, meteredBytes(budget), "Ready admission does not open or meter its encoded file")
            assertTrue(runtime.pageImagePipeline.snapshot().cache.keys.isEmpty(), "Ready is not a decoded cache hit")
        } finally {
            budget.close()
            runtime.close()
            scope.cancel()
            legacy.removeNode()
        }
    }

    @Test
    fun `normal pipeline reads local bytes outside the permit while a bound source meters them`() = runBlocking {
        val file = png(directory.resolve("original.png"))
        val page = ReaderPageId(ReaderChapterId(87), 0)
        for (bindPermit in listOf(false, true)) {
            val budget = ReaderDualPageFactBudget()
            assertEquals(listOf(page), budget.reserve(listOf(page), 0))
            val permit = ReaderDualPageFactReadPermit(budget, page) { 0 }
            val bytesRead = AtomicLong()
            val opens = AtomicInteger()
            val closes = AtomicInteger()
            val reporter = ReaderIoReporter(clock = ReaderMonotonicClock(System::nanoTime))
            // This is the existing content-owner injection boundary. The real File Source is
            // counted before buffering; only the control arm explicitly attaches the permit.
            val contentOwner = DesktopReaderPageContentOwner(
                scope = this,
                encodedPageReader = { ref ->
                    assertEquals(file.toURI().toString(), ref.value)
                    opens.incrementAndGet()
                    val counted = object : ForwardingSource(file.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long =
                            super.read(sink, byteCount).also { if (it > 0) bytesRead.addAndGet(it) }

                        override fun close() {
                            closes.incrementAndGet()
                            super.close()
                        }
                    }
                    val source = if (bindPermit) permit.bound(counted, System::nanoTime) else counted
                    source.buffer().use { it.readByteArray() }
                },
                ioReporter = reporter,
            )
            // No decoder override: this exercises the normal production Skia decoder and cache.
            val pipeline = DesktopReaderPageImagePipeline(this, contentOwner, reporter)
            val key = ReaderPageDecodeKey(
                ReaderPageContentOpenRequest(page, 1, EncodedPageRef(file.toURI().toString())),
                PageDecodePurpose.FULL_PAGE,
                512,
                512,
            )
            try {
                assertTrue(pipeline.snapshot().cache.keys.isEmpty())
                requireNotNull(pipeline.acquire(key)).use {
                    assertEquals(80, it.asset.sourceWidth)
                    assertEquals(120, it.asset.sourceHeight)
                    assertTrue(it.asset.originalSamples.isNotEmpty())
                }
                assertEquals(file.length(), bytesRead.get(), "Count real Source reads, not returned array size")
                assertTrue(bytesRead.get() > 0)
                assertEquals(if (bindPermit) bytesRead.get() else 0L, meteredBytes(budget))
                assertEquals(1, opens.get())
                assertEquals(1, closes.get())
                requireNotNull(pipeline.acquire(key)).close()
                assertEquals(1, opens.get(), "A decoded cache hit performs no second file read")
                assertEquals(file.length(), bytesRead.get())
                assertEquals(1, closes.get())
                assertTrue(contentOwner.snapshot().activeLeaseCounts.isEmpty())
                println("READY_FACT_READ bound=$bindPermit physical=${bytesRead.get()} metered=${meteredBytes(budget)}")
            } finally {
                pipeline.close()
                contentOwner.close()
                budget.close()
            }
            assertEquals(1, closes.get(), "Closing owners must not close the physical source twice")
        }
    }

    private fun png(file: File): File = file.also {
        val image = BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB)
        assertTrue(ImageIO.write(image, "png", it))
    }

    private fun meteredBytes(budget: ReaderDualPageFactBudget): Long =
        ReaderDualPageFactBudget::class.java.getDeclaredField("newlyReadBytes").apply {
            isAccessible = true
        }.getLong(budget)
}
