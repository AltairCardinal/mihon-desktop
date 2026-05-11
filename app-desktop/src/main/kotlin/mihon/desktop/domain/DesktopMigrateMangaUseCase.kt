package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Lightweight chapter descriptor used for chapter read-status matching.
 * Decoupled from DB Chapter to allow easy unit testing.
 */
data class ChapterForMigration(
    val id: Long,
    val name: String,
    val chapterNumber: Double,
    val read: Boolean,
)

/**
 * Returns the set of chapter_number values that are marked as read.
 * Excludes negative chapter numbers (specials/extras).
 */
fun buildReadChapterNumbers(chapters: List<ChapterForMigration>): Set<Double> =
    chapters
        .filter { it.read && it.chapterNumber >= 0.0 }
        .map { it.chapterNumber }
        .toSet()

/**
 * Returns true if this chapter should be marked read given the source manga's read history.
 */
fun shouldMarkRead(chapterNumber: Double, readNumbers: Set<Double>): Boolean =
    chapterNumber >= 0.0 && chapterNumber in readNumbers

// ---------------------------------------------------------------------------

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
 * 1. Saves [target] manga to the DB (via [AddMangaToLibrary])
 * 2. Optionally copies chapter read status (by chapter number)
 * 3. Optionally copies category assignments
 * 4. Optionally copies notes
 * 5. If [replace]=true, removes [sourceManga] from the library
 */
class DesktopMigrateMangaUseCase(
    private val addMangaToLibrary: AddMangaToLibrary,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val mangaRepository: MangaRepository,
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
        val savedTarget = addMangaToLibrary.await(targetSManga, targetSourceId, targetChapters)

        // 2. Copy chapter read status
        if (options.copyChapters) {
            val sourceChapters = getChaptersByMangaId.await(sourceManga.id).map {
                ChapterForMigration(it.id, it.name, it.chapterNumber, it.read)
            }
            val readNumbers = buildReadChapterNumbers(sourceChapters)
            if (readNumbers.isNotEmpty()) {
                val targetDbChapters = getChaptersByMangaId.await(savedTarget.id)
                val updates = targetDbChapters
                    .filter { shouldMarkRead(it.chapterNumber, readNumbers) }
                    .map { ChapterUpdate(id = it.id, read = true) }
                if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
            }
        }

        // 3. Copy category assignments
        if (options.copyCategories) {
            val categories = getCategories.await(sourceManga.id)
            if (categories.isNotEmpty()) {
                setMangaCategories.await(savedTarget.id, categories.map { it.id })
            }
        }

        // 4. Copy notes
        if (options.copyNotes && !sourceManga.notes.isNullOrBlank()) {
            mangaRepository.update(MangaUpdate(id = savedTarget.id, notes = sourceManga.notes))
        }

        // 5. Remove source manga from library if replace=true
        if (replace) {
            mangaRepository.update(
                MangaUpdate(id = sourceManga.id, favorite = false, dateAdded = 0L),
            )
        }

        return savedTarget
    }
}
