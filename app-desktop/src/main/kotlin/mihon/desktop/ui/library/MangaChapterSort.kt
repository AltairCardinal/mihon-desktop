package mihon.desktop.ui.library

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

fun sortMangaDetailChapters(
    chapters: List<Chapter>,
    mode: ChapterSortMode,
    ascending: Boolean,
): List<Chapter> {
    val comparator: Comparator<Chapter> = when (mode) {
        ChapterSortMode.BY_SOURCE_ORDER -> compareByDescending { it.sourceOrder }
        ChapterSortMode.BY_CHAPTER_NUMBER -> compareBy { it.chapterNumber }
        ChapterSortMode.BY_DATE_UPLOAD -> compareBy { it.dateUpload }
        ChapterSortMode.BY_ALPHABET -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    return if (ascending) chapters.sortedWith(comparator) else chapters.sortedWith(comparator.reversed())
}

fun ChapterSortMode.toMangaFlag(): Long =
    when (this) {
        ChapterSortMode.BY_SOURCE_ORDER -> Manga.CHAPTER_SORTING_SOURCE
        ChapterSortMode.BY_CHAPTER_NUMBER -> Manga.CHAPTER_SORTING_NUMBER
        ChapterSortMode.BY_DATE_UPLOAD -> Manga.CHAPTER_SORTING_UPLOAD_DATE
        ChapterSortMode.BY_ALPHABET -> Manga.CHAPTER_SORTING_ALPHABET
    }

fun chapterSortModeFromManga(manga: Manga): ChapterSortMode =
    when (manga.sorting) {
        Manga.CHAPTER_SORTING_NUMBER -> ChapterSortMode.BY_CHAPTER_NUMBER
        Manga.CHAPTER_SORTING_UPLOAD_DATE -> ChapterSortMode.BY_DATE_UPLOAD
        Manga.CHAPTER_SORTING_ALPHABET -> ChapterSortMode.BY_ALPHABET
        else -> ChapterSortMode.BY_SOURCE_ORDER
    }

fun chapterSortFlags(mode: ChapterSortMode, ascending: Boolean, currentFlags: Long = 0L): Long =
    currentFlags
        .setFlag(mode.toMangaFlag(), Manga.CHAPTER_SORTING_MASK)
        .setFlag(
            if (ascending) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC,
            Manga.CHAPTER_SORT_DIR_MASK,
        )

fun chapterDisplayFlags(displayMode: Long, currentFlags: Long = 0L): Long =
    currentFlags.setFlag(displayMode, Manga.CHAPTER_DISPLAY_MASK)

fun nextChapterSort(currentMode: ChapterSortMode, currentAscending: Boolean, requestedMode: ChapterSortMode): Pair<ChapterSortMode, Boolean> =
    if (currentMode == requestedMode) {
        currentMode to !currentAscending
    } else {
        requestedMode to false
    }

private fun Long.setFlag(flag: Long, mask: Long): Long {
    return this and mask.inv() or (flag and mask)
}
