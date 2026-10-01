package mihon.desktop.ui.library

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.missingChaptersCount
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

    // Number authority is independent of display sorting. Each numeric interval is shown once;
    // unknown names and alternate releases never manufacture gaps or reorder real chapters.
    val byNumber = chapters.withIndex().map { it.index to it.value.effectiveMissingChapterNumber() }
        .filter { it.second >= 0.0 }.groupBy { floor(it.second).toInt() }.toSortedMap()
    val before = mutableMapOf<Int, MangaDetailChapterListRow.MissingCountRow>()
    val after = mutableMapOf<Int, MangaDetailChapterListRow.MissingCountRow>()
    var lower = 0
    byNumber.forEach { (higher, group) ->
        val count = calculateChapterGap(higher.toDouble(), lower.toDouble()).coerceAtLeast(0)
        if (count > 0) {
            val row = MangaDetailChapterListRow.MissingCountRow("missing-$lower-$higher", count)
            if (ascending) {
                before[group.first().first] = row
            } else {
                val lowerGroup = byNumber[lower]
                if (lowerGroup != null) {
                    before[lowerGroup.first().first] = row
                } else {
                    after[group.last().first] = row
                }
            }
        }
        lower = higher
    }
    return buildList {
        chapters.forEachIndexed { index, chapter ->
            before[index]?.let(::add)
            add(MangaDetailChapterListRow.ChapterRow(chapter))
            after[index]?.let(::add)
        }
    }
}

fun realChapterIds(rows: List<MangaDetailChapterListRow>): List<Long> =
    rows.mapNotNull { row -> (row as? MangaDetailChapterListRow.ChapterRow)?.chapter?.id }

internal fun List<Chapter>.detailMissingChaptersCount(): Int = map {
    it.effectiveMissingChapterNumber()
}.missingChaptersCount()

private fun Chapter.effectiveMissingChapterNumber(): Double = if (isRecognizedNumber) {
    chapterNumber
} else {
    // Preserve cached Desktop name recovery without changing repository chapter numbers.
    ChapterRecognition.parseChapterNumber(mangaTitle = "", chapterName = name, chapterNumber = null)
}
