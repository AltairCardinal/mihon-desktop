package mihon.desktop.di

import android.app.Application
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopOwnerIngressDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.OwnerUiDependencies
import mihon.desktop.compat.AndroidCompat
import mihon.desktop.backup.AutoBackupScheduler
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DefaultDownloadFileOperations
import mihon.desktop.download.DownloadFileOperations
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.DesktopArtifactAuthenticator
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopAvailableSource
import mihon.desktop.extension.FixtureNewSource
import mihon.desktop.ui.extension.DesktopExtensionPresentationPort
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopNativeSharePort
import mihon.desktop.platform.DesktopShareService
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import mihon.desktop.security.DesktopAppLock
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.more.StatsScreenModel
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.ui.settings.DesktopUpdateScreenModel
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.test.http.SourceExtensionTestModeBridge
import mihon.desktop.test.http.SourceExtensionTestModeController
import mihon.desktop.migration.DesktopBatchMigrationController
import mihon.desktop.network.ChallengeRecoveryFailure
import mihon.desktop.network.ChallengeRecoveryIntent
import mihon.desktop.network.ChallengeRecoveryState
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopAuthenticatedSessionCommitter
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopChallengeBrowserLoginBridge
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.domain.download.DownloadRepository
import mihon.domain.download.EnqueueDownload
import mihon.domain.download.IsChapterDownloaded
import mihon.domain.download.DownloadQueueStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.service.ReleaseService
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.CredentialNamespace
import mihon.desktop.tracking.DesktopTrackerSyncScheduler
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceLoginRequest
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.data.download.PersistentDownloadStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.JvmDatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer

