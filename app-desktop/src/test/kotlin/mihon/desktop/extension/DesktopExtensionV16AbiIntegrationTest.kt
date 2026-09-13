package mihon.desktop.extension

import android.app.Application
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.desktop.di.initDesktopDIForTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * AEX-01 A3: invoke the fixed v1.6 binary through the production desktop loader.
 *
 * The fixture is deliberately loaded by [DesktopExtensionLoader]; reflection is only used to
 * locate the external Kotlin suspend probe without compiling this test against its implementation
 * classes. The probe's first parameter is the host [Source] interface and therefore exercises
 * the actual ABI boundary rather than a fixture-only helper.
 */
@Isolated
class DesktopExtensionV16AbiIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fixed v1_6 probe invokes the host Source ABI through production loader`() = runBlocking {
        val fixture = repositoryRoot().resolve(EXTERNAL_V16_JAR)
        assertTrue(fixture.isFile, "Missing fixed v1.6 fixture: $fixture")

        val loader = DesktopExtensionLoader(tempDir)
        val loaded = loader.loadFromSingleJar(fixture)
        try {
            assertEquals(2, loaded.size, "v1.6 factory did not yield en/zh sources: ${loader.diagnostics}")
            val source = loaded.single { it.source.lang == "en" }.source
            assertTrue(source.supportsLatest)
            val popularPageOne = source.getPopularManga(1)
            assertEquals(listOf("AEX-00 popular-en-1"), popularPageOne.mangas.map(SManga::title))
            assertTrue(popularPageOne.hasNextPage)
            assertTrue(!source.getPopularManga(2).hasNextPage)
            assertEquals(listOf("AEX-00 latest-en-1"), source.getLatestUpdates(1).mangas.map(SManga::title))
            val search = source.getSearchManga(2, "query", source.getFilterList())
            assertEquals(
                listOf("AEX-00 search-query-en-2"),
                search.mangas.map(SManga::title),
            )
            assertTrue(!search.hasNextPage)
            assertEquals(
                listOf("AEX-00 search-query-en-1"),
                source.getSearchManga(1, "query", source.getFilterList()).mangas.map(SManga::title),
            )
            assertEquals(1, source.getFilterList().size)
            val probeClass = loaded.first().classLoader.loadClass(EXTERNAL_V16_PROBE)
            val probe = probeClass.getDeclaredField("INSTANCE").get(null)
            val probeMethod = probeClass.getMethod(
                "updateSummary",
                Source::class.java,
                SManga::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Continuation::class.java,
            )

            val manga = SManga.create().apply {
                url = "/aex01/v16"
                title = "AEX-01"
                memo = buildJsonObject { put("aex00.memo", "existing-manga") }
            }
            val chapter = SChapter.create().apply {
                url = "/aex01/chapter"
                name = "AEX-01 chapter"
                memo = buildJsonObject { put("aex00.chapterMemo", "existing") }
            }
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
        } finally {
            loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
        }
    }

    @Test
    fun `controlled v1_5 source uses the host update bridge through production loader`() = runBlocking {
        val fixture = repositoryRoot().resolve(EXTERNAL_V15_JAR)
        assertTrue(fixture.isFile, "Missing fixed v1.5 fixture: $fixture")

        val loader = DesktopExtensionLoader(tempDir)
        val loaded = loader.loadFromSingleJar(fixture)
        try {
            assertEquals(1, loaded.size, "v1.5 fixture diagnostics: ${loader.diagnostics}")
            val source = loaded.single().source
            assertEquals(0xAE0015L, source.id)
            assertEquals("AEX-00 v1.5 suspend-only fixture", source.name)
            val manga = SManga.create().apply {
                url = "/aex00/v15"
                title = "Legacy"
            }
            val chapters = listOf(
                SChapter.create().apply {
                    url = "/aex00/existing"
                    name = "existing"
                },
            )
            listOf(
                Triple(false, false, "Legacy|1|existing"),
                Triple(false, true, "Legacy|0|"),
                Triple(true, false, "Legacy (v1.5)|1|existing"),
                Triple(true, true, "Legacy (v1.5)|0|"),
            ).forEach { (fetchDetails, fetchChapters, expected) ->
                val update = source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
                val chapterName = update.chapters.singleOrNull()?.name.orEmpty()
                assertEquals(
                    expected,
                    "${update.manga.title}|${update.chapters.size}|$chapterName",
                )
            }
        } finally {
            loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
        }
    }

    @Test
    fun `real v1_6 MangaDex source parses a production page response through its loader`() = runBlocking {
        val preferences = IsolatedDesktopPreferenceStore.create()
        val previousInjekt = Injekt
        val root = repositoryRoot().toPath()
        try {
            val diContext = initDesktopDIForTest(
                appDir = tempDir.resolve("real-mangadex-app"),
                preferenceStore = preferences.store,
            )
            try {
                val application = Injekt.get<Application>()
                val productionClient = Injekt.get<NetworkHelper>().client
                MockWebServer().also { it.start() }.use { server ->
                    val interceptedUrls = mutableListOf<String>()
                    val client = productionClient.newBuilder()
                        .addInterceptor { chain ->
                            val original = chain.request()
                            interceptedUrls += original.url.toString()
                            val rewritten = server.url(original.url.encodedPath).newBuilder().apply {
                                original.url.queryParameterNames.forEach { name ->
                                    original.url.queryParameterValues(name).forEach { value ->
                                        addQueryParameter(name, value)
                                    }
                                }
                            }.build()
                            chain.proceed(original.newBuilder().url(rewritten).build())
                        }
                        .build()
                    // initDesktopDIForTest wires the complete production graph first. Reset only
                    // the test registry before loading the extension so its lazy NetworkHelper
                    // resolves this local client instead of the already-cached desktop client.
                    patchInjekt()
                    Injekt.addSingleton(application)
                    Injekt.addSingleton(NetworkHelper(client))

                    val inputJar = tempDir.resolve("keiyoushi-mangadex-1.6.0.jar")
                    Files.copy(root.resolve(REAL_MANGADEX_JAR), inputJar.toPath())
                    writeExtensionMeta(
                        inputJar,
                        ExtensionMeta(
                            pkgName = REAL_PACKAGE,
                            versionCode = 106000L,
                            versionName = "1.6.0",
                            artifactSha256 = REAL_JAR_SHA256,
                            source = ExtensionOrigin.COMPILED_JAR,
                            name = "MangaDex",
                            language = "all",
                            extensionClass = REAL_FACTORY,
                        ),
                    )
                    val adaptedJar = tempDir.resolve("keiyoushi-mangadex-1.6.0-adapted.jar")
                    val runtimeJar = if (DefaultJvmExtensionArtifactAdapter.adaptIfRequired(inputJar, adaptedJar)) {
                        writeExtensionMeta(
                            adaptedJar,
                            ExtensionMeta(
                                pkgName = REAL_PACKAGE,
                                versionCode = 106000L,
                                versionName = "1.6.0",
                                artifactSha256 = REAL_JAR_SHA256,
                                source = ExtensionOrigin.CONVERTED_APK,
                                name = "MangaDex",
                                language = "all",
                                extensionClass = REAL_FACTORY,
                            ),
                        )
                        adaptedJar
                    } else {
                        inputJar
                    }

                    val loader = DesktopExtensionLoader(tempDir)
                    val loaded = loader.loadFromSingleJar(runtimeJar)
                    try {
                        assertEquals(61, loaded.size, "MangaDex SourceFactory diagnostics: ${loader.diagnostics}")
                        val source = loaded.single { it.source.lang == "en" }.source
                        val chapter = SChapter.create().apply {
                            url = "https://mangadex.org/chapter/11111111-1111-4111-8111-111111111111"
                            name = "AEX-01 real page"
                        }
                        server.enqueue(
                            MockResponse(
                                code = 200,
                                body = REAL_PAGE_RESPONSE,
                            ),
                        )
                        val pages = runCatching { source.getPageList(chapter) }.getOrElse { error ->
                            throw AssertionError(
                                "MangaDex page request did not use the injected production client: " +
                                    "serverRequests=${server.requestCount}, intercepted=$interceptedUrls",
                                error,
                            )
                        }
                        assertEquals(1, pages.size)
                        assertEquals(
                            "/data/aex01hash/page-1.jpg",
                            pages.single().imageUrl,
                        )
                        assertEquals(1, server.requestCount)
                        assertEquals(
                            "/at-home/server/11111111-1111-4111-8111-111111111111",
                            server.takeRequest().url.encodedPath,
                        )
                    } finally {
                        loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                    }
                }
            } finally {
                diContext.closeAndJoin()
            }
        } finally {
            Injekt = previousInjekt
            preferences.close()
        }
    }

    private fun repositoryRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app-desktop").isDirectory && File(it, "docs").isDirectory }

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

    private companion object {
        const val EXTERNAL_V16_JAR =
            "app-desktop/src/test/resources/extensions/real/aex00-external-v16-controlled-sample.jar"
        const val EXTERNAL_V16_PROBE = "aex00.external.v16.V16SourceAbiProbe"
        const val EXTERNAL_V15_JAR =
            "app-desktop/src/test/resources/extensions/real/aex00-external-v15-suspend-only.jar"
        const val REAL_MANGADEX_JAR =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.6.0.jar"
        const val REAL_JAR_SHA256 = "2781d8593d68f2e79ad54f20949399c44afb703d5679299f84f7356df697008e"
        const val REAL_PACKAGE = "eu.kanade.tachiyomi.extension.all.mangadex"
        const val REAL_FACTORY = "keiyoushi.source.Generated"
        const val REAL_PAGE_RESPONSE =
            "{\"result\":\"ok\",\"baseUrl\":\"https://uploads.mangadex.org\",\"chapter\":{\"hash\":\"aex01hash\",\"data\":[\"page-1.jpg\"],\"dataSaver\":[\"page-1.jpg\"]}}"
    }
}
