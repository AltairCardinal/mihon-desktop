package mihon.desktop.updates

import mihon.domain.download.EnqueueDownload
import mihon.domain.download.IsChapterDownloaded
import mihon.desktop.download.DesktopDownloadIdentityResolver
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object UpdatesScreenModelFactory {
    fun create(): UpdatesScreenModel {
        return UpdatesScreenModel(
            getUpdates = Injekt.get<GetUpdates>(),
            updateChapter = Injekt.get<UpdateChapter>(),
            getManga = Injekt.get<GetManga>(),
            updatesPreferences = Injekt.get<UpdatesPreferences>(),
            isChapterDownloaded = Injekt.get<IsChapterDownloaded>(),
            downloadIdentity = Injekt.get<DesktopDownloadIdentityResolver>()::resolve,
            enqueueDownload = Injekt.get<EnqueueDownload>(),
            creatorArchiveRepository = Injekt.get<tachiyomi.domain.creator.repository.CreatorArchiveRepository>(),
        )
    }
}