@Isolated
class DesktopDiWiringTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `owner UI boundary provides the owner graph dependencies`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        val owner = DesktopOwnerIngressDependencies(Injekt.get(), DesktopUiDependencies.fromInjekt())
        val scene = ImageComposeScene(100, 100, coroutineContext = coroutineContext) {}
        var rendered: DesktopUiDependencies? = null
        try {
            scene.setContent { OwnerUiDependencies(owner) { rendered = LocalDesktopUiDependencies.current } }
            scene.render()
            assertSame(owner.uiDependencies, rendered)
            assertSame(owner.uiDependencies.externalActionNavigator, rendered?.externalActionNavigator)
        } finally {
            scene.close()
            context.closeAndJoin()
        }
    }

    @Test
    fun `owner graph retains the UI navigator instance selected from DI`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        try {
            val uiDependencies = DesktopUiDependencies.fromInjekt()
            val ownerDependencies = DesktopOwnerIngressDependencies(Injekt.get(), uiDependencies)

            assertSame(uiDependencies, ownerDependencies.uiDependencies)
            assertSame(uiDependencies.externalActionNavigator, ownerDependencies.uiDependencies.externalActionNavigator)
            assertTrue(uiDependencies.externalActionNavigator !== DesktopUiDependencies.fromInjekt().externalActionNavigator)
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `desktop DI shares the production updater controller with UI`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        try {
            assertNotNull(Injekt.get<ReleaseService>())
            assertNotNull(Injekt.get<GetApplicationRelease>())
            val controller = Injekt.get<DesktopUpdateController>()
            assertSame(controller, DesktopUiDependencies.fromInjekt().updateController)
            assertSame(Injekt.get<DesktopUpdateScreenModel>(), DesktopUiDependencies.fromInjekt().updateScreenModel)
            assertSame(controller, Injekt.get<DesktopUpdateScreenModel>().controller)
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `reinitializing test DI permanently retires the old updater graph`(@TempDir tempDir: File) = runBlocking {
        val first = initDesktopDIForTest(tempDir.resolve("updater-first"), DesktopPreferenceStore())
        val oldModel = Injekt.get<DesktopUpdateScreenModel>()
        val oldController = Injekt.get<DesktopUpdateController>()
        val oldRuntime = Injekt.get<DesktopAppRuntime>()
        val second = initDesktopDIForTest(tempDir.resolve("updater-second"), DesktopPreferenceStore())
        try {
            assertTrue(oldModel !== Injekt.get<DesktopUpdateScreenModel>())
            assertTrue(oldController !== Injekt.get<DesktopUpdateController>())
            assertTrue(oldRuntime !== Injekt.get<DesktopAppRuntime>())
            assertFalse(oldModel.intent(mihon.desktop.ui.settings.DesktopUpdateIntent.CHECK))
        } finally {
            oldModel.dispose()
            second.closeAndJoin()
            first.closeAndJoin()
        }
    }

    @Test
    fun `desktop DI wires one shared security core into runtime`(@TempDir tempDir: File) = runBlocking {
        val namespaces = mutableListOf<CredentialNamespace>()
        val backend = object : CredentialBackend {
            override fun save(account: String, secret: CharArray) = Unit
            override fun load(account: String): CharArray? = null
            override fun delete(account: String) = Unit
        }
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(),
            credentialBackendFactory = { namespace ->
                namespaces += namespace
                backend
            },
        )
        try {
            assertEquals(listOf(CredentialNamespace.APP_LOCK_V1), namespaces)
            assertNotNull(Injekt.get<SecurityPreferences>())
            assertNotNull(Injekt.get<DesktopPassphraseVerifier>())
            val appLock = Injekt.get<DesktopAppLock>()
            assertSame(appLock, Injekt.get<DesktopAppRuntime>().appLock)
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `desktop DI shares one native share service instance with UI`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        try {
            assertNotNull(Injekt.get<DesktopNativeSharePort>())
            val service = Injekt.get<DesktopShareService>()

            assertSame(service, DesktopUiDependencies.fromInjekt().shareService)
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `desktop DI binds the started Android compat Application exact type`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        try {
            val application = Injekt.get<Application>()

            assertSame(AndroidCompat.context, application.getBaseContext())
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `challenge DI uses one bridge manager committer and helper jar for all explicit success paths`(
        @TempDir tempDir: File,
    ) = runBlocking {
        MockWebServer().use { solverServer ->
            solverServer.start()
            val sourceServer = MockWebServer().also { it.start() }
            val sourceUrl = sourceServer.url("/chapter")
            val store = DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}"))
            val preferences = DesktopAppPreferences(store)
            val context = initDesktopDIForTest(
                tempDir,
                store,
                startDownloadWorker = false,
                browserOpener = DesktopBrowserOpener { _, _ -> true },
            )
            try {
                val manager = Injekt.get<CloudflareChallengeManager>()
                val bridge = Injekt.get<DesktopChallengeBrowserLoginBridge>()
                val helper = Injekt.get<DesktopNetworkHelper>()
                val sourceLoginFactory = Injekt.get<DesktopSourceLoginSessionFactory>()
                val ui = DesktopUiDependencies.fromInjekt()
                assertEquals(listOf(manager, bridge, helper), listOf(ui.cloudflareChallengeManager, ui.challengeBrowserLoginBridge, ui.networkHelper))
                assertSame(Injekt.get<DesktopAuthenticatedSessionCommitter>(), Injekt.get<AuthenticatedSessionCommitter>())
                assertSame(Injekt.get<AuthenticatedSessionCommitter>(), sourceLoginFactory.committer)
                assertSame(sourceLoginFactory, ui.sourceLoginSessionFactory)
                assertEquals(0, solverServer.requestCount, "runtime providers must not be observed while the helper is built")

                val manual = manager.publish(loginRequest(sourceUrl))
                assertTrue(
                    manager.recover(manual, ChallengeRecoveryIntent.SubmitManualCookies(loginSession("manual-secret", sourceUrl))) is
                        ChallengeRecoveryState.Recovered,
                )
                assertEquals("manual-secret", helper.cookieJar.get(sourceUrl).single().value)

                val browser = manager.publish(loginRequest(sourceUrl))
                val browserRecovery = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.recover(browser, ChallengeRecoveryIntent.OpenBrowser)
                }
                assertTrue(bridge.complete(browser, loginSession("browser-secret", sourceUrl)))
                assertTrue(browserRecovery.await() is ChallengeRecoveryState.Recovered)
                assertEquals("browser-secret", helper.cookieJar.get(sourceUrl).single().value)
                assertEquals(0, solverServer.requestCount)

                assertEquals(
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverUnavailable),
                    manager.recover(manager.publish(loginRequest(sourceUrl)), ChallengeRecoveryIntent.UseFlareSolverr),
                )
                preferences.flareSolverrEnabled.set(true)
                preferences.flareSolverrUrl.set("ftp://invalid")
                manager.recover(manager.publish(loginRequest(sourceUrl)), ChallengeRecoveryIntent.UseFlareSolverr)
                assertEquals(0, solverServer.requestCount)

                solverServer.enqueue(
                    MockResponse(
                        body = """{"status":"ok","solution":{"userAgent":"solver-agent","cookies":[{"name":"cf_clearance","value":"solver-secret","domain":"${sourceUrl.host}"}]}}""",
                    ),
                )
                preferences.flareSolverrUrl.set(solverServer.url("/").toString())
                assertTrue(
                    manager.recover(manager.publish(loginRequest(sourceUrl)), ChallengeRecoveryIntent.UseFlareSolverr) is
                        ChallengeRecoveryState.Recovered,
                )
                assertEquals("solver-secret", helper.cookieJar.get(sourceUrl).single().value)
                assertEquals("solver-agent", manager.solverUserAgentFor(sourceUrl))
                sourceServer.enqueue(MockResponse(body = "ok"))
                helper.client.newCall(Request.Builder().url(sourceUrl).build()).execute().close()
                val outbound = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("source request missing")
                assertEquals("cf_clearance=solver-secret", outbound.headers["Cookie"])
                assertEquals("solver-agent", outbound.headers["User-Agent"])
            } finally {
                context.closeAndJoin()
                sourceServer.close()
            }
        }
    }

    @Test
    fun `extension install uses DI manager and updates its runtime inside transaction`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            artifactAuthenticator = DesktopArtifactAuthenticator { _, _, _ -> },
        )
        try {
            val manager = Injekt.get<DesktopExtensionManager>()
            val api = Injekt.get<DesktopExtensionApi>()
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body(Buffer().write(sourceJar())).build())
                val extension = DesktopAvailableExtension(
                    name = "Fixture",
                    pkgName = "mihon.desktop.extension",
                    versionName = "1.0",
                    versionCode = 1,
                    libVersion = 1.4,
                    lang = "en",
                    isNsfw = false,
                    jarUrl = server.url("/fixture.jar").toString(),
                    iconUrl = "",
                    repoUrl = "https://repo.example",
                    repoName = "Fixture repo",
                    repoFingerprint = "fixture-key",
                    sources = listOf(DesktopAvailableSource(FixtureNewSource.ID, "en", "Fixture", "https://example.com")),
                )

                val result = api.installExtension(extension, manager)

                assertNotNull(result as? DesktopExtensionApi.InstallResult.Success)
                assertSame(manager, Injekt.get<DesktopExtensionManager>())
                assertNotNull(manager.getSource(FixtureNewSource.ID))
                assertEquals(1, server.requestCount)
            }
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `reader DI resolves extension package for incognito history gating`(@TempDir tempDir: File) = runBlocking {
        val extensionDir = tempDir.resolve("extensions").also(File::mkdirs)
        extensionDir.resolve("mihon.desktop.extension.jar").writeBytes(sourceJar())
        val store = DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}"))
        store.getStringSet("incognito_extensions").set(setOf("mihon.desktop.extension"))
        val context = initDesktopDIForTest(tempDir, store, startDownloadWorker = false)
        try {
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = FixtureNewSource.ID,
                        url = "/incognito-manga",
                        title = "Incognito manga",
                    ),
                ),
            ).single()
            Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/incognito-chapter",
                        name = "Incognito chapter",
                    ),
                ),
            )
            val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single()

            Injekt.get<ReaderProgressTracker>().track(
                eventId = "di-extension-incognito",
                chapterId = chapter.id,
                lastPageRead = 2,
                totalPages = 10,
                sourceId = FixtureNewSource.ID,
                mangaId = manga.id,
                chapterNumber = 1.0,
            )

            assertTrue(context.handler.db.historyQueries.getHistoryByMangaId(manga.id).executeAsList().isEmpty())
            assertEquals(2L, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single().lastPageRead)
        } finally {
            context.closeAndJoin()
        }
    }

    private fun sourceJar(): ByteArray {
        val source = FixtureNewSource::class.java
        val path = source.name.replace('.', '/') + ".class"
        val classBytes = checkNotNull(source.classLoader.getResourceAsStream(path)).use { it.readBytes() }
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                listOf(
                    path to classBytes,
                    "META-INF/services/eu.kanade.tachiyomi.source.Source" to source.name.toByteArray(),
                ).forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
    }

    @Test
    fun `extension presentation DI keeps default flow singleton and captured reinit ownership`(@TempDir tempDir: File) = runBlocking {
        val first = initDesktopDIForTest(
            tempDir.resolve("extension-first"),
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        )
        try {
            val firstManager = Injekt.get<DesktopExtensionManager>()
            val firstPort = Injekt.get<DesktopExtensionPresentationPort>()
            val firstModel = Injekt.get<ExtensionsScreenModel>()
            val firstController = Injekt.get<SourceExtensionTestModeController>()
            assertSame(firstManager.installedExtensions, firstPort.installedExtensions)
            assertSame(firstPort, Injekt.get<DesktopExtensionPresentationPort>())
            assertSame(firstModel, Injekt.get<ExtensionsScreenModel>())
            assertSame(firstModel, first.extensionScreenModel)
            assertSame(firstController, SourceExtensionTestModeBridge.controller)
            firstModel.search("first-query")
            assertEquals("first-query", firstController.snapshot().searchQuery)
            assertFalse(firstModel.closed)

            val second = initDesktopDIForTest(
                tempDir.resolve("extension-second"),
                DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            )
            val secondModel = Injekt.get<ExtensionsScreenModel>()
            val secondController = Injekt.get<SourceExtensionTestModeController>()
            try {
                assertTrue(firstModel.closed)
                assertTrue(firstModel !== secondModel)
                assertFalse(secondModel.closed)
                assertSame(secondModel, Injekt.get<ExtensionsScreenModel>())
                assertSame(secondModel, second.extensionScreenModel)
                assertTrue(firstController !== secondController)
                assertSame(secondController, SourceExtensionTestModeBridge.controller)
                assertSame(
                    Injekt.get<DesktopExtensionManager>().installedExtensions,
                    Injekt.get<DesktopExtensionPresentationPort>().installedExtensions,
                )
                first.closeAndJoin()
                assertFalse(secondModel.closed)
                assertSame(secondController, SourceExtensionTestModeBridge.controller)
            } finally {
                second.closeAndJoin()
                assertTrue(secondModel.closed)
                assertNull(SourceExtensionTestModeBridge.controller)
            }
        } finally {
            first.closeAndJoin()
        }
    }

    @Test
    fun `reinitializing test DI replaces every binding and scheduler context`(@TempDir tempDir: File) = runBlocking {
        val firstManga = Manga.create().copy(id = 101, source = 9, title = "First manga", favorite = true)
        val firstChapter = Chapter.create().copy(id = 102, mangaId = firstManga.id, name = "First chapter", url = "/102")
        val firstStore = DesktopPreferenceStore(
            Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}"),
        ).also { it.getBoolean("download_new", false).set(true) }
        val firstContext = initDesktopDIForTest(
            tempDir.resolve("first"),
            firstStore,
            libraryProvider = { listOf(LibraryManga(firstManga, emptyList(), 0, 0, 0, 0, 0, 0)) },
            updateManga = { LibraryUpdateChecker.UpdateResult(1, listOf(firstChapter)) },
            startDownloadWorker = false,
        )
        try {
        val firstHandler = firstContext.handler
        Injekt.get<LibraryUpdateScheduler>().runNow().join()

        val secondManga = Manga.create().copy(id = 201, source = 10, title = "Second manga", favorite = true)
        val secondChapter = Chapter.create().copy(id = 202, mangaId = secondManga.id, name = "Second chapter", url = "/202")
        val secondStore = DesktopPreferenceStore(
            Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}"),
        ).also { it.getBoolean("download_new", false).set(true) }
        val secondContext = initDesktopDIForTest(
            tempDir.resolve("second"),
            secondStore,
            libraryProvider = { listOf(LibraryManga(secondManga, emptyList(), 0, 0, 0, 0, 0, 0)) },
            updateManga = { LibraryUpdateChecker.UpdateResult(1, listOf(secondChapter)) },
            startDownloadWorker = false,
        )

        try {
        val secondHandler = secondContext.handler
        assertSame(secondStore, Injekt.get<PreferenceStore>())
        assertSame(secondHandler, Injekt.get<DatabaseHandler>())
        assertNotNull(Injekt.get<DesktopAppPreferences>())
        assertNotNull(Injekt.get<ReaderPreferences>())
        assertNotNull(Injekt.get<LibraryUpdateScheduler>())

        Injekt.get<LibraryUpdateScheduler>().runNow().join()

        val firstEntries = PersistentDownloadStore(firstHandler.db).entries()
        val secondEntries = PersistentDownloadStore(secondHandler.db).entries()
        assertEquals(listOf(firstChapter.id), firstEntries.map { it.chapterId })
        assertEquals(listOf(secondChapter.id), secondEntries.map { it.chapterId })
        } finally {
            secondContext.closeAndJoin()
        }
        } finally {
            firstContext.closeAndJoin()
        }
    }

    @Test
    fun `reinitializing while scheduler runs joins old work and closes old database`(@TempDir tempDir: File): Unit = runBlocking {
        val updateStarted = CompletableDeferred<Unit>()
        val updateStopped = CompletableDeferred<Unit>()
        val manga = Manga.create().copy(id = 301, source = 11, title = "Blocking manga", favorite = true)
        val firstContext = initDesktopDIForTest(
            tempDir.resolve("running-first"),
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            libraryProvider = { listOf(LibraryManga(manga, emptyList(), 0, 0, 0, 0, 0, 0)) },
            updateManga = { _: Manga ->
                updateStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    updateStopped.complete(Unit)
                }
            },
        )
        try {
            Injekt.get<LibraryUpdateScheduler>().runNow()
            updateStarted.await()

            val secondContext = initDesktopDIForTest(
                tempDir.resolve("running-second"),
                DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            )
            try {
                assertEquals(Unit, withTimeout(1_000) { updateStopped.await() })
                assertThrows(Exception::class.java) {
                    runBlocking { firstContext.handler.await { mangasQueries.resetViewerFlags() } }
                }
                assertSame(secondContext.handler, Injekt.get<DatabaseHandler>())
                secondContext.handler.await { mangasQueries.resetViewerFlags() }
            } finally {
                secondContext.closeAndJoin()
            }
        } finally {
            firstContext.closeAndJoin()
        }
    }

    @Test
    fun `reinitializing stops active download worker before installing fresh context`(@TempDir tempDir: File): Unit = runBlocking {
        val downloadExecuteStarted = CompletableDeferred<Unit>()
        val downloadReadStarted = CompletableDeferred<Unit>()
        val downloadFinallyEntered = CompletableDeferred<Unit>()
        val releaseDownloadFinally = CompletableDeferred<Unit>()
        val firstContext = initDesktopDIForTest(
            tempDir.resolve("download-first"),
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            startDownloadWorker = true,
            downloadFileOperations = FinallyTrackingDownloadFileOperations(
                downloadExecuteStarted,
                downloadReadStarted,
                downloadFinallyEntered,
                releaseDownloadFinally,
            ),
        )
        try {
            val oldManager = Injekt.get<DesktopDownloadManager>()
            oldManager.enqueue(
                DownloadItem(
                    sourceId = 1,
                    mangaTitle = "Old manga",
                    chapterName = "Old chapter",
                    chapterId = 401,
                    pageUrls = listOf("https://downloads.example.test/page.jpg"),
                ),
            )
            withTimeout(2_000) { downloadExecuteStarted.await() }
            withTimeout(2_000) { downloadReadStarted.await() }
            assertEquals(DownloadStatus.DOWNLOADING, oldManager.queue.value.single().status)
            val closeOldManager = async(Dispatchers.IO) { oldManager.stopAndJoin() }
            withTimeout(2_000) { downloadFinallyEntered.await() }
            assertEquals(false, closeOldManager.isCompleted)
            releaseDownloadFinally.complete(Unit)
            withTimeout(2_000) { closeOldManager.await() }
            val stoppedQueue = oldManager.queue.value
            firstContext.closeAndJoin()
            val secondContext = initDesktopDIForTest(
                tempDir.resolve("download-second"),
                DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            )
            try {
                assertEquals(0, oldManager.activeJobCount)
                assertThrows(Exception::class.java) {
                    runBlocking { firstContext.handler.await { mangasQueries.resetViewerFlags() } }
                }
                assertSame(secondContext.handler, Injekt.get<DatabaseHandler>())
                assertSame(Injekt.get<DesktopDownloadManager>(), Injekt.get<DownloadRepository>())
                val freshEntry = mihon.domain.download.DownloadQueueEntry(
                    chapterId = 402,
                    mangaId = 403,
                    sourceId = 2,
                    mangaTitle = "Fresh manga",
                    chapterName = "Fresh chapter",
                    chapterUrl = "/fresh-chapter",
                    pageUrls = emptyList(),
                    status = DownloadQueueStatus.QUEUED,
                    progress = 0,
                    position = 0,
                    retryCount = 0,
                    failure = null,
                )
                Injekt.get<EnqueueDownload>()(freshEntry)
                assertEquals(listOf(freshEntry), PersistentDownloadStore(secondContext.handler.db).entries())
                assertEquals(stoppedQueue, oldManager.queue.value)
            } finally {
                secondContext.closeAndJoin()
            }
        } finally {
            releaseDownloadFinally.complete(Unit)
            firstContext.closeAndJoin()
        }
    }

    private class FinallyTrackingDownloadFileOperations(
        private val executeStarted: CompletableDeferred<Unit>,
        private val started: CompletableDeferred<Unit>,
        private val finallyEntered: CompletableDeferred<Unit>,
        private val releaseFinally: CompletableDeferred<Unit>,
    ) : DownloadFileOperations by DefaultDownloadFileOperations {
        override fun execute(client: OkHttpClient, url: String): Response {
            executeStarted.complete(Unit)
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(byteArrayOf().toResponseBody())
                .build()
        }

        override suspend fun readBody(response: Response): ByteArray = try {
            started.complete(Unit)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                finallyEntered.complete(Unit)
                releaseFinally.await()
            }
        }
    }

    private fun loginRequest(url: HttpUrl = "https://reader.example.com/chapter".toHttpUrl()) = SourceLoginRequest(
        url = url,
        requiredCookieNames = setOf("cf_clearance"),
        timeoutMillis = 30_000,
    )

    private fun loginSession(value: String, url: HttpUrl = loginRequest().url) = AuthenticatedSession(
        listOf(AuthenticatedCookie("cf_clearance", value, url.host, true, "/", null, url.isHttps, true)),
    )

    @Test
    fun `测试配置入口使用隔离内存存储并解析实际依赖`(@TempDir tempDir: File) = runBlocking {
        val manga = Manga.create().copy(id = 71, source = 9, title = "Queued manga", favorite = true)
        val chapter = Chapter.create().copy(id = 72, mangaId = manga.id, name = "New chapter", url = "/72")
        val configuredStore = DesktopPreferenceStore(
            Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}"),
        )
        configuredStore.getBoolean("download_new", false).set(true)
        val context = initDesktopDIForTest(
            tempDir,
            configuredStore,
            libraryProvider = { listOf(LibraryManga(manga, emptyList(), 0, 0, 0, 0, 0, 0)) },
            updateManga = { LibraryUpdateChecker.UpdateResult(1, listOf(chapter)) },
            startDownloadWorker = false,
        )

        try {
        val handler = context.handler
        assertSame(configuredStore, Injekt.get<PreferenceStore>())
        assertNotNull(Injekt.get<DesktopAppPreferences>())
        assertNotNull(Injekt.get<ReaderPreferences>())
        assertNotNull(Injekt.get<LibraryUpdateScheduler>())
        assertNotNull(Injekt.get<DesktopNetworkHelper>())
        assertNotNull(Injekt.get<DesktopTaskScheduler>())
        assertNotNull(Injekt.get<DesktopBatchMigrationController>())
        assertNotNull(Injekt.get<DesktopDownloadManager>())
        assertNotNull(Injekt.get<DownloadRepository>())
        assertNotNull(Injekt.get<EnqueueDownload>())
        assertNotNull(Injekt.get<IsChapterDownloaded>())
        assertNotNull(Injekt.get<RecordReadingProgress>())
        assertNotNull(Injekt.get<AutoBackupScheduler>())
        assertNotNull(Injekt.get<DesktopExtensionManager>())
        assertNotNull(Injekt.get<TrackRepository>())
        assertNotNull(Injekt.get<TrackerSessionProvider>())
        assertNotNull(Injekt.get<TrackerServiceRegistry>())
        assertNotNull(Injekt.get<DesktopCredentialStore>())
        assertNotNull(Injekt.get<ReadingProgressTrackSync>())
        assertNotNull(Injekt.get<DesktopTrackerSyncScheduler>())
        assertEquals(emptySet<Long>(), Injekt.get<TrackerSessionProvider>().loggedInTrackerIds().first())
        assertNotNull(Injekt.get<BackupRestoreScreenModelFactory>())
        assertNotNull(Injekt.get<CreateCategoryWithName>())
        assertNotNull(Injekt.get<GetCategories>())
        assertNotNull(Injekt.get<RenameCategory>())
        assertNotNull(Injekt.get<DeleteCategory>())
        assertNotNull(Injekt.get<ReorderCategory>())
        assertNotNull(Injekt.get<UpdateLibraryMembership>())
        assertNotNull(Injekt.get<DesktopCustomCoverStore>())
        assertNotNull(LibraryScreenModelFactory.create())
        assertNotNull(MangaDetailScreenModelFactory.create(manga.id))
        assertNotNull(StatsScreenModel(Injekt.get<GetLibraryManga>().subscribe()))

        Injekt.get<LibraryUpdateScheduler>().runNow().join()
        val database = handler.db
        val queued = PersistentDownloadStore(database).entries()
        assertEquals(listOf(chapter.id), queued.map { it.chapterId })
        assertEquals(listOf(DownloadQueueStatus.QUEUED), queued.map { it.status })

        val preference = Injekt.get<PreferenceStore>().getString("wiring_observe", "initial")
        val changed = async(start = CoroutineStart.UNDISPATCHED) { preference.changes().first() }
        preference.set("updated")
        assertEquals("updated", withTimeout(1_000) { changed.await() })
        } finally {
            context.closeAndJoin()
        }
    }
}
