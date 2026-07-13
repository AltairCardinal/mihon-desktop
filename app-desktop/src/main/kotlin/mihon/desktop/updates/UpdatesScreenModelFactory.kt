package mihon.desktop.updates

import mihon.domain.download.EnqueueDownload
import mihon.domain.download.IsChapterDownloaded
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
            enqueueDownload = Injekt.get<EnqueueDownload>(),
        )
    }
}
