package mihon.desktop.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mihon.injekt.patchInjekt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mihon.desktop.extension.DesktopExtensionLoader
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.source.DesktopSourceManager
import eu.kanade.tachiyomi.network.NetworkHelper
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import mihon.desktop.domain.DesktopSystemNotifier
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.DesktopSourceRepository
import mihon.desktop.source.LocalSourceScanService
import mihon.desktop.settings.LibraryCategoryPrefs
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.DesktopStorageFolderProvider
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.download.PersistentDownloadStore
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import mihon.data.repository.ExtensionRepoRepositoryImpl
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.domain.DesktopMigrateMangaUseCase
import mihon.desktop.js.DesktopJsEngine
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.domain.ReaderModeMemoryCleaner
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.domain.DesktopTrackerSessionProvider
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.tracking.DesktopTrackerServiceRegistry
import mihon.desktop.tracking.DesktopTrackerSyncScheduler
import mihon.desktop.migration.DesktopBatchMigrationController
import mihon.desktop.domain.MigrationOptions
import mihon.desktop.network.AuthenticatedCookieLookup
import mihon.desktop.network.DesktopAuthenticatedSessionCommitter
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopChallengeBrowserLoginBridge
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.network.FlareSolverrClient
import mihon.desktop.test.http.MigrationBatchTestBridge
import mihon.desktop.test.http.TrackingTestBridge
import mihon.desktop.tracking.TrackingTestModeController
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.reader.ReaderPreferences
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.upcoming.interactor.GetUpcomingManga
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.data.release.DesktopPlatformInfo
import tachiyomi.data.release.PlatformInfo
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.SyncReadingProgressWithTrack
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.repository.UpdatesRepository
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.AuthenticatedCookie
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Initializes all desktop DI bindings.
 * Call once at application startup before showing any UI.
 */
fun initDesktopDI() {
    val paths = DesktopPlatformPaths.current()
    val preferenceStore = initConfigLayer(paths.configDir)
    val networkHelper = initNetworkLayer(paths, preferenceStore)
    val handler = initDataLayer(paths)
    initExtensionLayer(paths, networkHelper, handler)
    initDomainLayer(handler)
    initUILayer(paths, preferenceStore, networkHelper, handler)
}

internal fun initDesktopConfigurationForTest(appDir: File, preferenceStore: PreferenceStore) {
    registerDesktopSettings(preferenceStore)
    registerDesktopReader(preferenceStore)
    Injekt.addSingleton<PreferenceStore>(preferenceStore)
    Injekt.addSingleton<FolderProvider>(DesktopStorageFolderProvider(appDir))
    Injekt.addSingleton<PlatformInfo>(DesktopPlatformInfo())
}

internal suspend fun initDesktopDIForTest(
    appDir: File,
    preferenceStore: PreferenceStore,
    libraryProvider: (suspend () -> List<tachiyomi.domain.library.model.LibraryManga>)? = null,
    updateManga: (suspend (tachiyomi.domain.manga.model.Manga) -> LibraryUpdateChecker.UpdateResult)? = null,
    startDownloadWorker: Boolean = false,
    downloadFileOperations: mihon.desktop.download.DownloadFileOperations = mihon.desktop.download.DefaultDownloadFileOperations,
    browserOpener: DesktopBrowserOpener? = null,
): DesktopTestDIContext {
    activeDesktopTestDIContext?.closeAndJoin()
    patchInjekt()
    val paths = desktopPaths(appDir)
    initDesktopConfigurationForTest(appDir, preferenceStore)
    val networkHelper = initNetworkLayer(paths, preferenceStore, browserOpener)
    val handler = initDataLayer(paths)
    initExtensionLayer(paths, networkHelper, handler)
    initDomainLayer(handler)
    initUILayer(
        paths,
        preferenceStore,
        networkHelper,
        handler,
        libraryProvider,
        updateManga,
        startDownloadWorker,
        downloadFileOperations,
    )
    return DesktopTestDIContext(
        handler = handler as JvmDatabaseHandler,
        networkHelper = networkHelper,
        scheduler = Injekt.get(),
        downloadManager = Injekt.get(),
        extensionManager = Injekt.get(),
    ).also { activeDesktopTestDIContext = it }
}

