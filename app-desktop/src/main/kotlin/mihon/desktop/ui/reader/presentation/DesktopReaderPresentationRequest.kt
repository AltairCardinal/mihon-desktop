package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.session.ReaderChapterSession

internal fun desktopReaderPresentationRequest(
    chapter: ReaderChapterSession,
    direction: ReaderDirection,
    splitPageIndices: Set<Int> = emptySet(),
    spreadPageIndices: Set<Int> = emptySet(),
    forcedSinglePageIndices: Set<Int> = emptySet(),
    matchedPagePairs: Set<Pair<Int, Int>> = emptySet(),
    splitWidePages: Boolean = true,
): ReaderPresentationRequest {
    val pagesByIndex = chapter.pages.associateBy { it.id.sourcePageIndex }
    return ReaderPresentationRequest(
        chapter = chapter,
        direction = direction,
        splitPageIds = splitPageIndices.mapNotNullTo(linkedSetOf()) { pagesByIndex[it]?.id },
        dualPagedOptions = DualPagedPresentationOptions(
            spreadPageIds = spreadPageIndices.mapNotNullTo(linkedSetOf()) { pagesByIndex[it]?.id },
            forcedSinglePageIds = forcedSinglePageIndices.mapNotNullTo(linkedSetOf()) { pagesByIndex[it]?.id },
            matchedPagePairs = matchedPagePairs.mapNotNullTo(linkedSetOf()) { (first, second) ->
                val firstPage = pagesByIndex[first]?.id
                val secondPage = pagesByIndex[second]?.id
                if (firstPage != null && secondPage != null) firstPage to secondPage else null
            },
        ),
    ).let { request ->
        if (splitWidePages) request else request.copy(splitPageIds = emptySet())
    }
}
