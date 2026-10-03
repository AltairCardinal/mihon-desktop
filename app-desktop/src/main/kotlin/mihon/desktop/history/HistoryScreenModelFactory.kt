package mihon.desktop.history

import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object HistoryScreenModelFactory {

    fun datePreferences(): mihon.presentation.history.HistoryDatePreferences {
        val store = Injekt.get<tachiyomi.core.common.preference.PreferenceStore>()
        return mihon.presentation.history.HistoryDatePreferences(
            store.getBoolean("relative_time_v2", true).get(),
            store.getString("app_date_format", "").get(),
        )
    }

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
