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
        getChapter = Injekt.get<GetChapter>(),
        getManga = Injekt.get<GetManga>(),
        getChapters = Injekt.get<tachiyomi.domain.chapter.interactor.GetChaptersByMangaId>(),
        prepareDirectory = { manga ->
            val owner = Injekt.get<mihon.desktop.domain.SaveSourceMangaForDetails>()
            val manager = Injekt.get<tachiyomi.domain.source.service.SourceManager>()
            owner.awaitPrepared(manager.get(manga.source), manga)
        },
        isDownloaded = { manga, chapter ->
            val identity = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>().resolve(manga, chapter)
            Injekt.get<mihon.desktop.download.DesktopDownloadManager>().isDownloaded(manga.source, identity)
        },
    )
}
