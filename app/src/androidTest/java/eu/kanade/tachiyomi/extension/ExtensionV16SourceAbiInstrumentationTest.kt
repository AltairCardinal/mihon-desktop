package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/** AEX-01 A4: direct ART loading of the fixed controlled v1.6 component. */
class ExtensionV16SourceAbiInstrumentationTest {

    @Test
    fun fixedV16ProbeUsesHostSourceAbiOnArt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assetContext = instrumentation.context
        val apk = fixedApk(assetContext, context, APK_ASSET, APK_SHA256, "aex01-v16-controlled-sample.apk")
        val loader = ChildFirstPathClassLoader(
            apk.absolutePath,
            null,
            checkNotNull(javaClass.classLoader),
        )
        try {
            val factory = loader.loadClass(FACTORY_CLASS).getDeclaredConstructor().newInstance() as SourceFactory
            val sources = factory.createSources()
            assertEquals(2, sources.size)
            val source = sources.single { it.lang == "en" }
            assertEquals(loader, source.javaClass.classLoader)
            assertTrue(source.supportsLatest)
            val popularPageOne = source.getPopularManga(1)
            assertEquals(listOf("AEX-00 popular-en-1"), popularPageOne.mangas.map(SManga::title))
            assertTrue(popularPageOne.hasNextPage)
            assertFalse(source.getPopularManga(2).hasNextPage)
            assertEquals(listOf("AEX-00 latest-en-1"), source.getLatestUpdates(1).mangas.map(SManga::title))
            val search = source.getSearchManga(2, "query", source.getFilterList())
            assertEquals(listOf("AEX-00 search-query-en-2"), search.mangas.map(SManga::title))
            assertFalse(search.hasNextPage)
            assertEquals(1, source.getFilterList().size)

            val manga = SManga.create().apply {
                url = "/aex01/art"
                title = "AEX-01"
                memo = buildJsonObject { put("aex00.memo", "existing-manga") }
            }
            val chapter = SChapter.create().apply {
                url = "/aex01/art-chapter"
                name = "AEX-01 chapter"
                memo = buildJsonObject { put("aex00.chapterMemo", "existing") }
            }
            val probeClass = loader.loadClass(PROBE_CLASS)
            assertEquals(loader, probeClass.classLoader)
            val probe = checkNotNull(probeClass.getDeclaredField("INSTANCE").get(null))
            val probeMethod = probeClass.getMethod(
                "updateSummary",
                Source::class.java,
                SManga::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Continuation::class.java,
            )
            listOf(
                Triple(false, false, "manga=existing-manga;chapter=existing;chapters=1"),
                Triple(false, true, "manga=existing-manga;chapter=preserved;chapters=1"),
                Triple(true, false, "manga=preserved-en;chapter=existing;chapters=1"),
                Triple(true, true, "manga=preserved-en;chapter=preserved;chapters=1"),
            ).forEach { (fetchDetails, fetchChapters, expected) ->
                assertEquals(
                    expected,
                    invokeSuspend(
                        probeMethod,
                        probe,
                        source,
                        manga,
                        listOf(chapter),
                        fetchDetails,
                        fetchChapters,
                    ),
                )
            }
            assertTrue(source.getPageList(chapter).isEmpty())
            val errorChapter = SChapter.create().apply { url = "/aex00/error" }
            assertTrue(runCatching { source.getPageList(errorChapter) }.exceptionOrNull() is IllegalStateException)

            val chapterModel = ChapterImpl().apply {
                url = "/aex01/memo"
                name = "memo source"
            }
            assertTrue(chapterModel.memo.isEmpty())
            chapterModel.memo = buildJsonObject { put("aex01", "preserved") }
            val copiedChapter = SChapter.create().apply { copyFrom(chapterModel) }
            assertEquals(chapterModel.memo, copiedChapter.memo)
        } finally {
            apk.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun fixedControlledV15SourceUsesLegacySuspendAbiOnArt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apk = fixedApk(
            instrumentation.context,
            context,
            APK_V15_ASSET,
            APK_V15_SHA256,
            "aex01-v15-controlled-sample.apk",
        )
        val loader = ChildFirstPathClassLoader(
            apk.absolutePath,
            null,
            checkNotNull(javaClass.classLoader),
        )
        try {
            val source = loader.loadClass(V15_SOURCE_CLASS).getDeclaredConstructor().newInstance() as Source
            assertEquals(loader, source.javaClass.classLoader)
            val manga = SManga.create().apply {
                url = "/aex00/v15"
                title = "Legacy"
            }
            assertEquals("AEX-00 v1.5 suspend-only fixture", source.name)
            assertEquals("Legacy (v1.5)", source.getMangaDetails(manga).title)
            assertTrue(source.getChapterList(manga).isEmpty())
            val existingChapters = listOf(SChapter.create().apply { url = "/aex00/existing" })
            listOf(
                false to false,
                false to true,
                true to false,
                true to true,
            ).forEach { (fetchDetails, fetchChapters) ->
                val update = source.getMangaUpdate(manga, existingChapters, fetchDetails, fetchChapters)
                if (fetchDetails) {
                    assertEquals("Legacy (v1.5)", update.manga.title)
                } else {
                    assertSame(manga, update.manga)
                }
                if (fetchChapters) {
                    assertTrue(update.chapters.isEmpty())
                } else {
                    assertSame(existingChapters, update.chapters)
                }
            }
        } finally {
            apk.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun realV16MangaDexFactoryUsesHostSourceOnArt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apk = fixedApk(
            instrumentation.context,
            context,
            REAL_MANGADEX_ASSET,
            REAL_MANGADEX_SHA256,
            "keiyoushi-mangadex-1.6.0.apk",
        )
        val loader = ChildFirstPathClassLoader(
            apk.absolutePath,
            null,
            checkNotNull(javaClass.classLoader),
        )
        try {
            val factory = loader.loadClass(REAL_MANGADEX_FACTORY)
                .getDeclaredConstructor()
                .newInstance() as SourceFactory
            val sources = factory.createSources()
            assertEquals(61, sources.size)
            val source = sources.single { it.lang == "en" }
            assertEquals(loader, source.javaClass.classLoader)
            assertEquals("MangaDex", source.name)
            val manga = SManga.create().apply {
                url = "/manga/11111111-1111-4111-8111-111111111111"
                title = "AEX-01 real MangaDex"
            }
            val chapters = listOf(
                SChapter.create().apply { url = "/chapter/11111111-1111-4111-8111-111111111111" },
            )
            val noOpFailure = runCatching {
                source.getMangaUpdate(manga, chapters, fetchDetails = false, fetchChapters = false)
            }.exceptionOrNull()
            assertTrue(noOpFailure is IllegalStateException)

            val pageResponse = withLocalHttp(REAL_PAGE_RESPONSE) { serverUrl ->
                withRoutedProductionClient(serverUrl) {
                    source.getPageList(chapters.single())
                }
            }
            assertEquals(1, pageResponse.value.size)
            assertEquals("/data/aex01hash/page-1.jpg", pageResponse.value.single().imageUrl)
        } finally {
            apk.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun realComicFuryPageListUsesProductionHttpAndHostPageOnArt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apk = fixedApk(
            instrumentation.context,
            context,
            APK_COMICFURY_ASSET,
            APK_COMICFURY_SHA256,
            "aex01-comicfury-1.4.8.apk",
        )
        val loader = ChildFirstPathClassLoader(
            apk.absolutePath,
            null,
            checkNotNull(javaClass.classLoader),
        )
        try {
            val factory = loader.loadClass(
                COMICFURY_FACTORY_CLASS,
            ).getDeclaredConstructor().newInstance() as SourceFactory
            val source = factory.createSources().first()
            assertEquals(loader, source.javaClass.classLoader)
            val plainResponse = withLocalHttp(PAGE_HTML) { chapterUrl ->
                val chapter = SChapter.create().apply {
                    url = chapterUrl
                    name = "ComicFury fixture chapter"
                }
                source.getPageList(chapter)
            }
            assertEquals(1, plainResponse.value.size)
            assertEquals("https://example.invalid/page.png", plainResponse.value.single().imageUrl)

            val gzipResponse = withLocalHttp(PAGE_HTML, gzip = true) { chapterUrl ->
                val chapter = SChapter.create().apply {
                    url = chapterUrl
                    name = "ComicFury gzip fixture chapter"
                }
                source.getPageList(chapter)
            }
            assertEquals("gzip", gzipResponse.acceptEncoding)
            assertEquals(1, gzipResponse.value.size)
            assertEquals("https://example.invalid/page.png", gzipResponse.value.single().imageUrl)
        } finally {
            apk.parentFile?.deleteRecursively()
        }
    }

    private suspend fun <T> withLocalHttp(
        body: String,
        gzip: Boolean = false,
        block: suspend (String) -> T,
    ): LocalHttpResponse<T> {
        ServerSocket(0).use { server ->
            var acceptEncoding: String? = null
            val worker = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = input.readLine().orEmpty()
                            if (line.isEmpty()) break
                            if (line.startsWith("Accept-Encoding:", ignoreCase = true)) {
                                acceptEncoding = line.substringAfter(':').trim()
                            }
                        }
                        val bytes = if (gzip) body.toGzipBytes() else body.toByteArray(Charsets.UTF_8)
                        val contentEncoding = if (gzip) "Content-Encoding: gzip\r\n" else ""
                        val output = socket.getOutputStream()
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/html; charset=utf-8\r\n" +
                                    contentEncoding +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.UTF_8),
                        )
                        output.write(bytes)
                        output.flush()
                    }
                }
            }.apply { start() }
            val result = try {
                block("http://127.0.0.1:${server.localPort}/comicfury-chapter")
            } finally {
                server.close()
                worker.join(10_000)
            }
            return LocalHttpResponse(result, acceptEncoding)
        }
    }

    private data class LocalHttpResponse<T>(
        val value: T,
        val acceptEncoding: String?,
    )

    private fun String.toGzipBytes(): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    private suspend fun <T> withRoutedProductionClient(serverUrl: String, block: suspend () -> T): T {
        val network = Injekt.get<NetworkHelper>()
        val clientField = NetworkHelper::class.java.getDeclaredField("client").apply {
            isAccessible = true
        }
        val productionClient = network.client
        val routedClient = productionClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val localUrl = serverUrl.toHttpUrl().newBuilder()
                    .encodedPath(request.url.encodedPath)
                    .encodedQuery(request.url.encodedQuery)
                    .build()
                chain.proceed(request.newBuilder().url(localUrl).build())
            }
            .build()
        clientField.set(network, routedClient)
        return try {
            block()
        } finally {
            clientField.set(network, productionClient)
            routedClient.connectionPool.evictAll()
        }
    }

    private fun fixedApk(
        assetContext: Context,
        targetContext: Context,
        asset: String,
        expectedSha256: String,
        fileName: String,
    ): File = File.createTempFile("aex01-", "", targetContext.cacheDir).let { marker ->
        check(marker.delete())
        check(marker.mkdirs())
        File(marker, fileName)
    }.apply {
        assetContext.copyAsset(asset, this)
        assertEquals(expectedSha256, sha256())
        check(setReadOnly()) { "Unable to mark the fixed extension APK read-only" }
    }

    private fun invokeSuspend(
        method: Method,
        receiver: Any,
        vararg arguments: Any?,
    ): Any? {
        val completed = CountDownLatch(1)
        var resumed: Result<Any?>? = null
        val continuation = object : Continuation<Any?> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Any?>) {
                resumed = result
                completed.countDown()
            }
        }
        val immediate = try {
            method.invoke(receiver, *arguments, continuation)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        if (immediate !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) return immediate
        check(completed.await(10, TimeUnit.SECONDS)) { "External suspend probe did not complete" }
        return checkNotNull(resumed).getOrThrow()
    }

    private fun Context.copyAsset(asset: String, destination: File) {
        assets.open(asset).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256").digest(readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val APK_ASSET = "aex00-external-v16-controlled-sample.apk"
        const val APK_SHA256 = "34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182"
        const val APK_V15_ASSET = "aex00-external-v15-controlled-sample.apk"
        const val APK_V15_SHA256 = "caf80d849e2eb5ad8f0be5121f914d9cee1ee06c15653d15f33321602183a316"
        const val V15_SOURCE_CLASS = "aex00.external.v15.LegacySuspendOnlySource"
        const val APK_COMICFURY_ASSET = "keiyoushi-comicfury-1.4.8.apk"
        const val APK_COMICFURY_SHA256 =
            "9403d439eefec8ccff3fa7a3edd810046a12206d944302013bc3f94538b3def7"
        const val REAL_MANGADEX_ASSET = "keiyoushi-mangadex-1.6.0.apk"
        const val REAL_MANGADEX_SHA256 =
            "35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35"
        const val REAL_MANGADEX_FACTORY = "keiyoushi.source.Generated"
        const val REAL_PAGE_RESPONSE =
            "{\"result\":\"ok\",\"baseUrl\":\"https://uploads.mangadex.org\",\"chapter\":{\"hash\":\"aex01hash\",\"data\":[\"page-1.jpg\"],\"dataSaver\":[\"page-1.jpg\"]}}"
        const val COMICFURY_FACTORY_CLASS = "eu.kanade.tachiyomi.extension.all.comicfury.ExtensionGenerated"
        const val PAGE_HTML =
            "<div class=\"is--comic-page\"><div class=\"is--image-segment\"><div>" +
                "<img src=\"https://example.invalid/page.png\"></div></div></div>"
        const val FACTORY_CLASS = "aex00.external.v16.V16SourceFactory"
        const val PROBE_CLASS = "aex00.external.v16.V16SourceAbiProbe"
    }
}
