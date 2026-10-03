package mihon.desktop.ui.library

import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.domain.reader.progress.ReaderChapterDisplayOrder
import mihon.domain.reader.progress.ReaderEntryCandidate
import mihon.domain.reader.progress.resolveReaderEntry
import mihon.domain.reader.session.ReaderChapterId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.util.Locale

internal enum class MangaDetailPrimaryActionType {
    TOGGLE_LIBRARY,
    EDIT_FETCH_INTERVAL,
    TRACKING,
    OPEN_IN_BROWSER,
    COPY_LINK,
    SHARE,
    CONTINUE_READING,
}

internal enum class MangaDetailDownloadAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,
    BOOKMARKED_CHAPTERS,
}

internal fun mangaDetailPrimaryActionTypes(
    isFavorite: Boolean,
    isHttpSource: Boolean,
    hasUnreadChapters: Boolean,
): List<MangaDetailPrimaryActionType> {
    return buildList {
        add(MangaDetailPrimaryActionType.TOGGLE_LIBRARY)
        if (isFavorite) {
            add(MangaDetailPrimaryActionType.EDIT_FETCH_INTERVAL)
        }
        add(MangaDetailPrimaryActionType.TRACKING)
        if (isHttpSource) {
            add(MangaDetailPrimaryActionType.OPEN_IN_BROWSER)
            add(MangaDetailPrimaryActionType.COPY_LINK)
            add(MangaDetailPrimaryActionType.SHARE)
        }
        if (hasUnreadChapters) {
            add(MangaDetailPrimaryActionType.CONTINUE_READING)
        }
    }
}

internal fun mangaDetailDownloadActions(): List<MangaDetailDownloadAction> =
    MangaDetailDownloadAction.entries

internal fun nextUnreadChapter(chapters: List<Chapter>, manga: Manga): Chapter? {
    val sortedChapters = chapters.filter { it.url.externalChapterUrlOrNull() == null }.sortedWith(getChapterSort(manga))
    val targetId = resolveReaderEntry(
        chapters = sortedChapters.map { chapter ->
            ReaderEntryCandidate(
                chapterId = ReaderChapterId(chapter.id),
                isRead = chapter.read,
            )
        },
        displayOrder = if (manga.sortDescending()) {
            ReaderChapterDisplayOrder.STORY_DESCENDING
        } else {
            ReaderChapterDisplayOrder.STORY_ASCENDING
        },
    )
    return chapters.firstOrNull { it.id == targetId?.value }
}

internal fun continueActionResumes(
    chapters: List<Chapter>,
    target: Chapter,
    synchronizedResumeChapterId: Long?,
): Boolean = chapters.any { it.read } || target.lastPageRead > 0 || synchronizedResumeChapterId == target.id

internal enum class ChapterReadIndicator {
    UNREAD_DOT,
    PROGRESS_RING,
    READ_CHECK,
}

internal data class ChapterReadPresentation(
    val indicator: ChapterReadIndicator,
    val pageNumber: Long?,
)

internal fun chapterReadPresentation(chapter: Chapter): ChapterReadPresentation = when {
    chapter.read -> ChapterReadPresentation(ChapterReadIndicator.READ_CHECK, pageNumber = null)
    chapter.lastPageRead > 0 -> ChapterReadPresentation(
        ChapterReadIndicator.PROGRESS_RING,
        pageNumber = chapter.lastPageRead + 1,
    )
    else -> ChapterReadPresentation(ChapterReadIndicator.UNREAD_DOT, pageNumber = null)
}

internal fun chapterDisplayTitle(
    chapter: Chapter,
    displayMode: Long,
    locale: Locale = Locale.getDefault(),
): String {
    if (displayMode != Manga.CHAPTER_DISPLAY_NUMBER) return chapter.name
    val number = chapter.chapterNumber
    val formatted = if (number % 1.0 == 0.0) {
        number.toInt().toString()
    } else {
        number.toString().trimEnd('0').trimEnd('.')
    }
    return MR.strings.desktop_ui_chapter_number.localized(locale, formatted)
}
