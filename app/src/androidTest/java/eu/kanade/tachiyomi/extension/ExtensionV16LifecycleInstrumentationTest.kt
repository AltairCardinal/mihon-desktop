package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchRequest
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceMangaUpdateService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

internal data class LifecycleApkFixture(
    val asset: String,
    val sha256: String,
    val signer: String,
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double,
)

/** Real APK installation/registration; UI automation confirms OS dialogs without shell install privileges. */
class ExtensionV16LifecycleInstrumentationTest {
    @Test
    fun fixedSignedFactoryInstallsThroughProductionManagerAndRegistersBothLanguages() =
        runLifecycle(BasePreferences.ExtensionInstaller.PRIVATE)

    @Test
    fun systemInstallRequiresNormalConfirmationAndRegistersBothLanguages() =
        runLifecycle(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)

    @Test
    fun cancellingNormalSystemConfirmationLeavesNoInstalledExtension() =
        runLifecycle(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER, cancel = true)

    /** Default is a self-cleaning DB contract; explicit prepare/verify runs add a cross-process assertion. */
    @Test
    fun installedSourceUpdatesPersistMangaAndReadingState() = runBlocking {
        checkEmulator()
        val phase = InstrumentationRegistry.getArguments().getString("aex04RestartPhase")
        check(phase == null || phase == "prepare" || phase == "verify")
        if (phase != "verify") runLifecycle(BasePreferences.ExtensionInstaller.PRIVATE, persist = true)
        if (phase != "prepare") {
            try {
                verifyPersistedState(requireRestart = phase == "verify")
            } finally {
                cleanupPersistedState()
            }
        }
    }

