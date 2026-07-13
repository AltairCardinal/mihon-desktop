package mihon.desktop.library

import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.settings.LibraryCategoryPrefs
import mihon.desktop.ui.library.LibraryScreenModel
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.repository.TrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object LibraryScreenModelFactory {
    fun create(): LibraryScreenModel {
        val downloadManager = runCatching { Injekt.get<DesktopDownloadManager>() }.getOrNull()
        val updateScheduler = Injekt.get<LibraryUpdateScheduler>()
        return LibraryScreenModel(
            getLibraryManga = Injekt.get<GetLibraryManga>(),
            getCategories = Injekt.get<GetCategories>(),
            createCategory = Injekt.get<CreateCategoryWithName>(),
            renameCategory = Injekt.get<RenameCategory>(),
            deleteCategory = Injekt.get<DeleteCategory>(),
            reorderCategory = Injekt.get<ReorderCategory>(),
            updateChecker = Injekt.get<LibraryUpdateChecker>(),
            sourceManager = Injekt.get<SourceManager>(),
            chapterRepository = Injekt.get<ChapterRepository>(),
            mangaRepository = Injekt.get<MangaRepository>(),
            setMangaCategories = Injekt.get<SetMangaCategories>(),
            enqueueDownload = downloadManager?.let { it::enqueue },
            downloadProvider = runCatching { Injekt.get<DesktopDownloadProvider>() }.getOrNull(),
            downloadPreferences = runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull(),
            categoryPrefs = runCatching { Injekt.get<LibraryCategoryPrefs>() }.getOrNull(),
            categoryRepository = Injekt.get<CategoryRepository>(),
            trackRepository = Injekt.get<TrackRepository>(),
            startBackgroundUpdate = updateScheduler::runNow,
            cancelBackgroundUpdate = updateScheduler::cancelUpdate,
            backgroundUpdateStatus = { updateScheduler.taskSnapshot()?.status },
        )
    }
}
