package eu.kanade.tachiyomi.extension

import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Original signed APK, real installation, app DI and ChapterLoader; only the remote HTTP boundary is controlled. */
class InstalledMangaDexReaderInstrumentationTest {
    @Test
    fun installedV16MangaDexReadsDownloadsAndReopensOfflineThroughProduction() = runBlocking {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
            "This fixture is restricted to the dedicated emulator, never a personal device"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val networkState = context.activeNetworkState()
        check(networkState.isOnline) {
            "Production DownloadJob needs a validated network before this acceptance run: $networkState"
        }
        val manager = Injekt.get<ExtensionManager>()
        val sources = Injekt.get<SourceManager>()
        val repositories = Injekt.get<ExtensionRepoRepository>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val downloads = Injekt.get<DownloadManager>()
        val provider = Injekt.get<DownloadProvider>()
        val storage = Injekt.get<StorageManager>()
        val storagePreference = Injekt.get<StoragePreferences>().baseStorageDirectory()
        val previousStorage = storagePreference.get()
        val wasStorageSet = storagePreference.isSet()
        val wifiPreference = Injekt.get<DownloadPreferences>().downloadOnlyOverWifi()
        val previousWifi = wifiPreference.get()
        val wasWifiSet = wifiPreference.isSet()
        check(!downloads.isRunning && downloads.queueState.value.isEmpty()) {
            "Refusing to interfere with an existing download queue"
        }
        val preference = Injekt.get<BasePreferences>().extensionInstaller()
        val previousInstaller = preference.get()
        val wasSet = preference.isSet()
        withTimeout(30_000) { manager.isInitialized.first { it } }
        val target = File(context.filesDir, "exts/$PACKAGE.ext")
        check(!target.exists())
        check(ExtensionLoader.getExtensionPackageInfoFromPkgName(context, PACKAGE) == null)
        check(manager.installedExtensionsFlow.value.none { it.pkgName == PACKAGE })
        val apk = instrumentation.context.assets.open("keiyoushi-mangadex-1.6.0.apk").use { it.readBytes() }
        assertEquals(SHA256, digest(apk))
        val png = Base64.decode(PNG, Base64.DEFAULT)
        val chapterUuid = UUID.randomUUID().toString()
        val imagePath = "/data/$chapterUuid/page-1.jpg"
        val requests = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        val network = Injekt.get<NetworkHelper>()
        val clientField = NetworkHelper::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = network.client
        var ownedManga: Manga? = null
        var ownedChapter: Chapter? = null
        var reader: ReaderChapter? = null
        var testFailure: Throwable? = null
        val downloadRoot = File.createTempFile("aex04-reader-download-", "", context.cacheDir).apply {
            check(delete())
            check(mkdirs())
        }
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val baseUrl = "http://127.0.0.1:${server.localPort}"
            check(repositories.getRepo(baseUrl) == null)
            val worker = Thread {
                try {
                    while (!server.isClosed) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val request = checkNotNull(input.readLine())
                            val path = request.split(' ')[1].substringBefore('?')
                            requests += path
                            while (!input.readLine().isNullOrEmpty()) Unit
                            val (type, body) = when (path) {
                                "/fixture.apk" -> "application/vnd.android.package-archive" to apk
                                "/at-home/server/$chapterUuid" ->
                                    "application/json" to
                                        (
                                            "{\"result\":\"ok\",\"baseUrl\":\"https://uploads.mangadex.org\"," +
                                                "\"chapter\":{\"hash\":\"$chapterUuid\",\"data\":[\"page-1.jpg\"]," +
                                                "\"dataSaver\":[\"page-1.jpg\"]}}"
                                            ).toByteArray(Charsets.UTF_8)
                                imagePath -> "image/png" to png
                                else -> error("Unexpected production HTTP request: $request")
                            }
                            socket.getOutputStream().use { output ->
                                output.write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        )
                                        .toByteArray(Charsets.US_ASCII),
                                )
                                output.write(body)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) failure.set(error)
                }
            }.apply {
                isDaemon = true
                start()
            }
            val routedClient = originalClient.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val url = baseUrl.toHttpUrl().newBuilder()
                    .encodedPath(request.url.encodedPath).encodedQuery(request.url.encodedQuery).build()
                chain.proceed(request.newBuilder().url(url).build())
            }.build()
            try {
                clientField.set(network, routedClient)
                wifiPreference.set(false)
                storagePreference.set(Uri.fromFile(downloadRoot).toString())
                withTimeout(10_000) {
                    while (storage.getDownloadsDirectory()?.uri?.path != File(downloadRoot, "downloads").path) delay(20)
                }
                repositories.insertRepo(ExtensionRepo(baseUrl, "AEX-04 fixed MangaDex", null, baseUrl, SIGNER))
                preference.set(BasePreferences.ExtensionInstaller.PRIVATE)
                val available = Extension.Available(
                    name = "MangaDex", pkgName = PACKAGE, versionName = "1.6.0", versionCode = 106000,
                    libVersion = 1.6, lang = "all", isNsfw = true, sources = emptyList(), apkName = "fixture.apk",
                    iconUrl = "", repoUrl = baseUrl, repoName = "AEX-04 fixed MangaDex", repoFingerprint = SIGNER,
                    declaredSha256 = SHA256, downloadUrl = "$baseUrl/fixture.apk",
                )
                val terminal = withTimeout(60_000) {
                    manager.installExtension(available).first(InstallStep::isCompleted)
                }
                assertEquals(
                    "error=${manager.installErrors.value[PACKAGE]} requests=$requests " +
                        "fixtureFailure=${failure.get()} " +
                        "proxy=${originalClient.proxySelector.select(java.net.URI(baseUrl))}",
                    InstallStep.Installed,
                    terminal,
                )
                val installed = withTimeout(15_000) {
                    manager.installedExtensionsFlow.first { list -> list.any { it.pkgName == PACKAGE } }
                        .single { it.pkgName == PACKAGE }
                }
                assertEquals(SHA256, digest(target.readBytes()))
                assertEquals(1.6, installed.libVersion, 0.0)
                assertEquals(61, installed.sources.size)
                val source = installed.sources.single { it.lang == "en" }
                withTimeout(15_000) { sources.querySources.first { list -> list.any { it.id == source.id } } }
                assertSame(source, sources.get(source.id))
                val mangaUrl = "/manga/$chapterUuid"
                check(mangas.getMangaByUrlAndSourceId(mangaUrl, source.id) == null)
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(source = source.id, url = mangaUrl, title = "AEX-04 reader $chapterUuid"),
                    ),
                ).single().also { ownedManga = it }
                val chapter = chapters.addAll(
                    listOf(
                        Chapter.create().copy(mangaId = manga.id, url = "/chapter/$chapterUuid", name = "AEX-04 page"),
                    ),
                ).single().also { ownedChapter = it }
                val persistedChapter = checkNotNull(chapters.getChapterById(chapter.id))
                val loaded = ReaderChapter(persistedChapter).also { reader = it }
                ChapterLoader(context, downloads, provider, manga, source)
                    .loadChapter(loaded)
                assertTrue(loaded.state is ReaderChapter.State.Loaded)
                val page = checkNotNull(loaded.pages).single()
                val loader = checkNotNull(loaded.pageLoader)
                assertFalse(loader.isLocal)
                loader.onPageSelected(page)
                withTimeout(30_000) {
                    while (page.status != Page.State.Ready) {
                        failure.get()?.let { throw AssertionError("Local HTTP failed: $requests", it) }
                        assertTrue("Reader failed: ${page.status}; HTTP: $requests", page.status !is Page.State.Error)
                        delay(20)
                    }
                }
                assertArrayEquals(png, checkNotNull(page.stream).invoke().use { it.readBytes() })
                assertEquals(listOf("/fixture.apk", "/at-home/server/$chapterUuid", imagePath), requests.toList())
                loader.recycle()
                downloads.downloadChapters(manga, listOf(persistedChapter))
                withTimeout(90_000) {
                    while (!downloads.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            source.id,
                            skipCache = true,
                        )
                    ) {
                        failure.get()?.let { throw AssertionError("Download HTTP failed: $requests", it) }
                        assertTrue(
                            "Download failed: ${downloads.queueState.value.map { it.status }}",
                            downloads.queueState.value.none { it.status == Download.State.ERROR },
                        )
                        delay(50)
                    }
                    while (downloads.queueState.value.isNotEmpty()) delay(20)
                }
                assertTrue(File(downloadRoot, "downloads").walkTopDown().any { it.isFile && it.name != ".nomedia" })
                // Close the only HTTP endpoint: local re-read must work without a remote fallback or injected loader.
                server.close()
                worker.join(5_000)
                val requestsBeforeOffline = requests.toList()
                val offline = ReaderChapter(checkNotNull(chapters.getChapterById(chapter.id))).also { reader = it }
                ChapterLoader(context, downloads, provider, manga, source).loadChapter(offline)
                assertTrue(checkNotNull(offline.pageLoader).isLocal)
                val offlinePage = checkNotNull(offline.pages).single()
                assertArrayEquals(png, checkNotNull(offlinePage.stream).invoke().use { it.readBytes() })
                assertEquals(requestsBeforeOffline, requests.toList())
            } catch (error: Throwable) {
                testFailure = error
                throw error
            } finally {
                var cleanupFailure: Throwable? = null
                suspend fun cleanup(block: suspend () -> Unit) {
                    try {
                        block()
                    } catch (error: Throwable) {
                        val previous = cleanupFailure
                        if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
                    }
                }
                cleanup { reader?.pageLoader?.recycle() }
                cleanup {
                    clientField.set(network, originalClient)
                    routedClient.connectionPool.evictAll()
                }
                cleanup {
                    downloads.pauseDownloads()
                    downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.manga.id == ownedManga?.id })
                }
                cleanup {
                    ownedManga?.let { manga ->
                        val driver = Injekt.get<SqlDriver>()
                        Injekt.get<DatabaseHandler>().await(inTransaction = true) {
                            driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                                bindLong(0, manga.id)
                                bindLong(1, manga.source)
                                bindString(2, manga.url)
                            }
                        }
                        assertEquals(null, mangas.getMangaByUrlAndSourceId(manga.url, manga.source))
                    }
                }
                cleanup { ownedChapter?.let { assertEquals(null, chapters.getChapterById(it.id)) } }
                cleanup {
                    manager.installedExtensionsFlow.value.singleOrNull { it.pkgName == PACKAGE }?.let {
                        check(!it.isShared)
                        manager.uninstallExtension(it)
                        withTimeout(15_000) {
                            manager.installedExtensionsFlow.first { list ->
                                list.none { entry -> entry.pkgName == PACKAGE }
                            }
                        }
                    }
                }
                cleanup { repositories.deleteRepo(baseUrl) }
                cleanup { if (wasSet) preference.set(previousInstaller) else preference.delete() }
                cleanup { if (wasStorageSet) storagePreference.set(previousStorage) else storagePreference.delete() }
                cleanup { if (wasWifiSet) wifiPreference.set(previousWifi) else wifiPreference.delete() }
                cleanup {
                    withTimeout(10_000) {
                        val testDownloadPath = File(downloadRoot, "downloads").path
                        while (storage.getDownloadsDirectory()?.uri?.path == testDownloadPath) delay(20)
                    }
                }
                cleanup {
                    server.close()
                    worker.join(5_000)
                }
                cleanup {
                    check(downloadRoot.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
                    check(downloadRoot.deleteRecursively())
                }
                cleanupFailure?.let { error -> testFailure?.addSuppressed(error) ?: throw error }
            }
        }
        assertFalse(target.exists())
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PACKAGE = "eu.kanade.tachiyomi.extension.all.mangadex"
        const val SHA256 = "35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35"
        const val SIGNER = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII="
    }
}
