package mihon.desktop.history

import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object HistoryScreenModelFactory {

    fun create(): HistoryScreenModel = HistoryScreenModel(
        readingProgress = Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>(),
        getHistory = Injekt.get<GetHistory>(),
        removeHistory = Injekt.get<RemoveHistory>(),

        getManga = Injekt.get<GetManga>(),
        getChapters = Injekt.get<tachiyomi.domain.chapter.interactor.GetChaptersByMangaId>(),
        getNextChapters = Injekt.get<tachiyomi.domain.history.interactor.GetNextChapters>(),
        favoriteActions = tachiyomi.domain.history.service.HistoryFavoriteActions(
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            bindEnhanced = ::bindHistoryEnhancedTrackers,
        ),
        isDownloaded = { manga, chapter ->
            val identity = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>().resolve(manga, chapter)
            Injekt.get<mihon.desktop.download.DesktopDownloadManager>().isDownloaded(manga.source, identity)
        },
    )
}
