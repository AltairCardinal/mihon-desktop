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
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.test.http.ReaderIoTestModeBridge
import mihon.desktop.test.http.ReaderTestModeController
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.observability.ReaderMonotonicClock
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okio.Buffer
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

enum class MountedReaderPresentationMode(
    val isWebtoon: Boolean,
    val isDualPage: Boolean,
    val initialPage: Int,
    val visiblePageIndices: Set<Int>,
) {
    SINGLE(
        isWebtoon = false,
        isDualPage = false,
        initialPage = 0,
        visiblePageIndices = setOf(0),
    ),
    DUAL(
        isWebtoon = false,
        isDualPage = true,
        // Page zero is the cover singleton. Starting at one exercises a real two-slot spread.
        initialPage = 1,
        visiblePageIndices = setOf(1, 2),
    ),
    WEBTOON(
        isWebtoon = true,
        isDualPage = false,
        initialPage = 0,
        visiblePageIndices = setOf(0),
    ),
}

enum class MountedReaderContentRoute {
    DIRECTORY,
    CBZ,
    ONLINE,
}

data class MountedReaderPresentationCase(
    val mode: MountedReaderPresentationMode,
    val route: MountedReaderContentRoute,
) {
    override fun toString(): String = "${mode.name.lowercase()}-${route.name.lowercase()}"
}

@OptIn(ExperimentalComposeUiApi::class)
internal class MountedReaderPresentationFixture(
    root: File,
    coroutineContext: CoroutineContext,
    val case: MountedReaderPresentationCase,
    pageImageDecoder: DesktopReaderPageImageDecoder = SkiaDesktopReaderPageImageDecoder(),
    private val pageCount: Int = 4,
    mangaId: Long = 0L,
    pairingCoordinator: DesktopChapterPairingCoordinator? = null,
    initialPage: Int = case.mode.initialPage,
) : AutoCloseable {
    private val productionFixture = ReaderProductionTestFixture(root, coroutineContext)
    private val previousInjekt = Injekt
    private val controller = ReaderTestModeController()
    private val source = if (case.route == MountedReaderContentRoute.ONLINE) {
        repeat(pageCount) {
            productionFixture.server.enqueue(
                MockResponse.Builder().body(Buffer().write(productionFixture.pageBytes)).build(),
            )
        }
        MountedOnlineReaderSource(
            pageCount = pageCount,
            imageUrl = { index -> productionFixture.server.url("/page/$index.png").toString() },
            httpClient = OkHttpClient(),
        )
    } else {
        null
    }
    private val localChapterPath = when (case.route) {
        MountedReaderContentRoute.DIRECTORY -> createDirectory(root.resolve("local/Chapter 1"))
        MountedReaderContentRoute.CBZ -> createCbz(root.resolve("local/Chapter 1.cbz"))
        MountedReaderContentRoute.ONLINE -> null
    }
    private val screen = DesktopReaderScreen(
        chapterTitle = CHAPTER_TITLE,
        mangaTitle = MANGA_TITLE,
        isWebtoon = case.mode.isWebtoon,
        sourceId = SOURCE_ID,
        chapterUrl = CHAPTER_URL,
        chapterId = CHAPTER_ID,
        mangaId = mangaId,
        initialPage = initialPage,
        isDualPage = case.mode.isDualPage,
        localChapterPath = localChapterPath,
    )
    private val testAppPreferences = DesktopAppPreferences(InMemoryPreferenceStore())

    val scene = productionFixture.scene
    val allPageIndices: Set<Int> = (0 until pageCount).toSet()
    val visiblePageIndices: Set<Int> = case.mode.visiblePageIndices
    val mountedPageIndices: Set<Int> = when (case.mode) {
        MountedReaderPresentationMode.SINGLE -> (visiblePageIndices + 1).intersect(allPageIndices)
        // The four-page fixture is cover 0, visible spread 1/2, and adjacent spread 3.
        MountedReaderPresentationMode.DUAL -> setOf(0, 1, 2, 3).intersect(allPageIndices)
        MountedReaderPresentationMode.WEBTOON -> visiblePageIndices
    }

    init {
        ReaderIoTestModeBridge.install(controller)
        var now = 0L
        val runtimeFactory = DesktopReaderRuntimeFactory(
            prefs = productionFixture.readerPreferences,
            downloadProvider = DesktopDownloadProvider(root.resolve("downloads")),
            sourceManager = FakeDesktopSourceManager(listOfNotNull(source)),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = root.resolve("encoded"),
            readerIoProbe = ReaderIoTestModeBridge,
            readerMonotonicClock = ReaderMonotonicClock { ++now },
            readerIoGate = productionFixture.ioGate,
            pageImageDecoder = pageImageDecoder,
            pairingCoordinator = pairingCoordinator,
        )
        patchInjekt()
        Injekt.addSingleton(runtimeFactory)
        scene.setContent {
            CompositionLocalProvider(
                LocalDesktopUiDependencies provides mockk<DesktopUiDependencies>(relaxed = true) {
                    every { appPreferences } returns testAppPreferences
                },
            ) {
                MaterialTheme { Navigator(screen) { screen.Content() } }
            }
        }
    }

    fun events() = controller.snapshot()

    fun httpRequestCount() = productionFixture.server.requestCount

    fun releaseBackgroundGates(excluding: Set<ReaderIoGatePoint> = emptySet()) {
        ReaderIoGatePoint.entries.filterNot(excluding::contains).forEach { productionFixture.gate(it).release() }
    }

    override fun close() {
        releaseBackgroundGates()
        productionFixture.close()
        ReaderIoTestModeBridge.clear(controller)
        controller.close()
        Injekt = previousInjekt
    }

    private fun createDirectory(directory: File): String = directory.also {
        it.mkdirs()
        repeat(pageCount) { index ->
            it.resolve("${(index + 1).toString().padStart(3, '0')}.png")
                .writeBytes(productionFixture.pageBytes)
        }
    }.absolutePath

    private fun createCbz(archive: File): String = archive.also {
        it.parentFile.mkdirs()
        ZipOutputStream(it.outputStream().buffered()).use { output ->
            repeat(pageCount) { index ->
                output.putNextEntry(ZipEntry("${(index + 1).toString().padStart(3, '0')}.png"))
                output.write(productionFixture.pageBytes)
                output.closeEntry()
            }
        }
    }.absolutePath

    private class MountedOnlineReaderSource(
        private val pageCount: Int,
        private val imageUrl: (Int) -> String,
        private val httpClient: OkHttpClient,
    ) : CatalogueSource {
        override val id = SOURCE_ID
        override val name = "mounted-reader-source"
        override val lang = "en"
        override val supportsLatest = false

        @Suppress("unused")
        fun getClient(): OkHttpClient = httpClient

        override suspend fun getPageList(chapter: SChapter): List<Page> =
            List(pageCount) { index ->
                Page(index = index, url = "/page/$index", imageUrl = imageUrl(index))
            }

        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override fun getFilterList(): FilterList = FilterList()
    }

    companion object {
        const val SOURCE_ID = 42L
        const val CHAPTER_ID = 7L
        const val CHAPTER_URL = "/current"
        const val MANGA_TITLE = "Manga"
        const val CHAPTER_TITLE = "Chapter 1"
    }
}
