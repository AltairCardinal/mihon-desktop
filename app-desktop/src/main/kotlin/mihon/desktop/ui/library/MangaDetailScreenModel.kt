package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Voyager ScreenModel for [MangaDetailScreen].
 *
 * Owns all manga-detail state and exposes it as [StateFlow<MangaDetailState>].
 * All filter, sort, and dialog state transitions go through explicit methods,
 * enabling JVM unit tests without Compose or DI.
 */
class MangaDetailScreenModel(
    val mangaId: Long,
) : ScreenModel {

    private val _state = MutableStateFlow(MangaDetailState())
    val state: StateFlow<MangaDetailState> = _state.asStateFlow()

    // ── Data loading ──────────────────────────────────────────────────────────

    fun setManga(manga: Manga?) {
        _state.update { state ->
            if (manga == null) {
                state.copy(manga = null)
            } else {
                state.copy(
                    manga = manga,
                    chapterSortMode = chapterSortModeFromManga(manga),
                    chapterSortAscending = !manga.sortDescending(),
                )
            }
        }
    }

    fun setChapters(chapters: List<Chapter>) {
        _state.update { it.copy(chapters = chapters) }
    }

    fun setIsUpdating(updating: Boolean) {
        _state.update { it.copy(isUpdating = updating) }
    }

    fun setAvailableScanlators(scanlators: Set<String>) {
        _state.update { it.copy(availableScanlators = scanlators) }
    }

    fun setExcludedScanlators(scanlators: Set<String>) {
        _state.update { it.copy(excludedScanlators = scanlators) }
    }

    // ── Filter toggles ────────────────────────────────────────────────────────

    fun setFilterShowRead(show: Boolean) {
        _state.update { it.copy(filterShowRead = show) }
    }

    fun setFilterShowUnread(show: Boolean) {
        _state.update { it.copy(filterShowUnread = show) }
    }

    fun setFilterShowBookmarked(show: Boolean) {
        _state.update { it.copy(filterShowBookmarked = show) }
    }

    fun setFilterShowDownloaded(show: Boolean) {
        _state.update { it.copy(filterShowDownloaded = show) }
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    fun setSortMode(mode: ChapterSortMode) {
        _state.update { it.copy(chapterSortMode = mode) }
    }

    fun setSortAscending(ascending: Boolean) {
        _state.update { it.copy(chapterSortAscending = ascending) }
    }

    /**
     * Taps a sort mode button:
     * - Same mode → flip ascending/descending
     * - Different mode → switch to new mode, reset to descending
     */
    fun toggleSort(mode: ChapterSortMode) {
        _state.update { s ->
            val (nextMode, nextAscending) = nextChapterSort(s.chapterSortMode, s.chapterSortAscending, mode)
            s.copy(chapterSortMode = nextMode, chapterSortAscending = nextAscending)
        }
    }

    // ── Dialog / sheet visibility ─────────────────────────────────────────────

    fun toggleFilterMenu() {
        _state.update { it.copy(showFilterMenu = !it.showFilterMenu) }
    }

    fun setShowNotesDialog(show: Boolean) {
        _state.update { it.copy(showNotesDialog = show) }
    }

    fun setShowMigrateSourcePicker(show: Boolean) {
        _state.update { it.copy(showMigrateSourcePicker = show) }
    }

    fun setDeleteConfirmChapter(chapter: Chapter?) {
        _state.update { it.copy(deleteConfirmChapter = chapter) }
    }

    fun setMarkAllReadConfirm(show: Boolean) {
        _state.update { it.copy(markAllReadConfirm = show) }
    }

    // ── Migration state ───────────────────────────────────────────────────────

    fun setMigrateSearchResults(results: List<SManga>?) {
        _state.update { it.copy(migrateSearchResults = results) }
    }

    fun setMigrateTargetSourceId(sourceId: Long?) {
        _state.update { it.copy(migrateTargetSourceId = sourceId) }
    }

    fun setMigrateSearching(searching: Boolean) {
        _state.update { it.copy(migrateSearching = searching) }
    }

    fun setMigrateConfirmItem(item: SManga?) {
        _state.update { it.copy(migrateConfirmItem = item) }
    }
}
