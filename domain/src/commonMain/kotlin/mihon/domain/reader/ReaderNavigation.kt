package mihon.domain.reader

import tachiyomi.domain.manga.model.Manga

enum class ReaderDirection { LTR, RTL, VERTICAL }

enum class PhysicalDirection { LEFT, RIGHT, UP, DOWN }

enum class NavigationPreset { RIGHT_AND_LEFT, L, KINDLE, EDGE, DISABLED }

enum class NavigationInversion(
    val horizontal: Boolean,
    val vertical: Boolean,
) {
    NONE(false, false),
    HORIZONTAL(true, false),
    VERTICAL(false, true),
    BOTH(true, true),
}

sealed interface ReaderNavigationCommand {
    data object Menu : ReaderNavigationCommand
    data object Previous : ReaderNavigationCommand
    data object Next : ReaderNavigationCommand
    data object PhysicalLeft : ReaderNavigationCommand
    data object PhysicalRight : ReaderNavigationCommand
    data class GoToPage(val pageIndex: Int) : ReaderNavigationCommand
    data class RetryChapter(val chapterId: Long) : ReaderNavigationCommand
    data class ChapterBoundary(val direction: ReaderTransitionDirection) : ReaderNavigationCommand
}

data class NormalizedReaderRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val command: ReaderNavigationCommand,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun inverted(inversion: NavigationInversion): NormalizedReaderRegion {
        val invertedLeft = if (inversion.horizontal) 1f - right else left
        val invertedRight = if (inversion.horizontal) 1f - left else right
        val invertedTop = if (inversion.vertical) 1f - bottom else top
        val invertedBottom = if (inversion.vertical) 1f - top else bottom
        return copy(
            left = invertedLeft,
            top = invertedTop,
            right = invertedRight,
            bottom = invertedBottom,
        )
    }
}

object ReaderNavigation {
    fun commandAt(
        x: Float,
        y: Float,
        preset: NavigationPreset,
        direction: ReaderDirection,
        inversion: NavigationInversion = NavigationInversion.NONE,
    ): ReaderNavigationCommand {
        if (x !in 0f..1f || y !in 0f..1f) return ReaderNavigationCommand.Menu
        val transformedX = if (inversion.horizontal) 1f - x else x
        val transformedY = if (inversion.vertical) 1f - y else y
        val command = regions(preset).firstOrNull { it.contains(transformedX, transformedY) }?.command
            ?: return ReaderNavigationCommand.Menu
        return when (command) {
            ReaderNavigationCommand.PhysicalLeft -> resolvePhysicalPageCommand(PhysicalDirection.LEFT, direction)
            ReaderNavigationCommand.PhysicalRight -> resolvePhysicalPageCommand(PhysicalDirection.RIGHT, direction)
            else -> command
        }
    }

    fun regions(preset: NavigationPreset): List<NormalizedReaderRegion> = when (preset) {
        NavigationPreset.DISABLED -> emptyList()
        NavigationPreset.RIGHT_AND_LEFT -> listOf(
            NormalizedReaderRegion(0f, 0f, 0.33f, 1f, ReaderNavigationCommand.PhysicalLeft),
            NormalizedReaderRegion(0.66f, 0f, 1.001f, 1f, ReaderNavigationCommand.PhysicalRight),
        )
        NavigationPreset.L -> listOf(
            NormalizedReaderRegion(0f, 0.33f, 0.33f, 0.66f, ReaderNavigationCommand.Previous),
            NormalizedReaderRegion(0f, 0f, 1.001f, 0.33f, ReaderNavigationCommand.Previous),
            NormalizedReaderRegion(0.66f, 0.33f, 1.001f, 0.66f, ReaderNavigationCommand.Next),
            NormalizedReaderRegion(0f, 0.66f, 1.001f, 1.001f, ReaderNavigationCommand.Next),
        )
        NavigationPreset.KINDLE -> listOf(
            NormalizedReaderRegion(0.33f, 0.33f, 1.001f, 1.001f, ReaderNavigationCommand.Next),
            NormalizedReaderRegion(0f, 0.33f, 0.33f, 1.001f, ReaderNavigationCommand.Previous),
        )
        NavigationPreset.EDGE -> listOf(
            NormalizedReaderRegion(0f, 0f, 0.33f, 1.001f, ReaderNavigationCommand.Next),
            NormalizedReaderRegion(0.33f, 0.66f, 0.66f, 1.001f, ReaderNavigationCommand.Previous),
            NormalizedReaderRegion(0.66f, 0f, 1.001f, 1.001f, ReaderNavigationCommand.Next),
        )
    }

