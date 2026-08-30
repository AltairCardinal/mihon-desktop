package mihon.desktop.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.test.http.ReaderIoTestEvent
import mihon.desktop.test.http.ReaderIoTestModeBridge
import mihon.desktop.test.http.ReaderTestModeController
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderMonotonicClock
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class ReaderCriticalPathProductionTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `one and one hundred eighty page directories have the same production first frame cost`() = runTest {
        val traces = listOf(
            captureFirstFrame(Route.DOWNLOADED_DIRECTORY, pageCount = 1),
            captureFirstFrame(Route.DOWNLOADED_DIRECTORY, pageCount = 180),
            captureFirstFrame(Route.LOCAL_DIRECTORY, pageCount = 1),
            captureFirstFrame(Route.LOCAL_DIRECTORY, pageCount = 180),
        )

        traces.forEach(::assertCriticalPath)
        Route.entries.filter { it.isDirectory }.forEach { route ->
            val routeTraces = traces.filter { it.route == route }.sortedBy { it.pageCount }
            assertEquals(2, routeTraces.size)
            assertEquals(routeTraces.first().eventTypes, routeTraces.last().eventTypes)
            assertEquals(
                routeTraces.first().structuralCost,
                routeTraces.last().structuralCost,
            )
        }
    }

    @Test
    fun `archive and online production routes present current page before background gates`() = runTest {
        listOf(
            captureFirstFrame(Route.LOCAL_CBZ, pageCount = 180),
            captureFirstFrame(Route.ONLINE, pageCount = 180),
        ).forEach(::assertCriticalPath)
    }

    @Test
    fun `disabled content operation probe does not evaluate archive identity`() {
        var identityEvaluated = false

        DesktopReaderContentOperationProbe.None.record(
            kind = DesktopReaderContentOperationKind.ARCHIVE_SIGNATURE,
            itemIdentity = {
                identityEvaluated = true
                "native-archive-entry"
            },
        )

        assertFalse(identityEvaluated)
    }

    private suspend fun TestScope.captureFirstFrame(
        route: Route,
        pageCount: Int,
    ): FirstFrameTrace {
        val caseRoot = tempDir.resolve("${route.name.lowercase()}-$pageCount")
        val fixture = ReaderProductionTestFixture(caseRoot, currentCoroutineContext())
        val downloadProvider = DesktopDownloadProvider(caseRoot.resolve("downloads"))
        val localChapterPath = when (route) {
            Route.DOWNLOADED_DIRECTORY -> {
                writePages(
                    downloadProvider.chapterDownloadDir(SOURCE_ID, MANGA_TITLE, CHAPTER_TITLE),
                    pageCount,
                    fixture.pageBytes,
                )
                null
            }
            Route.LOCAL_DIRECTORY -> writePages(
                caseRoot.resolve("local/Chapter 1"),
                pageCount,
                fixture.pageBytes,
            ).absolutePath
            Route.LOCAL_CBZ -> createCbz(
                caseRoot.resolve("local/Chapter 1.cbz"),
                pageCount,
                fixture.pageBytes,
            ).absolutePath
            Route.ONLINE -> null
        }
        val source = if (route == Route.ONLINE) {
            fixture.server.enqueue(MockResponse.Builder().body(Buffer().write(fixture.pageBytes)).build())
            CriticalPathSource(
                pageCount = pageCount,
                imageUrl = fixture.server.url("/current.png").toString(),
                httpClient = OkHttpClient(),
            )
        } else {
            null
        }
        val sourceManager = FakeDesktopSourceManager(listOfNotNull(source))
        val controller = ReaderTestModeController()
        val contentOperations = CopyOnWriteArrayList<DesktopReaderContentOperation>()
        ReaderIoTestModeBridge.install(controller)
        var now = 0L
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = downloadProvider,
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = caseRoot.resolve("encoded"),
            readerIoProbe = ReaderIoTestModeBridge,
            readerMonotonicClock = ReaderMonotonicClock { ++now },
            readerIoGate = fixture.ioGate,
            readerContentOperationProbe = DesktopReaderContentOperationProbe(contentOperations::add),
        )
        val screen = DesktopReaderScreen(
            chapterTitle = CHAPTER_TITLE,
            mangaTitle = MANGA_TITLE,
            sourceId = SOURCE_ID,
            chapterUrl = "/current",
            chapterId = CURRENT_CHAPTER_ID,
            localChapterPath = localChapterPath,
            chapters = listOf(
                ReaderChapterRef(
                    id = NEXT_CHAPTER_ID,
                    url = "/next",
                    name = "Chapter 2",
                    chapterNumber = 2.0,
                ),
                ReaderChapterRef(
                    id = CURRENT_CHAPTER_ID,
                    url = "/current",
                    name = CHAPTER_TITLE,
                    chapterNumber = 1.0,
                ),
            ),
            currentChapterIndex = 1,
        )
        val previousInjekt = Injekt
        try {
            patchInjekt()
            Injekt.addSingleton(factory)
            fixture.scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides mockk<DesktopUiDependencies>(relaxed = true),
                ) {
                    MaterialTheme { Navigator(screen) { screen.Content() } }
                }
            }
            runCurrent()
            withTimeout(5_000) {
                while (controller.snapshot().none { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name }) {
                    fixture.scene.render()
                    runCurrent()
                }
            }
            val firstFrameEvents = controller.snapshot()
            val requiredGates = setOf(ReaderIoGatePoint.CACHE_SCAN)
            var gatePumpAttempts = 0
            while (requiredGates.any { !fixture.gate(it).isEntered } && gatePumpAttempts < 100) {
                fixture.scene.render()
                runCurrent()
                gatePumpAttempts += 1
            }
            val enteredGates = requiredGates.filterTo(mutableSetOf()) { fixture.gate(it).isEntered }
            assertEquals(
                requiredGates,
                enteredGates,
                "$route did not enter reader I/O gates before the first-frame trace was captured",
            )
            return FirstFrameTrace(
                route = route,
                pageCount = pageCount,
                events = firstFrameEvents,
                sourcePageListCalls = source?.pageListCalls ?: 0,
                imageRequests = fixture.server.requestCount,
                contentOperations = contentOperations.toList(),
                enteredGates = enteredGates,
            )
        } finally {
            ReaderIoGatePoint.entries.forEach { fixture.gate(it).release() }
            fixture.close()
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
            Injekt = previousInjekt
        }
    }

    private fun assertCriticalPath(trace: FirstFrameTrace) {
        val events = trace.events
        val types = trace.eventTypes
        assertEquals(ReaderIoEventType.OPEN_READER_INTENT.name, types.first())
        assertEquals(ReaderIoEventType.FIRST_PAGE_PRESENTED.name, types.last())
        assertEquals(1, types.count { it == ReaderIoEventType.PAGE_LIST_READY.name })
        assertEquals(1, trace.currentPageDecodeCount)
        assertEquals(1, types.count { it == ReaderIoEventType.DECODE.name })
        assertTrue(ReaderIoEventType.OPEN_PAGE.name in types)
        assertTrue(ReaderIoEventType.CACHE_RECONCILE.name !in types)
        assertTrue(ReaderIoEventType.ADJACENT_IO.name !in types)
        assertTrue(
            events.none { event ->
                event.pageIndex != null && event.pageIndex != 0 &&
                    event.type in setOf(ReaderIoEventType.OPEN_PAGE.name, ReaderIoEventType.DECODE.name)
            },
        )
        assertEquals(1, events.single { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name }.generation)
        when (trace.route) {
            Route.ONLINE -> {
                assertEquals(1, trace.sourcePageListCalls)
                assertEquals(1, trace.imageRequests)
            }
            else -> {
                assertEquals(0, trace.sourcePageListCalls)
                assertEquals(0, trace.imageRequests)
            }
        }
        assertTrue(ReaderIoGatePoint.CACHE_SCAN in trace.enteredGates)
        when (trace.route) {
            Route.DOWNLOADED_DIRECTORY,
            Route.LOCAL_DIRECTORY,
            -> assertTrue(trace.contentOperations.isEmpty())
            Route.LOCAL_CBZ -> assertEquals(
                listOf(DesktopReaderContentOperationKind.ARCHIVE_PAGE_COPY to 0),
                trace.contentOperations.map { it.kind to it.pageIndex },
            )
            Route.ONLINE -> assertTrue(trace.contentOperations.isEmpty())
        }
    }

    private fun writePages(
        directory: File,
        pageCount: Int,
        bytes: ByteArray,
    ): File = directory.also {
        it.mkdirs()
        repeat(pageCount) { index ->
            it.resolve("${(index + 1).toString().padStart(3, '0')}.png").writeBytes(bytes)
        }
    }

    private fun createCbz(
        archive: File,
        pageCount: Int,
        bytes: ByteArray,
    ): File = archive.also {
        it.parentFile.mkdirs()
        ZipOutputStream(it.outputStream().buffered()).use { output ->
            repeat(pageCount) { index ->
                output.putNextEntry(ZipEntry("${(index + 1).toString().padStart(3, '0')}.png"))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private enum class Route(val isDirectory: Boolean = false) {
        DOWNLOADED_DIRECTORY(isDirectory = true),
        LOCAL_DIRECTORY(isDirectory = true),
        LOCAL_CBZ,
        ONLINE,
    }

    private data class FirstFrameTrace(
        val route: Route,
        val pageCount: Int,
        val events: List<ReaderIoTestEvent>,
        val sourcePageListCalls: Int,
        val imageRequests: Int,
        val contentOperations: List<DesktopReaderContentOperation>,
        val enteredGates: Set<ReaderIoGatePoint>,
    ) {
        val eventTypes = events.map(ReaderIoTestEvent::type)
        val currentPageDecodeCount = events.count {
            it.type == ReaderIoEventType.DECODE.name &&
                it.chapterId == CURRENT_CHAPTER_ID &&
                it.pageIndex == 0 &&
                it.generation == 1L
        }

        val structuralCost = events.size + contentOperations.size + sourcePageListCalls + imageRequests
    }

    private class CriticalPathSource(
        private val pageCount: Int,
        private val imageUrl: String,
        private val httpClient: OkHttpClient,
    ) : CatalogueSource {
        override val id = SOURCE_ID
        override val name = "critical-path-source"
        override val lang = "en"
        override val supportsLatest = false
        var pageListCalls = 0
            private set

        @Suppress("unused")
        fun getClient(): OkHttpClient = httpClient

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            pageListCalls++
            return List(pageCount) { index -> Page(index, url = "/page/$index", imageUrl = imageUrl) }
        }

        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override fun getFilterList(): FilterList = FilterList()
    }

    private companion object {
        const val SOURCE_ID = 42L
        const val CURRENT_CHAPTER_ID = 7L
        const val NEXT_CHAPTER_ID = 8L
        const val MANGA_TITLE = "Manga"
        const val CHAPTER_TITLE = "Chapter 1"
    }
}
