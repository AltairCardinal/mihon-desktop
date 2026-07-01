package mihon.desktop.history

import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object HistoryScreenModelFactory {

    fun create(): HistoryScreenModel = HistoryScreenModel(
        getHistory = Injekt.get<GetHistory>(),
        removeHistory = Injekt.get<RemoveHistory>(),
        getChapter = Injekt.get<GetChapter>(),
        getManga = Injekt.get<GetManga>(),
    )
}