private var activeDesktopTestDIContext: DesktopTestDIContext? = null

internal class DesktopTestDIContext(
    val handler: JvmDatabaseHandler,
    private val networkHelper: DesktopNetworkHelper,
    private val scheduler: LibraryUpdateScheduler,
    private val downloadManager: mihon.desktop.download.DesktopDownloadManager,
    private val extensionManager: DesktopExtensionManager,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        scheduler.stop()
    }

    suspend fun closeAndJoin() {
        if (closed) return
        closed = true
        scheduler.stopAndJoin()
        // Cancel calls before joining downloads because OkHttp execute() is blocking.
        networkHelper.client.dispatcher.cancelAll()
        downloadManager.stopAndJoin()
        extensionManager.close()
        networkHelper.close()
        handler.close()
        if (activeDesktopTestDIContext === this) activeDesktopTestDIContext = null
    }
}

// ── Config layer ─────────────────────────────────────────────────────────────
// Preferences, storage, platform metadata.
// No external dependencies.

internal fun initConfigLayer(appDir: File): DesktopPreferenceStore {
    val preferenceStore = DesktopPreferenceStore()
    Injekt.addSingleton<PreferenceStore>(preferenceStore)
    registerDesktopSettings(preferenceStore)
    registerDesktopReader(preferenceStore)
    Injekt.addSingleton<FolderProvider>(DesktopStorageFolderProvider())
    Injekt.addSingleton<PlatformInfo>(DesktopPlatformInfo())
    return preferenceStore
}

private fun registerDesktopSettings(preferenceStore: PreferenceStore) {
    Injekt.addSingleton(DesktopAppPreferences(preferenceStore))
    Injekt.addSingleton(LibraryCategoryPrefs(preferenceStore))
}

private fun registerDesktopReader(preferenceStore: PreferenceStore) {
    Injekt.addSingleton(ReaderPreferences(preferenceStore))
}

// ── Network layer ─────────────────────────────────────────────────────────────
// HTTP client, JSON serializer, DoH, Cloudflare bypass.
// Depends on: config layer (preferenceStore for DoH setting).

internal fun initNetworkLayer(
    paths: DesktopPlatformPaths,
    preferenceStore: PreferenceStore,
    browserOpener: DesktopBrowserOpener? = null,
): DesktopNetworkHelper {
    return registerDesktopNetwork(paths, preferenceStore, browserOpener)
}

private fun registerDesktopNetwork(
    paths: DesktopPlatformPaths,
    preferenceStore: PreferenceStore,
    browserOpener: DesktopBrowserOpener?,
): DesktopNetworkHelper {
    val dohProvider = preferenceStore.getObjectFromString(
        key = "doh_provider",
        defaultValue = mihon.desktop.settings.DohProvider.OFF,
        serializer = { it.name },
        deserializer = { mihon.desktop.settings.DohProvider.valueOf(it) },
    ).get()
    val appPreferences = Injekt.get<DesktopAppPreferences>()
    val browserBridge = DesktopChallengeBrowserLoginBridge(browserOpener = browserOpener)
    lateinit var networkHelper: DesktopNetworkHelper
    lateinit var authenticatedSessionCommitter: DesktopAuthenticatedSessionCommitter
    val challengeManager = mihon.desktop.network.CloudflareChallengeManager(
        browserAdapterProvider = browserBridge::adapterFor,
        committerProvider = { authenticatedSessionCommitter },
        flareSolverrClientProvider = {
            appPreferences.flareSolverrRuntimeConfig()?.let { config ->
                FlareSolverrClient(config.baseUrl.toString(), networkHelper.client)
            }
        },
        authenticatedCookieLookup = AuthenticatedCookieLookup { url ->
            networkHelper.cookieJar.get(url).map { cookie ->
                AuthenticatedCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    hostOnly = cookie.hostOnly,
                    path = cookie.path,
                    expiresAt = cookie.expiresAt,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                )
            }
        },
    )
    networkHelper = DesktopNetworkHelper(
        cacheDir = paths.networkCacheDir,
        cookieStorageFile = paths.cookiesFile,
        dohProvider = dohProvider,
        challengeManager = challengeManager,
    )
    authenticatedSessionCommitter = DesktopAuthenticatedSessionCommitter(networkHelper.cookieJar)
    Injekt.addSingleton(browserBridge)
    Injekt.addSingleton(challengeManager)
    Injekt.addSingleton(authenticatedSessionCommitter)
    Injekt.addSingleton<tachiyomi.domain.source.service.AuthenticatedSessionCommitter>(authenticatedSessionCommitter)
    Injekt.addSingleton(
        browserOpener?.let { DesktopSourceLoginSessionFactory(authenticatedSessionCommitter, it) }
            ?: DesktopSourceLoginSessionFactory(authenticatedSessionCommitter),
    )
    Injekt.addSingleton(networkHelper)
    Injekt.addSingleton(networkHelper.client)
    Injekt.addSingleton(NetworkHelper(networkHelper.client))
    Injekt.addSingleton(
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )
    return networkHelper
}

