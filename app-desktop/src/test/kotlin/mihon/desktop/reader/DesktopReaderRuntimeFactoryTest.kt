package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.browse.localReaderScreen
import mihon.desktop.ui.reader.ReaderLifecycleEffect
import mihon.desktop.ui.reader.ReaderModeState
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderRuntimeFactoryTest {

    @TempDir
    lateinit var tempDir: File

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

            coVerify(exactly = 0) { progressTracker.track(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
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
        val scene = ImageComposeScene(320, 240, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent { ReaderLifecycleEffect(runtime) }
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
            runtime.close()
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

        val model = factory.createScreenModel(
            initialContext = context,
            isWebtoon = false,
            mangaViewerFlags = 0L,
            dualPageOverride = null,
        )
        val runtime = requireNotNull(model.runtime)
        assertDoesNotThrow {
            runtime.session.activate(
                context.copy(chapterId = 42L, chapterUrl = "/chapter/42", chapterTitle = "Chapter 42"),
            )
        }

        model.onDispose()

        assertThrows(IllegalStateException::class.java) { runtime.session.activate(context) }
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
        try {
            val sessionState = runtime.session.state.value
            val model = factory.createModel(
                runtime = runtime,
                isWebtoon = false,
                mangaViewerFlags = 0L,
                dualPageOverride = null,
            )

            assertNotNull(runtime.session.core)
            assertSame(runtime.session.core.snapshot, sessionState.snapshot)
            assertEquals(sessionState, mihon.desktop.reader.DesktopReaderSessionState(model.state.value.context, model.state.value.session))
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
            runtime.close()
        }
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
}
