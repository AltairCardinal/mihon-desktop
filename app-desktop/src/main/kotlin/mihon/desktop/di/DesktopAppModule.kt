package mihon.desktop.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.Json
import mihon.desktop.extension.DesktopExtensionLoader
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.source.DesktopSourceManager
import eu.kanade.tachiyomi.network.NetworkHelper
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.settings.DesktopAppPreferences
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
import mihon.desktop.domain.AddMangaToLibrary
import mihon.desktop.domain.DesktopCategoryManager
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.reader.ReaderPreferences
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.data.release.DesktopPlatformInfo
import tachiyomi.data.release.PlatformInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.repository.UpdatesRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Initializes all desktop DI bindings.
 * Call once at application startup before showing any UI.
 */
fun initDesktopDI() {
    val home = System.getProperty("user.home")
    val appDir = File(home, ".mihon").also { it.mkdirs() }

    // Preferences
    val preferenceStore = DesktopPreferenceStore()
    Injekt.addSingleton<PreferenceStore>(preferenceStore)
    Injekt.addSingleton(DesktopAppPreferences(preferenceStore))
    Injekt.addSingleton(LibraryCategoryPrefs(preferenceStore))

    // Storage
    Injekt.addSingleton<FolderProvider>(DesktopStorageFolderProvider())

    // Network (apply DoH if configured)
    val dohProvider = preferenceStore.getObjectFromString(
        key = "doh_provider",
        defaultValue = mihon.desktop.settings.DohProvider.OFF,
        serializer = { it.name },
        deserializer = { mihon.desktop.settings.DohProvider.valueOf(it) },
    ).get()
    val networkHelper = DesktopNetworkHelper(cacheDir = File(appDir, "cache/network"), dohProvider = dohProvider)
    Injekt.addSingleton(networkHelper)
    Injekt.addSingleton(networkHelper.client)
    Injekt.addSingleton(NetworkHelper(networkHelper.client))

    // JSON
    Injekt.addSingleton(
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    // Platform info
    Injekt.addSingleton<PlatformInfo>(DesktopPlatformInfo())

    // Database
    val handler = initDatabase(File(appDir, "mihon.db"))

    // Repositories
    val mangaRepository: MangaRepository = MangaRepositoryImpl(handler)
    val chapterRepository: ChapterRepository = ChapterRepositoryImpl(handler)
    val categoryRepository: CategoryRepository = CategoryRepositoryImpl(handler)
    val historyRepository: HistoryRepository = HistoryRepositoryImpl(handler)
    val updatesRepository: UpdatesRepository = UpdatesRepositoryImpl(handler)
    Injekt.addSingleton(mangaRepository)
    Injekt.addSingleton(chapterRepository)
    Injekt.addSingleton(categoryRepository)
    Injekt.addSingleton(historyRepository)
    Injekt.addSingleton(updatesRepository)

    // Extensions loaded from ~/.mihon/extensions/*.jar
    val extensionManager = DesktopExtensionManager(
        DesktopExtensionLoader(File(appDir, "extensions")),
    )
    extensionManager.loadAll()
    Injekt.addSingleton(extensionManager)

    // Source manager backed by loaded extensions
    val sourceManager = DesktopSourceManager(extensionManager)
    Injekt.addSingleton<SourceManager>(sourceManager)
    Injekt.addSingleton(sourceManager)

    // Domain use cases
    Injekt.addSingleton(GetLibraryManga(mangaRepository))
    Injekt.addSingleton(GetManga(mangaRepository))
    Injekt.addSingleton(GetMangaWithChapters(mangaRepository, chapterRepository))
    Injekt.addSingleton(GetChapter(chapterRepository))
    Injekt.addSingleton(GetChaptersByMangaId(chapterRepository))
    Injekt.addSingleton(GetCategories(categoryRepository))

    // Phase A — library loop
    val networkToLocalManga = NetworkToLocalManga(mangaRepository)
    val updateChapter = UpdateChapter(chapterRepository)
    val upsertHistory = UpsertHistory(historyRepository)
    Injekt.addSingleton(networkToLocalManga)
    Injekt.addSingleton(updateChapter)
    Injekt.addSingleton(upsertHistory)
    Injekt.addSingleton(AddMangaToLibrary(networkToLocalManga, mangaRepository, chapterRepository))
    Injekt.addSingleton(ReaderPreferences())

    // Phase B — library management
    Injekt.addSingleton(DesktopCategoryManager(categoryRepository))
    Injekt.addSingleton(LibraryUpdateChecker(chapterRepository))
    Injekt.addSingleton(
        LibraryUpdateScheduler(
            appPreferences = Injekt.get<DesktopAppPreferences>(),
            updateChecker = Injekt.get<LibraryUpdateChecker>(),
            getLibraryManga = Injekt.get<GetLibraryManga>(),
            sourceManager = Injekt.get<SourceManager>(),
        ),
    )

    // Phase D — history + updates tabs
    Injekt.addSingleton(GetHistory(historyRepository))
    Injekt.addSingleton(RemoveHistory(historyRepository))
    Injekt.addSingleton(GetUpdates(updatesRepository))

    // Phase C — downloads
    val downloadsDir = File(appDir, "downloads")
    val downloadPreferences = DesktopDownloadPreferences(preferenceStore)
    val downloadProvider = mihon.desktop.download.DesktopDownloadProvider(downloadsDir)
    val downloadManager = mihon.desktop.download.DesktopDownloadManager(
        provider = downloadProvider,
        downloadPreferences = downloadPreferences,
    )
    downloadManager.start()
    Injekt.addSingleton(downloadPreferences)
    Injekt.addSingleton(downloadProvider)
    Injekt.addSingleton(downloadManager)

    // ReaderProgressTracker needs appPreferences, downloadPreferences, downloadManager
    val appPreferences = Injekt.get<DesktopAppPreferences>()
    Injekt.addSingleton(
        ReaderProgressTracker(
            updateChapter = updateChapter,
            upsertHistory = upsertHistory,
            appPreferences = appPreferences,
            downloadPreferences = downloadPreferences,
            downloadManager = downloadManager,
        ),
    )
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
