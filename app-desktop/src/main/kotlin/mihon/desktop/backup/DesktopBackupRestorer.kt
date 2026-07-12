package mihon.desktop.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
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
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.track.repository.TrackRepository
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.desktop.backup.models.*

data class RestoreProgress(val completed: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 1f else completed.toFloat() / total
}

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
    private val setExcludedScanlatorsForManga: (suspend (Long, List<String>) -> Unit)? = null,
    private val trackRepository: TrackRepository? = null,
    private val preferenceStore: PreferenceStore? = null,
    private val sourcePreferenceStore: ((Long) -> PreferenceStore)? = null,
    private val extensionRepoRepository: ExtensionRepoRepository? = null,
) {

    /**
     * Restores all data from [backup].
     * Returns a [RestoreResult] summarising what was restored and what failed.
     */
    suspend fun restore(backup: Backup, onProgress: suspend (RestoreProgress) -> Unit = {}): RestoreResult {
        val result = RestoreResult()
        val total = backup.backupCategories.size + backup.backupManga.size +
            backup.backupPreferences.size + backup.backupSourcePreferences.sumOf { it.prefs.size } +
            backup.backupExtensionRepo.size + backup.backupManga.sumOf { it.tracking.size }
        var completed = 0
        suspend fun reportProcessed() {
            if (currentCoroutineContext().isActive) {
                onProgress(RestoreProgress(++completed, total))
            }
        }

        restorePreferences(backup.backupPreferences, preferenceStore, "preferences", result, ::reportProcessed)
        backup.backupSourcePreferences.forEach { source ->
            currentCoroutineContext().ensureActive()
            val sourceId = source.sourceKey.toLongOrNull()
            if (sourceId == null) {
                source.prefs.forEach {
                    result.addError("sourcePreferences:${source.sourceKey}:${it.key}", "invalid source id")
                    reportProcessed()
                }
            } else {
                restorePreferences(source.prefs, sourcePreferenceStore?.invoke(sourceId), "sourcePreferences:${source.sourceKey}", result, ::reportProcessed)
            }
        }
        backup.backupExtensionRepo.forEach { repo ->
            currentCoroutineContext().ensureActive()
            runRestoreUnit("extensionRepo:${repo.baseUrl}", result) {
                val repository = extensionRepoRepository ?: error("extension repository dependency is missing")
                repository.upsertRepo(repo.baseUrl, repo.name, repo.shortName, repo.website, repo.signingKeyFingerprint)
            }
            reportProcessed()
        }

        // 1. Categories
        val categoryNameToId = restoreCategories(backup.backupCategories, result, ::reportProcessed)

        // 2. Manga + chapters + history
        for (backupManga in backup.backupManga) {
            currentCoroutineContext().ensureActive()
            try {
                val mangaId = restoreManga(backupManga)
                restoreTracking(mangaId, backupManga.tracking, result, ::reportProcessed)
                restoreChapters(mangaId, backupManga.chapters, result)
                restoreHistory(mangaId, backupManga.history, result)
                restoreMangaCategories(
                    mangaId,
                    backupManga.categories,
                    backup.backupCategories,
                    categoryNameToId,
                )
                restoreExcludedScanlators(mangaId, backupManga.excludedScanlators, result)
                result.incrementSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.addError(backupManga.url, e)
            } finally {
                reportProcessed()
            }
        }

        return result
    }

    // ── Categories ─────────────────────────────────────────────────────────────

    private suspend fun restoreCategories(
        backupCategories: List<BackupCategory>,
        result: RestoreResult,
        onProcessed: suspend () -> Unit,
    ): Map<String, Long> {
        val existing = categoryRepository.getAll().associateBy { it.name }
        val nameToId = mutableMapOf<String, Long>()

        // Keep existing IDs as-is
        existing.forEach { (name, cat) -> nameToId[name] = cat.id }

        // Insert any missing categories
        for (backupCat in backupCategories) {
            currentCoroutineContext().ensureActive()
            try {
                if (backupCat.name !in existing) {
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
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.addError("category:${backupCat.name}", e)
            } finally {
                onProcessed()
            }
        }
        return nameToId
    }

    // ── Manga ─────────────────────────────────────────────────────────────────

    /** Returns the local DB id for the manga (existing or newly inserted). */
    private suspend fun restoreManga(backupManga: BackupManga): Long {
        val existing = mangaRepository.getMangaByUrlAndSourceId(backupManga.url, backupManga.source)
        if (existing != null) {
            val backupHasNewerBibliography = backupManga.version > existing.version || !existing.initialized
            mangaRepository.update(
                MangaUpdate(
                    id = existing.id,
                    favorite = existing.favorite || backupManga.favorite,
                    dateAdded = listOf(existing.dateAdded, backupManga.dateAdded).filter { it > 0 }.minOrNull(),
                    viewerFlags = (backupManga.viewer_flags ?: backupManga.viewer).toLong(),
                    chapterFlags = backupManga.chapterFlags.toLong(),
                    title = backupManga.title.takeIf { backupHasNewerBibliography && it.isNotBlank() },
                    artist = backupManga.artist.takeIf { backupHasNewerBibliography },
                    author = backupManga.author.takeIf { backupHasNewerBibliography },
                    description = backupManga.description.takeIf { backupHasNewerBibliography },
                    genre = backupManga.genre.takeIf { backupHasNewerBibliography && it.isNotEmpty() },
                    status = backupManga.status.toLong().takeIf { backupHasNewerBibliography },
                    thumbnailUrl = backupManga.thumbnailUrl.takeIf { backupHasNewerBibliography },
                    updateStrategy = backupManga.updateStrategy,
                    initialized = existing.initialized || backupManga.initialized,
                    version = maxOf(existing.version, backupManga.version),
                    notes = backupManga.notes,
                ),
            )
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
                    viewerFlags = (backupManga.viewer_flags ?: backupManga.viewer).toLong(),
                    chapterFlags = backupManga.chapterFlags.toLong(),
                    coverLastModified = 0L,
                    updateStrategy = backupManga.updateStrategy,
                    initialized = backupManga.initialized,
                    lastModifiedAt = backupManga.lastModifiedAt,
                    favoriteModifiedAt = backupManga.favoriteModifiedAt,
                    version = backupManga.version,
                    notes = backupManga.notes,
                ),
            ),
        )
        return newMangas.first().id
    }

    private suspend fun restoreTracking(
        mangaId: Long,
        tracking: List<BackupTracking>,
        result: RestoreResult,
        onProcessed: suspend () -> Unit,
    ) {
        if (tracking.isEmpty()) return
        tracking.forEach { item ->
            currentCoroutineContext().ensureActive()
            runRestoreUnit("tracking:$mangaId:${item.syncId}", result) {
                val repository = trackRepository ?: error("tracking repository dependency is missing")
                repository.insert(item.getTrackImpl().copy(mangaId = mangaId))
            }
            onProcessed()
        }
    }

    private suspend fun restorePreferences(
        preferences: List<BackupPreference>,
        store: PreferenceStore?,
        keyPrefix: String,
        result: RestoreResult,
        onProcessed: suspend () -> Unit,
    ) {
        if (preferences.isEmpty()) return
        preferences.forEach { preference ->
            currentCoroutineContext().ensureActive()
            try {
                val target = store ?: error("preference store dependency is missing")
                when (val value = preference.value) {
                    is IntPreferenceValue -> target.getInt(preference.key).set(value.value)
                    is LongPreferenceValue -> target.getLong(preference.key).set(value.value)
                    is FloatPreferenceValue -> target.getFloat(preference.key).set(value.value)
                    is StringPreferenceValue -> target.getString(preference.key).set(value.value)
                    is BooleanPreferenceValue -> target.getBoolean(preference.key).set(value.value)
                    is StringSetPreferenceValue -> target.getStringSet(preference.key).set(value.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.addError("$keyPrefix:${preference.key}", e)
            } finally {
                onProcessed()
            }
        }
    }

    private suspend fun runRestoreUnit(key: String, result: RestoreResult, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.addError(key, e)
        }
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
            currentCoroutineContext().ensureActive()
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
                    lastModifiedAt = backupCh.lastModifiedAt,
                    version = backupCh.version,
                )
            } else {
                toUpdate += ChapterUpdate(
                    id = existing.id,
                    name = backupCh.name,
                    scanlator = backupCh.scanlator,
                    read = existing.read || backupCh.read,
                    bookmark = existing.bookmark || backupCh.bookmark,
                    lastPageRead = maxOf(existing.lastPageRead, backupCh.lastPageRead),
                    dateFetch = backupCh.dateFetch,
                    dateUpload = backupCh.dateUpload,
                    chapterNumber = backupCh.chapterNumber.toDouble(),
                    sourceOrder = backupCh.sourceOrder,
                    version = maxOf(existing.version, backupCh.version),
                )
            }
        }

        if (toInsert.isNotEmpty()) {
            try {
                chapterRepository.addAll(toInsert)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.addError("chapters:manga$mangaId", e)
            }
        }
        if (toUpdate.isNotEmpty()) {
            try {
                chapterRepository.updateAll(toUpdate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.addError("chapters:manga$mangaId", e)
            }
        }
    }

    // ── History ───────────────────────────────────────────────────────────────

    private suspend fun restoreHistory(
        mangaId: Long,
        backupHistory: List<mihon.desktop.backup.models.BackupHistory>,
        result: RestoreResult,
    ) {
        if (backupHistory.isEmpty()) return

        val chaptersByUrl = chapterRepository.getChapterByMangaId(mangaId).associateBy { it.url }
        val historyByChapterId = historyRepository.getHistoryByMangaId(mangaId).associateBy { it.chapterId }
        val backupHistoryByUrl = backupHistory.associateBy { it.url }

        for ((url, item) in backupHistoryByUrl) {
            currentCoroutineContext().ensureActive()
            val chapter = chaptersByUrl[url] ?: continue
            if (item.lastRead > 0L) {
                val existing = historyByChapterId[chapter.id]
                try {
                    historyRepository.upsertHistory(
                        HistoryUpdate(
                            chapterId = chapter.id,
                            readAt = Date(maxOf(item.lastRead, existing?.readAt?.time ?: 0L)),
                            sessionReadDuration = maxOf(item.readDuration, existing?.readDuration ?: 0L) -
                                (existing?.readDuration ?: 0L),
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    result.addError("history:$url", e)
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

    private suspend fun restoreExcludedScanlators(
        mangaId: Long,
        excludedScanlators: List<String>,
        result: RestoreResult,
    ) {
        if (excludedScanlators.isEmpty()) return
        try {
            val setter = setExcludedScanlatorsForManga ?: error("excluded scanlator dependency is missing")
            setter(mangaId, excludedScanlators)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.addError("excludedScanlators:manga$mangaId", e)
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
        data class Failure(val key: String, val cause: Throwable)
        var successCount: Int = 0
            private set
        val errors: MutableList<Pair<String, String>> = mutableListOf()
        val failures: MutableList<Failure> = mutableListOf()

        fun incrementSuccess() { successCount++ }

        fun addError(key: String, message: String) {
            errors += Pair(key, message)
        }

        fun addError(key: String, cause: Throwable) {
            errors += key to (cause.message ?: "restore failed")
            failures += Failure(key, cause)
        }

        val hasErrors: Boolean get() = errors.isNotEmpty()
    }
}
