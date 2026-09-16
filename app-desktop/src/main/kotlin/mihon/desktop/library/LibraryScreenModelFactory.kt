package mihon.desktop.library

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.download.DesktopDownloadIdentityResolver
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.settings.LibraryCategoryPrefs
import mihon.desktop.ui.library.LibraryScreenModel
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.service.TrackerSessionProvider
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object LibraryScreenModelFactory {
    fun create(): LibraryScreenModel {
        val downloadManager = runCatching { Injekt.get<DesktopDownloadManager>() }.getOrNull()
        val downloadProvider = runCatching { Injekt.get<DesktopDownloadProvider>() }.getOrNull()
        val downloadIdentityResolver = runCatching { Injekt.get<DesktopDownloadIdentityResolver>() }.getOrNull()
        val getChaptersByMangaId = Injekt.get<GetChaptersByMangaId>()
        val updateScheduler = Injekt.get<LibraryUpdateScheduler>()
        return LibraryScreenModel(
            readingProgress = Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>(),
            getLibraryManga = Injekt.get<GetLibraryManga>(),
            getCategories = Injekt.get<GetCategories>(),
            createCategory = Injekt.get<CreateCategoryWithName>(),
            renameCategory = Injekt.get<RenameCategory>(),
            deleteCategory = Injekt.get<DeleteCategory>(),
            reorderCategory = Injekt.get<ReorderCategory>(),
            updateChecker = Injekt.get<LibraryUpdateChecker>(),
            sourceManager = Injekt.get<SourceManager>(),
            getChaptersByMangaId = getChaptersByMangaId,
            getBookmarkedChaptersByMangaId = Injekt.get<GetBookmarkedChaptersByMangaId>(),
            getNextChapters = Injekt.get<GetNextChapters>(),
            setChapterReadStatus = Injekt.get<SetChapterReadStatus>(),
            updateManga = Injekt.get<UpdateManga>(),
            setMangaCategories = Injekt.get<SetMangaCategories>(),
            enqueueDownload = downloadManager?.let { it::enqueue },
            downloadProvider = downloadProvider,
            isMangaDownloaded = if (downloadProvider != null && downloadIdentityResolver != null) {
                { item ->
                    downloadProvider.hasMangaDownloads(
                        item.manga.source,
                        downloadIdentityResolver.resolve(item.manga),
                    )
                }
            } else {
                null
            },
            downloadPreferences = runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull(),
            categoryPrefs = runCatching { Injekt.get<LibraryCategoryPrefs>() }.getOrNull(),
            getTracksPerManga = Injekt.get<GetTracksPerManga>(),
            trackerSessionProvider = Injekt.get<TrackerSessionProvider>(),
            startBackgroundUpdate = updateScheduler::runNow,
            startScopedBackgroundUpdate = updateScheduler::runNow,
            cancelBackgroundUpdate = updateScheduler::cancelUpdate,
            backgroundUpdateStatus = { updateScheduler.taskSnapshot()?.status },
            backgroundUpdateJob = updateScheduler::currentUpdateJob,
            libraryPreferences = runCatching {
                Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>()
            }.getOrNull(),
            setDisplayModeInteractor = runCatching { Injekt.get<SetDisplayMode>() }.getOrNull(),
            setSortModeForCategory = runCatching { Injekt.get<SetSortModeForCategory>() }.getOrNull(),
            downloadedChapterCount = if (downloadProvider != null && downloadIdentityResolver != null) {
                { item ->
                    downloadProvider.downloadedChapterCount(
                        item.manga.source,
                        downloadIdentityResolver.resolve(item.manga),
                    ).toLong()
                }
            } else {
                null
            },
            deleteMangaDownloads = if (downloadProvider != null && downloadIdentityResolver != null) {
                { item ->
                    if (downloadManager != null) {
                        val chapterIds = getChaptersByMangaId.awaitOrThrow(item.id).mapTo(mutableSetOf()) { it.id }
                        val queuedTargetIds = chapterIds + downloadManager.queue.value.mapNotNull { queued ->
                            queued.chapterId.takeIf { queued.mangaId == item.id }
                        }
                        check(downloadManager.cancelAndAwaitRetirements(queuedTargetIds)) {
                            "Unable to retire downloads for manga ${item.id}"
                        }
                    }
                    check(
                        downloadProvider.deleteMangaDownloads(
                            item.manga.source,
                            item.manga.title,
                            downloadIdentityResolver.resolve(item.manga),
                        ),
                    ) { "Unable to delete downloads for manga ${item.id}" }
                }
            } else {
                null
            },
            deleteCustomCover = runCatching { Injekt.get<DesktopCustomCoverStore>() }
                .getOrNull()
                ?.let { store -> store::deleteCustomCover },
            sharedDownloadPreferences = runCatching { Injekt.get<DownloadPreferences>() }.getOrNull(),
            deleteChapterDownload = if (downloadProvider != null && downloadIdentityResolver != null) {
                { item, chapter ->
                    if (downloadManager != null) {
                        check(downloadManager.cancelAndAwaitRetirement(chapter.id)) {
                            "Unable to retire download ${chapter.id}"
                        }
                    }
                    check(
                        downloadProvider.deleteChapterDownload(
                            item.manga.source,
                            downloadIdentityResolver.resolve(item.manga, chapter),
                        ),
                    ) { "Unable to delete download ${chapter.id}" }
                }
            } else {
                null
            },
            isChapterDownloaded = if (downloadProvider != null && downloadIdentityResolver != null) {
                { item, chapter ->
                    downloadProvider.isChapterDownloaded(
                        item.manga.source,
                        downloadIdentityResolver.resolve(item.manga, chapter),
                    )
                }
            } else {
                null
            },
            isChapterQueued = downloadManager?.let { manager ->
                { chapter ->
                    manager.queue.value.any { item -> item.chapterId == chapter.id }
                }
            },
            downloadQueueChanges = downloadManager?.queue
                ?.map { queue -> queue.map { it.chapterId to it.status } }
                ?.distinctUntilChanged()
                ?.map { Unit }
                ?: flowOf(Unit),
        )
    }
}
