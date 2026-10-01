package mihon.desktop.ui.library

import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.task.TaskState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * All state for [MangaDetailScreen], owned by [MangaDetailScreenModel].
 * Pure data — no Compose dependencies, fully testable on the JVM.
 */
data class MangaDetailState(
    val syncedResumeChapterId: Long? = null,
    // ── Loaded data ──────────────────────────────────────────────────────────
    val manga: Manga? = null,
    val chapters: List<Chapter> = emptyList(),
    val isUpdating: Boolean = false,
    val coverTask: TaskState<Unit> = TaskState.Idle,
    val coverFeedback: String? = null,
    val coverLastModified: Long = 0L,
    val coverModel: String? = null,
    val hasCustomCover: Boolean = false,

    // ── Sort state ───────────────────────────────────────────────────────────
    val chapterSortMode: ChapterSortMode = ChapterSortMode.BY_SOURCE_ORDER,
    val chapterSortAscending: Boolean = false,

    // ── Scanlator filters ────────────────────────────────────────────────────
    val availableScanlators: Set<String> = emptySet(),
    val excludedScanlators: Set<String> = emptySet(),

    // ── Dialog / sheet visibility ─────────────────────────────────────────────
    val showFilterMenu: Boolean = false,
    val chapterSettingsFeedback: String? = null,
    val chapterSettingsFeedbackIsError: Boolean = false,
    val showNotesDialog: Boolean = false,
    val showMigrateSourcePicker: Boolean = false,
    val deleteConfirmChapter: Chapter? = null,
    val markAllReadConfirm: Boolean = false,
    val batchActionMessage: String? = null,

    // ── Migration state ───────────────────────────────────────────────────────
    val migrateSearchResults: List<SManga>? = null,
    val migrateTargetSourceId: Long? = null,
    val migrateSearching: Boolean = false,
    val migrateConfirmItem: SManga? = null,
) {
    // Compatibility projections; persisted manga flags remain the only filter authority.
    val filterShowRead get() = manga?.unreadFilterRaw != Manga.CHAPTER_SHOW_UNREAD
    val filterShowUnread get() = manga?.unreadFilterRaw != Manga.CHAPTER_SHOW_READ
    val filterShowBookmarked get() = manga?.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED
    val filterShowDownloaded get() = manga?.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED
}