// ── Data layer ────────────────────────────────────────────────────────────────
// SQLite database + all repository implementations.
// No dependency on network or extensions.

internal fun initDataLayer(paths: DesktopPlatformPaths): DatabaseHandler {
    val handler = initDatabase(paths.databaseFile)
    val mangaRepository: MangaRepository = MangaRepositoryImpl(handler)
    val chapterRepository: ChapterRepository = ChapterRepositoryImpl(handler)
    val categoryRepository: CategoryRepository = CategoryRepositoryImpl(handler)
    val historyRepository: HistoryRepository = HistoryRepositoryImpl(handler)
    val updatesRepository: UpdatesRepository = UpdatesRepositoryImpl(handler)
    val creatorRepository: CreatorRepository = CreatorRepositoryImpl(handler)
    val extensionRepoRepository: ExtensionRepoRepository = ExtensionRepoRepositoryImpl(handler)
    val trackRepository: TrackRepository = TrackRepositoryImpl(handler)
    Injekt.addSingleton(mangaRepository)
    Injekt.addSingleton(chapterRepository)
    Injekt.addSingleton(categoryRepository)
    Injekt.addSingleton(historyRepository)
    Injekt.addSingleton(updatesRepository)
    Injekt.addSingleton(creatorRepository)
    Injekt.addSingleton(extensionRepoRepository)
    Injekt.addSingleton(trackRepository)
    return handler
}

internal fun initDataLayer(appDir: File): DatabaseHandler = initDataLayer(
    desktopPaths(appDir),
)

private fun desktopPaths(appDir: File) =
    DesktopPlatformPaths(
        configDir = appDir,
        databaseFile = File(appDir, "mihon.db"),
        networkCacheDir = File(appDir, "cache/network"),
        cookiesFile = File(appDir, "cookies.json"),
        downloadsDir = File(appDir, "downloads"),
        extensionsDir = File(appDir, "extensions"),
        coversDir = File(appDir, "covers"),
        logsDir = File(appDir, "logs"),
        backupsDir = File(appDir, "backups"),
        testScreenshotsDir = File(appDir, "test-screenshots"),
    )

// ── Extension layer ────────────────────────────────────────────────────────────
// Extension loader, source manager, extension API.
// Depends on: data layer (extensionRepoRepository), network layer.

internal fun initExtensionLayer(paths: DesktopPlatformPaths, networkHelper: DesktopNetworkHelper, handler: DatabaseHandler) {
    registerDesktopExtension(paths, networkHelper, handler)
}

