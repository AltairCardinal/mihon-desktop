package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import mihon.domain.migration.MigrationChapter
import mihon.domain.migration.MigrationMangaMetadata
import mihon.domain.migration.MigrationOrchestrator
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Flags controlling which metadata is copied during a migration.
 */
data class MigrationOptions(
    val copyChapters: Boolean = true,
    val copyCategories: Boolean = true,
    val copyNotes: Boolean = true,
)

/**
 * Executes a manga migration on desktop:
 * 1. Saves [target] manga and chapters through [SaveSourceMangaForDetails]
 * 2. Optionally copies chapter read status (by chapter number)
 * 3. Optionally copies category assignments
 * 4. Optionally copies notes
 * 5. If [replace]=true, removes [sourceManga] from the library
 */
class DesktopMigrateMangaUseCase(
    private val saveSourceMangaForDetails: SaveSourceMangaForDetails,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val mangaRepository: MangaRepository,
    private val orchestrator: MigrationOrchestrator = MigrationOrchestrator(),
) {
    suspend fun await(
        sourceManga: Manga,
        targetSManga: eu.kanade.tachiyomi.source.model.SManga,
        targetSourceId: Long,
        targetChapters: List<SChapter>,
        options: MigrationOptions = MigrationOptions(),
        replace: Boolean = true,
    ): Manga {
        // 1. Persist target manga + chapters to DB
        val persistedTarget = saveSourceMangaForDetails.await(targetSManga, targetSourceId, targetChapters)
        val targetDateAdded = if (persistedTarget.favorite) persistedTarget.dateAdded else System.currentTimeMillis()
        val savedTarget = persistedTarget.copy(favorite = true, dateAdded = targetDateAdded)
        val flags = buildSet {
            if (options.copyChapters) add(MigrationFlag.CHAPTER)
            if (options.copyCategories) add(MigrationFlag.CATEGORY)
            if (options.copyNotes) add(MigrationFlag.NOTES)
        }
        val libraryPlan = orchestrator.libraryPlan(
            current = MigrationMangaMetadata(
                mangaId = sourceManga.id,
                categoryIds = if (options.copyCategories) getCategories.await(sourceManga.id).map { it.id } else emptyList(),
                chapterFlags = sourceManga.chapterFlags,
                viewerFlags = sourceManga.viewerFlags,
                dateAdded = sourceManga.dateAdded,
                notes = sourceManga.notes,
            ),
            targetMangaId = savedTarget.id,
            flags = flags,
            replace = replace,
            now = targetDateAdded,
        )

        // 2. Copy chapter read status
        if (options.copyChapters) {
            val sourceChapters = getChaptersByMangaId.await(sourceManga.id).map {
                MigrationChapter(it.id, it.chapterNumber, it.read, it.bookmark, it.dateFetch)
            }
            val targetDbChapters = getChaptersByMangaId.await(savedTarget.id)
            val updates = orchestrator.chapterUpdates(
                sourceChapters,
                targetDbChapters.map { MigrationChapter(it.id, it.chapterNumber, it.read, it.bookmark, it.dateFetch) },
            ).map { ChapterUpdate(id = it.id, read = it.read, bookmark = it.bookmark, dateFetch = it.dateFetch) }
            if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
        }

        // 3. Copy category assignments
        // Categories were applied by UpdateLibraryMembership with the favorite update.

        // 4. Copy notes
        if (libraryPlan.targetNotes != null) {
            mangaRepository.update(MangaUpdate(id = savedTarget.id, notes = libraryPlan.targetNotes))
        }

        // 5. Commit target membership and optional source removal in one database transaction.
        mangaRepository.updateMembershipsAtomically(
            buildList {
                add(LibraryMembershipUpdate(savedTarget.id, true, libraryPlan.targetDateAdded, libraryPlan.targetCategoryIds))
                if (libraryPlan.removeCurrentFromLibrary) {
                    add(LibraryMembershipUpdate(sourceManga.id, false, 0, emptyList()))
                }
            },
        )

        return savedTarget
    }
}
