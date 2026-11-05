package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DownloaderExtensionCompatibilityTest {
    @Test
    fun `comic metadata source hooks cannot publish a completed chapter after an ABI failure`() = runTest {
        withDownloader { downloader ->
            withChapterStorage(downloader) { storage ->
                prepareCompletedImage(downloader, storage)
                for (site in listOf("url", "name")) {
                    storage.messages.clear()
                    val failure = NoSuchMethodError("future comic metadata $site")
                    val source = mockk<HttpSource>()
                    every { source.name } answers { if (site == "name") throw failure else "Healthy" }
                    every { source.getChapterUrl(any()) } answers
                        { if (site == "url") throw failure else "https://fixture/chapter" }
                    val download = Download(
                        source,
                        Manga.create().copy(id = 1, title = "Fixture"),
                        Chapter.create().copy(id = 2, mangaId = 1, name = "Chapter", url = "/chapter"),
                    )
                        .apply { pages = listOf(Page(0, imageUrl = "https://fixture/image")) }
                    invokeSuspend<Unit>(downloader, "downloadChapter", download)
                    assertEquals(Download.State.ERROR, download.status)
                    assertEquals(
                        listOf(
                            RuntimeEnvironment.getApplication().stringResource(
                                MR.strings.source_error_extension_incompatible,
                            ),
                        ),
                        storage.messages,
                    )
                }
                coVerify(exactly = 0) { storage.cache.addChapter(any(), any(), any()) }
                verify(exactly = 0) { storage.temporary.renameTo(any()) }
            }
        }
    }

    @Test
    fun `incompatible chapter does not prevent an independent healthy chapter from committing`() = runTest {
        withDownloader { downloader ->
            withChapterStorage(downloader) { storage ->
                prepareCompletedImage(downloader, storage)
                every { storage.temporary.renameTo(any()) } returns true
                val bad = mockk<HttpSource>()
                coEvery { bad.getPageList(any()) } throws NoSuchMethodError("future chapter ABI")
                val good = mockk<HttpSource>()
                every { good.name } returns "Healthy"
                every { good.getChapterUrl(any()) } returns "https://fixture/chapter"
                fun chapter(source: HttpSource, id: Long) = Download(
                    source,
                    Manga.create().copy(id = id, title = "Fixture $id"),
                    Chapter.create().copy(id = id, mangaId = id, name = "Chapter", url = "/chapter"),
                )
                val failed = chapter(bad, 1)
                val healthy = chapter(good, 2).apply {
                    pages = listOf(Page(0, imageUrl = "https://fixture/image"))
                }
                val first = async { invokeSuspend<Unit>(downloader, "downloadChapter", failed) }
                val second = async { invokeSuspend<Unit>(downloader, "downloadChapter", healthy) }
                first.await()
                second.await()
                assertEquals(Download.State.ERROR, failed.status)
                assertEquals("Healthy chapter feedback: ${storage.messages}", Download.State.DOWNLOADED, healthy.status)
                coVerify(exactly = 1) { storage.cache.addChapter(any(), any(), match { it.id == 2L }) }
                coVerify(exactly = 0) { storage.cache.addChapter(any(), any(), match { it.id == 1L }) }
            }
        }
    }

    @Test
    fun `download provider guards only the source directory identity hook`() {
        val provider = DownloadProvider(
            RuntimeEnvironment.getApplication(),
            mockk(),
            tachiyomi.domain.library.service.LibraryPreferences(InMemoryPreferenceStore()),
        )
        var failure: Throwable? = NoSuchMethodError("future source display identity")
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 7L
            override val name = "Healthy"
            override fun toString(): String {
                failure?.let { throw it }
                return "Healthy"
            }
        }
        val original = failure
        val error = runCatching { provider.getSourceDirName(source) }.exceptionOrNull()
        assertTrue(error is SourceCompatibilityException)
        assertSame(original, error?.cause)
        assertEquals(SourceOperationPhase.METADATA, (error as SourceCompatibilityException).phase)
        val fatal = object : VirtualMachineError("controlled directory identity fatal") {}
        for (control in listOf<Throwable>(
            fatal,
            CancellationException("directory cancelled"),
            AssertionError("host identity bug"),
        )) {
            failure = control
            assertSame(control, runCatching { provider.getSourceDirName(source) }.exceptionOrNull())
        }
        failure = null
        assertEquals("Healthy", provider.getSourceDirName(source))
    }

    @Test
    fun `owned download job propagates fatal failure to its handler after the chapter boundary`() = runTest {
        withDownloader { downloader ->
            withChapterStorage(downloader) { storage ->
                val source = mockk<HttpSource>()
                val fatal = object : VirtualMachineError("controlled owned download fatal") {}
                coEvery { source.getPageList(any()) } throws IOException("wrapped job", fatal)
                val download = Download(
                    source,
                    Manga.create().copy(id = 1, title = "Fixture"),
                    Chapter.create().copy(id = 2, mangaId = 1, name = "Chapter", url = "/chapter"),
                )
                val observed = java.util.concurrent.atomic.AtomicReference<Throwable>()
                val scope = CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
                        kotlinx.coroutines.CoroutineExceptionHandler { _, error -> observed.set(error) },
                )
                try {
                    val method = Downloader::class.java.declaredMethods.single { it.name == "launchDownloadJob" }
                    method.isAccessible = true
                    val job = method.invoke(downloader, scope, download) as kotlinx.coroutines.Job
                    val completed = kotlinx.coroutines.CompletableDeferred<Throwable?>()
                    job.invokeOnCompletion { completed.complete(it) }
                    assertSame(fatal, completed.await())
                    job.join()
                    assertSame(fatal, observed.get())
                    assertTrue(storage.messages.isEmpty())
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun `chapter page list and image URL ABI failures remain errors with localized feedback and no commit`() =
        runTest {
            withDownloader { downloader ->
                withChapterStorage(downloader) { storage ->
                    for (site in listOf("pages", "url", "image")) {
                        storage.messages.clear()
                        val source = mockk<HttpSource>()
                        val failure = NoSuchMethodError("future $site ABI")
                        every { source.name } returns "Broken fixture"
                        if (site == "pages") {
                            coEvery { source.getPageList(any()) } throws failure
                        } else if (site == "url") {
                            coEvery { source.getPageList(any()) } returns listOf(Page(0, "/resolve"))
                            coEvery { source.getImageUrl(any()) } throws failure
                        } else {
                            coEvery { source.getPageList(any()) } returns
                                listOf(Page(0, imageUrl = "https://fixture/image"))
                            coEvery { source.getImage(any()) } throws failure
                        }
                        val download = Download(
                            source,
                            Manga.create().copy(id = 1, title = "Fixture"),
                            Chapter.create().copy(id = 2, mangaId = 1, name = "Chapter", url = "/chapter"),
                        )
                        invokeSuspend<Unit>(downloader, "downloadChapter", download)
                        assertEquals(Download.State.ERROR, download.status)
                        assertEquals(
                            listOf(
                                RuntimeEnvironment.getApplication().stringResource(
                                    MR.strings.source_error_extension_incompatible,
                                ),
                            ),
                            storage.messages,
                        )
                        assertTrue(download.pages.orEmpty().none { it.status == Page.State.Ready })
                        if (site != "pages") {
                            val error = (download.pages!!.single().status as Page.State.Error).error
                            assertTrue(error is SourceCompatibilityException)
                            assertSame(failure, error.cause)
                            assertEquals(
                                if (site ==
                                    "url"
                                ) {
                                    SourceOperationPhase.IMAGE_URL
                                } else {
                                    SourceOperationPhase.IMAGE_REQUEST
                                },
                                (error as SourceCompatibilityException).phase,
                            )
                        }
                    }
                    coVerify(exactly = 0) { storage.cache.addChapter(any(), any(), any()) }
                    verify(exactly = 0) { storage.temporary.renameTo(any()) }
                }
            }
        }

    @Test
    fun `chapter boundary preserves fatal and host Errors before an ordinary download failure can be reported`() =
        runTest {
            withDownloader { downloader ->
                withChapterStorage(downloader) { storage ->
                    val source = mockk<HttpSource>()
                    val fatal = object : VirtualMachineError("controlled chapter fatal") {}
                    for (control in listOf<Throwable>(
                        fatal,
                        CancellationException("chapter cancelled"),
                        AssertionError("host chapter bug"),
                    )) {
                        coEvery { source.getPageList(any()) } throws IOException("wrapped chapter", control)
                        val download = Download(
                            source,
                            Manga.create().copy(id = 1, title = "Fixture"),
                            Chapter.create().copy(id = 2, mangaId = 1, name = "Chapter", url = "/chapter"),
                        )
                        val error = runCatching {
                            invokeSuspend<Unit>(downloader, "downloadChapter", download)
                        }.exceptionOrNull()
                        assertSame(control, error)
                        assertTrue(storage.messages.isEmpty())
                        assertTrue(download.status != Download.State.DOWNLOADED)
                    }
                }
            }
        }

    @Test
    fun `incompatible image is attempted once while an independent healthy image completes`() = runTest {
        withDownloader { downloader ->
            var calls = 0
            val failure = NoSuchMethodError("future overridden image API")
            val bad = mockk<HttpSource>()
            coEvery { bad.getImage(any()) } answers {
                calls++
                throw failure
            }
            val healthy = mockk<HttpSource>()
            coEvery { healthy.getImage(any()) } answers { response() }
            val file = targetFile()
            val directory = mockk<UniFile>()
            every { directory.createFile(any()) } returns file
            val failed = async {
                runCatching {
                    invokeSuspend<UniFile>(
                        downloader,
                        "downloadImage",
                        Page(0, imageUrl = "https://fixture/image"),
                        bad,
                        directory,
                        "001",
                    )
                }
            }
            val good = async {
                invokeSuspend<UniFile>(
                    downloader,
                    "downloadImage",
                    Page(1, imageUrl = "https://fixture/healthy"),
                    healthy,
                    directory,
                    "002",
                )
            }
            val error = failed.await().exceptionOrNull()
            assertTrue("Expected typed source failure, received $error", error is SourceCompatibilityException)
            assertSame(failure, error?.cause)
            assertEquals(SourceOperationPhase.IMAGE_REQUEST, (error as SourceCompatibilityException).phase)
            assertEquals(1, calls)
            assertSame(file, good.await())
        }
    }

    @Test
    fun `ordinary image IO retains bounded retry but control and host Errors are never retried`() = runTest {
        withDownloader { downloader ->
            val source = mockk<HttpSource>()
            val directory = mockk<UniFile>()
            val page = Page(0, imageUrl = "https://fixture/image")
            var calls = 0
            val io = IOException("ordinary network failure")
            coEvery { source.getImage(any()) } answers {
                calls++
                throw io
            }
            assertSame(
                io,
                runCatching {
                    invokeSuspend<UniFile>(downloader, "downloadImage", page, source, directory, "001")
                }.exceptionOrNull(),
            )
            assertEquals(4, calls)
            val fatal = object : VirtualMachineError("controlled image fatal") {}
            for (control in listOf<Throwable>(
                fatal,
                CancellationException("image cancelled"),
                AssertionError("host image bug"),
            )) {
                calls = 0
                coEvery { source.getImage(any()) } answers {
                    calls++
                    throw IOException("wrapped image", control)
                }
                assertSame(
                    control,
                    runCatching {
                        invokeSuspend<UniFile>(downloader, "downloadImage", page, source, directory, "001")
                    }.exceptionOrNull(),
                )
                assertEquals(1, calls)
            }
        }
    }

    private fun response() = Response.Builder().request(Request.Builder().url("https://fixture/image").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body("encoded image".toResponseBody("image/jpeg".toMediaType())).build()

    private fun targetFile() = mockk<UniFile>().also {
        every { it.openOutputStream() } returns ByteArrayOutputStream()
        every { it.renameTo(any()) } returns true
    }

    private data class ChapterStorage(
        val cache: DownloadCache,
        val temporary: UniFile,
        val messages: MutableList<String?>,
    )

    private fun prepareCompletedImage(downloader: Downloader, storage: ChapterStorage) {
        val preferences = member<DownloadPreferences>(downloader, "downloadPreferences")
        preferences.saveChaptersAsCBZ().set(false)
        preferences.splitTallImages().set(false)
        val categories = member<tachiyomi.domain.category.interactor.GetCategories>(downloader, "getCategories")
        val tracks = member<tachiyomi.domain.track.interactor.GetTracks>(downloader, "getTracks")
        coEvery { categories.await(any()) } returns emptyList()
        coEvery { tracks.await(any()) } returns emptyList()
        val file = targetFile()
        every { file.name } returns "001.jpg"
        every { file.uri } returns android.net.Uri.parse("file:///fixture/001.jpg")
        every { storage.temporary.listFiles() } returns arrayOf(file)
        every { storage.temporary.createFile(any()) } returns targetFile()
        every { storage.temporary.exists() } returns false
        coEvery { storage.cache.addChapter(any(), any(), any()) } returns Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> member(downloader: Downloader, name: String): T = Downloader::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(downloader) as T

    private suspend fun <T> withChapterStorage(downloader: Downloader, block: suspend (ChapterStorage) -> T): T {
        fun field(
            name: String,
        ): Any = Downloader::class.java.getDeclaredField(name).apply { isAccessible = true }.get(downloader)
        val provider = field("provider") as DownloadProvider
        val cache = field("cache") as DownloadCache
        val imageCache = field("chapterCache") as ChapterCache
        every { imageCache.isImageInCache(any()) } returns false
        val mangaDirectory = mockk<UniFile>()
        val temporary = mockk<UniFile>()
        every { provider.getMangaDir(any(), any()) } returns Result.success(mangaDirectory)
        every { provider.getChapterDirName(any(), any(), any(), any()) } returns "Chapter"
        every { mangaDirectory.createDirectory(any()) } returns temporary
        every { temporary.listFiles() } returns emptyArray()
        every { temporary.findFile(any()) } returns null
        val messages = mutableListOf<String?>()
        val notifier = mockk<DownloadNotifier>(relaxed = true)
        every { notifier.onError(any(), any(), any(), any()) } answers { messages += firstArg<String?>() }
        Downloader::class.java.getDeclaredField("notifier\$delegate").apply { isAccessible = true }
            .set(downloader, lazy { notifier })
        mockkObject(DiskUtil, DownloadJob.Companion)
        every { DiskUtil.getAvailableStorageSpace(any<UniFile>()) } returns Long.MAX_VALUE
        every { DownloadJob.stop(any()) } returns Unit
        try {
            return block(ChapterStorage(cache, temporary, messages))
        } finally {
            unmockkObject(DiskUtil, DownloadJob.Companion)
        }
    }

    private suspend fun <T> withDownloader(block: suspend (Downloader) -> T): T {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val sourceManager = mockk<SourceManager>(relaxed = true)
        Injekt.addSingleton<SourceManager>(sourceManager)
        Injekt.addSingleton<Json>(Json)
        Injekt.addSingleton<GetManga>(mockk(relaxed = true))
        Injekt.addSingleton<GetChapter>(mockk(relaxed = true))
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("active_downloads", 0).edit().clear().commit()
        val preferences = context.getSharedPreferences("ec-download-compatibility", 0)
        preferences.edit().clear().commit()
        val provider = spyk(
            DownloadProvider(
                context,
                mockk(),
                tachiyomi.domain.library.service.LibraryPreferences(InMemoryPreferenceStore()),
            ),
        )
        val downloader = Downloader(
            context, provider, mockk(), sourceManager, mockk<ChapterCache>(),
            DownloadPreferences(tachiyomi.core.common.preference.AndroidPreferenceStore(context, preferences)),
            XML {}, mockk(), mockk(),
        )
        try {
            downloader.awaitQueueRestored()
            return block(downloader)
        } finally {
            (
                Downloader::class.java.getDeclaredField("scope").apply {
                    isAccessible = true
                }.get(downloader) as CoroutineScope
                ).cancel()
            Injekt = previous
        }
    }

    /** Invoke the actual private production stage without widening the product API for a test. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> invokeSuspend(target: Downloader, name: String, vararg args: Any): T =
        suspendCoroutineUninterceptedOrReturn<T> { continuation ->
            val method = Downloader::class.java.declaredMethods.single {
                it.name == name &&
                    it.parameterCount == args.size + 1
            }
            method.isAccessible = true
            try {
                method.invoke(target, *args, continuation)
            } catch (
                error: InvocationTargetException,
            ) {
                throw error.targetException
            }
        } as T
}
