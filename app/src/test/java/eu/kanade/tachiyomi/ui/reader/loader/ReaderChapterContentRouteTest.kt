package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.archive.ArchiveReader
import mihon.core.archive.EpubReader
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.content.ReaderChapterRoute
import mihon.domain.reader.content.ReaderChapterRouteResolver
import mihon.domain.reader.content.ReaderSourceContentKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

class ReaderChapterContentRouteTest {

    @Test
    fun `download route wins before source-specific dispatch`() {
        val route = selectReaderChapterContentRoute(
            downloaded = true,
            source = mockk<Source>(),
        )

        assertInstanceOf(ReaderChapterContentRoute.Download::class.java, route)
        assertEquals(ReaderChapterRoute.DOWNLOAD, route.sharedRoute)
    }

    @Test
    fun `online source dispatches to HTTP content`() {
        val route = selectReaderChapterContentRoute(
            downloaded = false,
            source = mockk<HttpSource>(),
        )

        assertInstanceOf(ReaderChapterContentRoute.Online::class.java, route)
        assertEquals(ReaderChapterRoute.ONLINE, route.sharedRoute)
    }

    @Test
    fun `local directory archive and epub retain their distinct dispatch`() {
        val source = mockk<LocalSource>()
        val file = mockk<UniFile>()

        val directory = selectReaderChapterContentRoute(false, source) { Format.Directory(file) }
        val archive = selectReaderChapterContentRoute(false, source) { Format.Archive(file) }
        val epub = selectReaderChapterContentRoute(false, source) { Format.Epub(file) }

        assertInstanceOf(ReaderChapterContentRoute.LocalDirectory::class.java, directory)
        assertInstanceOf(ReaderChapterContentRoute.LocalArchive::class.java, archive)
        assertInstanceOf(ReaderChapterContentRoute.LocalEpub::class.java, epub)
        assertEquals(ReaderChapterRoute.LOCAL_DIRECTORY, directory.sharedRoute)
        assertEquals(ReaderChapterRoute.LOCAL_ARCHIVE, archive.sharedRoute)
        assertEquals(ReaderChapterRoute.LOCAL_EPUB, epub.sharedRoute)
    }

    @Test
    fun `android production selector delegates the final route decision to the shared resolver`() {
        var receivedDownloadFact: Boolean? = null
        var receivedSourceKind: ReaderSourceContentKind? = null
        val resolver = ReaderChapterRouteResolver { downloadLocated, sourceKind ->
            receivedDownloadFact = downloadLocated
            receivedSourceKind = sourceKind
            ReaderChapterRoute.UNSUPPORTED
        }

        val route = selectReaderChapterContentRoute(
            downloaded = true,
            source = mockk<HttpSource>(),
            routeResolver = resolver,
        )

        assertInstanceOf(ReaderChapterContentRoute.Unsupported::class.java, route)
        assertEquals(true, receivedDownloadFact)
        assertEquals(ReaderSourceContentKind.ONLINE, receivedSourceKind)
    }

    @Test
    fun `android download provider consumes the shared ordered chapter candidates`() {
        val nonAsciiPreference = mockk<Preference<Boolean>>()
        every { nonAsciiPreference.get() } returns false
        val libraryPreferences = mockk<LibraryPreferences>()
        every { libraryPreferences.disallowNonAsciiFilenames() } returns nonAsciiPreference
        val provider = DownloadProvider(
            context = mockk(relaxed = true),
            storageManager = mockk<StorageManager>(),
            libraryPreferences = libraryPreferences,
        )
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Source",
            mangaTitle = "Manga",
            chapterName = "Chapter 1",
            scanlator = "Group",
            chapterUrl = "/chapter/1",
            disallowNonAsciiFilenames = false,
        )

