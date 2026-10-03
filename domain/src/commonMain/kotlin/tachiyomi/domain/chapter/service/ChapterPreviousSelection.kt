package tachiyomi.domain.chapter.service

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Previous-only ordering: keep equal comparator values stable across display directions. */
fun chaptersBeforePointer(filteredChapters: List<Chapter>, manga: Manga, pointerId: Long): List<Chapter> {
    val compare = getChapterSort(manga, sortDescending = false)
    val ascending = filteredChapters.sortedWith(
        Comparator<Chapter> { first, second -> compare(first, second) }
            .thenBy { it.id },
    )
    val position = ascending.indexOfFirst { it.id == pointerId }
    return if (position < 0) emptyList() else ascending.take(position)
}