private fun registerDesktopExtension(paths: DesktopPlatformPaths, networkHelper: DesktopNetworkHelper, handler: DatabaseHandler) {
    val extensionRepoRepository = Injekt.get<ExtensionRepoRepository>()
    val extensionApi = DesktopExtensionApi(
        client = networkHelper.client,
        json = Injekt.get<Json>(),
        extensionRepoRepository = extensionRepoRepository,
    )
    val extensionManager = DesktopExtensionManager(
        loader = DesktopExtensionLoader(paths.extensionsDir),
        artifactProvider = extensionApi::downloadArtifact,
    )
    extensionManager.loadAll()
    Injekt.addSingleton(extensionManager)
    Injekt.addSingleton(extensionApi)
    val sourceManager = DesktopSourceManager(extensionManager, Injekt.get())
    Injekt.addSingleton<SourceManager>(sourceManager)
    Injekt.addSingleton(sourceManager)
    registerDesktopTracking(sourceManager, networkHelper.client)
    Injekt.addSingleton<SourceRepository>(DesktopSourceRepository(sourceManager, handler))
    val extensionRepoService = ExtensionRepoService(Injekt.get<NetworkHelper>(), Injekt.get<Json>())
    Injekt.addSingleton(extensionRepoService)
    Injekt.addSingleton(GetExtensionRepo(extensionRepoRepository))
    Injekt.addSingleton(CreateExtensionRepo(extensionRepoRepository, extensionRepoService))
    Injekt.addSingleton(DeleteExtensionRepo(extensionRepoRepository))
    Injekt.addSingleton(ReplaceExtensionRepo(extensionRepoRepository))
    Injekt.addSingleton(UpdateExtensionRepo(extensionRepoRepository, extensionRepoService))
}

private fun registerDesktopTracking(sourceManager: SourceManager, client: OkHttpClient) {
    val trackRepository = Injekt.get<TrackRepository>()
    val credentialStore = DesktopCredentialStore()
    val enhancedTrackerContexts = mihon.desktop.tracking.DesktopEnhancedTrackerContextProvider().apply {
        attach(sourceManager)
    }
    val trackerRegistry = DesktopTrackerServiceRegistry.production(
        client = client,
        json = Injekt.get<Json>(),
        credentialStore = credentialStore,
        enhancedContextProvider = enhancedTrackerContexts,
        sourceClient = enhancedTrackerContexts::sourceClient,
    )
    Injekt.addSingleton<TrackerServiceRegistry>(trackerRegistry)
    TrackingTestBridge.controller = TrackingTestModeController(trackRepository, trackerRegistry)
    Injekt.addSingleton(credentialStore)
    Injekt.addSingleton<tachiyomi.domain.track.service.EnhancedTrackerContextProvider>(enhancedTrackerContexts)
    Injekt.addSingleton(enhancedTrackerContexts)
    Injekt.addSingleton<TrackerSessionProvider>(DesktopTrackerSessionProvider(trackerRegistry))
}

// ── Domain layer ──────────────────────────────────────────────────────────────
// All use cases. Only depends on repositories (data layer).
// Can be initialised in unit tests without network/extension/UI layers.

