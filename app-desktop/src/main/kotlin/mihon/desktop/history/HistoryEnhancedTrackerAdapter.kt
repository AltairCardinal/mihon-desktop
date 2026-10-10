package mihon.desktop.history

import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** First binding uses existing provider ports; regular tracking keeps its own workflow. */
internal suspend fun bindHistoryEnhancedTrackers(manga: tachiyomi.domain.manga.model.Manga) {
    val registry = Injekt.get<tachiyomi.domain.track.service.TrackerServiceRegistry>()
    registry.refresh()
    var failure: Exception? = null
    registry.services.filterIsInstance<tachiyomi.domain.track.service.EnhancedTrackerService>().forEach { service ->
        if (service.profile.value.loggedIn && service.profile.value.unavailableReason == null) {
            try {
                val matched = tachiyomi.domain.track.service.EnhancedTrackerWorkflow().bindIfMatched(
                    service,
                    tachiyomi.domain.track.service.EnhancedTrackerManga(manga.id, manga.source, manga.url, manga.title),
                )
                if (matched != null) {
                    Injekt.get<tachiyomi.domain.track.interactor.InsertTrack>().awaitOrThrow(matched)
                    val progress = tachiyomi.domain.track.interactor.SyncEnhancedChapterProgress(Injekt.get(), Injekt.get(), Injekt.get())
                    progress.await(manga.id, matched) { track -> requireNotNull(service as? tachiyomi.domain.track.service.TrackerProviderPort).update(track) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
    }
    failure?.let { throw it }
}