    fun resolvePhysicalPageCommand(
        physicalDirection: PhysicalDirection,
        readingDirection: ReaderDirection,
    ): ReaderNavigationCommand = when (readingDirection) {
        ReaderDirection.LTR -> when (physicalDirection) {
            PhysicalDirection.LEFT, PhysicalDirection.UP -> ReaderNavigationCommand.Previous
            PhysicalDirection.RIGHT, PhysicalDirection.DOWN -> ReaderNavigationCommand.Next
        }
        ReaderDirection.RTL -> when (physicalDirection) {
            PhysicalDirection.LEFT, PhysicalDirection.DOWN -> ReaderNavigationCommand.Next
            PhysicalDirection.RIGHT, PhysicalDirection.UP -> ReaderNavigationCommand.Previous
        }
        ReaderDirection.VERTICAL -> when (physicalDirection) {
            PhysicalDirection.UP, PhysicalDirection.LEFT -> ReaderNavigationCommand.Previous
            PhysicalDirection.DOWN, PhysicalDirection.RIGHT -> ReaderNavigationCommand.Next
        }
    }
}

data class ReaderChapterEntry(
    val id: Long,
    val isRead: Boolean = false,
    val isFiltered: Boolean = false,
    val isDuplicate: Boolean = false,
    val chapterNumber: Double = -1.0,
    val scanlator: String? = null,
)

/** Matches Android's duplicate selection: current chapter, then current scanlator, then first in each number group. */
fun markDuplicateChapters(
    chapters: List<ReaderChapterEntry>,
    currentChapterId: Long,
): List<ReaderChapterEntry> {
    val current = chapters.firstOrNull { it.id == currentChapterId }
    val retainedIds = chapters
        .groupBy(ReaderChapterEntry::chapterNumber)
        .values
        .mapNotNull { sameNumber ->
            sameNumber.firstOrNull { it.id == currentChapterId }
                ?: current?.scanlator?.let { scanlator -> sameNumber.firstOrNull { it.scanlator == scanlator } }
                ?: sameNumber.firstOrNull()
        }
        .mapTo(mutableSetOf(), ReaderChapterEntry::id)
    return chapters.map { chapter -> chapter.copy(isDuplicate = chapter.id !in retainedIds) }
}

data class ChapterSkipPolicy(
    val read: Boolean = false,
    val filtered: Boolean = false,
    val duplicate: Boolean = false,
) {
    fun shouldSkip(chapter: ReaderChapterEntry): Boolean =
        (read && chapter.isRead) ||
            (filtered && chapter.isFiltered) ||
            (duplicate && chapter.isDuplicate)
}

/** Applies reader skip preferences while guaranteeing that the chapter being opened remains available. */
fun filterChaptersForReader(
    chapters: List<ReaderChapterEntry>,
    currentChapterId: Long,
    skipPolicy: ChapterSkipPolicy,
): List<ReaderChapterEntry> = chapters.filter { chapter ->
    chapter.id == currentChapterId || !skipPolicy.shouldSkip(chapter)
}

/** Authoritative manga chapter-filter metadata shared by Android and Desktop reader entries. */
fun isReaderChapterFiltered(
    unreadFilterRaw: Long,
    downloadedFilterRaw: Long,
    bookmarkedFilterRaw: Long,
    chapterIsRead: Boolean,
    chapterIsBookmarked: Boolean,
    chapterIsDownloaded: Boolean,
): Boolean =
    (unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !chapterIsRead) ||
        (unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && chapterIsRead) ||
        (downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED && !chapterIsDownloaded) ||
        (downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED && chapterIsDownloaded) ||
        (bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !chapterIsBookmarked) ||
        (bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && chapterIsBookmarked)

enum class ChapterListDirection { NEWER, OLDER }

sealed interface ChapterNavigationResult {
    data class Found(val index: Int, val chapter: ReaderChapterEntry) : ChapterNavigationResult
    data class Boundary(val direction: ChapterListDirection) : ChapterNavigationResult
    data object InvalidCurrent : ChapterNavigationResult
}

fun findAdjacentChapter(
    chapters: List<ReaderChapterEntry>,
    currentIndex: Int,
    direction: ChapterListDirection,
    skipPolicy: ChapterSkipPolicy,
): ChapterNavigationResult {
    if (currentIndex !in chapters.indices) return ChapterNavigationResult.InvalidCurrent
    val indices = when (direction) {
        ChapterListDirection.NEWER -> (currentIndex - 1) downTo 0
        ChapterListDirection.OLDER -> (currentIndex + 1) until chapters.size
    }
    for (index in indices) {
        val chapter = chapters[index]
        if (!skipPolicy.shouldSkip(chapter)) return ChapterNavigationResult.Found(index, chapter)
    }
    return ChapterNavigationResult.Boundary(direction)
}
