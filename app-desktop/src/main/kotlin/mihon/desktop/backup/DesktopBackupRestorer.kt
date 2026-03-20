package mihon.desktop.backup

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupManga
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.Date

/**
 * Restores a `.tachibk` backup into the local database.
 *
 * Pure logic — no Injekt / DI. Instantiate with the four repositories
 * and call [restore].
 */
class DesktopBackupRestorer(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val historyRepository: HistoryRepository,
) {

    /**
     * Restores all data from [backup].
     * Returns a [RestoreResult] summarising what was restored and what failed.
     */
    suspend fun restore(backup: Backup): RestoreResult {
        val result = RestoreResult()

        // 1. Categories
        val categoryNameToId = restoreCategories(backup.backupCategories, result)

        // 2. Manga + chapters + history
        for (backupManga in backup.backupManga) {
            try {
                val mangaId = restoreManga(backupManga)
                restoreChapters(mangaId, backupManga.chapters, result)
                restoreHistory(mangaId, backupManga.history, backupManga.chapters, result)
                restoreMangaCategories(
                    mangaId,
                    backupManga.categories,
                    backup.backupCategories,
                    categoryNameToId,
                )
                result.incrementSuccess()
            } catch (e: Exception) {
                result.addError(backupManga.url, e.message ?: "unknown error")
            }
        }

        return result
    }

    // ── Categories ─────────────────────────────────────────────────────────────

    private suspend fun restoreCategories(
        backupCategories: List<BackupCategory>,
        result: RestoreResult,
    ): Map<String, Long> {
        val existing = categoryRepository.getAll().associateBy { it.name }
        val nameToId = mutableMapOf<String, Long>()

        // Keep existing IDs as-is
        existing.forEach { (name, cat) -> nameToId[name] = cat.id }

        // Insert any missing categories
        for (backupCat in backupCategories) {
            if (backupCat.name !in existing) {
                try {
                    categoryRepository.insert(
                        Category(
                            id = -1L,
                            name = backupCat.name,
                            order = backupCat.order,
                            flags = backupCat.flags,
                        ),
                    )
                    // Re-fetch to get the auto-assigned ID
                    val inserted = categoryRepository.getAll().find { it.name == backupCat.name }
                    if (inserted != null) nameToId[backupCat.name] = inserted.id
                } catch (e: Exception) {
                    result.addError("category:${backupCat.name}", e.message ?: "insert failed")
                }
            }
        }
        return nameToId
    }

    // ── Manga ─────────────────────────────────────────────────────────────────

    /** Returns the local DB id for the manga (existing or newly inserted). */
    private suspend fun restoreManga(backupManga: BackupManga): Long {
        val existing = mangaRepository.getMangaByUrlAndSourceId(backupManga.url, backupManga.source)
        if (existing != null) {
            // Update metadata from backup if it was previously uninitialized
            if (!existing.initialized) {
                mangaRepository.update(
                    MangaUpdate(
                        id = existing.id,
                        title = backupManga.title.takeIf { it.isNotBlank() },
                        artist = backupManga.artist,
                        author = backupManga.author,
                        description = backupManga.description,
                        genre = backupManga.genre.takeIf { it.isNotEmpty() },
                        status = backupManga.status.toLong().takeIf { it >= 0 },
                        thumbnailUrl = backupManga.thumbnailUrl,
                        initialized = backupManga.initialized.takeIf { it },
                        notes = backupManga.notes.takeIf { it.isNotBlank() },
                    ),
                )
            }
            return existing.id
        }

        // Insert as new manga
        val newMangas = mangaRepository.insertNetworkManga(
            listOf(
                Manga(
                    id = -1L,
                    source = backupManga.source,
                    url = backupManga.url,
                    title = backupManga.title,
                    artist = backupManga.artist,
                    author = backupManga.author,
                    description = backupManga.description,
                    genre = backupManga.genre.takeIf { it.isNotEmpty() },
                    status = backupManga.status.toLong(),
                    thumbnailUrl = backupManga.thumbnailUrl,
                    favorite = backupManga.favorite,
                    lastUpdate = 0L,
                    nextUpdate = 0L,
                    fetchInterval = 0,
                    dateAdded = backupManga.dateAdded,
                    viewerFlags = 0L,
                    chapterFlags = backupManga.chapterFlags.toLong(),
                    coverLastModified = 0L,
                    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                    initialized = backupManga.initialized,
                    lastModifiedAt = backupManga.lastModifiedAt,
                    favoriteModifiedAt = null,
                    version = 0L,
                    notes = backupManga.notes,
                ),
            ),
        )
        return newMangas.first().id
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    private suspend fun restoreChapters(
        mangaId: Long,
        backupChapters: List<BackupChapter>,
        result: RestoreResult,
    ) {
        if (backupChapters.isEmpty()) return

        val existingByUrl = chapterRepository.getChapterByMangaId(mangaId).associateBy { it.url }

        val toInsert = mutableListOf<Chapter>()
        val toUpdate = mutableListOf<ChapterUpdate>()

        for (backupCh in backupChapters) {
            val existing = existingByUrl[backupCh.url]
            if (existing == null) {
                toInsert += Chapter.create().copy(
                    mangaId = mangaId,
                    url = backupCh.url,
                    name = backupCh.name,
                    scanlator = backupCh.scanlator,
                    read = backupCh.read,
                    bookmark = backupCh.bookmark,
                    lastPageRead = backupCh.lastPageRead,
                    dateFetch = backupCh.dateFetch,
                    dateUpload = backupCh.dateUpload,
                    chapterNumber = backupCh.chapterNumber.toDouble(),
                    sourceOrder = backupCh.sourceOrder,
                )
            } else if (backupCh.read && !existing.read) {
                // Backup is authoritative on read state: mark as read
                toUpdate += ChapterUpdate(id = existing.id, read = true, lastPageRead = backupCh.lastPageRead)
            }
        }

        if (toInsert.isNotEmpty()) {
            try {
                chapterRepository.addAll(toInsert)
            } catch (e: Exception) {
                result.addError("chapters:manga$mangaId", "insert failed: ${e.message}")
            }
        }
        if (toUpdate.isNotEmpty()) {
            try {
                chapterRepository.updateAll(toUpdate)
            } catch (e: Exception) {
                result.addError("chapters:manga$mangaId", "update failed: ${e.message}")
            }
        }
    }

    // ── History ───────────────────────────────────────────────────────────────

    private suspend fun restoreHistory(
        mangaId: Long,
        backupHistory: List<mihon.desktop.backup.models.BackupHistory>,
        backupChapters: List<BackupChapter>,
        result: RestoreResult,
    ) {
        if (backupHistory.isEmpty()) return

        val chaptersByUrl = chapterRepository.getChapterByMangaId(mangaId).associateBy { it.url }
        val backupChapterUrlToLastRead = backupHistory.associate { it.url to it.lastRead }

        for ((url, lastRead) in backupChapterUrlToLastRead) {
            val chapter = chaptersByUrl[url] ?: continue
            if (lastRead > 0L) {
                try {
                    historyRepository.upsertHistory(
                        HistoryUpdate(
                            chapterId = chapter.id,
                            readAt = Date(lastRead),
                            sessionReadDuration = 0L,
                        ),
                    )
                } catch (e: Exception) {
                    result.addError("history:$url", e.message ?: "upsert failed")
                }
            }
        }
    }

    // ── Manga-Category links ──────────────────────────────────────────────────

    private suspend fun restoreMangaCategories(
        mangaId: Long,
        backupCategoryIndices: List<Long>,
        backupCategories: List<BackupCategory>,
        categoryNameToId: Map<String, Long>,
    ) {
        if (backupCategoryIndices.isEmpty()) return
        val ids = resolveBackupCategoryIds(backupCategoryIndices, backupCategories, categoryNameToId)
        if (ids.isNotEmpty()) {
            mangaRepository.setMangaCategories(mangaId, ids)
        }
    }

    // ── Pure-logic helpers (accessible from tests) ─────────────────────────────

    companion object {

        /**
         * Returns the union of [existingNames] and names from [backupCategories],
         * de-duplicated and sorted.
         */
        fun mergeCategories(
            existingNames: List<String>,
            backupCategories: List<BackupCategory>,
        ): List<String> = (existingNames + backupCategories.map { it.name }).distinct()

        /**
         * Resolves backup category indices → real DB category IDs using [categoryMap].
         *
         * The backup encodes category membership as 0-based indices into [backupCategories].
         * [categoryMap] maps category name → actual DB id.
         */
        fun resolveBackupCategoryIds(
            backupCategoryIndices: List<Long>,
            backupCategories: List<BackupCategory>,
            categoryMap: Map<String, Long>,
        ): List<Long> {
            // Build index-to-BackupCategory map
            val indexToCat = backupCategories.associateBy { it.order }
            return backupCategoryIndices.mapNotNull { index ->
                val catName = indexToCat[index]?.name ?: return@mapNotNull null
                categoryMap[catName]
            }
        }

        /**
         * Returns a map of chapterUrl → read state, with backup read=true taking priority.
         */
        fun mergeChapterReadStates(
            backupChapters: List<BackupChapter>,
            existingReadStates: Map<String, Boolean>,
        ): Map<String, Boolean> {
            val result = existingReadStates.toMutableMap()
            for (ch in backupChapters) {
                // Backup wins if it says read=true
                if (ch.read) result[ch.url] = true else result.putIfAbsent(ch.url, false)
            }
            return result
        }
    }

    // ── RestoreResult ─────────────────────────────────────────────────────────

    class RestoreResult {
        var successCount: Int = 0
            private set
        val errors: MutableList<Pair<String, String>> = mutableListOf()

        fun incrementSuccess() { successCount++ }

        fun addError(key: String, message: String) {
            errors += Pair(key, message)
        }

        val hasErrors: Boolean get() = errors.isNotEmpty()
    }
}
