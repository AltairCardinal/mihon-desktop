package mihon.desktop

import androidx.compose.runtime.compositionLocalOf
import eu.kanade.tachiyomi.core.security.SecurityPreferences
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
import mihon.desktop.download.DownloadQueueScreenModel
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.license.DependencyNoticeProvider
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopChallengeBrowserLoginBridge
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.migration.DesktopBatchMigrationController
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopDeepLinkHandler
import mihon.desktop.platform.DesktopBackupFilePicker
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.privacy.DesktopPrivacyCapabilities
import mihon.desktop.privacy.DesktopWindowPrivacyController
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.platform.DesktopLocaleAdapter
import mihon.desktop.tracking.DesktopTrackerOAuthCallbackBroker
import mihon.desktop.tracking.DesktopTrackerServiceRegistry
import mihon.desktop.source.LocalSourceScanService
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.desktop.ui.ExternalActionNavigator
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.ui.settings.DesktopUpdateScreenModel
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.upcoming.interactor.GetUpcomingManga
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.SetCreatorFollow
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
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class DesktopUiDependencies(
    val appPreferences: DesktopAppPreferences,
    val localeAdapter: DesktopLocaleAdapter = DesktopLocaleAdapter(appPreferences.appLanguage),
    val backupRestoreScreenModelFactory: BackupRestoreScreenModelFactory,
    val backupFilePicker: DesktopBackupFilePicker,
    val getCategories: GetCategories,
    val categoryRepository: CategoryRepository,
    val chapterRepository: ChapterRepository,
    val getChaptersByMangaId: GetChaptersByMangaId,
    val cloudflareChallengeManager: CloudflareChallengeManager,
    val challengeBrowserLoginBridge: DesktopChallengeBrowserLoginBridge,
    val sourceLoginSessionFactory: DesktopSourceLoginSessionFactory,
    val createExtensionRepo: CreateExtensionRepo,
    val getCreators: GetCreators,
    val getCreatorDetails: GetCreatorDetails,
    val setCreatorFollow: SetCreatorFollow,
    val discoverCreatorWorks: DiscoverCreatorWorks,
    val deleteExtensionRepo: DeleteExtensionRepo,
    val downloadManager: DesktopDownloadManager,
    val downloadPreferences: DesktopDownloadPreferences,
    val dependencyNoticeProvider: DependencyNoticeProvider,
    val extensionApi: DesktopExtensionApi,
    val extensionManager: DesktopExtensionManager,
    val externalActionNavigator: ExternalActionNavigator,
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
    val privacyCapabilities: DesktopPrivacyCapabilities,
    val windowPrivacyController: DesktopWindowPrivacyController,
    val securityPreferences: SecurityPreferences,
    val passphraseVerifier: DesktopPassphraseVerifier,
    val shareService: DesktopShareService,
    val replaceExtensionRepo: ReplaceExtensionRepo,
    val saveSourceMangaForDetails: SaveSourceMangaForDetails,
    val setExcludedScanlators: SetExcludedScanlators,
    val sourceManager: SourceManager,
    val sourceMangaSearchService: SourceMangaSearchService,
    val sourceRepository: SourceRepository,
    val updateExtensionRepo: UpdateExtensionRepo,
    val updateMangaNotes: UpdateMangaNotes,
    val getTracks: GetTracks,
    val insertTrack: InsertTrack,
    val deleteTrack: DeleteTrack,
    val trackerServiceRegistry: TrackerServiceRegistry = DesktopTrackerServiceRegistry(),
    val trackerOAuthCallbackBroker: DesktopTrackerOAuthCallbackBroker = DesktopTrackerOAuthCallbackBroker(),
    val updateController: DesktopUpdateController? = null,
    val updateScreenModel: DesktopUpdateScreenModel? = null,
) {
    suspend fun getMangaTitle(mangaId: Long): String {
        return mangaRepository.getMangaById(mangaId).title
    }

    fun getSourcesWithFavoriteCount(): Flow<List<Pair<Source, Long>>> {
        return sourceRepository.getSourcesWithFavoriteCount()
    }

    fun createDownloadQueueScreenModel(): DownloadQueueScreenModel = DownloadQueueScreenModel(
        downloadManager = downloadManager,
        chapterRepository = chapterRepository,
        sourceManager = sourceManager,
    )

    companion object {
        fun fromInjekt(): DesktopUiDependencies {
            val appPreferences = Injekt.get<DesktopAppPreferences>()
            val localeAdapter = DesktopLocaleAdapter(appPreferences.appLanguage).also { it.applyPersisted() }
            val saveSourceMangaForDetails = Injekt.get<SaveSourceMangaForDetails>()
            val sourceManager = Injekt.get<SourceManager>()
            return DesktopUiDependencies(
                appPreferences = appPreferences,
                localeAdapter = localeAdapter,
                backupRestoreScreenModelFactory = Injekt.get(),
                backupFilePicker = Injekt.get(),
                getCategories = Injekt.get(),
                categoryRepository = Injekt.get(),
                chapterRepository = Injekt.get(),
                getChaptersByMangaId = Injekt.get(),
                cloudflareChallengeManager = Injekt.get(),
                challengeBrowserLoginBridge = Injekt.get(),
                sourceLoginSessionFactory = Injekt.get(),
                createExtensionRepo = Injekt.get(),
                getCreators = Injekt.get(),
                getCreatorDetails = Injekt.get(),
                setCreatorFollow = Injekt.get(),
                discoverCreatorWorks = Injekt.get(),
                deleteExtensionRepo = Injekt.get(),
                downloadManager = Injekt.get(),
                downloadPreferences = Injekt.get(),
                dependencyNoticeProvider = Injekt.get(),
                extensionApi = Injekt.get(),
                extensionManager = Injekt.get(),
                externalActionNavigator = ExternalActionNavigator(
                    DesktopDeepLinkHandler(sourceManager, saveSourceMangaForDetails)::resolve,
                ),
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
                privacyCapabilities = Injekt.get(),
                windowPrivacyController = Injekt.get(),
                securityPreferences = Injekt.get(),
                passphraseVerifier = Injekt.get(),
                shareService = Injekt.get(),
                replaceExtensionRepo = Injekt.get(),
                saveSourceMangaForDetails = saveSourceMangaForDetails,
                setExcludedScanlators = Injekt.get(),
                sourceManager = sourceManager,
                sourceMangaSearchService = Injekt.get(),
                sourceRepository = Injekt.get(),
                updateExtensionRepo = Injekt.get(),
                updateMangaNotes = Injekt.get(),
                getTracks = Injekt.get(),
                insertTrack = Injekt.get(),
                deleteTrack = Injekt.get(),
                trackerServiceRegistry = Injekt.get(),
                trackerOAuthCallbackBroker = Injekt.get(),
                updateController = Injekt.get(),
                updateScreenModel = Injekt.get(),
            )
        }
    }
}

val LocalDesktopUiDependencies = compositionLocalOf<DesktopUiDependencies> {
    error("DesktopUiDependencies is not provided")
}

val LocalExtensionScreenModel = compositionLocalOf<() -> ExtensionsScreenModel> { { Injekt.get() } }
