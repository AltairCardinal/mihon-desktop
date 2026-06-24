package mihon.desktop.ui.library

import tachiyomi.domain.chapter.model.Chapter

internal enum class ChapterSelectionDownloadAction {
    DOWNLOAD,
    DELETE_DOWNLOAD,
}

internal enum class ChapterSelectionActionType {
    BOOKMARK,
    MARK_READ,
    MARK_UNREAD,
    MARK_BELOW_READ,
    DOWNLOAD,
    DELETE_DOWNLOAD,
}

internal fun chapterSelectionActionTypes(
    downloadAction: ChapterSelectionDownloadAction,
): List<ChapterSelectionActionType> {
    val finalDownloadAction = when (downloadAction) {
        ChapterSelectionDownloadAction.DOWNLOAD -> ChapterSelectionActionType.DOWNLOAD
        ChapterSelectionDownloadAction.DELETE_DOWNLOAD -> ChapterSelectionActionType.DELETE_DOWNLOAD
    }
    return listOf(
        ChapterSelectionActionType.BOOKMARK,
        ChapterSelectionActionType.MARK_READ,
        ChapterSelectionActionType.MARK_UNREAD,
        ChapterSelectionActionType.MARK_BELOW_READ,
        finalDownloadAction,
    )
}

internal fun chapterSelectionDownloadAction(
    selectedChapters: List<Chapter>,
    isDownloaded: (Chapter) -> Boolean,
): ChapterSelectionDownloadAction {
    return if (selectedChapters.isNotEmpty() && selectedChapters.all(isDownloaded)) {
        ChapterSelectionDownloadAction.DELETE_DOWNLOAD
    } else {
        ChapterSelectionDownloadAction.DOWNLOAD
    }
}

internal fun chaptersAtOrBelowSelection(
    displayedChapters: List<Chapter>,
    selectedIds: Set<Long>,
): List<Chapter> {
    val firstSelectedIndex = displayedChapters.indexOfFirst { it.id in selectedIds }
    if (firstSelectedIndex < 0) return emptyList()
    return displayedChapters.drop(firstSelectedIndex)
}