        assertEquals(
            listOf(
                "Group_Chapter 1_690668",
                "Group_Chapter 1_690668.cbz",
                "Group_Chapter 1",
                "Group_Chapter 1.cbz",
                "Group_Chapter 1_690668",
                "Group_Chapter 1_690668.cbz",
            ),
            provider.getValidChapterDirNames(identity.chapterName, identity.scanlator, identity.chapterUrl),
        )
    }

    @Test
    fun `chapter loader production wiring preserves every supported route`() = runTest {
        val selectedRoutes = mutableListOf<String>()
        val factories = ReaderPageLoaderFactories(
            download = {
                selectedRoutes += "download"
                ImmediatePageLoader()
            },
            localDirectory = {
                selectedRoutes += "directory"
                ImmediatePageLoader()
            },
            localArchive = {
                selectedRoutes += "archive"
                ImmediatePageLoader()
            },
            localEpub = {
                selectedRoutes += "epub"
                ImmediatePageLoader()
            },
            online = { _, _ ->
                selectedRoutes += "online"
                ImmediatePageLoader()
            },
            missingSource = { error("unexpected missing source") },
            unsupported = { error("unexpected unsupported source") },
        )
        val file = mockk<UniFile>()
        val cases = listOf(
            RouteCase(mockk<Source>(), downloaded = true),
            RouteCase(localSource(Format.Directory(file))),
            RouteCase(localSource(Format.Archive(file))),
            RouteCase(localSource(Format.Epub(file))),
            RouteCase(mockk<HttpSource>()),
        )

        cases.forEachIndexed { index, case ->
            val downloadManager = mockk<DownloadManager>(relaxed = true)
            every {
                downloadManager.isChapterDownloaded(any(), any(), any(), any(), any(), any())
            } returns case.downloaded
            val chapter = ReaderChapter(
                Chapter.create().copy(id = index.toLong() + 1, mangaId = 1, scanlator = "scanlator"),
            )
            ChapterLoader(
                context = mockk<Context>(relaxed = true),
                downloadManager = downloadManager,
                downloadProvider = mockk<DownloadProvider>(relaxed = true),
                manga = mockk<Manga>(relaxed = true),
                source = case.source,
                routedPageLoaderFactories = factories,
            ).loadChapter(chapter)
        }

        assertEquals(listOf("download", "directory", "archive", "epub", "online"), selectedRoutes)
    }

    @Test
    fun `default production factories bind every route to its concrete loader`() {
        val context = mockk<Context>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val source = mockk<Source>()
        val downloadManager = mockk<DownloadManager>(relaxed = true)
        val downloadProvider = mockk<DownloadProvider>(relaxed = true)
        val archiveReader = mockk<ArchiveReader>(relaxed = true)
        val epubReader = mockk<EpubReader>(relaxed = true)
        val chapterCache = mockk<ChapterCache>(relaxed = true)
        val archiveFile = mockk<UniFile>()
        val epubFile = mockk<UniFile>()
        val openedArchives = mutableListOf<UniFile>()
        val openedEpubs = mutableListOf<UniFile>()
        var cacheLookups = 0
        val factories = defaultReaderPageLoaderFactories(
            context = context,
            manga = manga,
            source = source,
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            archiveReaderFactory = { file ->
                openedArchives += file
                archiveReader
            },
            epubReaderFactory = { file ->
                openedEpubs += file
                epubReader
            },
            chapterCacheProvider = {
                cacheLookups++
                chapterCache
            },
        )
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 1))
        val httpSource = mockk<HttpSource>()

        val loaders = listOf(
            createReaderPageLoader(ReaderChapterContentRoute.Download, chapter, source, factories),
            createReaderPageLoader(
                ReaderChapterContentRoute.LocalDirectory(mockk()),
                chapter,
                source,
                factories,
            ),
            createReaderPageLoader(ReaderChapterContentRoute.LocalArchive(archiveFile), chapter, source, factories),
            createReaderPageLoader(ReaderChapterContentRoute.LocalEpub(epubFile), chapter, source, factories),
            createReaderPageLoader(ReaderChapterContentRoute.Online, chapter, httpSource, factories),
        )

        assertInstanceOf(DownloadPageLoader::class.java, loaders[0])
        assertInstanceOf(DirectoryPageLoader::class.java, loaders[1])
        assertInstanceOf(ArchivePageLoader::class.java, loaders[2])
        assertInstanceOf(EpubPageLoader::class.java, loaders[3])
        assertInstanceOf(HttpPageLoader::class.java, loaders[4])
        assertEquals(listOf(archiveFile), openedArchives)
        assertEquals(listOf(epubFile), openedEpubs)
        assertEquals(1, cacheLookups)
        loaders.forEach(PageLoader::recycle)
    }

    private fun localSource(format: Format): LocalSource = mockk<LocalSource>().also { source ->
        every { source.getFormat(any()) } returns format
    }

    private data class RouteCase(
        val source: Source,
        val downloaded: Boolean = false,
    )

    private class ImmediatePageLoader : PageLoader() {
        override var isLocal = false
        override suspend fun getPages() = listOf(ReaderPage(0, "/page"))
    }
}
