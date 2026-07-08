package mihon.desktop.ui.library

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.ChapterRecognition
import kotlin.math.floor

sealed interface MangaDetailChapterListRow {
    data class ChapterRow(val chapter: Chapter) : MangaDetailChapterListRow
    data class MissingCountRow(val id: String, val count: Int) : MangaDetailChapterListRow
}

fun mangaDetailChapterRows(
    chapters: List<Chapter>,
    ascending: Boolean,
    hideMissingChapters: Boolean,
): List<MangaDetailChapterListRow> {
    if (hideMissingChapters || chapters.isEmpty()) {
        return chapters.map(MangaDetailChapterListRow::ChapterRow)
    }

    return buildList {
        chapters.forEachIndexed { index, chapter ->
            val previous = chapters.getOrNull(index - 1)
            val missingCount = missingCountBefore(
                previous = previous,
                current = chapter,
                ascending = ascending,
            )
            if (missingCount > 0) {
                add(
                    MangaDetailChapterListRow.MissingCountRow(
                        id = "missing-${previous?.id ?: "start"}-${chapter.id}",
                        count = missingCount,
                    ),
                )
            }
            add(MangaDetailChapterListRow.ChapterRow(chapter))
        }
    }
}

fun realChapterIds(rows: List<MangaDetailChapterListRow>): List<Long> =
    rows.mapNotNull { row -> (row as? MangaDetailChapterListRow.ChapterRow)?.chapter?.id }

private fun missingCountBefore(
    previous: Chapter?,
    current: Chapter,
    ascending: Boolean,
): Int {
    val currentNumber = current.displayChapterNumber()
    if (currentNumber < 0.0) return 0
    if (previous == null) {
        if (!ascending) return 0
        return floor(currentNumber).toInt().minus(1).coerceAtLeast(0)
    }

    val previousNumber = previous.displayChapterNumber()
    if (previousNumber < 0.0) return 0
    val lower = if (ascending) previousNumber else currentNumber
    val higher = if (ascending) currentNumber else previousNumber
    return calculateChapterGap(higher, lower).coerceAtLeast(0)
}

private fun Chapter.displayChapterNumber(): Double {
    if (isRecognizedNumber) return chapterNumber
    return ChapterRecognition.parseChapterNumber(
        mangaTitle = "",
        chapterName = name,
        chapterNumber = null,
    )
}
