package mihon.desktop.ui.library

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * All state for [MangaDetailScreen], owned by [MangaDetailScreenModel].
 * Pure data — no Compose dependencies, fully testable on the JVM.
 */
data class MangaDetailState(
    // ── Loaded data ──────────────────────────────────────────────────────────
    val manga: Manga? = null,
    val chapters: List<Chapter> = emptyList(),
    val isUpdating: Boolean = false,

    // ── Filter state ─────────────────────────────────────────────────────────
    val filterShowRead: Boolean = true,
    val filterShowUnread: Boolean = true,
    val filterShowBookmarked: Boolean = false,
    val filterShowDownloaded: Boolean = false,

    // ── Sort state ───────────────────────────────────────────────────────────
    val chapterSortMode: ChapterSortMode = ChapterSortMode.BY_SOURCE_ORDER,
    val chapterSortAscending: Boolean = false,

    // ── Scanlator filters ────────────────────────────────────────────────────
    val availableScanlators: Set<String> = emptySet(),
    val excludedScanlators: Set<String> = emptySet(),

    // ── Dialog / sheet visibility ─────────────────────────────────────────────
    val showFilterMenu: Boolean = false,
    val showNotesDialog: Boolean = false,
    val showMigrateSourcePicker: Boolean = false,
    val deleteConfirmChapter: Chapter? = null,
    val markAllReadConfirm: Boolean = false,

    // ── Migration state ───────────────────────────────────────────────────────
    val migrateSearchResults: List<SManga>? = null,
    val migrateTargetSourceId: Long? = null,
    val migrateSearching: Boolean = false,
    val migrateConfirmItem: SManga? = null,
)
