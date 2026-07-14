package mihon.domain.migration.usecases

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import mihon.domain.migration.MigrationChapter
import mihon.domain.migration.MigrationMangaMetadata
import mihon.domain.migration.MigrationOrchestrator
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import java.time.Instant

class MigrateMangaUseCase(
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
) {
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }
    private val orchestrator = MigrationOrchestrator()

    suspend operator fun invoke(current: Manga, target: Manga, replace: Boolean): Result<Unit> {
        val targetSource = sourceManager.get(target.source)
            ?: return Result.failure(IllegalStateException("Target source ${target.source} is unavailable"))
        val currentSource = sourceManager.get(current.source)
        val flags = sourcePreferences.migrationFlags().get()

        try {
            val chapters = targetSource.getChapterList(target.toSManga())

            try {
                syncChaptersWithSource.await(chapters, target, targetSource)
            } catch (_: Exception) {
                // Worst case, chapters won't be synced
            }

            // Update chapters read, bookmark and dateFetch
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)
                val mangaChapters = getChaptersByMangaId.await(target.id)

                val chapterUpdates = orchestrator.chapterUpdates(
                    prevMangaChapters.map {
                        MigrationChapter(it.id, it.chapterNumber, it.read, it.bookmark, it.dateFetch)
                    },
                    mangaChapters.map { MigrationChapter(it.id, it.chapterNumber, it.read, it.bookmark, it.dateFetch) },
                ).map { plan ->
                    ChapterUpdate(
                        id = plan.id,
                        read = plan.read,
                        bookmark = plan.bookmark,
                        dateFetch = plan.dateFetch,
                    )
                }
                updateChapter.awaitAll(chapterUpdates)
            }

            // Update categories
            val libraryPlan = orchestrator.libraryPlan(
                current = MigrationMangaMetadata(
                    mangaId = current.id,
                    categoryIds = if (MigrationFlag.CATEGORY in
                        flags
                    ) {
                        getCategories.await(current.id).map { it.id }
                    } else {
                        emptyList()
                    },
                    chapterFlags = current.chapterFlags,
                    viewerFlags = current.viewerFlags,
                    dateAdded = current.dateAdded,
                    notes = current.notes,
                ),
                targetMangaId = target.id,
                flags = flags,
                replace = replace,
                now = Instant.now().toEpochMilli(),
            )
            if (MigrationFlag.CATEGORY in flags) setMangaCategories.await(target.id, libraryPlan.targetCategoryIds)

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

            val currentMangaUpdate = MangaUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetMangaUpdate = MangaUpdate(
                id = target.id,
                favorite = true,
                chapterFlags = libraryPlan.targetChapterFlags,
                viewerFlags = libraryPlan.targetViewerFlags,
                dateAdded = libraryPlan.targetDateAdded,
                notes = libraryPlan.targetNotes,
            )

            updateManga.awaitAll(listOfNotNull(currentMangaUpdate, targetMangaUpdate))
            return Result.success(Unit)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return Result.failure(e)
        }
    }
}
