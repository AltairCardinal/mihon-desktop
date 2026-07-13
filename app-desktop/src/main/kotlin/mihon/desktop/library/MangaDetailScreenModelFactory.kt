package mihon.desktop.library

import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.ui.library.MangaDetailScreenModel
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object MangaDetailScreenModelFactory {
    fun create(mangaId: Long): MangaDetailScreenModel {
        val downloadManager = runCatching { Injekt.get<DesktopDownloadManager>() }.getOrNull()
        return MangaDetailScreenModel(
            mangaId = mangaId,
            getMangaWithChapters = Injekt.get<GetMangaWithChapters>(),
            sourceManager = Injekt.get<SourceManager>(),
            updateChecker = Injekt.get<LibraryUpdateChecker>(),
            getAvailableScanlators = Injekt.get<GetAvailableScanlators>(),
            getExcludedScanlators = Injekt.get<GetExcludedScanlators>(),
            setExcludedScanlators = Injekt.get<SetExcludedScanlators>(),
            categoryRepository = Injekt.get<CategoryRepository>(),
            chapterRepository = Injekt.get<ChapterRepository>(),
            mangaRepository = Injekt.get<MangaRepository>(),
            setMangaCategories = Injekt.get<SetMangaCategories>(),
            creatorRepository = Injekt.get<CreatorRepository>(),
            enqueueDownload = downloadManager?.let { it::enqueue },
            downloadQueue = downloadManager?.queue,
            isDownloaded = downloadManager?.let { manager ->
                { sourceId, mangaTitle, chapterName ->
                    manager.isDownloaded(sourceId, mangaTitle, chapterName)
                }
            },
            deleteDownload = downloadManager?.let { manager ->
                { sourceId, mangaTitle, chapterName ->
                    manager.deleteDownload(sourceId, mangaTitle, chapterName)
                }
            },
            cancelDownload = downloadManager?.let { manager ->
                { chapterId -> manager.cancel(chapterId) }
            },
            updateLibraryMembership = Injekt.get<UpdateLibraryMembership>(),
        )
    }
}