internal fun initDomainLayer(handler: DatabaseHandler) {
    val mangaRepository = Injekt.get<MangaRepository>()
    val chapterRepository = Injekt.get<ChapterRepository>()
    val categoryRepository = Injekt.get<CategoryRepository>()
    val historyRepository = Injekt.get<HistoryRepository>()
    val updatesRepository = Injekt.get<UpdatesRepository>()
    val creatorRepository = Injekt.get<CreatorRepository>()
    val sourceMangaSearchService = SourceMangaSearchService()

    Injekt.addSingleton(sourceMangaSearchService)
    Injekt.addSingleton(GetLibraryManga(mangaRepository))
    Injekt.addSingleton(GetDuplicateLibraryManga(mangaRepository))
    Injekt.addSingleton(GetUpcomingManga(mangaRepository))
    Injekt.addSingleton(GetManga(mangaRepository))
    Injekt.addSingleton(GetMangaWithChapters(mangaRepository, chapterRepository))
    Injekt.addSingleton(GetChapter(chapterRepository))
    Injekt.addSingleton(GetChaptersByMangaId(chapterRepository))
    Injekt.addSingleton(GetCategories(categoryRepository))
    Injekt.addSingleton(GetExcludedScanlators(handler))
    Injekt.addSingleton(SetExcludedScanlators(handler))
    Injekt.addSingleton(GetAvailableScanlators(chapterRepository))
    Injekt.addSingleton(GetHistory(historyRepository))
    Injekt.addSingleton(RemoveHistory(historyRepository))
    Injekt.addSingleton(GetUpdates(updatesRepository))

    val networkToLocalManga = NetworkToLocalManga(mangaRepository)
    val updateChapter = UpdateChapter(chapterRepository)
    val upsertHistory = UpsertHistory(historyRepository)
    Injekt.addSingleton(networkToLocalManga)
    Injekt.addSingleton(updateChapter)
    Injekt.addSingleton(upsertHistory)

    val saveSourceMangaForDetails = SaveSourceMangaForDetails(networkToLocalManga, mangaRepository, chapterRepository)
    Injekt.addSingleton(saveSourceMangaForDetails)
    Injekt.addSingleton(GetFavorites(mangaRepository))
    val setMangaCategories = SetMangaCategories(mangaRepository)
    Injekt.addSingleton(setMangaCategories)
    val updateLibraryMembership = UpdateLibraryMembership(mangaRepository)
    Injekt.addSingleton(updateLibraryMembership)
    Injekt.addSingleton(
        DesktopMigrateMangaUseCase(
            saveSourceMangaForDetails = saveSourceMangaForDetails,
            getChaptersByMangaId = Injekt.get<GetChaptersByMangaId>(),
            updateChapter = updateChapter,
            getCategories = Injekt.get<GetCategories>(),
            mangaRepository = mangaRepository,
        ),
    )
    Injekt.addSingleton(UpdateMangaNotes(mangaRepository))
    Injekt.addSingleton(ReaderModeMemoryCleaner(mangaRepository))
    Injekt.addSingleton(LibraryUpdateChecker(chapterRepository))
    Injekt.addSingleton(CreatorDiscoveryService(creatorRepository, sourceMangaSearchService))
}

// ── UI / Service layer ────────────────────────────────────────────────────────
// Downloads, backup, JS engine, local source scanner, notifications,
// reader progress tracker, preferences used by UI.
// Depends on: all lower layers.

