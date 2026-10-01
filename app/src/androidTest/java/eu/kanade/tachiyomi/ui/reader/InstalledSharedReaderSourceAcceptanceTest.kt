package eu.kanade.tachiyomi.ui.reader

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import mihon.domain.reader.session.ReaderChapterLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** Original installed APK + host DI + ChapterLoader; only its HTTP response boundary is controlled. */
class InstalledSharedReaderSourceAcceptanceTest {
    @Test
    fun originalInstalledSourceParsesHttpAndRecoversThroughChapterLoader() = runBlocking {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val sources = Injekt.get<SourceManager>()
        val sourceId = arguments.getString("sharedReaderSourceId")?.toLongOrNull()
        val installedSource = if (sourceId == null) {
            sources.getOnlineSources().single { it.javaClass.name.contains(".manhuagui.") }
        } else {
            requireNotNull(sources.get(sourceId)) as HttpSource
        }
        val isManHuaGui = installedSource.javaClass.name.contains(".manhuagui.")
        check(isManHuaGui || installedSource.javaClass.name.contains(".copymanga.")) {
            "Requires the original ManHuaGui or CopyManga APK"
        }
        fun supplied(name: String): ByteArray? = arguments.getString(name)?.let { path ->
            val file = File(path).canonicalFile
            check(file.toPath().startsWith(context.filesDir.canonicalFile.toPath())) {
                "Acceptance response must be in the isolated app's private files directory"
            }
            check(file.isFile)
            file.readBytes()
        }
        val shape = supplied("sharedReaderSuccessFile") ?: run {
            check(isManHuaGui) {
                "CopyManga requires its original captured response files; no replacement parser fixture"
            }
            instrumentation.context.assets.open("shared-reader-manhuagui-packed.html").use { it.readBytes() }
        }
        val empty =
            supplied("sharedReaderEmptyFile")
                ?: shape.toString(Charsets.UTF_8).replace("\"files\":[\"001.jpg\"]", "\"files\":[]").toByteArray()
        val missing =
            supplied("sharedReaderMissingFile")
                ?: shape.toString(Charsets.UTF_8).replace("\"files\":", "\"missing\":").toByteArray()
        val malformed = supplied("sharedReaderMalformedFile") ?: "<html><script>window[\"__mhg\"](broken".toByteArray()
        if (!isManHuaGui) {
            check(
                listOf("sharedReaderEmptyFile", "sharedReaderMissingFile", "sharedReaderMalformedFile").all {
                    arguments.containsKey(it)
                },
            )
        }
        val chapterUrl = arguments.getString("sharedReaderChapterUrl") ?: "/comic/123/chapter.html"
        val expectedCount = arguments.getString("sharedReaderPageCount")?.toIntOrNull() ?: 1
        check(expectedCount > 0)
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
        }
        val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val downloads = Injekt.get<DownloadManager>()
        check(!downloads.isRunning && downloads.queueState.value.isEmpty())
        val network = Injekt.get<NetworkHelper>()

