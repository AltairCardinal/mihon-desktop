package mihon.desktop.updates

import mihon.desktop.download.DesktopDownloadManager
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object UpdatesScreenModelFactory {
    fun create(): UpdatesScreenModel {
        val downloadManager = Injekt.get<DesktopDownloadManager>()
        return UpdatesScreenModel(
            getUpdates = Injekt.get<GetUpdates>(),
            updateChapter = Injekt.get<UpdateChapter>(),
            getManga = Injekt.get<GetManga>(),
            updatesPreferences = Injekt.get<UpdatesPreferences>(),
            isDownloaded = {
                downloadManager.isDownloaded(
                    sourceId = it.sourceId,
                    mangaTitle = it.mangaTitle,
                    chapterName = it.chapterName,
                )
            },
            enqueueDownload = downloadManager::enqueue,
        )
    }
}
