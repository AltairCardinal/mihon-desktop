package mihon.desktop.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import mihon.desktop.extension.DesktopExtensionLoader
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
import mihon.data.repository.ExtensionRepoRepositoryImpl
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.domain.AddMangaToLibrary
import mihon.desktop.domain.DesktopMangaCoverManager
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.domain.DesktopMigrateMangaUseCase
import mihon.desktop.js.DesktopJsEngine
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.domain.DesktopCategoryManager
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.domain.ReaderModeMemoryCleaner
import mihon.desktop.domain.ReaderProgressTracker
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
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.data.release.DesktopPlatformInfo
import tachiyomi.data.release.PlatformInfo
import tachiyomi.domain.category.interactor.GetCategories
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
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.repository.UpdatesRepository
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
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

internal fun initDesktopDIForTest(appDir: File, preferenceStore: PreferenceStore) {
    val paths = desktopPaths(appDir)
    initDesktopConfigurationForTest(appDir, preferenceStore)
    val networkHelper = initNetworkLayer(paths, preferenceStore)
    val handler = initDataLayer(paths)
    initExtensionLayer(paths, networkHelper, handler)
    initDomainLayer(handler)
    initUILayer(paths, preferenceStore, networkHelper, handler)
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

internal fun initNetworkLayer(paths: DesktopPlatformPaths, preferenceStore: PreferenceStore): DesktopNetworkHelper {
    return registerDesktopNetwork(paths, preferenceStore)
}

private fun registerDesktopNetwork(paths: DesktopPlatformPaths, preferenceStore: PreferenceStore): DesktopNetworkHelper {
    val dohProvider = preferenceStore.getObjectFromString(
        key = "doh_provider",
        defaultValue = mihon.desktop.settings.DohProvider.OFF,
        serializer = { it.name },
        deserializer = { mihon.desktop.settings.DohProvider.valueOf(it) },
    ).get()
    val challengeManager = mihon.desktop.network.CloudflareChallengeManager()
    Injekt.addSingleton(challengeManager)
    val networkHelper = DesktopNetworkHelper(
        cacheDir = paths.networkCacheDir,
        cookieStorageFile = paths.cookiesFile,
        dohProvider = dohProvider,
        challengeManager = challengeManager,
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
    Injekt.addSingleton(mangaRepository)
    Injekt.addSingleton(chapterRepository)
    Injekt.addSingleton(categoryRepository)
    Injekt.addSingleton(historyRepository)
    Injekt.addSingleton(updatesRepository)
    Injekt.addSingleton(creatorRepository)
    Injekt.addSingleton(extensionRepoRepository)
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
    val extensionManager = DesktopExtensionManager(
        DesktopExtensionLoader(paths.extensionsDir),
    )
    extensionManager.loadAll()
    Injekt.addSingleton(extensionManager)
    val sourceManager = DesktopSourceManager(extensionManager, Injekt.get())
    Injekt.addSingleton<SourceManager>(sourceManager)
    Injekt.addSingleton(sourceManager)
    Injekt.addSingleton<SourceRepository>(DesktopSourceRepository(sourceManager, handler))
    val extensionRepoRepository = Injekt.get<ExtensionRepoRepository>()
    val extensionRepoService = ExtensionRepoService(Injekt.get<NetworkHelper>(), Injekt.get<Json>())
    Injekt.addSingleton(extensionRepoService)
    Injekt.addSingleton(GetExtensionRepo(extensionRepoRepository))
    Injekt.addSingleton(CreateExtensionRepo(extensionRepoRepository, extensionRepoService))
    Injekt.addSingleton(DeleteExtensionRepo(extensionRepoRepository))
    Injekt.addSingleton(ReplaceExtensionRepo(extensionRepoRepository))
    Injekt.addSingleton(UpdateExtensionRepo(extensionRepoRepository, extensionRepoService))
    Injekt.addSingleton(
        DesktopExtensionApi(
            client = networkHelper.client,
            json = Injekt.get<Json>(),
            extensionRepoRepository = extensionRepoRepository,
        ),
    )
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

    val addMangaToLibrary = AddMangaToLibrary(networkToLocalManga, mangaRepository, chapterRepository)
    Injekt.addSingleton(addMangaToLibrary)
    Injekt.addSingleton(SaveSourceMangaForDetails(networkToLocalManga, mangaRepository, chapterRepository))
    Injekt.addSingleton(GetFavorites(mangaRepository))
    val setMangaCategories = SetMangaCategories(mangaRepository)
    Injekt.addSingleton(setMangaCategories)
    Injekt.addSingleton(
        DesktopMigrateMangaUseCase(
            addMangaToLibrary = addMangaToLibrary,
            getChaptersByMangaId = Injekt.get<GetChaptersByMangaId>(),
            updateChapter = updateChapter,
            getCategories = Injekt.get<GetCategories>(),
            setMangaCategories = setMangaCategories,
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
) {
    val mangaRepository = Injekt.get<MangaRepository>()
    val chapterRepository = Injekt.get<ChapterRepository>()
    val categoryRepository = Injekt.get<CategoryRepository>()
    val historyRepository = Injekt.get<HistoryRepository>()
    val appPreferences = Injekt.get<DesktopAppPreferences>()
    val updateChapter = Injekt.get<UpdateChapter>()
    val upsertHistory = Injekt.get<UpsertHistory>()

    val notificationService = registerDesktopLibrary(paths, preferenceStore, categoryRepository, appPreferences)
    val (downloadPreferences, downloadManager) = registerDesktopDownload(paths, preferenceStore)

    Injekt.addSingleton(DesktopJsEngine())
    Injekt.addSingleton(
        LocalSourceScanService(
            prefs = appPreferences,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ),
    )
    Injekt.addSingleton(
        ReaderProgressTracker(
            updateChapter = updateChapter,
            upsertHistory = upsertHistory,
            appPreferences = appPreferences,
            downloadPreferences = downloadPreferences,
            downloadManager = downloadManager,
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
        ),
    )
}

private fun registerDesktopLibrary(
    paths: DesktopPlatformPaths,
    preferenceStore: PreferenceStore,
    categoryRepository: CategoryRepository,
    appPreferences: DesktopAppPreferences,
): DesktopNotificationService {
    Injekt.addSingleton(paths)
    Injekt.addSingleton(DesktopMangaCoverManager(paths.coversDir))
    val notificationService = DesktopNotificationService()
    Injekt.addSingleton(notificationService)
    val taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(paths.configDir.toPath().resolve("background-tasks.json")))
    val taskNotifier = DesktopSystemNotifier(system = { false }, fallback = notificationService)
    Injekt.addSingleton(taskScheduler)
    Injekt.addSingleton(taskNotifier)
    Injekt.addSingleton(DesktopCategoryManager(categoryRepository))
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
        ),
    )
    Injekt.addSingleton(UpdatesPreferences(preferenceStore))
    return notificationService
}

private fun registerDesktopDownload(
    paths: DesktopPlatformPaths,
    preferenceStore: PreferenceStore,
): Pair<DesktopDownloadPreferences, mihon.desktop.download.DesktopDownloadManager> {
    val downloadPreferences = DesktopDownloadPreferences(preferenceStore)
    val downloadProvider = mihon.desktop.download.DesktopDownloadProvider(paths.downloadsDir)
    val downloadManager = mihon.desktop.download.DesktopDownloadManager(
        provider = downloadProvider,
        downloadPreferences = downloadPreferences,
    )
    downloadManager.start()
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
 * If migration fails (e.g. corrupted file), deletes the file and recreates.
 */
private fun createDriver(dbFile: File): SqlDriver {
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
                schema.migrate(driver, currentVersion.toLong(), schema.version)
            }
        }
    } catch (e: Exception) {
        // Corrupted DB: delete and recreate from scratch
        System.err.println("Database error, recreating: ${e.message}")
        driver.close()
        dbFile.delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-wal").delete()
        return createDriver(dbFile)
    }
    return driver
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