    private fun checkEmulator() {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
            "This test changes the dedicated emulator profile; do not run on a personal device"
        }
    }

    internal fun runLifecycle(
        installer: BasePreferences.ExtensionInstaller,
        cancel: Boolean = false,
        persist: Boolean = false,
        fixture: LifecycleApkFixture = V16_FIXTURE,
        additionalApks: Map<String, ByteArray> = emptyMap(),
        transformDownload: (ByteArray) -> ByteArray = { it },
        installThroughCatalogUi: (suspend (String) -> Unit)? = null,
        beforeSystemConfirmation: suspend () -> Unit = {},
        onInstalled: suspend (Extension.Installed, String) -> Unit = { _, _ -> },
    ) = runBlocking {
        checkEmulator()
        check(!persist || fixture == V16_FIXTURE) { "Restart receipt belongs to the fixed v1.6 fixture only" }
        val packageName = fixture.packageName
        if (persist) check(!receipt().contains("pid")) { "Verify/clean the prior owned restart fixture first" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = Injekt.get<ExtensionManager>()
        val sourceManager = Injekt.get<SourceManager>()
        val repositories = Injekt.get<ExtensionRepoRepository>()
        val installerPreference = Injekt.get<BasePreferences>().extensionInstaller()
        val previousInstaller = installerPreference.get()
        val wasInstallerSet = installerPreference.isSet()
        val systemInstall = installer != BasePreferences.ExtensionInstaller.PRIVATE
        val needsConfirmation = installer == BasePreferences.ExtensionInstaller.PACKAGEINSTALLER ||
            installer == BasePreferences.ExtensionInstaller.LEGACY
        if (needsConfirmation) {
            check(context.packageManager.canRequestPackageInstalls()) {
                "Enable unknown-app installation through Settings before instrumentation; restore it after the runner exits"
            }
        }
        val target = File(context.filesDir, "exts/$packageName.ext")
        check(!target.exists()) { "Refusing to replace an existing extension fixture" }
        check(ExtensionLoader.getExtensionPackageInfoFromPkgName(context, packageName) == null)
        val bytes = instrumentation.context.assets.open(fixture.asset).use { it.readBytes() }
        assertEquals(fixture.sha256, digest(bytes))
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val url = "http://127.0.0.1:${server.localPort}"
            check(repositories.getRepo(url) == null)
            val worker = Thread {
                try {
                    while (!server.isClosed) {
                        server.accept().use { socket ->
                            socket.soTimeout = 15_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val path = input.readLine().split(' ')[1]
                            while (!input.readLine().isNullOrEmpty()) Unit
                            val body = when (path) {
                                "/fixture.apk" -> transformDownload(bytes)
                                "/index_v2.json" -> """
                                {"name":"AEX-04 fixed fixture","badgeLabel":"AEX-04",
                                "signingKey":"${fixture.signer}","contact":{"website":"$url"},
                                "extensionList":{"extensions":[{
                                "name":"${fixture.name}","packageName":"$packageName",
                                "resources":{"apkUrl":"$url/fixture.apk","iconUrl":"$url/icon.png"},
                                "extensionLib":"${fixture.libVersion}","versionCode":${fixture.versionCode},
                                "versionName":"${fixture.versionName}","contentWarning":"CONTENT_WARNING_SAFE",
                                "sources":[]}]}}
                                """.trimIndent().toByteArray(Charsets.UTF_8)
                                else -> additionalApks[path] ?: byteArrayOf()
                            }
                            socket.getOutputStream().use { output ->
                                output.write(
                                    (
                                        "HTTP/1.1 ${if (body.isEmpty()) "404 Not Found" else "200 OK"}\r\n" +
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
            var installed: Extension.Installed? = null
            var retained = false
            try {
                repositories.insertRepo(
                    ExtensionRepo(
                        url,
                        "AEX-04 fixed fixture",
                        null,
                        url,
                        fixture.signer,
                        indexUrl = if (installThroughCatalogUi != null) "$url/index_v2.json" else null,
                    ),
                )
                installerPreference.set(installer)
                if (systemInstall) {
                    context.startActivity(
                        requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                withTimeout(30_000) { manager.isInitialized.first { it } }
                val available = Extension.Available(
                    name = fixture.name, pkgName = packageName, versionName = fixture.versionName,
                    versionCode = fixture.versionCode, libVersion = fixture.libVersion, lang = "all", isNsfw = true,
                    sources = emptyList(), apkName = "fixture.apk", iconUrl = "", repoUrl = url,
                    repoName = "AEX-04 fixed fixture", repoFingerprint = fixture.signer,
                    declaredSha256 = fixture.sha256, downloadUrl = "$url/fixture.apk",
                )
                val terminal = withTimeout(60_000) {
                    if (installThroughCatalogUi != null) {
                        check(!systemInstall && !cancel)
                        installThroughCatalogUi(packageName)
                        manager.installedExtensionsFlow.first { list -> list.any { it.pkgName == packageName } }
                        InstallStep.Installed
                    } else {
                        coroutineScope {
                            val result = async { manager.installExtension(available).first(InstallStep::isCompleted) }
                            if (needsConfirmation) {
                                beforeSystemConfirmation()
                                clickInstallerButton(if (cancel) setOf("Cancel") else setOf("Install"))
                            }
                            result.await()
                        }
                    }
                }
                serverFailure.get()?.let { throw AssertionError("Fixture HTTP server failed", it) }
                if (cancel) {
                    assertEquals(InstallStep.Idle, terminal)
                    assertEquals(null, ExtensionLoader.getExtensionPackageInfoFromPkgName(context, packageName))
                    assertTrue(manager.installedExtensionsFlow.value.none { it.pkgName == packageName })
                    return@runBlocking
                }
                assertEquals(InstallStep.Installed, terminal)
                installed = withTimeout(15_000) {
                    manager.installedExtensionsFlow.first { entries -> entries.any { it.pkgName == packageName } }
                        .single { it.pkgName == packageName }
                }
                val installedApk = if (systemInstall) {
                    File(
                        requireNotNull(
                            ExtensionLoader.getExtensionPackageInfoFromPkgName(context, packageName)?.applicationInfo,
                        ).sourceDir,
                    )
                } else {
                    target
                }
                assertEquals(fixture.sha256, digest(installedApk.readBytes()))
                assertEquals(systemInstall, installed.isShared)
                assertEquals(fixture.libVersion, installed.libVersion, 0.0)
                assertTrue(installed.sources.isNotEmpty())
                if (fixture == V16_FIXTURE) {
                    assertEquals("all", installed.lang)
                    assertEquals(setOf("en", "zh"), installed.sources.map { it.lang }.toSet())
                }
                val ids = installed.sources.map { it.id }.toSet()
                val installedSources = installed.sources
                withTimeout(15_000) {
                    sourceManager.querySources.first { entries ->
                        installedSources.all { source -> entries.any { it === source } }
                    }
                }
                for (source in installed.sources) {
                    assertSame(source, sourceManager.get(source.id))
                    assertTrue(source.javaClass.classLoader !== context.classLoader)
                    if (fixture != V16_FIXTURE) continue
                    val search = Injekt.get<SourceMangaSearchService>()
                    val popular = search.loadPage(source, 1, SourceMangaSearchRequest.Popular)
                    assertEquals("AEX-00 popular-${source.lang}-1", popular.mangas.single().title)
                    assertEquals(
                        "AEX-00 search-query-${source.lang}-2",
                        search.loadPage(
                            source,
                            2,
                            SourceMangaSearchRequest.Search("query", source.getFilterList()),
                        ).mangas.single().title,
                    )
                    val updated = SourceMangaUpdateService().await(
                        source,
                        Manga.create().copy(source = source.id, url = popular.mangas.single().url),
                        emptyList(),
                        fetchDetails = true,
                        fetchChapters = true,
                    )
                    assertEquals("\"preserved-${source.lang}\"", updated.manga.memo["aex00.memo"].toString())
                    assertEquals("\"preserved\"", updated.chapters.single().memo["aex00.chapterMemo"].toString())
                }
                val reloaded = ExtensionLoader.loadExtensionFromPkgName(context, packageName) as LoadResult.Success
                assertEquals(ids, reloaded.extension.sources.map { it.id }.toSet())
                assertEquals(installed.lang, reloaded.extension.lang)
                if (persist) {
                    try {
                        persistReadingState(installed, url)
                        retained = true
                    } catch (failure: Throwable) {
                        cleanupPersistedRows()
                        check(receipt().edit().clear().commit())
                        throw failure
                    }
                }
                onInstalled(installed, url)
            } finally {
                // Only the known fixture created above is eligible for cleanup, never other installed extensions.
                val owned =
                    installed ?: manager.installedExtensionsFlow.value.singleOrNull { it.pkgName == packageName }
                if (owned != null && !retained) {
                    manager.uninstallExtension(owned)
                    if (systemInstall) clickInstallerButton(setOf("OK", "Uninstall"))
                    withTimeout(15_000) {
                        manager.installedExtensionsFlow.first { entries -> entries.none { it.pkgName == packageName } }
                        val ownedIds = owned.sources.map { it.id }.toSet()
                        sourceManager.querySources.first { entries -> entries.none { it.id in ownedIds } }
                    }
                }
                if (!retained) repositories.deleteRepo(url)
                if (wasInstallerSet) installerPreference.set(previousInstaller) else installerPreference.delete()
                server.close()
                worker.join(5_000)
            }
        }
        assertEquals(persist, target.exists())
    }

    private fun receipt() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("aex04-owned-restart-fixture", Context.MODE_PRIVATE)

    private suspend fun persistReadingState(extension: Extension.Installed, repoUrl: String) {
        val receipt = receipt()
        check(!receipt.contains("pid")) { "A prior restart fixture must be verified/cleaned before preparing another" }
        check(receipt.edit().putInt("pid", Process.myPid()).putString("repo", repoUrl).commit())
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        for (source in extension.sources) {
            val url = "/aex04/persist/${source.lang}"
            check(mangas.getMangaByUrlAndSourceId(url, source.id) == null)
            val manga = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = source.id, url = url, title = "AEX-04 reading state", favorite = true),
                ),
            ).single()
            check(
                receipt.edit().putLong("manga.${source.lang}", manga.id)
                    .putLong("source.${source.lang}", source.id).commit(),
            )
            val chapter = chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/aex00/chapter-${source.lang}",
                        name = "AEX-04 existing chapter",
                        read = true,
                        bookmark = true,
                        lastPageRead = 7,
                    ),
                ),
            ).single()
            check(receipt.edit().putLong("chapter.${source.lang}", chapter.id).commit())
            Injekt.get<UpdateManga>().awaitFromRemote(manga, source, fetchDetails = true, fetchChapters = true)
            assertReadingState(
                mangas.getMangaById(manga.id),
                chapters.getChapterByMangaId(manga.id).single(),
                source.lang,
            )
        }
        check(receipt.edit().putBoolean("ready", true).commit())
        println("AEX04_RESTART_PREPARED pid=${Process.myPid()} sources=${extension.sources.size}")
    }

    private suspend fun verifyPersistedState(requireRestart: Boolean) {
        val receipt = receipt()
        check(receipt.getBoolean("ready", false)) { "Run the prepare phase before cross-process verification" }
        if (requireRestart) assertNotEquals(receipt.getInt("pid", -1), Process.myPid())
        val manager = Injekt.get<ExtensionManager>()
        val sourceManager = Injekt.get<SourceManager>()
        val installed = withTimeout(15_000) {
            manager.installedExtensionsFlow.first { entries -> entries.any { it.pkgName == PACKAGE } }
                .single { it.pkgName == PACKAGE }
        }
        val sourceIds = installed.sources.map { it.id }.toSet()
        withTimeout(15_000) {
            sourceManager.querySources.first { sources -> sources.map { it.id }.containsAll(sourceIds) }
        }
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        for (language in listOf("en", "zh")) {
            val source = requireNotNull(sourceManager.get(receipt.getLong("source.$language", -1)))
            val mangaId = receipt.getLong("manga.$language", -1)
            val manga = mangas.getMangaById(mangaId)
            val chapter = chapters.getChapterByMangaId(mangaId).single()
            assertEquals(receipt.getLong("chapter.$language", -1), chapter.id)
            assertReadingState(manga, chapter, language)
            // Re-enter the actual app updater with the source reconstructed from the installed APK.
            Injekt.get<UpdateManga>().awaitFromRemote(manga, source, fetchDetails = true, fetchChapters = false)
            assertReadingState(mangas.getMangaById(mangaId), chapters.getChapterByMangaId(mangaId).single(), language)
        }
        println(
            "AEX04_RESTART_VERIFIED preparePid=${receipt.getInt(
                "pid",
                -1,
            )} verifyPid=${Process.myPid()} crossProcess=$requireRestart",
        )
    }

    private fun assertReadingState(manga: Manga, chapter: Chapter, language: String) {
        assertTrue(manga.favorite)
        assertEquals("AEX-04 reading state", manga.title)
        assertEquals("\"preserved-$language\"", manga.memo["aex00.memo"].toString())
        assertEquals("\"preserved\"", chapter.memo["aex00.chapterMemo"].toString())
        assertTrue(chapter.read)
        assertTrue(chapter.bookmark)
        assertEquals(7L, chapter.lastPageRead)
    }

    private suspend fun cleanupPersistedRows() {
        val receipt = receipt()
        val driver = Injekt.get<SqlDriver>()
        for (language in listOf("en", "zh")) {
            val mangaId = receipt.getLong("manga.$language", -1)
            if (mangaId < 0) continue
            val sourceId = receipt.getLong("source.$language", -1)
            // Cleanup only exact test-owned rows; normal reads/writes above use production repositories.
            Injekt.get<DatabaseHandler>().await(inTransaction = true) {
                driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                    bindLong(0, mangaId)
                    bindLong(1, sourceId)
                    bindString(2, "/aex04/persist/$language")
                }
            }
            assertEquals(
                null,
                Injekt.get<MangaRepository>().getMangaByUrlAndSourceId("/aex04/persist/$language", sourceId),
            )
            val chapterId = receipt.getLong("chapter.$language", -1)
            if (chapterId >= 0) assertEquals(null, Injekt.get<ChapterRepository>().getChapterById(chapterId))
        }
    }

    private suspend fun cleanupPersistedState() {
        val receipt = receipt()
        if (!receipt.contains("pid")) return
        cleanupPersistedRows()
        val manager = Injekt.get<ExtensionManager>()
        manager.installedExtensionsFlow.value.singleOrNull { it.pkgName == PACKAGE }?.let { extension ->
            check(!extension.isShared)
            manager.uninstallExtension(extension)
            withTimeout(15_000) {
                manager.installedExtensionsFlow.first { entries ->
                    entries.none {
                        it.pkgName ==
                            PACKAGE
                    }
                }
            }
        }
        receipt.getString("repo", null)?.let { Injekt.get<ExtensionRepoRepository>().deleteRepo(it) }
        check(receipt.edit().clear().commit())
    }

    internal suspend fun clickInstallerButton(labels: Set<String>) {
        val button = awaitInstallerButton(labels)
        check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    internal suspend fun awaitInstallerButton(labels: Set<String>): AccessibilityNodeInfo =
        awaitNode { node ->
            node.packageName?.toString()?.endsWith("packageinstaller") == true &&
                labels.any { it.equals(node.text?.toString(), ignoreCase = true) } && node.isEnabled && node.isClickable
        }

    private suspend fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = withTimeout(
        25_000,
    ) {
        while (true) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (root != null) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited++ < 512) {
                    val node = queue.removeFirst()
                    if (predicate(node)) return@withTimeout node
                    repeat(node.childCount) { index -> node.getChild(index)?.let(queue::add) }
                }
            }
            delay(100)
        }
        error("Unreachable")
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    internal companion object {
        const val PACKAGE = "aex00.external.v16.controlled"
        const val ASSET = "aex00-external-v16-controlled-sample.apk"
        const val SHA256 = "34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182"
        const val SIGNER = "9be8a18439915033e8362f25426323e8b7b94f223eadca4962ce5f91a23d6021"
        val V16_FIXTURE = LifecycleApkFixture(
            ASSET,
            SHA256,
            SIGNER,
            PACKAGE,
            "AEX-00 v1.6 controlled",
            "1.6.0",
            160000,
            1.6,
        )
    }
}
