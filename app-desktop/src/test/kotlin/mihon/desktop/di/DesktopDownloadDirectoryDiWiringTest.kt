package mihon.desktop.di

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.download.DefaultDownloadFileOperations
import mihon.desktop.download.DesktopDownloadDirectoryController
import mihon.desktop.download.DesktopDownloadIdentityResolver
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadFileOperations
import mihon.desktop.download.DownloadItem
import mihon.desktop.platform.DesktopDownloadDirectoryAvailability
import mihon.desktop.platform.DesktopDownloadDirectorySelection
import mihon.desktop.platform.DesktopDownloadDirectoryState
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Isolated
class DesktopDownloadDirectoryDiWiringTest {

    @Test
    fun `default directory is frozen and shared by UI manager provider and reader`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val appDir = tempDir.resolve("profile")
        val context = initDesktopDIForTest(
            appDir = appDir,
            preferenceStore = isolatedDesktopPreferenceStore(),
            startDownloadWorker = false,
        )
        try {
            val state = Injekt.get<DesktopDownloadDirectoryState>()
            val controller = Injekt.get<DesktopDownloadDirectoryController>()
            val provider = Injekt.get<DesktopDownloadProvider>()
            val manager = Injekt.get<DesktopDownloadManager>()
            val reader = Injekt.get<DesktopReaderRuntimeFactory>()
            val ui = DesktopUiDependencies.fromInjekt()
            val expected = appDir.resolve("downloads").normalized()

            assertEquals(expected, state.defaultDirectory)
            assertNull(state.configuredDirectory)
            assertEquals(expected, state.activeDirectory)
            assertEquals(expected, state.pendingDirectory)
            assertEquals(DesktopDownloadDirectoryAvailability.UNKNOWN, state.availability)
            assertFalse(state.restartRequired)
            assertSame(state, controller.startupState)
            assertEquals(state, controller.currentState())
            assertSame(state, ui.downloadDirectoryState)
            assertSame(controller, ui.downloadDirectoryController)
            assertSame(manager, ui.downloadManager)
            assertSame(provider, manager.privateField("provider"))
            assertSame(provider, reader.privateField("downloadProvider"))
            assertProviderRoot(provider, expected)
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `valid missing custom directory stays active without startup filesystem access`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val appDir = tempDir.resolve("profile")
        val custom = tempDir.resolve("offline-parent/downloads").normalized()
        val store = isolatedDesktopPreferenceStore()
        seedConfiguredDirectory(store, custom.path)
        assertFalse(custom.exists())

        val context = initDesktopDIForTest(
            appDir = appDir,
            preferenceStore = store,
            startDownloadWorker = false,
        )
        try {
            val state = Injekt.get<DesktopDownloadDirectoryState>()
            val provider = Injekt.get<DesktopDownloadProvider>()

            assertEquals(custom, state.configuredDirectory)
            assertEquals(custom, state.activeDirectory)
            assertEquals(custom, state.pendingDirectory)
            assertEquals(DesktopDownloadDirectoryAvailability.UNKNOWN, state.availability)
            assertFalse(state.restartRequired)
            assertFalse(custom.exists())
            assertProviderRoot(provider, custom)
            assertFalse(custom.exists(), "Path construction must not probe or create an offline custom root")
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `custom directory drives real manager write and reader artifact lookup`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val appDir = tempDir.resolve("profile")
        val custom = tempDir.resolve("custom-downloads").normalized()
        val store = isolatedDesktopPreferenceStore()
        seedConfiguredDirectory(store, custom.path)
        val context = initDesktopDIForTest(
            appDir = appDir,
            preferenceStore = store,
            startDownloadWorker = true,
            downloadFileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: okhttp3.OkHttpClient, url: String): Response = Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(JPEG_BYTES.toResponseBody())
                    .build()
            },
        )
        try {
            val sourceId = 4242L
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = sourceId,
                        url = "/custom-root-manga",
                        title = "Custom root manga",
                    ),
                ),
            ).single()
            Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/custom-root-chapter",
                        name = "Custom root chapter",
                    ),
                ),
            )
            val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single()
            val item = DownloadItem(
                sourceId = sourceId,
                mangaTitle = manga.title,
                chapterName = chapter.name,
                chapterId = chapter.id,
                mangaId = manga.id,
                chapterUrl = chapter.url,
                pageUrls = listOf("https://fixture.invalid/001.jpg"),
            )
            val identity = Injekt.get<DesktopDownloadIdentityResolver>().resolve(manga, chapter)
            val manager = Injekt.get<DesktopDownloadManager>()
            val provider = Injekt.get<DesktopDownloadProvider>()

            manager.enqueue(item)
            withTimeout(5_000) {
                while (manager.queue.value.any { it.chapterId == chapter.id }) delay(10)
            }

            val downloadedPage = provider.canonicalChapterDownloadDir(identity).resolve("001.jpg")
            val defaultArtifact = DesktopDownloadProvider(appDir.resolve("downloads"))
                .canonicalChapterDownloadDir(identity)
            assertTrue(downloadedPage.isFile)
            assertTrue(downloadedPage.toPath().startsWith(custom.toPath()))
            assertFalse(defaultArtifact.exists())

            val readerRuntime = Injekt.get<DesktopReaderRuntimeFactory>().createRuntime(
                DesktopReaderChapterContext(
                    chapterId = chapter.id,
                    sourceId = sourceId,
                    chapterUrl = chapter.url,
                    mangaTitle = manga.title,
                    chapterTitle = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                    chapterIndex = 0,
                    initialPage = 0,
                    wasRead = false,
                    mangaId = manga.id,
                ),
                this,
            )
            try {
                withTimeout(5_000) {
                    while (readerRuntime.session.state.value.snapshot.activeChapter.loadState !is ReaderChapterLoadState.Loaded) {
                        delay(10)
                    }
                }
                val readerPage = readerRuntime.session.state.value.snapshot.activeChapter.pages.single()
                assertEquals(downloadedPage.toURI().toString(), readerPage.encodedPageRef?.value)
                assertEquals(ReaderPageLoadState.Ready, readerPage.loadState)
                assertFalse(defaultArtifact.exists())
            } finally {
                readerRuntime.close()
            }
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `saving a new directory keeps current graph frozen and restart activates it without migration`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val appDir = tempDir.resolve("profile")
        val oldRoot = tempDir.resolve("old-root").apply { mkdirs() }.normalized()
        val oldSentinel = oldRoot.resolve("keep.txt").apply { writeText("old") }
        val newRoot = tempDir.resolve("new-root").normalized()
        val store = isolatedDesktopPreferenceStore()
        seedConfiguredDirectory(store, oldRoot.path)

        val first = initDesktopDIForTest(appDir, store, startDownloadWorker = false)
        var restarted: DesktopTestDIContext? = null
        try {
            val frozenState = Injekt.get<DesktopDownloadDirectoryState>()
            val controller = Injekt.get<DesktopDownloadDirectoryController>()
            val frozenProvider = Injekt.get<DesktopDownloadProvider>()
            val frozenManager = Injekt.get<DesktopDownloadManager>()
            val frozenReader = Injekt.get<DesktopReaderRuntimeFactory>()
            val ui = DesktopUiDependencies.fromInjekt()
            val defaultSentinel = frozenState.defaultDirectory.resolve("default-keep.txt").apply { writeText("default") }

            assertInstanceOf(
                DesktopDownloadDirectorySelection.ValidCustom::class.java,
                ui.downloadPreferences.downloadDirectory(frozenState.defaultDirectory).save(newRoot.path),
            )

            val pendingState = controller.currentState()
            assertSame(frozenState, Injekt.get<DesktopDownloadDirectoryState>())
            assertSame(controller, Injekt.get<DesktopDownloadDirectoryController>())
            assertSame(frozenProvider, Injekt.get<DesktopDownloadProvider>())
            assertSame(frozenProvider, frozenManager.privateField("provider"))
            assertSame(frozenProvider, frozenReader.privateField("downloadProvider"))
            assertEquals(oldRoot, frozenState.activeDirectory)
            assertEquals(oldRoot, pendingState.activeDirectory)
            assertEquals(newRoot, pendingState.configuredDirectory)
            assertEquals(newRoot, pendingState.pendingDirectory)
            assertTrue(pendingState.restartRequired)
            assertProviderRoot(frozenProvider, oldRoot)
            assertEquals("old", oldSentinel.readText())
            assertEquals("default", defaultSentinel.readText())
            assertTrue(newRoot.isDirectory)
            assertFalse(newRoot.resolve("keep.txt").exists())

            first.closeAndJoin()
            restarted = initDesktopDIForTest(appDir, store, startDownloadWorker = false)

            val restartedState = Injekt.get<DesktopDownloadDirectoryState>()
            val restartedController = Injekt.get<DesktopDownloadDirectoryController>()
            val restartedProvider = Injekt.get<DesktopDownloadProvider>()
            val restartedManager = Injekt.get<DesktopDownloadManager>()
            val restartedReader = Injekt.get<DesktopReaderRuntimeFactory>()
            assertEquals(newRoot, restartedState.activeDirectory)
            assertEquals(newRoot, restartedState.pendingDirectory)
            assertFalse(restartedState.restartRequired)
            assertEquals(restartedState, restartedController.currentState())
            assertSame(restartedProvider, restartedManager.privateField("provider"))
            assertSame(restartedProvider, restartedReader.privateField("downloadProvider"))
            assertProviderRoot(restartedProvider, newRoot)
            assertEquals("old", oldSentinel.readText())
            assertEquals("default", defaultSentinel.readText())
            assertFalse(newRoot.resolve("keep.txt").exists())
        } finally {
            restarted?.closeAndJoin()
            first.closeAndJoin()
        }
    }

    @Test
    fun `restoring default keeps custom graph frozen until restart`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val appDir = tempDir.resolve("profile")
        val custom = tempDir.resolve("custom-root").apply { mkdirs() }.normalized()
        val sentinel = custom.resolve("keep.txt").apply { writeText("custom") }
        val store = isolatedDesktopPreferenceStore()
        seedConfiguredDirectory(store, custom.path)

        val first = initDesktopDIForTest(appDir, store, startDownloadWorker = false)
        var restarted: DesktopTestDIContext? = null
        try {
            val startupState = Injekt.get<DesktopDownloadDirectoryState>()
            val controller = Injekt.get<DesktopDownloadDirectoryController>()
            val provider = Injekt.get<DesktopDownloadProvider>()
            val manager = Injekt.get<DesktopDownloadManager>()
            val reader = Injekt.get<DesktopReaderRuntimeFactory>()

            DesktopUiDependencies.fromInjekt().downloadPreferences
                .downloadDirectory(startupState.defaultDirectory)
                .restoreDefault()

            val pendingState = controller.currentState()
            assertEquals(custom, pendingState.activeDirectory)
            assertNull(pendingState.configuredDirectory)
            assertEquals(startupState.defaultDirectory, pendingState.pendingDirectory)
            assertTrue(pendingState.restartRequired)
            assertSame(provider, manager.privateField("provider"))
            assertSame(provider, reader.privateField("downloadProvider"))
            assertProviderRoot(provider, custom)
            assertEquals("custom", sentinel.readText())

            first.closeAndJoin()
            restarted = initDesktopDIForTest(appDir, store, startDownloadWorker = false)

            val restartedState = Injekt.get<DesktopDownloadDirectoryState>()
            val restartedProvider = Injekt.get<DesktopDownloadProvider>()
            assertNull(restartedState.configuredDirectory)
            assertEquals(restartedState.defaultDirectory, restartedState.activeDirectory)
            assertEquals(restartedState.defaultDirectory, restartedState.pendingDirectory)
            assertFalse(restartedState.restartRequired)
            assertProviderRoot(restartedProvider, restartedState.defaultDirectory)
            assertEquals("custom", sentinel.readText())
        } finally {
            restarted?.closeAndJoin()
            first.closeAndJoin()
        }
    }

    @Test
    fun `invalid configured syntax falls back to default while retaining diagnostic and raw value`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val appDir = tempDir.resolve("profile")
        val store = isolatedDesktopPreferenceStore()
        val rawInvalidPath = "relative/downloads"
        seedConfiguredDirectory(store, rawInvalidPath)

        val context = initDesktopDIForTest(appDir, store, startDownloadWorker = false)
        try {
            val state = Injekt.get<DesktopDownloadDirectoryState>()
            val controller = Injekt.get<DesktopDownloadDirectoryController>()
            val provider = Injekt.get<DesktopDownloadProvider>()
            val expectedDefault = appDir.resolve("downloads").normalized()

            assertNull(state.configuredDirectory)
            assertEquals(expectedDefault, state.activeDirectory)
            assertEquals(expectedDefault, state.pendingDirectory)
            assertEquals(DesktopDownloadDirectoryAvailability.INVALID_SYNTAX, state.availability)
            assertInstanceOf(IllegalArgumentException::class.java, state.cause)
            assertFalse(state.restartRequired)
            assertEquals(state, controller.currentState())
            assertProviderRoot(provider, expectedDefault)
            assertSame(state, DesktopUiDependencies.fromInjekt().downloadDirectoryState)
            assertEquals(
                rawInvalidPath,
                store.getString(Preference.appStateKey("download_directory"), "").get(),
            )
        } finally {
            context.closeAndJoin()
        }
    }

    private fun seedConfiguredDirectory(store: DesktopPreferenceStore, rawPath: String) {
        store.getString(Preference.appStateKey("download_directory"), "").set(rawPath)
    }

    private fun assertProviderRoot(provider: DesktopDownloadProvider, expectedRoot: File) {
        assertEquals(
            expectedRoot.resolve("7/Manga/Chapter"),
            provider.chapterDownloadDir(7L, "Manga", "Chapter"),
        )
    }

    private fun File.normalized(): File = toPath().toAbsolutePath().normalize().toFile()

    private fun Any.privateField(name: String): Any? = javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(this)

    private companion object {
        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
