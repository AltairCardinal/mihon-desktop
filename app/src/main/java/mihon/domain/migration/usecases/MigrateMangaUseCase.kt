package mihon.domain.migration.usecases

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import java.time.Instant

class MigrateMangaUseCase(
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
    private val mangaRepository: tachiyomi.domain.manga.repository.MangaRepository,
) {
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    suspend operator fun invoke(current: Manga, target: Manga, replace: Boolean): Result<Unit> {
        if (current.id == target.id || (current.source == target.source && current.url == target.url)) {
            return Result.failure(IllegalArgumentException("Cannot migrate onto the same manga"))
        }
        val targetSource = sourceManager.get(target.source)
            ?: return Result.failure(IllegalStateException("Target source ${target.source} is unavailable"))
        val currentSource = sourceManager.get(current.source)
        val flags = sourcePreferences.migrationFlags().get()

        try {
            val chapters = targetSource.getChapterList(target.toSManga())

            syncChaptersWithSource.await(chapters, target, targetSource)

            // Update track
            getTracks.await(current.id).mapNotNull { track ->
                val updatedTrack = track.copy(mangaId = target.id)

                val service = enhancedServices
                    .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                if (service != null) {
                    service.migrateTrack(updatedTrack, target, targetSource)
                } else {
                    updatedTrack
                }
            }
                .takeIf { it.isNotEmpty() }
                ?.let { insertTrack.awaitAll(it) }

            // Delete downloaded
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                downloadManager.deleteManga(current, currentSource)
            }

            // Update custom cover (recheck if custom cover exists)
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
            }

            mangaRepository.commitMigration(
                mihon.domain.migration.MigrationCommit(
                    current.id, current.source, current.url, target.id, target.source, target.url,
                    flags, replace, Instant.now().toEpochMilli(),
                ),
            )
            return Result.success(Unit)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return Result.failure(e)
        }
    }
}
