package mihon.desktop

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.Flow
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.domain.DesktopCoverUpdater
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.DesktopMigrateMangaUseCase
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopChallengeBrowserLoginBridge
import mihon.desktop.migration.DesktopBatchMigrationController
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.tracking.DesktopTrackerServiceRegistry
import mihon.desktop.source.LocalSourceScanService
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.upcoming.interactor.GetUpcomingManga
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.track.repository.TrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class DesktopUiDependencies(
    val appPreferences: DesktopAppPreferences,
    val backupRestoreScreenModelFactory: BackupRestoreScreenModelFactory,
    val getCategories: GetCategories,
    val categoryRepository: CategoryRepository,
    val chapterRepository: ChapterRepository,
    val cloudflareChallengeManager: CloudflareChallengeManager,
    val challengeBrowserLoginBridge: DesktopChallengeBrowserLoginBridge,
    val createExtensionRepo: CreateExtensionRepo,
    val creatorDiscoveryService: CreatorDiscoveryService,
    val creatorRepository: CreatorRepository,
    val deleteExtensionRepo: DeleteExtensionRepo,
    val downloadManager: DesktopDownloadManager,
    val downloadPreferences: DesktopDownloadPreferences,
    val extensionApi: DesktopExtensionApi,
    val extensionManager: DesktopExtensionManager,
    val getExcludedScanlators: GetExcludedScanlators,
    val getExtensionRepo: GetExtensionRepo,
    val getFavorites: GetFavorites,
    val getLibraryManga: GetLibraryManga,
    val getManga: GetManga,
    val getUpcomingManga: GetUpcomingManga,
    val historyRepository: HistoryRepository,
    val localSourceScanService: LocalSourceScanService,
    val customCoverStore: DesktopCustomCoverStore,
    val coverUpdater: DesktopCoverUpdater,
    val mangaRepository: MangaRepository,
    val batchMigrationController: DesktopBatchMigrationController,
    val migrateManga: DesktopMigrateMangaUseCase,
    val networkHelper: DesktopNetworkHelper,
    val notificationService: DesktopNotificationService,
    val replaceExtensionRepo: ReplaceExtensionRepo,
    val saveSourceMangaForDetails: SaveSourceMangaForDetails,
    val setExcludedScanlators: SetExcludedScanlators,
    val sourceManager: SourceManager,
    val sourceMangaSearchService: SourceMangaSearchService,
    val sourceRepository: SourceRepository,
    val updateExtensionRepo: UpdateExtensionRepo,
    val updateMangaNotes: UpdateMangaNotes,
    val trackRepository: TrackRepository,
    val trackerServiceRegistry: TrackerServiceRegistry = DesktopTrackerServiceRegistry(),
) {
    suspend fun getMangaTitle(mangaId: Long): String {
        return mangaRepository.getMangaById(mangaId).title
    }

    fun getSourcesWithFavoriteCount(): Flow<List<Pair<Source, Long>>> {
        return sourceRepository.getSourcesWithFavoriteCount()
    }

    companion object {
        fun fromInjekt(): DesktopUiDependencies {
            return DesktopUiDependencies(
                appPreferences = Injekt.get(),
                backupRestoreScreenModelFactory = Injekt.get(),
                getCategories = Injekt.get(),
                categoryRepository = Injekt.get(),
                chapterRepository = Injekt.get(),
                cloudflareChallengeManager = Injekt.get(),
                challengeBrowserLoginBridge = Injekt.get(),
                createExtensionRepo = Injekt.get(),
                creatorDiscoveryService = Injekt.get(),
                creatorRepository = Injekt.get(),
                deleteExtensionRepo = Injekt.get(),
                downloadManager = Injekt.get(),
                downloadPreferences = Injekt.get(),
                extensionApi = Injekt.get(),
                extensionManager = Injekt.get(),
                getExcludedScanlators = Injekt.get(),
                getExtensionRepo = Injekt.get(),
                getFavorites = Injekt.get(),
                getLibraryManga = Injekt.get(),
                getManga = Injekt.get(),
                getUpcomingManga = Injekt.get(),
                historyRepository = Injekt.get(),
                localSourceScanService = Injekt.get(),
                customCoverStore = Injekt.get(),
                coverUpdater = DesktopCoverUpdater(Injekt.get(), Injekt.get()),
                mangaRepository = Injekt.get(),
                batchMigrationController = Injekt.get(),
                migrateManga = Injekt.get(),
                networkHelper = Injekt.get(),
                notificationService = Injekt.get(),
                replaceExtensionRepo = Injekt.get(),
                saveSourceMangaForDetails = Injekt.get(),
                setExcludedScanlators = Injekt.get(),
                sourceManager = Injekt.get(),
                sourceMangaSearchService = Injekt.get(),
                sourceRepository = Injekt.get(),
                updateExtensionRepo = Injekt.get(),
                updateMangaNotes = Injekt.get(),
                trackRepository = Injekt.get(),
                trackerServiceRegistry = Injekt.get(),
            )
        }
    }
}

val LocalDesktopUiDependencies = compositionLocalOf<DesktopUiDependencies> {
    error("DesktopUiDependencies is not provided")
}
