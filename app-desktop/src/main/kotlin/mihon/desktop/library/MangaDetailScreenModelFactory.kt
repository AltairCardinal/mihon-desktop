package mihon.desktop.library

import mihon.desktop.domain.DesktopCoverUpdater
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.download.DesktopDownloadIdentityResolver
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.ui.library.DesktopCoverFilePicker
import mihon.desktop.ui.library.MangaCoverAdapter
import mihon.desktop.ui.library.MangaDetailScreenModel
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.creator.interactor.LinkMangaCreator
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object MangaDetailScreenModelFactory {
    fun create(mangaId: Long): MangaDetailScreenModel {
        val downloadManager = runCatching { Injekt.get<DesktopDownloadManager>() }.getOrNull()
        val downloadIdentityResolver = runCatching { Injekt.get<DesktopDownloadIdentityResolver>() }.getOrNull()
        val coverStore = Injekt.get<DesktopCustomCoverStore>()
        val coverUpdater = DesktopCoverUpdater(coverStore, Injekt.get())
        return MangaDetailScreenModel(
            readingProgress = Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>(),
            mangaId = mangaId,
            getMangaWithChapters = Injekt.get<GetMangaWithChapters>(),
            sourceManager = Injekt.get<SourceManager>(),
            updateChecker = Injekt.get<LibraryUpdateChecker>(),
            getAvailableScanlators = Injekt.get<GetAvailableScanlators>(),
            getExcludedScanlators = Injekt.get<GetExcludedScanlators>(),
            setExcludedScanlators = Injekt.get<SetExcludedScanlators>(),
            getCategories = Injekt.get<GetCategories>(),
            libraryPreferences = Injekt.get<LibraryPreferences>(),
            updateChapter = Injekt.get<UpdateChapter>(),
            setChapterReadStatus = Injekt.get<SetChapterReadStatus>(),
            updateManga = Injekt.get<UpdateManga>(),
            setMangaChapterFlags = Injekt.get<SetMangaChapterFlags>(),
            setMangaCategories = Injekt.get<SetMangaCategories>(),
            linkMangaCreator = Injekt.get<LinkMangaCreator>(),
            manageCreatorIdentity = ManageCreatorIdentity(Injekt.get<CreatorArchiveRepository>()),
            enqueueDownload = downloadManager?.let { it::enqueue },
            downloadQueue = downloadManager?.queue,
            isDownloaded = downloadManager?.let { manager ->
                { manga, chapter ->
                    val identity = requireNotNull(downloadIdentityResolver) { "Download identity resolver is required" }
                        .resolve(manga, chapter)
                    manager.isDownloaded(manga.source, identity)
                }
            },
            deleteDownload = downloadManager?.let { manager ->
                { manga, chapter ->
                    val identity = requireNotNull(downloadIdentityResolver) { "Download identity resolver is required" }
                        .resolve(manga, chapter)
                    manager.deleteDownload(manga.source, identity)
                }
            },
            cancelDownload = downloadManager?.let { manager ->
                { chapterId -> manager.cancel(chapterId) }
            },
            retryDownload = downloadManager?.let { manager ->
                { chapterId -> manager.retryItem(chapterId) }
            },
            updateLibraryMembership = Injekt.get<UpdateLibraryMembership>(),
            coverAdapter = MangaCoverAdapter(DesktopCoverFilePicker(), coverUpdater::invoke),
            deleteCover = coverUpdater::delete,
            resolveCoverModel = coverStore::resolveModel,
        )
    }
}