internal fun initUILayer(
    paths: DesktopPlatformPaths,
    preferenceStore: PreferenceStore,
    networkHelper: DesktopNetworkHelper,
    handler: DatabaseHandler,
    libraryProvider: (suspend () -> List<tachiyomi.domain.library.model.LibraryManga>)? = null,
    updateManga: (suspend (tachiyomi.domain.manga.model.Manga) -> LibraryUpdateChecker.UpdateResult)? = null,
    startDownloadWorker: Boolean = true,
    downloadFileOperations: mihon.desktop.download.DownloadFileOperations = mihon.desktop.download.DefaultDownloadFileOperations,
) {
    val mangaRepository = Injekt.get<MangaRepository>()
    val chapterRepository = Injekt.get<ChapterRepository>()
    val categoryRepository = Injekt.get<CategoryRepository>()
    val historyRepository = Injekt.get<HistoryRepository>()
    val appPreferences = Injekt.get<DesktopAppPreferences>()
    Injekt.addSingleton(
        BackupRestoreScreenModelFactory(
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            categoryRepository = categoryRepository,
            historyRepository = historyRepository,
            getExcludedScanlators = Injekt.get(),
            setExcludedScanlators = Injekt.get(),
            trackRepository = Injekt.get(),
            preferenceStore = preferenceStore,
            extensionRepoRepository = Injekt.get(),
        ),
    )

    val database = (handler as JvmDatabaseHandler).db
    val (downloadPreferences, downloadManager) = registerDesktopDownload(
        paths,
        preferenceStore,
        database,
        startDownloadWorker,
        downloadFileOperations,
    )
    val readingProgress = RecordReadingProgress(SqlDelightReadingProgressRepository(database))
    Injekt.addSingleton<mihon.domain.download.DownloadRepository>(downloadManager)
    Injekt.addSingleton(mihon.domain.download.EnqueueDownload(downloadManager))
    Injekt.addSingleton(mihon.domain.download.IsChapterDownloaded(downloadManager))
    Injekt.addSingleton(mihon.domain.download.ObserveDownloadQueue(downloadManager))
    Injekt.addSingleton(mihon.domain.download.CancelDownload(downloadManager))
    Injekt.addSingleton(mihon.domain.download.RetryDownload(downloadManager))
    Injekt.addSingleton(mihon.domain.download.TransitionDownload(downloadManager))
    Injekt.addSingleton(mihon.domain.download.RecoverDownloads(downloadManager))
    Injekt.addSingleton(readingProgress)
    val sharedDownloadPreferences = DownloadPreferences(preferenceStore)
    if (
        preferenceStore.getBoolean("auto_download_new_chapters", false).get() &&
        !sharedDownloadPreferences.downloadNewChapters().get()
    ) {
        sharedDownloadPreferences.downloadNewChapters().set(true)
    }
    val filterChaptersForDownload = FilterChaptersForDownload(
        Injekt.get(),
        sharedDownloadPreferences,
        Injekt.get(),
    )
    Injekt.addSingleton(sharedDownloadPreferences)
    Injekt.addSingleton(filterChaptersForDownload)
    val notificationService = registerDesktopLibrary(
        paths,
        preferenceStore,
        categoryRepository,
        appPreferences,
        filterChaptersForDownload,
        Injekt.get(),
        libraryProvider,
        updateManga,
    )

    lateinit var trackSync: ReadingProgressTrackSync
    val trackerSyncScheduler = DesktopTrackerSyncScheduler(Injekt.get<DesktopTaskScheduler>()) { trackSync }
    trackSync = SyncReadingProgressWithTrack(
        repository = Injekt.get<TrackRepository>(),
        registry = Injekt.get<TrackerServiceRegistry>(),
        retryScheduler = trackerSyncScheduler,
    )
    Injekt.addSingleton<ReadingProgressTrackSync>(trackSync)
    Injekt.addSingleton(trackerSyncScheduler)

    val batchMigrationController = DesktopBatchMigrationController(
        scheduler = Injekt.get(),
        executeMigration = { mangaId, target, options ->
            val sourceManga = Injekt.get<GetManga>().await(mangaId) ?: error("Source manga no longer exists")
            val targetSource = Injekt.get<SourceManager>().get(target.sourceId) as? CatalogueSource
                ?: error("Target source is not installed")
            val targetManga = SManga.create().apply {
                url = target.url
                title = target.title
                thumbnail_url = target.thumbnailUrl
                author = target.author
                artist = target.artist
                description = target.description
                genre = target.genre?.joinToString(", ")
                status = target.status
            }
            Injekt.get<DesktopMigrateMangaUseCase>().await(
                sourceManga = sourceManga,
                targetSManga = targetManga,
                targetSourceId = target.sourceId,
                targetChapters = targetSource.getChapterList(targetManga),
                options = MigrationOptions(options.copyChapters, options.copyCategories, options.copyNotes),
                replace = options.replace,
            )
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
    Injekt.addSingleton(batchMigrationController)
    MigrationBatchTestBridge.controller = batchMigrationController

    Injekt.addSingleton(DesktopJsEngine())
    Injekt.addSingleton(
        LocalSourceScanService(
            prefs = appPreferences,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ),
    )
    Injekt.addSingleton(
        ReaderProgressTracker(
            recordReadingProgress = readingProgress,
            appPreferences = appPreferences,
            downloadPreferences = downloadPreferences,
            downloadManager = downloadManager,
            trackSync = trackSync,
            extensionPackageForSource = Injekt.get<DesktopExtensionManager>()::getExtensionPackage,
        ),
    )

    val autoBackupScheduler = registerDesktopBackup(
        appPreferences,
        mangaRepository,
        chapterRepository,
        categoryRepository,
        historyRepository,
    )
    Injekt.addSingleton(
        mihon.desktop.DesktopAppRuntime.create(
            libraryUpdateScheduler = Injekt.get<LibraryUpdateScheduler>(),
            localSourceScanService = Injekt.get<LocalSourceScanService>(),
            autoBackupScheduler = autoBackupScheduler,
            readerModeMemoryCleaner = Injekt.get<ReaderModeMemoryCleaner>(),
            trackerSyncScheduler = trackerSyncScheduler,
            batchMigrationController = batchMigrationController,
        ),
    )
}

private fun registerDesktopLibrary(
    paths: DesktopPlatformPaths,
    preferenceStore: PreferenceStore,
    categoryRepository: CategoryRepository,
    appPreferences: DesktopAppPreferences,
    filterChaptersForDownload: FilterChaptersForDownload,
    enqueueDownload: mihon.domain.download.EnqueueDownload,
    libraryProvider: (suspend () -> List<tachiyomi.domain.library.model.LibraryManga>)? = null,
    updateManga: (suspend (tachiyomi.domain.manga.model.Manga) -> LibraryUpdateChecker.UpdateResult)? = null,
): DesktopNotificationService {
    Injekt.addSingleton(paths)
    Injekt.addSingleton(DesktopCustomCoverStore(paths.coversDir))
    val notificationService = DesktopNotificationService()
    Injekt.addSingleton(notificationService)
    val taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(paths.configDir.toPath().resolve("background-tasks.json")))
    val taskNotifier = DesktopSystemNotifier(system = { false }, fallback = notificationService)
    Injekt.addSingleton(taskScheduler)
    Injekt.addSingleton(taskNotifier)
    val libraryPreferences = LibraryPreferences(preferenceStore)
    Injekt.addSingleton(libraryPreferences)
    Injekt.addSingleton(CreateCategoryWithName(categoryRepository, libraryPreferences))
    Injekt.addSingleton(RenameCategory(categoryRepository))
    Injekt.addSingleton(DeleteCategory(categoryRepository, libraryPreferences, Injekt.get()))
    Injekt.addSingleton(ReorderCategory(categoryRepository))
    Injekt.addSingleton(
        LibraryUpdateScheduler(
            appPreferences = appPreferences,
            updateChecker = Injekt.get<LibraryUpdateChecker>(),
            getLibraryManga = Injekt.get<GetLibraryManga>(),
            sourceManager = Injekt.get<SourceManager>(),
            categoryRepository = categoryRepository,
            notificationService = notificationService,
            creatorDiscoveryService = Injekt.get<CreatorDiscoveryService>(),
            taskScheduler = taskScheduler,
            taskNotifier = taskNotifier,
            libraryProvider = libraryProvider,
            updateManga = updateManga,
            autoDownload = { manga, chapters ->
                filterChaptersForDownload.await(manga, chapters).forEach { chapter ->
                    enqueueDownload(
                        mihon.domain.download.DownloadQueueEntry(
                            chapterId = chapter.id,
                            mangaId = manga.id,
                            sourceId = manga.source,
                            mangaTitle = manga.title,
                            chapterName = chapter.name,
                            chapterUrl = chapter.url,
                            pageUrls = emptyList(),
                            position = System.nanoTime(),
                        ),
                    )
                }
            },
        ),
    )
    Injekt.addSingleton(UpdatesPreferences(preferenceStore))
    return notificationService
}

private fun registerDesktopDownload(
    paths: DesktopPlatformPaths,
    preferenceStore: PreferenceStore,
    database: tachiyomi.data.Database,
    startWorker: Boolean = true,
    fileOperations: mihon.desktop.download.DownloadFileOperations = mihon.desktop.download.DefaultDownloadFileOperations,
): Pair<DesktopDownloadPreferences, mihon.desktop.download.DesktopDownloadManager> {
    val downloadPreferences = DesktopDownloadPreferences(preferenceStore)
    val downloadProvider = mihon.desktop.download.DesktopDownloadProvider(paths.downloadsDir)
    val downloadManager = mihon.desktop.download.DesktopDownloadManager(
        provider = downloadProvider,
        downloadPreferences = downloadPreferences,
        store = PersistentDownloadStore(database),
        fileOperations = fileOperations,
    )
    if (startWorker) downloadManager.start()
    Injekt.addSingleton(downloadPreferences)
    Injekt.addSingleton(downloadProvider)
    Injekt.addSingleton(downloadManager)
    return downloadPreferences to downloadManager
}

private fun registerDesktopBackup(
    appPreferences: DesktopAppPreferences,
    mangaRepository: MangaRepository,
    chapterRepository: ChapterRepository,
    categoryRepository: CategoryRepository,
    historyRepository: HistoryRepository,
): mihon.desktop.backup.AutoBackupScheduler {
    val scheduler = mihon.desktop.backup.AutoBackupScheduler(
        appPreferences = appPreferences,
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        categoryRepository = categoryRepository,
        historyRepository = historyRepository,
        excludedScanlatorsForManga = { mangaId ->
            Injekt.get<GetExcludedScanlators>().await(mangaId).toList()
        },
    )
    Injekt.addSingleton(scheduler)
    return scheduler
}

/**
 * Initializes the SQLDelight database backed by a JDBC SQLite file.
 *
 * For a fresh install, creates the schema from scratch (does NOT run Android
 * legacy migrations). For an existing database, migrates incrementally.
 * Migration failures are fatal and preserve the original database for diagnosis/recovery.
 */
internal fun createDriver(dbFile: File): SqlDriver {
    val schema = tachiyomi.data.Database.Schema
    val isNew = !dbFile.exists() || dbFile.length() == 0L
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    try {
        if (isNew) {
            schema.create(driver)
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0, null)
        } else {
            val currentVersion = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                parameters = 0,
                mapper = { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(
                        if (cursor.next().value) cursor.getLong(0)?.toInt() ?: 0 else 0,
                    )
                },
                binders = null,
            ).value
            if (currentVersion < schema.version) {
                val migrationVersion = recoverInterruptedMigrationVersion(driver, currentVersion)
                schema.migrate(driver, migrationVersion.toLong(), schema.version)
                driver.execute(null, "PRAGMA user_version = ${schema.version}", 0, null)
            }
        }
    } catch (e: Exception) {
        driver.close()
        throw IllegalStateException(
            "Unable to open or migrate Mihon database at ${dbFile.absolutePath}; the original database was preserved",
            e,
        )
    }
    return driver
}