        @Suppress("DEPRECATION")
        fun publicClients() = listOf(network.client, network.cloudflareClient, network.nonCloudflareClient)
        val publicOriginals = publicClients()
        // R8 may rename private fields or merge aliases; identify surviving storage by the public clients.
        val fields = network.javaClass.declaredFields.filter { field ->
            if (!okhttp3.OkHttpClient::class.java.isAssignableFrom(field.type)) {
                false
            } else {
                field.isAccessible = true
                publicOriginals.any { it === field.get(network) }
            }
        }
        check(fields.isNotEmpty()) { "No surviving production client storage can be controlled" }
        val originals = fields.map { it.get(network) }
        val response = AtomicReference(200 to shape)
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                try {
                    while (!server.isClosed) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val line = requireNotNull(input.readLine())
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            val imageRequest =
                                line.substringAfter(' ').substringBefore(' ').substringBefore('?').endsWith(".jpg") ||
                                    line.substringAfter(' ').substringBefore(' ').substringBefore('?').endsWith(".png")
                            val (status, body) = if (imageRequest) 200 to image else response.get()
                            val contentType = if (imageRequest) "image/png" else "text/html"
                            socket.getOutputStream().use { output ->
                                output.write(
                                    (
                                        "HTTP/1.1 $status Acceptance\r\nContent-Type: $contentType\r\n" +
                                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(Charsets.US_ASCII),
                                )
                                output.write(body)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) serverFailure.set(error)
                }
            }.apply {
                isDaemon = true
                start()
            }
            val route = okhttp3.Interceptor { chain ->
                val original = chain.request()
                val local = original.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
                chain.proceed(original.newBuilder().url(local).build())
            }
            try {
                fields.zip(originals).forEach { (field, original) ->
                    field.set(network, (original as okhttp3.OkHttpClient).newBuilder().addInterceptor(route).build())
                }
                check(publicClients().all { client -> client.interceptors.any { it === route } }) {
                    "All production public client getters must expose the controlled HTTP response route"
                }
                // Reload the original installed artifact with the normal trusted loader after routing;
                // eager source clients must not retain a pre-fixture HTTP client. No install or DI/source-map replacement.
                val packageName = if (isManHuaGui) {
                    "eu.kanade.tachiyomi.extension.zh.manhuagui"
                } else {
                    "eu.kanade.tachiyomi.extension.zh.copymanga"
                }
                val loaded = eu.kanade.tachiyomi.extension.util.ExtensionLoader.loadExtensionFromPkgName(
                    context,
                    packageName,
                )
                    as eu.kanade.tachiyomi.extension.model.LoadResult.Success
                val source = loaded.extension.sources.single { it.id == installedSource.id } as HttpSource
                check(source.client.interceptors.any { it === route }) {
                    "Original source must preserve the production client's controlled response route"
                }
                val manga = Manga.create().copy(
                    id = -SystemClock.uptimeMillis(),
                    source = source.id,
                    title = "Shared reader acceptance",
                )
                val cases = listOf(
                    (200 to empty) to AppError.NoResults.javaClass,
                    (200 to missing) to AppError.MalformedData::class.java,
                    (403 to "Forbidden".toByteArray()) to AppError.Authentication::class.java,
                    (429 to "Rate limited".toByteArray()) to AppError.RateLimited::class.java,
                    (500 to "Server error".toByteArray()) to AppError.Server::class.java,
                    (200 to malformed) to AppError.MalformedData::class.java,
                )
                for ((index, case) in cases.withIndex()) {
                    val chapter =
                        ReaderChapter(
                            Chapter.create().copy(
                                id = manga.id - index - 1,
                                mangaId = manga.id,
                                url = chapterUrl,
                                name = "Acceptance $index",
                            ),
                        )
                    val loader = ChapterLoader(context, downloads, Injekt.get<DownloadProvider>(), manga, source)
                    try {
                        response.set(case.first)
                        assertTrue(runCatching { withTimeout(30_000) { loader.loadChapter(chapter) } }.isFailure)
                        serverFailure.get()?.let { throw AssertionError("Controlled HTTP boundary failed", it) }
                        val loadState = chapter.sharedSessionStateFlow.value.activeChapter.loadState
                        val error = loadState as ReaderChapterLoadState.Error
                        assertTrue(
                            "Original parser error must reach the shared host contract",
                            case.second.isInstance(error.error),
                        )
                        response.set(200 to shape)
                        withTimeout(30_000) { loader.loadChapter(chapter) }
                        assertEquals(expectedCount, requireNotNull(chapter.pages).size)
                        val page = requireNotNull(chapter.pages).first()
                        requireNotNull(chapter.pageLoader).onPageSelected(page)
                        withTimeout(30_000) {
                            while (page.status != eu.kanade.tachiyomi.source.model.Page.State.Ready) {
                                check(page.status !is eu.kanade.tachiyomi.source.model.Page.State.Error)
                                delay(20)
                            }
                        }
                        assertTrue(requireNotNull(page.stream).invoke().use { it.readBytes() }.contentEquals(image))
                    } finally {
                        chapter.pageLoader?.recycle()
                    }
                }
            } finally {
                try {
                    fields.zip(originals).forEach { (field, original) -> field.set(network, original) }
                    check(publicClients().zip(publicOriginals).all { (actual, original) -> actual === original })
                } finally {
                    server.close()
                    worker.join(2_000)
                }
            }
        }
    }
}
