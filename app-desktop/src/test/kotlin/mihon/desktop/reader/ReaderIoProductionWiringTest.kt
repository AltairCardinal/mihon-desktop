package mihon.desktop.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.desktop.test.http.ReaderIoTestModeBridge
import mihon.desktop.test.http.ReaderIoTestEvent
import mihon.desktop.test.http.ReaderTestModeController
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class ReaderIoProductionWiringTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `production local reader reports intent page list decode and first drawn page in order`() = runTest {
        val fixture = ReaderProductionTestFixture(tempDir, currentCoroutineContext())
        val controller = ReaderTestModeController()
        ReaderIoTestModeBridge.install(controller)
        var now = 0L
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded"),
            readerIoProbe = ReaderIoTestModeBridge,
            readerMonotonicClock = ReaderMonotonicClock { ++now },
            readerIoGate = fixture.ioGate,
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            mangaTitle = "Manga",
            sourceId = 42L,
            chapterId = 7L,
            localChapterPath = fixture.downloadedDirectory.absolutePath,
            chapters = listOf(
                ReaderChapterRef(id = 8L, url = "/next", name = "Chapter 2", chapterNumber = 2.0),
                ReaderChapterRef(id = 7L, url = "/current", name = "Chapter 1", chapterNumber = 1.0),
            ),
            currentChapterIndex = 1,
        )
        val previousInjekt = Injekt
        val uiDependencies = mockk<DesktopUiDependencies>(relaxed = true)
        val scene = fixture.scene
        try {
            patchInjekt()
            Injekt.addSingleton(factory)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides uiDependencies) {
                    MaterialTheme { Navigator(screen) { screen.Content() } }
                }
            }
            runCurrent()
            assertTrue(controller.snapshot().none { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name })

            withTimeout(5_000) {
                while (controller.snapshot().none { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name }) {
                    scene.render()
                    runCurrent()
                }
            }
            listOf(ReaderIoGatePoint.CACHE_SCAN, ReaderIoGatePoint.ADJACENT_IO).forEach { point ->
                withTimeout(5_000) { fixture.gate(point).awaitEntered() }
            }
            assertFalse(
                fixture.gate(ReaderIoGatePoint.NON_CURRENT_PAGE).isEntered,
                "The shared image pipeline must not restore PagePreloader-owned background scheduling",
            )
            val firstFrameEvents = controller.snapshot()
            assertTrue(firstFrameEvents.none { it.type == ReaderIoEventType.CACHE_RECONCILE.name })
            assertTrue(firstFrameEvents.none { it.type == ReaderIoEventType.ADJACENT_IO.name })
            assertTrue(
                firstFrameEvents.none {
                    it.pageIndex != null && it.pageIndex != 0 &&
                        it.type in setOf(ReaderIoEventType.OPEN_PAGE.name, ReaderIoEventType.DECODE.name)
                },
            )
            ReaderIoGatePoint.entries.forEach { fixture.gate(it).release() }
            advanceUntilIdle()

            val events = controller.snapshot()
            val types = events.map(ReaderIoTestEvent::type)
            assertEquals(ReaderIoEventType.OPEN_READER_INTENT.name, types.first())
            assertTrue(ReaderIoEventType.CACHE_RECONCILE.name in types)
            assertTrue(ReaderIoEventType.PAGE_LIST_READY.name in types)
            assertTrue(ReaderIoEventType.OPEN_PAGE.name in types)
            assertTrue(ReaderIoEventType.DECODE.name in types)
            assertTrue(
                events.any {
                    it.type == ReaderIoEventType.ADJACENT_IO.name &&
                        it.chapterId == 8L &&
                        it.purpose == "ADJACENT_PREFETCH"
                },
            )
            assertTrue(
                events.none {
                    it.chapterId == 7L && it.pageIndex != null && it.pageIndex != 0 &&
                        it.type in setOf(ReaderIoEventType.OPEN_PAGE.name, ReaderIoEventType.DECODE.name)
                },
            )
            assertTrue(
                types.indexOf(ReaderIoEventType.DECODE.name) <
                    types.indexOf(ReaderIoEventType.FIRST_PAGE_PRESENTED.name),
            )
            assertEquals(7L, events.first { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name }.chapterId)
            assertEquals(0, events.first { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name }.pageIndex)
        } finally {
            ReaderIoGatePoint.entries.forEach { fixture.gate(it).release() }
            fixture.close()
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
            Injekt = previousInjekt
        }
    }
}