private fun recoverInterruptedMigrationVersion(driver: SqlDriver, recordedVersion: Int): Int {
    var recoveredVersion = recordedVersion
    if (recoveredVersion < 13 && driver.hasTable("download_queue")) recoveredVersion = 13
    if (recoveredVersion < 14 && driver.hasTable("reading_events")) recoveredVersion = 14
    if (recoveredVersion < 15 && driver.hasColumn("download_queue", "failure")) recoveredVersion = 15
    return recoveredVersion
}

private fun SqlDriver.hasTable(table: String): Boolean = executeQuery(
    identifier = null,
    sql = "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?)",
    parameters = 1,
    mapper = { cursor ->
        app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
    },
    binders = { bindString(0, table) },
).value

private fun SqlDriver.hasColumn(table: String, column: String): Boolean {
    require(table.all { it.isLetterOrDigit() || it == '_' })
    require(column.all { it.isLetterOrDigit() || it == '_' })
    return executeQuery(
        identifier = null,
        sql = "SELECT EXISTS(SELECT 1 FROM pragma_table_info('$table') WHERE name = '$column')",
        parameters = 0,
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
        },
        binders = null,
    ).value
}

private fun initDatabase(dbFile: File): DatabaseHandler {
    val driver = createDriver(dbFile)
    val database = tachiyomi.data.Database(
        driver = driver,
        historyAdapter = tachiyomi.data.History.Adapter(
            last_readAdapter = DateColumnAdapter,
        ),
        mangasAdapter = tachiyomi.data.Mangas.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
        ),
    )
    val handler = JvmDatabaseHandler(db = database, driver = driver)
    Injekt.addSingleton<DatabaseHandler>(handler)
    Injekt.addSingleton(database)
    Injekt.addSingleton(driver)
    return handler
}
